package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.List;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.storage.StorageEntry;

/**
 * One move or copy the user started, of one or more items. Progress is written by the queue's worker
 * thread and read by the UI, so the changing fields are volatile.
 */
public final class TransferTask implements Serializable {

    public enum Kind {
        MOVE("Moving", "Moved", "moved"), COPY("Copying", "Copied", "copied");

        private final String running;
        private final String done;
        private final String participle;

        Kind(String running, String done, String participle) {
            this.running = running;
            this.done = done;
            this.participle = participle;
        }

        public String running() {
            return running;
        }

        public String done() {
            return done;
        }

        /** As in "2 items were not moved". */
        public String participle() {
            return participle;
        }
    }

    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    private static final int PERCENT = 100;

    /**
     * @param name      the name it gets in the destination, fixed when queued
     * @param replace   an existing file of that name is replaced
     * @param duplicate copied into its own folder as "name copy"; {@code name} is ignored
     */
    public record Item(StorageEntry entry, String name, boolean replace, boolean duplicate) implements Serializable {
    }

    private final String id;
    private final Kind kind;
    private final FsRoot sourceRoot;
    private final FsRoot destinationRoot;
    private final List<String> destination;
    private final List<Item> items;
    /** Bytes of the items when they are all files; null when a folder makes the total unknown. */
    private final Long bytesTotal;
    private volatile State state = State.QUEUED;
    private volatile int itemsDone;
    private volatile long bytesDone;
    private volatile boolean cancelRequested;
    private volatile String message;
    private boolean announced;

    TransferTask(String id, Kind kind, FsRoot sourceRoot, FsRoot destinationRoot, List<String> destination,
                 List<Item> items) {
        this.id = id;
        this.kind = kind;
        this.sourceRoot = sourceRoot;
        this.destinationRoot = destinationRoot;
        this.destination = List.copyOf(destination);
        this.items = List.copyOf(items);
        this.bytesTotal = this.items.stream().anyMatch(i -> i.entry().folder()) ? null
                : this.items.stream().mapToLong(i -> i.entry().size() == null ? 0 : i.entry().size()).sum();
    }

    void state(State newState, String text) {
        this.state = newState;
        this.message = text;
    }

    void itemDone() {
        itemsDone++;
    }

    void copied(long bytes) {
        bytesDone += bytes;
    }

    void requestCancel() {
        cancelRequested = true;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    /** True once, for the UI to report a finished transfer exactly one time. Called on the UI thread only. */
    boolean announce() {
        if (announced || !finished()) {
            return false;
        }
        announced = true;
        return true;
    }

    /** Not yet reported to the user, finished or not. Called on the UI thread only. */
    boolean unannounced() {
        return !announced;
    }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    /** Percent of the bytes this app streamed; null when nothing reports bytes, so progress is unknown. */
    public Integer progress() {
        if (bytesTotal == null || bytesTotal <= 0 || bytesDone == 0) {
            return null;
        }
        return (int) Math.min(PERCENT, bytesDone * PERCENT / bytesTotal);
    }

    /** The destination folder's name, or the file system's for its top. */
    public String destinationName() {
        return destination.isEmpty() ? destinationRoot.name() : destination.getLast();
    }

    /** "“a”" for one item, "3 items" for more. */
    public String label() {
        return items.size() == 1 ? "“" + items.getFirst().entry().name() + "”" : items.size() + " items";
    }

    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public FsRoot sourceRoot() {
        return sourceRoot;
    }

    public FsRoot destinationRoot() {
        return destinationRoot;
    }

    public List<String> destination() {
        return destination;
    }

    public List<Item> items() {
        return items;
    }

    public int itemsDone() {
        return itemsDone;
    }

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }
}
