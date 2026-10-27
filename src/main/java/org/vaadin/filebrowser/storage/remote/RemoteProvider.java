package org.vaadin.filebrowser.storage.remote;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.vaadin.filebrowser.data.Capacity;
import org.vaadin.filebrowser.storage.Health;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;
import org.vaadin.filebrowser.storage.WriteHandle;

/**
 * A folder on a server reached over a file-transfer protocol. Subclasses provide the protocol's
 * primitive commands on a session; this class turns them into file-system operations with the
 * same checks and messages as the other providers. Uploads go to a hidden partial file that is
 * renamed into place on commit. Copies are downloaded and uploaded again, as neither protocol
 * copies on the server.
 */
public abstract class RemoteProvider<S> implements StorageProvider, AutoCloseable {

    private static final String PARTIAL_PREFIX = ".";
    private static final String PARTIAL_SUFFIX = ".uploading";

    /** What the server says about one file or folder. */
    public record Node(String name, boolean folder, Long size, Instant modified) {
    }

    /** Something the server refused; the session is still usable after it. */
    protected static class Refusal extends IOException {
        public Refusal(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    protected interface Work<S, T> {
        T run(S session) throws IOException;
    }

    protected final RemoteLocation location;
    private final Function<String, String> typeForName;
    private final SessionPool<S> pool;

    protected RemoteProvider(RemoteLocation location, Function<String, String> typeForName) {
        this.location = location;
        this.typeForName = typeForName;
        this.pool = new SessionPool<>(this::connect, this::alive, this::disconnect);
    }

    // ---- protocol primitives; paths are absolute on the server --------------------------------

    /** A signed-in session; failures to reach or sign in to the server are {@link StorageException}s. */
    protected abstract S connect() throws IOException;

    protected abstract boolean alive(S session);

    protected abstract void disconnect(S session);

    /** The entries of a folder, without {@code .} and {@code ..}. */
    protected abstract List<Node> listNodes(S session, String folder) throws IOException;

    /** @return null when nothing is at {@code path} */
    protected abstract Node statNode(S session, String path) throws IOException;

    protected abstract void makeFolder(S session, String path) throws IOException;

    /** Renames where nothing exists at {@code to}. */
    protected abstract void renameNode(S session, String from, String to) throws IOException;

    protected abstract void deleteFile(S session, String path) throws IOException;

    protected abstract void deleteEmptyFolder(S session, String path) throws IOException;

    /** Closing the stream ends the transfer and throws if the server reported it failed. */
    protected abstract InputStream readStream(S session, String path) throws IOException;

    /** Creates or truncates {@code path}; closing the stream ends the transfer. */
    protected abstract OutputStream writeStream(S session, String path) throws IOException;

    /** Moves {@code from} over the existing file {@code to}; by default deletes it first. */
    protected void replaceNode(S session, String from, String to) throws IOException {
        deleteFile(session, to);
        renameNode(session, from, to);
    }

    // ---- sessions -------------------------------------------------------------------------

    /** Runs {@code work} on a pooled session. */
    protected <T> T call(String action, Work<S, T> work) {
        final S session;
        try {
            session = pool.borrow();
        } catch (IOException e) {
            throw failure(action, e);
        }
        var broken = false;
        try {
            return work.run(session);
        } catch (IOException e) {
            broken = !(e instanceof Refusal);
            throw failure(action, e);
        } catch (RuntimeException e) {
            broken = true;
            throw e;
        } finally {
            pool.release(session, broken);
        }
    }

    /** By default the server's own reason is left out; it may be in another language or leak paths. */
    protected StorageException failure(String action, IOException e) {
        return new StorageException("Could not " + action + ".", e);
    }

    // ---- paths ----------------------------------------------------------------------------

    private String remote(String path) {
        return location.resolve(path);
    }

    private static String nameOf(String path) {
        final var slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String parentOf(String path) {
        final var slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean isPartial(String name) {
        return name.startsWith(PARTIAL_PREFIX) && name.endsWith(PARTIAL_SUFFIX);
    }

    private StorageEntry entry(String path, Node node) {
        if (node.folder()) {
            return new StorageEntry(path, nameOf(path), true, StorageEntry.FOLDER_TYPE, null, node.modified(), null, null);
        }
        final var name = nameOf(path);
        return new StorageEntry(path, name, false, typeForName.apply(name), node.size(), node.modified(), null, null);
    }

    // ---- reading --------------------------------------------------------------------------

    @Override
    public List<StorageEntry> list(String folder) {
        return call("read this folder", session -> {
            requireFolder(session, folder);
            final var out = new ArrayList<StorageEntry>();
            for (final var node : listNodes(session, remote(folder))) {
                if (!isPartial(node.name())) {
                    out.add(entry(StoragePaths.child(folder, node.name()), node));
                }
            }
            return out;
        });
    }

    @Override
    public Optional<StorageEntry> stat(String path) {
        return call("read “" + nameOf(path) + "”", session -> {
            final var node = statNode(session, remote(path));
            return Optional.ofNullable(node).map(found -> entry(path, found));
        });
    }

    private void requireFolder(S session, String path) throws IOException {
        final var node = statNode(session, remote(path));
        if (node == null || !node.folder()) {
            throw new StorageException("“" + nameOf(path) + "” no longer exists.");
        }
    }

    private void requireFree(S session, String path, String message) throws IOException {
        if (statNode(session, remote(path)) != null) {
            throw new StorageException(message);
        }
    }

    @Override
    public InputStream openRead(String path) {
        final var action = "read “" + nameOf(path) + "”";
        final S session;
        try {
            session = pool.borrow();
        } catch (IOException e) {
            throw failure(action, e);
        }
        try {
            final var node = statNode(session, remote(path));
            if (node == null || node.folder()) {
                throw new StorageException("“" + nameOf(path) + "” no longer exists.");
            }
            return new SessionInputStream(readStream(session, remote(path)), session);
        } catch (IOException e) {
            pool.release(session, !(e instanceof Refusal));
            throw failure(action, e);
        } catch (RuntimeException e) {
            pool.release(session, false);
            throw e;
        }
    }

    /** Gives its session back once the download is read, or abandoned. */
    private final class SessionInputStream extends FilterInputStream {

        private final S session;
        private boolean closed;

        SessionInputStream(InputStream in, S session) {
            super(in);
            this.session = session;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            var broken = true;
            try {
                super.close();
                broken = false;
            } finally {
                pool.release(session, broken);
            }
        }
    }

    // ---- changes --------------------------------------------------------------------------

    @Override
    public void createFolder(String parent, String name) {
        StoragePaths.requireValidName(name);
        call("create “" + name + "”", session -> {
            requireFolder(session, parent);
            final var path = StoragePaths.child(parent, name);
            requireFree(session, path, "“" + name + "” already exists here.");
            makeFolder(session, remote(path));
            return null;
        });
    }

    @Override
    public void rename(String path, String newName) {
        StoragePaths.requireValidName(newName);
        call("rename “" + nameOf(path) + "”", session -> {
            final var target = StoragePaths.child(parentOf(path), newName);
            requireFree(session, target, "“" + newName + "” already exists here.");
            renameNode(session, remote(path), remote(target));
            return null;
        });
    }

    @Override
    public void move(String path, String destinationFolder) {
        if (destinationFolder.equals(path) || destinationFolder.startsWith(path + "/")) {
            throw new StorageException("A folder can't be moved into itself.");
        }
        call("move “" + nameOf(path) + "”", session -> {
            requireFolder(session, destinationFolder);
            final var target = StoragePaths.child(destinationFolder, nameOf(path));
            requireFree(session, target, "“" + nameOf(path) + "” already exists in the destination.");
            renameNode(session, remote(path), remote(target));
            return null;
        });
    }

    /** Downloads and uploads again; a failure part-way removes what was copied so far. */
    @Override
    public void copy(String path, String targetName) {
        StoragePaths.requireValidName(targetName);
        final var action = "copy “" + nameOf(path) + "”";
        final var target = StoragePaths.child(parentOf(path), targetName);
        call(action, session -> {
            requireFree(session, target, "“" + targetName + "” already exists here.");
            final var source = statNode(session, remote(path));
            if (source == null) {
                throw new StorageException("“" + nameOf(path) + "” no longer exists.");
            }
            try {
                copyNode(session, path, target, source.folder());
            } catch (IOException | RuntimeException e) {
                removeQuietly(target);
                throw e;
            }
            return null;
        });
    }

    // the data goes through a second session: one session can't download and upload at once on FTP
    private void copyNode(S session, String from, String to, boolean folder) throws IOException {
        if (!folder) {
            try (final var in = readStream(session, remote(from))) {
                call("copy “" + nameOf(from) + "”", writer -> {
                    try (final var out = writeStream(writer, remote(to))) {
                        in.transferTo(out);
                    }
                    return null;
                });
            }
            return;
        }
        makeFolder(session, remote(to));
        for (final var child : listNodes(session, remote(from))) {
            if (!isPartial(child.name())) {
                copyNode(session, StoragePaths.child(from, child.name()), StoragePaths.child(to, child.name()),
                        child.folder());
            }
        }
    }

    private void removeQuietly(String path) {
        try {
            delete(path);
        } catch (RuntimeException e) {
            // the copy failed already; that is what gets reported
        }
    }

    @Override
    public void delete(String path) {
        if (path.isEmpty()) {
            throw new StorageException("The root of a file system can't be deleted.");
        }
        call("delete “" + nameOf(path) + "”", session -> {
            final var node = statNode(session, remote(path));
            if (node == null) {
                throw new StorageException("“" + nameOf(path) + "” no longer exists.");
            }
            deleteNode(session, remote(path), node.folder());
            return null;
        });
    }

    private void deleteNode(S session, String path, boolean folder) throws IOException {
        if (!folder) {
            deleteFile(session, path);
            return;
        }
        for (final var child : listNodes(session, path)) {
            deleteNode(session, path.equals("/") ? "/" + child.name() : path + "/" + child.name(), child.folder());
        }
        deleteEmptyFolder(session, path);
    }

    @Override
    public WriteHandle openWrite(String folder, String name, boolean replace) {
        StoragePaths.requireValidName(name);
        final var action = "save “" + name + "”";
        final S session;
        try {
            session = pool.borrow();
        } catch (IOException e) {
            throw failure(action, e);
        }
        try {
            requireFolder(session, folder);
            final var path = StoragePaths.child(folder, name);
            final var existing = statNode(session, remote(path));
            if (existing != null && existing.folder()) {
                throw new StorageException("A folder named “" + name + "” already exists here.");
            }
            if (existing != null && !replace) {
                throw new StorageException("“" + name + "” already exists here.");
            }
            final var partial = StoragePaths.child(folder, PARTIAL_PREFIX + name + "." + UUID.randomUUID() + PARTIAL_SUFFIX);
            final var stream = writeStream(session, remote(partial));
            return new RemoteWrite(session, stream, partial, path, replace, name);
        } catch (IOException e) {
            pool.release(session, !(e instanceof Refusal));
            throw failure(action, e);
        } catch (RuntimeException e) {
            pool.release(session, false);
            throw e;
        }
    }

    /** Writes to a hidden partial file on its own session; commit renames it into place. */
    private final class RemoteWrite implements WriteHandle {

        private final S session;
        /** The protocol's stream: closing it ends the transfer. */
        private final OutputStream transfer;
        /** What callers write to; closing it leaves ending the transfer to commit or abort. */
        private final OutputStream stream;
        private final String partial;
        private final String target;
        private final boolean replace;
        private final String name;
        private boolean done;

        RemoteWrite(S session, OutputStream transfer, String partial, String target, boolean replace, String name) {
            this.session = session;
            this.transfer = transfer;
            this.stream = new FilterOutputStream(transfer) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    flush();
                }
            };
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
            if (done) {
                return;
            }
            var broken = true;
            try {
                transfer.close();
                final var existing = statNode(session, remote(target));
                if (existing != null && !replace) {
                    deleteFile(session, remote(partial));
                    broken = false;
                    throw new StorageException("“" + name + "” already exists here.");
                }
                if (existing != null) {
                    replaceNode(session, remote(partial), remote(target));
                } else {
                    renameNode(session, remote(partial), remote(target));
                }
                broken = false;
            } catch (IOException e) {
                broken = !(e instanceof Refusal);
                done = true;
                pool.release(session, broken);
                removeQuietly(partial);
                throw new StorageException("“" + name + "” could not be saved.", e);
            } catch (RuntimeException e) {
                done = true;
                pool.release(session, broken);
                throw e;
            }
            done = true;
            pool.release(session, false);
        }

        @Override
        public void abort() {
            if (done) {
                return;
            }
            done = true;
            var broken = true;
            try {
                transfer.close();
                deleteFile(session, remote(partial));
                broken = false;
            } catch (IOException | RuntimeException e) {
                // a leftover partial file is hidden from listings
            } finally {
                pool.release(session, broken);
            }
        }
    }

    // ---- status ---------------------------------------------------------------------------

    @Override
    public Capacity capacity() {
        return null;
    }

    @Override
    public boolean healthy() {
        return health().ok();
    }

    @Override
    public Health health() {
        try {
            final boolean found = call("reach the server", session -> {
                final var root = statNode(session, location.folder());
                return root != null && root.folder();
            });
            return found ? Health.OK : Health.down("The folder no longer exists on the server.");
        } catch (StorageException e) {
            return Health.down(e.getMessage());
        }
    }

    /** Whether writing is allowed can't be told without writing; the admin's access choice decides. */
    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public void close() {
        pool.close();
    }
}
