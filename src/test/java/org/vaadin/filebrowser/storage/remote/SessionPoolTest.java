package org.vaadin.filebrowser.storage.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.storage.StorageException;

class SessionPoolTest {

    private final AtomicInteger opened = new AtomicInteger();
    private final List<Integer> closed = new ArrayList<>();
    private Instant now = Instant.parse("2026-10-27T10:00:00Z");
    private boolean alive = true;

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private SessionPool<Integer> pool() {
        return new SessionPool<>(opened::incrementAndGet, session -> alive, closed::add, clock);
    }

    @Test
    void reusesAGivenBackSessionAndDropsABrokenOne() throws IOException {
        try (final var pool = pool()) {
            final var first = pool.borrow();
            pool.release(first, false);
            assertThat(pool.borrow()).isEqualTo(first);
            pool.release(first, true);
            assertThat(closed).containsExactly(first);
            assertThat(pool.borrow()).isEqualTo(2);
        }
    }

    @Test
    void checksQuietSessionsAndClosesExpiredOnes() throws IOException {
        try (final var pool = pool()) {
            pool.release(pool.borrow(), false);
            now = now.plus(Duration.ofSeconds(20));
            alive = false;
            assertThat(pool.borrow()).isEqualTo(2);
            assertThat(closed).containsExactly(1);
            alive = true;
            pool.release(2, false);
            now = now.plus(Duration.ofMinutes(2));
            pool.closeExpired();
            assertThat(closed).containsExactly(1, 2);
        }
    }

    @Test
    void aClosedPoolClosesItsSessionsAndHandsOutNoMore() throws IOException {
        final var pool = pool();
        pool.release(pool.borrow(), false);
        pool.close();
        assertThat(closed).containsExactly(1);
        assertThatThrownBy(pool::borrow).isInstanceOf(StorageException.class);
    }
}
