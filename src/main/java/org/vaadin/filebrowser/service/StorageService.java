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
import java.util.Optional;
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
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.data.RootTypeInfo;
import org.vaadin.filebrowser.storage.LimitedProvider;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StorageProvider;
import org.vaadin.filebrowser.storage.StorageType;
import org.vaadin.filebrowser.storage.UntrustedServerException;

import jakarta.annotation.PreDestroy;

/** Hands out the storage provider for each configured file system and reports its status. */
@Service
public class StorageService {

    /** How long a status reading is reused before the storage is asked again. */
    private static final Duration STATUS_TTL = Duration.ofSeconds(30);
    /** A storage that takes longer to answer (e.g. a hung network mount) counts as offline. */
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(2);
    /** Checking a file system before adding it may reach a remote region, so it gets longer than a status read. */
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);

    /** The kind shown for a file system whose type the app no longer knows. */
    private static final String UNSUPPORTED_KIND = "unsupported";

    private final StorageTypes types;
    private final Function<String, Map<String, String>> secrets;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, LimitedProvider> providers = new ConcurrentHashMap<>();
    /** Options saved since the file systems were read; a caller may still hold one with the old options. */
    private final Map<String, RootOptions> savedOptions = new ConcurrentHashMap<>();
    private final Map<String, CachedStatus> statuses = new ConcurrentHashMap<>();
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    /** What the server sees at a path that is about to be registered. */
    public record PathProbe(boolean exists, boolean directory, boolean readable, boolean writable)
            implements Serializable {

        public static final PathProbe MISSING = new PathProbe(false, false, false, false);
    }

    private record CachedStatus(RootStatus status, Instant at) {
    }

    /**
     * What checking a new file system found.
     *
     * @param problem     why it can't be added as it is; null when it can
     * @param fingerprint the server's key or certificate to confirm before adding, or null
     */
    public record Check(String problem, String fingerprint) implements Serializable {

        public static final Check OK = new Check(null, null);

        public static Check failed(String problem) {
            return problem == null ? OK : new Check(problem, null);
        }
    }

    @Autowired
    public StorageService(StorageTypes types, RootSecrets secrets) {
        this(types, secrets::open);
    }

    /** For file systems without secrets, e.g. in tests. */
    StorageService(StorageTypes types) {
        this(types, rootId -> Map.of());
    }

    /** @param secrets the decrypted secrets of a registered file system, by its id */
    StorageService(StorageTypes types, Function<String, Map<String, String>> secrets) {
        this.types = types;
        this.secrets = secrets;
    }

    public StorageProvider provider(FsRoot root) {
        return providers.computeIfAbsent(cacheKey(root), k -> create(root));
    }

    /** Drops the cached provider and status of a file system that was removed. */
    // closed explicitly: the provider may hold sessions
    @SuppressWarnings("PMD.CloseResource")
    public void forget(FsRoot root) {
        final var key = cacheKey(root);
        final var provider = providers.remove(key);
        if (provider != null) {
            provider.close();
        }
        statuses.remove(key);
        savedOptions.remove(root.id());
    }

    /**
     * New requests get a provider with the new options at once. The old one is retired: what runs on it finishes,
     * and it closes after.
     */
    // retired, not closed: it closes itself once its last stream ends
    @SuppressWarnings("PMD.CloseResource")
    public void optionsChanged(FsRoot root, RootOptions options) {
        savedOptions.put(root.id(), options);
        final var old = providers.remove(cacheKey(root));
        if (old != null) {
            old.retire();
        }
    }

    private static String cacheKey(FsRoot root) {
        return root.id() + "|" + root.endpoint();
    }

    private LimitedProvider create(FsRoot root) {
        final var options = savedOptions.getOrDefault(root.id(), root.options());
        final var config = new StorageConfig(root.endpoint(), root.settings(), secrets.apply(root.id()),
                options.maxConnections());
        final var provider = types.get(root.type()).open(config);
        return new LimitedProvider(provider, options.maxConnections(), options.streamConnections());
    }

    public RootStatus status(FsRoot root) {
        final var key = cacheKey(root);
        final var cached = statuses.get(key);
        final var now = clock.instant();
        if (cached != null && cached.at().plus(STATUS_TTL).isAfter(now)) {
            return cached.status();
        }
        final var fresh = withTimeout(() -> readStatus(root), offline(root));
        statuses.put(key, new CachedStatus(fresh, now));
        return fresh;
    }

    private RootStatus readStatus(FsRoot root) {
        final StorageProvider provider;
        try {
            provider = provider(root);
        } catch (StorageException e) {
            return offline(root);
        }
        final var health = provider.health();
        if (!health.ok()) {
            return offline(root, health.reason());
        }
        final var mount = provider.mount();
        final var type = types.get(root.type());
        return new RootStatus(provider.capacity(), true, type.kindKey(root.settings(), mount),
                type.detail(root.settings(), mount));
    }

    private RootStatus offline(FsRoot root) {
        return offline(root, null);
    }

    private RootStatus offline(FsRoot root, String reason) {
        final Optional<MountInfo> mount = Optional.empty();
        return types.find(root.type())
                .map(type -> new RootStatus(null, false, type.kindKey(root.settings(), mount),
                        type.detail(root.settings(), mount), reason))
                .orElseGet(() -> new RootStatus(null, false, UNSUPPORTED_KIND, ""));
    }

    @PreDestroy
    void stop() {
        probes.shutdownNow();
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

    private static String name(Path path) {
        final var fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static List<String> readServerFolders(String path) {
        final var folder = Path.of(path);
        if (!folder.isAbsolute() || !Files.isDirectory(folder) || !Files.isReadable(folder)) {
            return List.of();
        }
        try (var children = Files.list(folder)) {
            return children
                    .filter(child -> !name(child).startsWith("."))
                    .filter(child -> Files.isDirectory(child) && Files.isReadable(child))
                    .map(StorageService::name)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** Whether a file system of {@code type} can be used with {@code config}. */
    public Check check(String typeKey, StorageConfig config) {
        final StorageType type;
        try {
            type = types.get(typeKey);
        } catch (StorageException e) {
            return Check.failed(e.getMessage());
        }
        final var info = types.info(typeKey);
        return withTimeout(() -> readCheck(type, info, config), Check.failed(info.checkTimeout()), CHECK_TIMEOUT);
    }

    // closed explicitly: only some providers are closeable
    @SuppressWarnings({"PMD.CloseResource", "PMD.UseTryWithResources"})
    private Check readCheck(StorageType type, RootTypeInfo info, StorageConfig config) {
        StorageProvider provider = null;
        try {
            provider = type.open(config);
            return Check.failed(type.problem(provider));
        } catch (UntrustedServerException e) {
            return new Check(e.getMessage(), e.getFingerprint());
        } catch (StorageException e) {
            return Check.failed(e.getMessage());
        } catch (RuntimeException e) {
            return Check.failed(info.checkFailed());
        } finally {
            if (provider instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // it was only opened for the check
                }
            }
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
