package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.storage.CopyCancelledException;
import org.vaadin.filebrowser.storage.CopyProgress;
import org.vaadin.filebrowser.storage.StorageException;

/**
 * The session's moves and copies. Tasks run one at a time, in the order they were queued, on a
 * virtual thread outside the Vaadin session lock; everything here is safe to call from any thread.
 */
public class TransferQueue implements Serializable {

    private static final System.Logger LOG = System.getLogger(TransferQueue.class.getName());

    /** What running a task means; the queue only orders, counts, stops and reports. */
    public interface Work extends Serializable {

        /** Before a task starts: throws when its file systems can't take it now. */
        void check(TransferTask task);

        void run(TransferTask task, TransferTask.Item item, CopyProgress progress);
    }

    private final Work work;
    private final List<TransferTask> tasks = new CopyOnWriteArrayList<>();
    /** Whether a worker thread is draining the queue; guarded by {@code this}. */
    private boolean draining;

    public TransferQueue(Work work) {
        this.work = work;
    }

    public TransferTask enqueue(TransferTask.Kind kind, FsRoot sourceRoot, FsRoot destinationRoot,
                                List<String> destination, List<TransferTask.Item> items) {
        final var task = new TransferTask(UUID.randomUUID().toString(), kind, sourceRoot, destinationRoot,
                destination, items);
        tasks.add(task);
        synchronized (this) {
            if (!draining) {
                draining = true;
                Thread.ofVirtual().name("transfers").start(this::drain);
            }
        }
        return task;
    }

    public List<TransferTask> all() {
        return List.copyOf(tasks);
    }

    /**
     * A queued task is dropped at once. A running one stops before its next item, or at once while this app
     * streams a copy; work the storage server is doing finishes first.
     */
    public void cancel(TransferTask task) {
        synchronized (this) {
            if (task.state() == TransferTask.State.QUEUED) {
                task.state(TransferTask.State.CANCELLED, "Cancelled; nothing was " + task.kind().participle() + ".");
                return;
            }
        }
        task.requestCancel();
    }

    /** Cancels unfinished transfers and forgets all of them. */
    public void clear() {
        tasks.forEach(this::cancel);
        tasks.clear();
    }

    /**
     * Whether the UI still has something to show: a transfer that runs, or one that finished, possibly within
     * milliseconds of being queued, and wasn't reported yet. UI thread only.
     */
    public boolean pending() {
        return tasks.stream().anyMatch(TransferTask::unannounced);
    }

    /** Finished transfers not yet reported to the user; each is returned once. UI thread only. */
    public List<TransferTask> takeFinished() {
        return tasks.stream().filter(TransferTask::announce).toList();
    }

    private void drain() {
        while (true) {
            final TransferTask next;
            synchronized (this) {
                next = tasks.stream().filter(t -> t.state() == TransferTask.State.QUEUED).findFirst().orElse(null);
                if (next == null) {
                    draining = false;
                    return;
                }
                next.state(TransferTask.State.RUNNING, null);
            }
            try {
                run(next);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Transfer failed unexpectedly", e);
                next.state(TransferTask.State.FAILED, "Something went wrong. " + notDone(next));
            }
        }
    }

    private void run(TransferTask task) {
        try {
            work.check(task);
        } catch (StorageException e) {
            task.state(TransferTask.State.FAILED, e.getMessage() + " Nothing was " + task.kind().participle() + ".");
            return;
        }
        final var progress = new CopyProgress() {
            @Override
            public void copied(long bytes) {
                task.copied(bytes);
            }

            @Override
            public boolean cancelled() {
                return task.cancelRequested();
            }
        };
        for (final var item : task.items()) {
            if (task.cancelRequested()) {
                task.state(TransferTask.State.CANCELLED, cancelled(task));
                return;
            }
            try {
                work.run(task, item, progress);
            } catch (CopyCancelledException e) {
                task.state(TransferTask.State.CANCELLED, cancelled(task));
                return;
            } catch (StorageException e) {
                task.state(TransferTask.State.FAILED, failed(task, e.getMessage()));
                return;
            }
            task.itemDone();
        }
        task.state(TransferTask.State.DONE, task.kind().done() + " " + task.label() + " to “" + task.destinationName() + "”");
    }

    private static String cancelled(TransferTask task) {
        final var done = task.itemsDone();
        if (done == 0) {
            return "Cancelled; nothing was " + task.kind().participle() + ".";
        }
        final var left = task.items().size() - done;
        return partial(task) + "; " + left + (left == 1 ? " was" : " were") + " cancelled.";
    }

    /** The reason, with what was done before it and what was left undone. */
    private static String failed(TransferTask task, String reason) {
        final var total = task.items().size();
        final var done = task.itemsDone();
        if (total == 1) {
            return reason;
        }
        if (done == 0) {
            return reason + " Nothing was " + task.kind().participle() + ".";
        }
        final var left = total - done - 1;
        final var rest = left == 0 ? "" : " " + Formats.plural(left, "other item") + (left == 1 ? " was" : " were")
                + " not " + task.kind().participle() + ".";
        return partial(task) + ". " + reason + rest;
    }

    private static String notDone(TransferTask task) {
        return task.itemsDone() == 0 ? "Nothing was " + task.kind().participle() + "." : partial(task) + ".";
    }

    /** "Moved 2 of 5 items to “Inbox”". */
    private static String partial(TransferTask task) {
        return task.kind().done() + " " + task.itemsDone() + " of " + task.items().size() + " items to “"
                + task.destinationName() + "”";
    }
}
