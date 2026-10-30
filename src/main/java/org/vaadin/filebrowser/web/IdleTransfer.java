package org.vaadin.filebrowser.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Copies a file to a client that may stop reading, e.g. a paused video. Once a write has been blocked for the idle
 * limit, the file's stream is closed, which gives its storage connection back; the browser asks again with a range
 * when it wants more.
 */
final class IdleTransfer {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long NOT_WRITING = Long.MIN_VALUE;
    private static final System.Logger LOG = System.getLogger(IdleTransfer.class.getName());

    private final InputStream in;
    private final Duration idleLimit;
    private final ScheduledExecutorService watcher;
    /** When the write that is under way started, or {@link #NOT_WRITING}. */
    private final AtomicLong writingSince = new AtomicLong(NOT_WRITING);
    /** Guarded by {@code this}. */
    private ScheduledFuture<?> check;
    private boolean done;
    private boolean closedIdle;

    private IdleTransfer(InputStream in, Duration idleLimit, ScheduledExecutorService watcher) {
        this.in = in;
        this.idleLimit = idleLimit;
        this.watcher = watcher;
    }

    /**
     * Copies {@code in} to {@code out}; returns early, without an error, when the client stopped reading.
     *
     * @param watcher runs the idle checks; one serves every transfer
     */
    static void transfer(InputStream in, OutputStream out, Duration idleLimit, ScheduledExecutorService watcher)
            throws IOException {
        new IdleTransfer(in, idleLimit, watcher).run(out);
    }

    private void run(OutputStream out) throws IOException {
        schedule(idleLimit.toNanos());
        try {
            final var buffer = new byte[BUFFER_SIZE];
            for (var read = in.read(buffer); read != -1; read = in.read(buffer)) {
                writingSince.set(System.nanoTime());
                out.write(buffer, 0, read);
                writingSince.set(NOT_WRITING);
            }
        } catch (IOException e) {
            if (!wasClosedIdle()) {
                throw e;
            }
        } finally {
            stop();
        }
    }

    private synchronized void schedule(long delayNanos) {
        if (!done) {
            check = watcher.schedule(this::checkIdle, delayNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void checkIdle() {
        final var since = writingSince.get();
        final var limit = idleLimit.toNanos();
        if (since == NOT_WRITING) {
            schedule(limit);
            return;
        }
        final var blocked = System.nanoTime() - since;
        if (blocked < limit) {
            schedule(limit - blocked);
            return;
        }
        synchronized (this) {
            if (done) {
                return;
            }
            closedIdle = true;
        }
        try {
            in.close();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Closing an idle stream failed", e);
        }
    }

    private synchronized boolean wasClosedIdle() {
        return closedIdle;
    }

    private synchronized void stop() {
        done = true;
        if (check != null) {
            check.cancel(false);
        }
    }
}
