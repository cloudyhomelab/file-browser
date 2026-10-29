package org.vaadin.filebrowser.storage.remote;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.vaadin.filebrowser.storage.StorageException;

/**
 * A few open sessions to one server, reused between operations. Remote protocols run one command
 * at a time per session, so each operation borrows one and gives it back. Sessions left idle for
 * a minute are closed; one idle for a while is checked before it is handed out again.
 */
public final class SessionPool<S> implements AutoCloseable {

    public static final int MAX_SESSIONS = 4;
    private static final Duration IDLE_LIMIT = Duration.ofSeconds(60);
    /** Servers drop quiet connections without telling, so one unused this long is checked first. */
    private static final Duration CHECK_AFTER = Duration.ofSeconds(10);
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);
    private static final ScheduledExecutorService SWEEPER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("remote-session-sweeper").factory());

    private final Opener<S> opener;
    private final Predicate<S> alive;
    private final Consumer<S> closer;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(MAX_SESSIONS, true);
    private final Deque<Idle<S>> idle = new ArrayDeque<>();
    private final ScheduledFuture<?> sweep;
    private boolean closed;

    /** Opens a session, signed in and ready. */
    @FunctionalInterface
    public interface Opener<S> {
        S open() throws IOException;
    }

    private record Idle<S>(S session, Instant since) {
    }

    /**
     * @param alive  whether a session still works; may talk to the server
     * @param closer closes a session, quietly
     */
    public SessionPool(Opener<S> opener, Predicate<S> alive, Consumer<S> closer) {
        this(opener, alive, closer, Clock.systemUTC());
    }

    SessionPool(Opener<S> opener, Predicate<S> alive, Consumer<S> closer, Clock clock) {
        this.opener = opener;
        this.alive = alive;
        this.closer = closer;
        this.clock = clock;
        this.sweep = SWEEPER.scheduleWithFixedDelay(this::closeExpired, IDLE_LIMIT.toSeconds(),
                IDLE_LIMIT.toSeconds(), TimeUnit.SECONDS);
    }

    /** A working session; give it back with {@link #release}. */
    public S borrow() throws IOException {
        try {
            if (!permits.tryAcquire(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new StorageException("The server is busy. Try again in a moment.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Stopped waiting for the server.", e);
        }
        try {
            final var reused = reusable();
            return reused != null ? reused : opener.open();
        } catch (IOException | RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    private S reusable() {
        while (true) {
            final Idle<S> next;
            synchronized (this) {
                if (closed) {
                    throw new StorageException("This file system was removed.");
                }
                next = idle.pollFirst();
            }
            if (next == null) {
                return null;
            }
            final var quiet = Duration.between(next.since(), clock.instant());
            if (quiet.compareTo(IDLE_LIMIT) < 0 && (quiet.compareTo(CHECK_AFTER) < 0 || alive.test(next.session()))) {
                return next.session();
            }
            closer.accept(next.session());
        }
    }

    /** @param broken true when the session failed in a way that may have left it unusable */
    public void release(S session, boolean broken) {
        var keep = !broken;
        synchronized (this) {
            if (keep && !closed) {
                idle.addFirst(new Idle<>(session, clock.instant()));
            } else {
                keep = false;
            }
        }
        if (!keep) {
            closer.accept(session);
        }
        permits.release();
    }

    void closeExpired() {
        final var cutoff = clock.instant().minus(IDLE_LIMIT);
        final var expired = new ArrayDeque<S>();
        synchronized (this) {
            idle.removeIf(entry -> {
                if (entry.since().isAfter(cutoff)) {
                    return false;
                }
                expired.add(entry.session());
                return true;
            });
        }
        expired.forEach(closer);
    }

    @Override
    public void close() {
        sweep.cancel(false);
        final var open = new ArrayDeque<S>();
        synchronized (this) {
            closed = true;
            idle.forEach(entry -> open.add(entry.session()));
            idle.clear();
        }
        open.forEach(closer);
    }
}
