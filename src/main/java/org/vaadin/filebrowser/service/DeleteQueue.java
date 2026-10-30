package org.vaadin.filebrowser.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.storage.StorageEntry;

/**
 * The session's deletes that wait so they can be undone; {@link PendingDeletes} does the waiting and the deleting.
 * Safe to call from any thread.
 */
public class DeleteQueue {

    private final PendingDeletes pendingDeletes;
    private final String owner;
    /** Ids of this session's deletes that have not finished. */
    private final Set<String> waiting = ConcurrentHashMap.newKeySet();
    private final Queue<PendingDeletes.Outcome> finished = new ConcurrentLinkedQueue<>();

    DeleteQueue(PendingDeletes pendingDeletes, String owner) {
        this.pendingDeletes = pendingDeletes;
        this.owner = owner;
    }

    /** Whether deletes wait so they can be undone, rather than running at once through {@link Workspace#delete}. */
    public boolean undoable() {
        return pendingDeletes.undoable();
    }

    public Duration delay() {
        return pendingDeletes.delay();
    }

    /** Deletes {@code entries} once the delay is over; until then they are hidden from this user and can be undone. */
    public PendingDeletes.Pending schedule(FsRoot root, List<StorageEntry> entries) {
        final var pending = pendingDeletes.schedule(root, owner, entries, outcome -> {
            finished.add(outcome);
            waiting.remove(outcome.pending().id());
        });
        waiting.add(pending.id());
        return pending;
    }

    /** False when the delete has already started. */
    public boolean undo(PendingDeletes.Pending pending) {
        final var cancelled = pendingDeletes.cancel(pending.id());
        if (cancelled) {
            waiting.remove(pending.id());
        }
        return cancelled;
    }

    public boolean anyWaiting() {
        return !waiting.isEmpty();
    }

    public List<PendingDeletes.Outcome> takeFinished() {
        final var out = new ArrayList<PendingDeletes.Outcome>();
        for (var outcome = finished.poll(); outcome != null; outcome = finished.poll()) {
            out.add(outcome);
        }
        return out;
    }
}
