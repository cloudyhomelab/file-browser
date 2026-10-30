package org.vaadin.filebrowser.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.vaadin.filebrowser.data.Capacity;

/**
 * Limits how many connections (sessions, HTTP connections, open files) a file system uses at once. A read or write
 * holds one until its stream or write handle is closed, a copy holds {@link #COPY_CONNECTIONS} while it runs, and
 * anything else one while it runs. Reads, writes and copies may only use part of the connections, so short
 * operations such as listing a folder always find one free.
 */
public final class LimitedProvider implements StorageProvider, AutoCloseable {

    /** Where the storage can't copy by itself, the copy reads and writes on two connections at once. */
    public static final int COPY_CONNECTIONS = 2;
    /** How long an operation waits for a free connection before giving up. */
    public static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    private final StorageProvider delegate;
    private final Duration waitLimit;
    private final Semaphore total;
    private final Semaphore streams;
    private final Object lock = new Object();
    /** Connections taken and not given back yet; guarded by {@link #lock}. */
    private int inUse;
    private boolean retired;
    private boolean closed;

    /** A connection grant; giving it back twice does nothing. */
    private final class Lease {

        private final int connections;
        private final boolean stream;
        private boolean released;

        Lease(int connections, boolean stream) {
            this.connections = connections;
            this.stream = stream;
        }

        void release() {
            synchronized (lock) {
                if (released) {
                    return;
                }
                released = true;
                inUse -= connections;
            }
            total.release(connections);
            if (stream) {
                streams.release(connections);
            }
            closeIfDone();
        }
    }

    /**
     * @param maxConnections    the most connections in use at once
     * @param streamConnections how many of them reads, writes and copies may use; at least {@link #COPY_CONNECTIONS}
     */
    public LimitedProvider(StorageProvider delegate, int maxConnections, int streamConnections) {
        this(delegate, maxConnections, streamConnections, WAIT_LIMIT);
    }

    LimitedProvider(StorageProvider delegate, int maxConnections, int streamConnections, Duration waitLimit) {
        if (streamConnections < COPY_CONNECTIONS || streamConnections > maxConnections) {
            throw new IllegalArgumentException("Streams need " + COPY_CONNECTIONS + " to " + maxConnections
                    + " connections, not " + streamConnections);
        }
        this.delegate = delegate;
        this.waitLimit = waitLimit;
        this.total = new Semaphore(maxConnections, true);
        this.streams = new Semaphore(streamConnections, true);
    }

    // ---- connections ----------------------------------------------------------------------

    // counted as in use before waiting, so a retire meanwhile doesn't close the provider under it
    private Lease acquire(int connections, boolean stream) {
        synchronized (lock) {
            inUse += connections;
        }
        try {
            takePermits(connections, stream);
        } catch (RuntimeException e) {
            synchronized (lock) {
                inUse -= connections;
            }
            closeIfDone();
            throw e;
        }
        return new Lease(connections, stream);
    }

    // all of a copy's connections are taken in one step, so two copies never each hold one while waiting for another
    private void takePermits(int connections, boolean stream) {
        final var deadline = System.nanoTime() + waitLimit.toNanos();
        try {
            if (stream && !streams.tryAcquire(connections, waitLimit.toNanos(), TimeUnit.NANOSECONDS)) {
                throw busy();
            }
            final var left = deadline - System.nanoTime();
            if (!total.tryAcquire(connections, Math.max(0, left), TimeUnit.NANOSECONDS)) {
                if (stream) {
                    streams.release(connections);
                }
                throw busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Stopped waiting for the server.", e);
        }
    }

    private static StorageException busy() {
        return new StorageException("The server is busy. Try again in a moment.");
    }

    private <T> T run(int connections, boolean stream, Supplier<T> work) {
        final var lease = acquire(connections, stream);
        try {
            return work.get();
        } finally {
            lease.release();
        }
    }

    private <T> T shortly(Supplier<T> work) {
        return run(1, false, work);
    }

    private void shortly(Runnable work) {
        run(1, false, () -> {
            work.run();
            return null;
        });
    }

    // ---- retiring -------------------------------------------------------------------------

    /**
     * Closes the provider underneath once nothing uses it any more: right away when idle, otherwise when the last
     * stream or operation ends. For a provider that new requests no longer reach, e.g. after its limits changed.
     */
    public void retire() {
        synchronized (lock) {
            retired = true;
        }
        closeIfDone();
    }

    private void closeIfDone() {
        synchronized (lock) {
            if (!retired || inUse > 0 || closed) {
                return;
            }
            closed = true;
        }
        closeDelegate();
    }

    /** Closes the provider underneath at once, cutting off what still runs on it. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        closeDelegate();
    }

    private void closeDelegate() {
        // only some providers hold anything to close
        if (delegate instanceof AutoCloseable) {
            closeQuietly((AutoCloseable) delegate);
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // it is no longer used either way
        }
    }

    // ---- short operations -----------------------------------------------------------------

    @Override
    public List<StorageEntry> list(String folder) {
        return shortly(() -> delegate.list(folder));
    }

    @Override
    public Optional<StorageEntry> stat(String path) {
        return shortly(() -> delegate.stat(path));
    }

    @Override
    public void createFolder(String parent, String name) {
        shortly(() -> delegate.createFolder(parent, name));
    }

    @Override
    public void rename(String path, String newName) {
        shortly(() -> delegate.rename(path, newName));
    }

    @Override
    public void renameReplacing(String path, String newName) {
        shortly(() -> delegate.renameReplacing(path, newName));
    }

    @Override
    public void move(String path, String destinationFolder, String targetName) {
        shortly(() -> delegate.move(path, destinationFolder, targetName));
    }

    @Override
    public void delete(String path) {
        shortly(() -> delegate.delete(path));
    }

    @Override
    public boolean deleteEmptyFolder(String path) {
        return shortly(() -> delegate.deleteEmptyFolder(path));
    }

    @Override
    public Capacity capacity() {
        return shortly(delegate::capacity);
    }

    @Override
    public boolean healthy() {
        return shortly(delegate::healthy);
    }

    @Override
    public Health health() {
        return shortly(delegate::health);
    }

    @Override
    public boolean writable() {
        return shortly(delegate::writable);
    }

    @Override
    public Optional<MountInfo> mount() {
        return shortly(delegate::mount);
    }

    // ---- streams --------------------------------------------------------------------------

    @Override
    public void copy(String path, String destinationFolder, String targetName) {
        run(COPY_CONNECTIONS, true, () -> {
            delegate.copy(path, destinationFolder, targetName);
            return null;
        });
    }

    @Override
    public void copy(String path, String destinationFolder, String targetName, CopyProgress progress) {
        run(COPY_CONNECTIONS, true, () -> {
            delegate.copy(path, destinationFolder, targetName, progress);
            return null;
        });
    }

    @Override
    public InputStream openRead(String path) {
        return held(() -> delegate.openRead(path));
    }

    @Override
    public InputStream openRead(String path, long offset, long length) {
        return held(() -> delegate.openRead(path, offset, length));
    }

    private InputStream held(Supplier<InputStream> open) {
        final var lease = acquire(1, true);
        try {
            final var in = open.get();
            return new LeasedInputStream(in, lease);
        } catch (RuntimeException e) {
            lease.release();
            throw e;
        }
    }

    @Override
    public WriteHandle openWrite(String folder, String name, boolean replace) {
        final var lease = acquire(1, true);
        try {
            final var write = delegate.openWrite(folder, name, replace);
            return new LeasedWrite(write, lease);
        } catch (RuntimeException e) {
            lease.release();
            throw e;
        }
    }

    private static final class LeasedInputStream extends FilterInputStream {

        private final Lease lease;

        LeasedInputStream(InputStream in, Lease lease) {
            super(in);
            this.lease = lease;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                lease.release();
            }
        }
    }

    private static final class LeasedWrite implements WriteHandle {

        private final WriteHandle write;
        private final Lease lease;

        LeasedWrite(WriteHandle write, Lease lease) {
            this.write = write;
            this.lease = lease;
        }

        @Override
        public OutputStream getStream() {
            return write.getStream();
        }

        @Override
        public void commit() {
            write.commit();
        }

        @Override
        public void abort() {
            try {
                write.abort();
            } finally {
                lease.release();
            }
        }

        @Override
        public void close() {
            try {
                write.close();
            } finally {
                lease.release();
            }
        }
    }
}
