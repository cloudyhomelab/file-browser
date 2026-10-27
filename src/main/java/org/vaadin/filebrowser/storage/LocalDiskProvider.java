package org.vaadin.filebrowser.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.vaadin.filebrowser.data.Capacity;

/**
 * A folder on the server's file system, including OS-mounted NFS and SMB shares. Every path is
 * resolved inside the root: {@code ..} segments and symlinks that lead outside it are refused.
 */
public class LocalDiskProvider implements StorageProvider {

    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;
    private static final String PARTIAL_PREFIX = ".";
    private static final String PARTIAL_SUFFIX = ".uploading";

    private final Path root;
    private final Function<String, String> typeForName;
    private final Function<String, String> kindForStoreType;

    /**
     * @param typeForName      maps a file name to the app's file type ({@code pdf}, {@code image}, …)
     * @param kindForStoreType maps a {@code FileStore} type ({@code nfs4}, {@code cifs}, …) to a mount kind
     */
    public LocalDiskProvider(Path root, Function<String, String> typeForName,
                             Function<String, String> kindForStoreType) {
        this.root = root.toAbsolutePath().normalize();
        this.typeForName = typeForName;
        this.kindForStoreType = kindForStoreType;
    }

    // ---- path guard -----------------------------------------------------------------------

    private Path realRoot() {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            throw new StorageException("The file system is not available.", e);
        }
    }

    /** The absolute path for {@code relative}, refusing anything that resolves outside the root. */
    Path resolve(String relative) {
        StoragePaths.requireValidPath(relative);
        final var realRoot = realRoot();
        final var candidate = realRoot.resolve(relative).normalize();
        if (!candidate.startsWith(realRoot)) {
            throw new StorageException("That location is outside this file system.");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            final Path real;
            try {
                real = candidate.toRealPath();
            } catch (IOException e) {
                throw new StorageException("That location cannot be read.", e);
            }
            if (!real.startsWith(realRoot)) {
                throw new StorageException("That location is outside this file system.");
            }
        }
        return candidate;
    }

    private String relative(Path absolute) {
        return StoragePaths.join(segmentsOf(realRoot().relativize(absolute)));
    }

    private static List<String> segmentsOf(Path relative) {
        final var out = new ArrayList<String>();
        relative.forEach(p -> {
            if (!p.toString().isEmpty()) {
                out.add(p.toString());
            }
        });
        return out;
    }

    private Path existing(String path) {
        final var absolute = resolve(path);
        if (!Files.exists(absolute)) {
            throw new StorageException("“" + absolute.getFileName() + "” no longer exists.");
        }
        return absolute;
    }

    private Path existingFolder(String path) {
        final var absolute = existing(path);
        if (!Files.isDirectory(absolute)) {
            throw new StorageException("“" + path + "” is not a folder.");
        }
        return absolute;
    }

    // ---- reading --------------------------------------------------------------------------

    @Override
    public List<StorageEntry> list(String folder) {
        final var dir = existingFolder(folder);
        final var realRoot = realRoot();
        final var out = new ArrayList<StorageEntry>();
        try (final var children = Files.list(dir)) {
            for (final var child : (Iterable<Path>) children::iterator) {
                if (escapes(child, realRoot) || isPartialUpload(child)) {
                    continue;
                }
                entry(child).ifPresent(out::add);
            }
        } catch (AccessDeniedException e) {
            throw new StorageException("You don't have permission to read this folder.", e);
        } catch (IOException | UncheckedIOException e) {
            throw new StorageException("This folder cannot be read.", e);
        }
        return out;
    }

    private static boolean escapes(Path child, Path realRoot) {
        try {
            return !child.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            // a dangling symlink has no real path and nothing to show
            return true;
        }
    }

    private static boolean isPartialUpload(Path path) {
        final var name = path.getFileName().toString();
        return name.startsWith(PARTIAL_PREFIX) && name.endsWith(PARTIAL_SUFFIX);
    }

    @Override
    public Optional<StorageEntry> stat(String path) {
        final var absolute = resolve(path);
        if (!Files.exists(absolute)) {
            return Optional.empty();
        }
        return entry(absolute);
    }

    private Optional<StorageEntry> entry(Path absolute) {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(absolute, BasicFileAttributes.class);
        } catch (IOException e) {
            return Optional.empty();
        }
        final var name = absolute.getFileName() == null ? "" : absolute.getFileName().toString();
        final var folder = attributes.isDirectory();
        final var type = folder ? StorageEntry.FOLDER_TYPE : typeForName.apply(name);
        final var size = folder ? null : attributes.size();
        final var modified = attributes.lastModifiedTime().toInstant();
        final var childCount = folder ? countChildren(absolute) : null;
        return Optional.of(new StorageEntry(relative(absolute), name, folder, type, size, modified, owner(absolute),
                childCount));
    }

    private static Integer countChildren(Path dir) {
        try (final var children = Files.list(dir)) {
            return (int) children.count();
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    private static String owner(Path path) {
        try {
            return Files.getOwner(path).getName();
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    @Override
    public InputStream openRead(String path) {
        final var absolute = existing(path);
        try {
            return Files.newInputStream(absolute);
        } catch (IOException e) {
            throw new StorageException("“" + absolute.getFileName() + "” cannot be read.", e);
        }
    }

    // ---- changes --------------------------------------------------------------------------

    @Override
    public void createFolder(String parent, String name) {
        StoragePaths.requireValidName(name);
        final var target = resolve(StoragePaths.child(parent, name));
        existingFolder(parent);
        try {
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException e) {
            throw new StorageException("“" + name + "” already exists here.", e);
        } catch (IOException e) {
            throw failure("create “" + name + "”", e);
        }
    }

    @Override
    public void rename(String path, String newName) {
        StoragePaths.requireValidName(newName);
        final var source = existing(path);
        final var target = resolve(StoragePaths.child(StoragePaths.join(parentOf(path)), newName));
        try {
            Files.move(source, target);
        } catch (FileAlreadyExistsException e) {
            throw new StorageException("“" + newName + "” already exists here.", e);
        } catch (IOException e) {
            throw failure("rename “" + source.getFileName() + "”", e);
        }
    }

    @Override
    public void move(String path, String destinationFolder) {
        final var source = existing(path);
        final var destination = existingFolder(destinationFolder);
        if (destination.startsWith(source)) {
            throw new StorageException("A folder can't be moved into itself.");
        }
        final var target = destination.resolve(source.getFileName().toString());
        try {
            Files.move(source, target);
        } catch (FileAlreadyExistsException e) {
            throw new StorageException("“" + source.getFileName() + "” already exists in the destination.", e);
        } catch (IOException e) {
            throw failure("move “" + source.getFileName() + "”", e);
        }
    }

    @Override
    public void copy(String path, String targetName) {
        StoragePaths.requireValidName(targetName);
        final var source = existing(path);
        final var target = resolve(StoragePaths.child(StoragePaths.join(parentOf(path)), targetName));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("“" + targetName + "” already exists here.");
        }
        try {
            if (Files.isDirectory(source)) {
                copyTree(source, target);
            } else {
                Files.copy(source, target);
            }
        } catch (IOException e) {
            throw failure("copy “" + source.getFileName() + "”", e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectory(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void delete(String path) {
        if (path.isEmpty()) {
            throw new StorageException("The root of a file system can't be deleted.");
        }
        final var target = existing(path);
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (DirectoryNotEmptyException e) {
            throw new StorageException("“" + target.getFileName() + "” changed while it was being deleted.", e);
        } catch (IOException e) {
            throw failure("delete “" + target.getFileName() + "”", e);
        }
    }

    @Override
    public WriteHandle openWrite(String folder, String name, boolean replace) {
        StoragePaths.requireValidName(name);
        existingFolder(folder);
        final var target = resolve(StoragePaths.child(folder, name));
        if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("“" + name + "” already exists here.");
        }
        if (Files.isDirectory(target)) {
            throw new StorageException("A folder named “" + name + "” already exists here.");
        }
        // write next to the target so the final move stays on the same file system and is atomic
        final var partial = target.resolveSibling(PARTIAL_PREFIX + name + "." + UUID.randomUUID() + PARTIAL_SUFFIX);
        final OutputStream stream;
        try {
            stream = Files.newOutputStream(partial);
        } catch (IOException e) {
            throw failure("upload “" + name + "”", e);
        }
        return new LocalWrite(stream, partial, target, replace, name);
    }

    private static final class LocalWrite implements WriteHandle {

        private final OutputStream stream;
        private final Path partial;
        private final Path target;
        private final boolean replace;
        private final String name;
        private boolean done;

        LocalWrite(OutputStream stream, Path partial, Path target, boolean replace, String name) {
            this.stream = stream;
            this.partial = partial;
            this.target = target;
            this.replace = replace;
            this.name = name;
        }

        @Override
        public OutputStream stream() {
            return stream;
        }

        @Override
        public void commit() {
            try {
                stream.close();
                if (replace) {
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                }
                done = true;
            } catch (FileAlreadyExistsException e) {
                abort();
                throw new StorageException("“" + name + "” already exists here.", e);
            } catch (IOException e) {
                abort();
                throw new StorageException("“" + name + "” could not be saved.", e);
            }
        }

        @Override
        public void abort() {
            if (done) {
                return;
            }
            done = true;
            try {
                stream.close();
            } catch (IOException ignored) {
                // the partial file is deleted next either way
            }
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // a leftover partial file is hidden from listings
            }
        }
    }

    private static List<String> parentOf(String path) {
        final var segments = StoragePaths.segments(path);
        return segments.isEmpty() ? List.of() : segments.subList(0, segments.size() - 1);
    }

    private static StorageException failure(String action, IOException e) {
        if (e instanceof AccessDeniedException) {
            return new StorageException("You don't have permission to " + action + ".", e);
        }
        if (e instanceof NoSuchFileException) {
            return new StorageException("Could not " + action + ": it no longer exists.", e);
        }
        return new StorageException("Could not " + action + ".", e);
    }

    // ---- status ---------------------------------------------------------------------------

    @Override
    public Capacity capacity() {
        try {
            final var store = Files.getFileStore(realRoot());
            final var total = store.getTotalSpace();
            final var used = total - store.getUsableSpace();
            return new Capacity(used / BYTES_PER_GB, total / BYTES_PER_GB);
        } catch (IOException | StorageException e) {
            return null;
        }
    }

    @Override
    public boolean healthy() {
        return Files.isDirectory(root) && Files.isReadable(root);
    }

    @Override
    public boolean writable() {
        return Files.isWritable(root);
    }

    @Override
    public Optional<MountInfo> mount() {
        try {
            final var store = Files.getFileStore(realRoot());
            final var type = store.type();
            return Optional.of(new MountInfo(kindForStoreType.apply(type), type, store.name()));
        } catch (IOException | StorageException e) {
            return Optional.empty();
        }
    }
}
