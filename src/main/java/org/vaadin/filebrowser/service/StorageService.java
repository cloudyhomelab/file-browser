package org.vaadin.filebrowser.service;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.data.Capacity;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.storage.LocalDiskProvider;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.storage.S3Provider;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StorageProvider;

/** Hands out the storage provider for each configured file system and reports its status. */
@Service
public class StorageService {

    /** How long a status reading is reused before the storage is asked again. */
    private static final Duration STATUS_TTL = Duration.ofSeconds(30);
    /** A storage that takes longer to answer (e.g. a hung network mount) counts as offline. */
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(2);
    /** Checking a bucket before adding it may reach a remote region, so it gets longer than a status read. */
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);

    public record RootStatus(Capacity capacity, boolean online, MountInfo mount) implements Serializable {

        public static final RootStatus OFFLINE = new RootStatus(null, false, MountInfo.UNKNOWN);
    }

    /** What the server sees at a path that is about to be registered. */
    public record PathProbe(boolean exists, boolean directory, boolean readable, boolean writable)
            implements Serializable {

        public static final PathProbe MISSING = new PathProbe(false, false, false, false);
    }

    private record CachedStatus(RootStatus status, Instant at) {
    }

    private final Catalog catalog;
    private final Function<FsRoot, S3Provider.Connection> s3Connections;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, StorageProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, CachedStatus> statuses = new ConcurrentHashMap<>();
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public StorageService(Catalog catalog, S3Connections s3Connections) {
        this(catalog, s3Connections::connection);
    }

    /** For local disks only, e.g. in tests. */
    StorageService(Catalog catalog) {
        this(catalog, root -> {
            throw new StorageException("S3 is not configured.");
        });
    }

    StorageService(Catalog catalog, Function<FsRoot, S3Provider.Connection> s3Connections) {
        this.catalog = catalog;
        this.s3Connections = s3Connections;
    }

    public StorageProvider provider(FsRoot root) {
        return providers.computeIfAbsent(cacheKey(root), k -> create(root));
    }

    /** Drops the cached provider and status of a file system that was removed. */
    public void forget(FsRoot root) {
        final var key = cacheKey(root);
        if (providers.remove(key) instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // the file system is gone either way
            }
        }
        statuses.remove(key);
    }

    private static String cacheKey(FsRoot root) {
        return root.id() + "|" + root.endpoint();
    }

    private StorageProvider create(FsRoot root) {
        return switch (root.type()) {
            case DISK -> new LocalDiskProvider(Path.of(root.endpoint()), catalog::typeForFileName, catalog::mountKind);
            case S3 -> new S3Provider(s3Connections.apply(root), catalog::typeForFileName);
        };
    }

    public RootStatus status(FsRoot root) {
        final var key = cacheKey(root);
        final var cached = statuses.get(key);
        final var now = clock.instant();
        if (cached != null && cached.at().plus(STATUS_TTL).isAfter(now)) {
            return cached.status();
        }
        final var fresh = withTimeout(() -> readStatus(root), RootStatus.OFFLINE);
        statuses.put(key, new CachedStatus(fresh, now));
        return fresh;
    }

    private RootStatus readStatus(FsRoot root) {
        final StorageProvider provider;
        try {
            provider = provider(root);
        } catch (StorageException e) {
            return RootStatus.OFFLINE;
        }
        if (!provider.healthy()) {
            return RootStatus.OFFLINE;
        }
        return new RootStatus(provider.capacity(), true, provider.mount());
    }

    public PathProbe probe(String path) {
        return withTimeout(() -> readProbe(path), PathProbe.MISSING);
    }

    private static PathProbe readProbe(String path) {
        final var target = Path.of(path);
        if (!target.isAbsolute() || !Files.exists(target)) {
            return PathProbe.MISSING;
        }
        return new PathProbe(true, Files.isDirectory(target), Files.isReadable(target), Files.isWritable(target));
    }

    /**
     * Names of the folders directly inside an absolute server path that the server can read, for
     * choosing a path to add. Hidden (dot) folders are left out; links to folders count as folders.
     */
    public List<String> serverFolders(String path) {
        return withTimeout(() -> readServerFolders(path), List.of());
    }

    private static List<String> readServerFolders(String path) {
        final var folder = Path.of(path);
        if (!folder.isAbsolute() || !Files.isDirectory(folder) || !Files.isReadable(folder)) {
            return List.of();
        }
        try (final var children = Files.list(folder)) {
            return children
                    .filter(child -> !child.getFileName().toString().startsWith("."))
                    .filter(child -> Files.isDirectory(child) && Files.isReadable(child))
                    .map(child -> child.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** Why a bucket can't be used with these settings, or null when it answers. */
    public String checkS3(S3Provider.Connection connection) {
        return withTimeout(() -> readS3Problem(connection), "The bucket didn't answer in time.", CHECK_TIMEOUT);
    }

    private String readS3Problem(S3Provider.Connection connection) {
        try (final var provider = new S3Provider(connection, catalog::typeForFileName)) {
            provider.list("");
            return null;
        } catch (StorageException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return "The bucket can't be reached with these settings.";
        }
    }

    private <T> T withTimeout(Supplier<T> task, T fallback) {
        return withTimeout(task, fallback, STATUS_TIMEOUT);
    }

    private <T> T withTimeout(Supplier<T> task, T fallback, Duration timeout) {
        final var future = CompletableFuture.supplyAsync(task, probes);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            future.cancel(true);
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
    }
}
