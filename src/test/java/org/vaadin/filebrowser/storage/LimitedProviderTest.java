package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LimitedProviderTest {

    private static final Duration SHORT_WAIT = Duration.ofMillis(50);
    private static final Duration LONG_WAIT = Duration.ofSeconds(5);

    @TempDir
    Path root;

    /** Tells whether it was closed, as a provider holding sessions would be. */
    private static class ClosingDisk extends LocalDiskProvider implements AutoCloseable {

        final AtomicBoolean closed = new AtomicBoolean();

        ClosingDisk(Path root) {
            super(root, name -> "generic", type -> "disk");
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
    }

    private LimitedProvider limited(StorageProvider disk, int max, int streams, Duration wait) {
        return new LimitedProvider(disk, max, streams, wait);
    }

    private LimitedProvider limited(int max, int streams) {
        return limited(new ClosingDisk(root), max, streams, SHORT_WAIT);
    }

    @Test
    void streamsNeverTakeTheConnectionsKeptForBrowsing() throws IOException {
        final var provider = limited(3, 2);
        final var first = provider.openRead("a.txt");
        try (var write = provider.openWrite("", "c.txt", false)) {
            assertThatThrownBy(() -> provider.openRead("b.txt")).hasMessageContaining("busy");
            assertThat(provider.list("")).hasSize(2);
            write.abort();
        }
        first.close();
        try (var again = provider.openRead("b.txt")) {
            assertThat(again.readAllBytes()).isEqualTo("b".getBytes());
        }
    }

    @Test
    void aCopyTakesTwoStreamConnectionsAtOnce() throws IOException {
        final var provider = limited(3, 2);
        try (var in = provider.openRead("a.txt")) {
            assertThatThrownBy(() -> provider.copy("b.txt", "", "b copy.txt")).hasMessageContaining("busy");
            assertThat(in.readAllBytes()).isEqualTo("a".getBytes());
        }
        provider.copy("b.txt", "", "b copy.txt");
        assertThat(root.resolve("b copy.txt")).hasContent("b");
    }

    @Test
    void copiesRunningTogetherDontBlockEachOther() throws Exception {
        final var entered = new CountDownLatch(1);
        final var gate = new CountDownLatch(1);
        final var disk = new ClosingDisk(root) {
            @Override
            public void copy(String path, String destinationFolder, String targetName, CopyProgress progress) {
                entered.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.copy(path, destinationFolder, targetName, progress);
            }
        };
        final var provider = limited(disk, 4, 3, LONG_WAIT);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = pool.submit(() -> provider.copy("a.txt", "", "a copy.txt", CopyProgress.NONE));
            assertThat(entered.await(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            final var second = pool.submit(() -> provider.copy("b.txt", "", "b copy.txt", CopyProgress.NONE));
            assertThat(provider.list("")).hasSize(2);
            gate.countDown();
            first.get(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            second.get(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
        assertThat(root.resolve("a copy.txt")).hasContent("a");
        assertThat(root.resolve("b copy.txt")).hasContent("b");
    }

    @Test
    void aFailedOpenGivesItsConnectionBack() throws IOException {
        final var provider = limited(3, 2);
        assertThatThrownBy(() -> provider.openRead("missing.txt")).isInstanceOf(StorageException.class);
        try (var a = provider.openRead("a.txt"); var b = provider.openRead("b.txt")) {
            assertThat(a.readAllBytes()).isEqualTo("a".getBytes());
            assertThat(b.readAllBytes()).isEqualTo("b".getBytes());
        }
    }

    @Test
    void aRetiredProviderClosesWhenItsLastStreamEnds() throws IOException {
        final var disk = new ClosingDisk(root);
        final var provider = limited(disk, 3, 2, SHORT_WAIT);
        final var in = provider.openRead("a.txt");
        provider.retire();
        assertThat(disk.closed).isFalse();
        assertThat(in.readAllBytes()).isEqualTo("a".getBytes());
        in.close();
        assertThat(disk.closed).isTrue();
    }

    @Test
    void anIdleRetiredProviderClosesAtOnce() {
        final var disk = new ClosingDisk(root);
        limited(disk, 3, 2, SHORT_WAIT).retire();
        assertThat(disk.closed).isTrue();
    }

    @Test
    void streamsNeedRoomForACopy() {
        final var disk = new ClosingDisk(root);
        assertThatThrownBy(() -> new LimitedProvider(disk, 3, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
