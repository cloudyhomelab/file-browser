package org.vaadin.filebrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IdleTransferTest {

    private static final Duration IDLE_LIMIT = Duration.ofMillis(100);
    private static final Duration LONG_WAIT = Duration.ofSeconds(5);
    private static final byte[] CONTENT = "some bytes".getBytes();

    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();

    /** A file whose stream fails once closed, as a storage stream does. */
    private static final class Source extends FilterInputStream {

        final AtomicBoolean closed = new AtomicBoolean();
        final CountDownLatch closing = new CountDownLatch(1);

        Source() {
            super(new ByteArrayInputStream(CONTENT));
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed.get()) {
                throw new IOException("closed");
            }
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() {
            closed.set(true);
            closing.countDown();
        }
    }

    /** A client that stops reading: a write blocks until the file was closed, or for long. */
    private static final class PausedClient extends OutputStream {

        private final Source source;

        PausedClient(Source source) {
            this.source = source;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                source.closing.await(LONG_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            write(0);
        }
    }

    @AfterEach
    void stopWatcher() {
        watcher.shutdownNow();
    }

    @Test
    void copiesWhatTheClientReads() throws IOException {
        final var source = new Source();
        final var out = new ByteArrayOutputStream();
        IdleTransfer.transfer(source, out, IDLE_LIMIT, watcher);
        assertThat(out.toByteArray()).isEqualTo(CONTENT);
        assertThat(source.closed).isFalse();
    }

    @Test
    void closesTheFileWhenTheClientStopsReading() throws IOException {
        final var source = new Source();
        final var started = System.nanoTime();
        final var client = new PausedClient(source);
        IdleTransfer.transfer(source, client, IDLE_LIMIT, watcher);
        assertThat(source.closed).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LONG_WAIT);
    }

    @Test
    void aClientThatLeavesIsStillAnError() {
        final InputStream source = new Source();
        final var gone = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client left");
            }
        };
        assertThatThrownBy(() -> IdleTransfer.transfer(source, gone, IDLE_LIMIT, watcher)).hasMessage("client left");
    }
}
