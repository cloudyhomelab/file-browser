package org.vaadin.filebrowser.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class NoticeLogTest {

    @Test
    void newestComeFirstAndErrorsArriveRead() {
        final var log = new NoticeLog();
        log.add("Renamed", "edit", false);
        log.add("Failed", "warn", true);

        assertThat(log.notices()).extracting(NoticeLog.Notice::message).containsExactly("Failed", "Renamed");
        assertThat(log.unreadCount()).isEqualTo(1);
    }

    @Test
    void markAllReadClearsTheBadge() {
        final var log = new NoticeLog();
        log.add("One", "check", false);
        log.add("Two", "check", false);

        log.markAllRead();

        assertThat(log.unreadCount()).isZero();
        assertThat(log.notices()).hasSize(2).allMatch(NoticeLog.Notice::read);
    }

    @Test
    void removeDropsOnlyThatNotice() {
        final var log = new NoticeLog();
        log.add("Keep", "check", false);
        log.add("Drop", "check", false);
        final var dropId = log.notices().getFirst().id();

        log.remove(dropId);

        assertThat(log.notices()).extracting(NoticeLog.Notice::message).containsExactly("Keep");
    }

    @Test
    void oldestAreDroppedBeyondTheCap() {
        final var log = new NoticeLog();
        for (int i = 0; i <= NoticeLog.MAX_NOTICES; i++) {
            log.add("n" + i, "check", false);
        }

        assertThat(log.notices()).hasSize(NoticeLog.MAX_NOTICES);
        assertThat(log.notices().getLast().message()).isEqualTo("n1");
    }

    @Test
    void listenersHearChangesUntilRemoved() {
        final var log = new NoticeLog();
        final var calls = new AtomicInteger();
        final var registration = log.addListener(calls::incrementAndGet);

        log.add("One", "check", false);
        log.markAllRead();
        log.markAllRead();
        registration.remove();
        log.add("Two", "check", false);

        assertThat(calls).hasValue(2);
    }
}
