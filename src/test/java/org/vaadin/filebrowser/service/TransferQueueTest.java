package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.storage.CopyCancelledException;
import org.vaadin.filebrowser.storage.CopyProgress;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;

class TransferQueueTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FsRoot ROOT = new FsRoot("r", "Share", "disk", "/srv", Map.of(), Access.READ_WRITE,
            RootStatus.UNKNOWN);

    /** Records what it ran; fails or blocks on the names it is told to. */
    private static final class FakeWork implements TransferQueue.Work {

        final List<String> ran = new CopyOnWriteArrayList<>();
        String checkFailure;
        String failOn;
        String blockOn;
        long streamBytes;
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void check(TransferTask task) {
            if (checkFailure != null) {
                throw new StorageException(checkFailure);
            }
        }

        @Override
        public void run(TransferTask task, TransferTask.Item item, CopyProgress progress) {
            final var name = item.entry().name();
            if (name.equals(blockOn)) {
                blocked.countDown();
                await(release);
                if (progress.cancelled() && streamBytes > 0) {
                    throw new CopyCancelledException(name);
                }
            }
            if (name.equals(failOn)) {
                throw new StorageException("“" + name + "” already exists in the destination.");
            }
            if (streamBytes > 0) {
                progress.copied(streamBytes);
                progress.verified(streamBytes);
            }
            ran.add(name);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<TransferTask.Item> items(String... names) {
        final var out = new ArrayList<TransferTask.Item>();
        for (final var name : names) {
            final var entry = new StorageEntry(name, name, false, "txt", 10L, Instant.EPOCH, null, null);
            out.add(new TransferTask.Item(entry, name, false, false));
        }
        return out;
    }

    private static TransferTask enqueue(TransferQueue queue, TransferTask.Kind kind, String... names) {
        return queue.enqueue(kind, ROOT, ROOT, List.of("Inbox"), items(names));
    }

    private static void finish(TransferTask task) throws InterruptedException {
        final var deadline = Instant.now().plus(WAIT);
        while (!task.finished() && Instant.now().isBefore(deadline)) {
            Thread.sleep(5);
        }
        assertThat(task.finished()).as("finished in time").isTrue();
    }

    @Test
    void tasksRunOneAtATimeInTheOrderTheyWereQueued() throws InterruptedException {
        final var work = new FakeWork();
        work.blockOn = "a";
        final var queue = new TransferQueue(work);

        final var first = enqueue(queue, TransferTask.Kind.MOVE, "a", "b");
        final var second = enqueue(queue, TransferTask.Kind.COPY, "c");
        await(work.blocked);
        assertThat(second.state()).isEqualTo(TransferTask.State.QUEUED);
        work.release.countDown();
        finish(second);

        assertThat(work.ran).containsExactly("a", "b", "c");
        assertThat(first.message()).isEqualTo("Moved 2 items to “Inbox”");
        assertThat(second.message()).isEqualTo("Copied “c” to “Inbox”");
        assertThat(queue.takeFinished()).containsExactly(first, second);
        assertThat(queue.takeFinished()).isEmpty();
    }

    @Test
    void notesFromTheWorkFollowTheOutcome() throws InterruptedException {
        final var queue = new TransferQueue(new TransferQueue.Work() {
            @Override
            public void check(TransferTask task) {
            }

            @Override
            public void prepare(TransferTask task) {
                task.bytesTotal(20);
            }

            @Override
            public void run(TransferTask task, TransferTask.Item item, CopyProgress progress) {
                progress.phase(item.entry().name(), CopyProgress.Phase.VERIFYING);
                progress.copied(10);
                progress.verified(10);
                task.note("“" + item.entry().name() + "” changed during the move; the original was kept.");
            }
        });

        final var task = enqueue(queue, TransferTask.Kind.MOVE, "a");
        finish(task);

        assertThat(task.status()).isEqualTo("Verifying “a”…");
        assertThat(task.progress()).isEqualTo(50);
        assertThat(task.message()).isEqualTo("Moved “a” to “Inbox”. “a” changed during the move; the original was kept.");
    }

    @Test
    void aFailureStopsTheBatchAndSaysWhatWasDone() throws InterruptedException {
        final var work = new FakeWork();
        work.failOn = "c";
        final var queue = new TransferQueue(work);

        final var task = enqueue(queue, TransferTask.Kind.MOVE, "a", "b", "c", "d", "e");
        finish(task);

        assertThat(task.state()).isEqualTo(TransferTask.State.FAILED);
        assertThat(work.ran).containsExactly("a", "b");
        assertThat(task.message()).isEqualTo("Moved 2 of 5 items to “Inbox”. “c” already exists in the destination."
                + " 2 other items were not moved.");
    }

    @Test
    void aFileSystemThatCantTakeItFailsTheWholeTask() throws InterruptedException {
        final var work = new FakeWork();
        work.checkFailure = "“Share” is read only.";
        final var queue = new TransferQueue(work);

        final var task = enqueue(queue, TransferTask.Kind.COPY, "a", "b");
        finish(task);

        assertThat(work.ran).isEmpty();
        assertThat(task.message()).isEqualTo("“Share” is read only. Nothing was copied.");
    }

    @Test
    void aQueuedTaskIsCancelledAtOnce() throws InterruptedException {
        final var work = new FakeWork();
        work.blockOn = "a";
        final var queue = new TransferQueue(work);

        final var running = enqueue(queue, TransferTask.Kind.MOVE, "a");
        final var waiting = enqueue(queue, TransferTask.Kind.COPY, "b");
        await(work.blocked);
        queue.cancel(waiting);
        assertThat(waiting.state()).isEqualTo(TransferTask.State.CANCELLED);
        work.release.countDown();
        finish(running);

        assertThat(work.ran).containsExactly("a");
        assertThat(waiting.message()).isEqualTo("Cancelled; nothing was copied.");
    }

    @Test
    void cancellingARunningBatchFinishesTheItemTheServerIsOnAndStopsBeforeTheNext() throws InterruptedException {
        final var work = new FakeWork();
        work.blockOn = "b";
        final var queue = new TransferQueue(work);

        final var task = enqueue(queue, TransferTask.Kind.COPY, "a", "b", "c", "d");
        await(work.blocked);
        queue.cancel(task);
        work.release.countDown();
        finish(task);

        assertThat(work.ran).containsExactly("a", "b");
        assertThat(task.state()).isEqualTo(TransferTask.State.CANCELLED);
        assertThat(task.message()).isEqualTo("Copied 2 of 4 items to “Inbox”; 2 were cancelled.");
    }

    @Test
    void aStreamedCopyStopsAtOnceAndReportsItsBytes() throws InterruptedException {
        final var work = new FakeWork();
        work.blockOn = "b";
        work.streamBytes = 10;
        final var queue = new TransferQueue(work);

        final var task = enqueue(queue, TransferTask.Kind.COPY, "a", "b", "c");
        await(work.blocked);
        assertThat(task.progress()).isEqualTo(10 * 100 / 30);
        queue.cancel(task);
        work.release.countDown();
        finish(task);

        assertThat(work.ran).containsExactly("a");
        assertThat(task.message()).isEqualTo("Copied 1 of 3 items to “Inbox”; 2 were cancelled.");
    }

    @Test
    void progressIsUnknownWhenNoBytesAreReported() throws InterruptedException {
        final var work = new FakeWork();
        work.blockOn = "b";
        final var queue = new TransferQueue(work);

        final var task = enqueue(queue, TransferTask.Kind.MOVE, "a", "b");
        await(work.blocked);
        assertThat(task.progress()).isNull();
        assertThat(task.itemsDone()).isEqualTo(1);
        work.release.countDown();
        finish(task);
    }
}
