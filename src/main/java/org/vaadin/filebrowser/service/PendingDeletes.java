package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;

/**
 * Deletes that wait {@code FILEBROWSER_DELETE_UNDO_SECONDS} so they can be undone. Application-wide, since a delete
 * runs whether or not the session that asked for it is still open; held in memory only, so a server stop during the
 * wait deletes nothing.
 */
@Service
public class PendingDeletes implements DisposableBean {

    private static final System.Logger LOG = System.getLogger(PendingDeletes.class.getName());

    private final StorageService storage;
    private final FileSystemRegistry registry;
    private final UserData userData;
    private final Duration undoDelay;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().daemon().name("pending-deletes").unstarted(r));
    /** Waiting and running deletes by id; a running one stays until it finishes, so its items stay hidden. */
    private final Map<String, Waiting> deletes = new ConcurrentHashMap<>();

    /** One confirmed delete; {@code entries} as they were listed when it was confirmed. */
    public record Pending(String id, FsRoot root, String email, List<StorageEntry> entries) implements Serializable {

        public String label() {
            return entries.size() > 1 ? entries.size() + " items" : "“" + entries.getFirst().name() + "”";
        }

        /** The entry that is {@code path} or holds it, if any. */
        Optional<StorageEntry> covering(String rootId, String path) {
            if (!root.id().equals(rootId)) {
                return Optional.empty();
            }
            return entries.stream()
                    .filter(e -> path.equals(e.path()) || path.startsWith(e.path() + StoragePaths.SEPARATOR))
                    .findFirst();
        }
    }

    /**
     * @param kept    names of items left alone because they changed during the wait
     * @param problem why the delete stopped early; null when every item was handled
     */
    public record Outcome(Pending pending, int deleted, List<String> kept, String problem) implements Serializable {
    }

    /** A name a pending delete still holds on the storage, and whose delete it is. */
    public record Hold(StorageEntry entry, String email) {
    }

    private static final class Waiting {
        private final Pending pending;
        private final Consumer<Outcome> onDone;
        private ScheduledFuture<?> timer;
        private boolean started;

        private Waiting(Pending pending, Consumer<Outcome> onDone) {
            this.pending = pending;
            this.onDone = onDone;
        }
    }

    @Autowired
    public PendingDeletes(StorageService storage, FileSystemRegistry registry, UserData userData,
                          FileBrowserProperties properties) {
        this(storage, registry, userData, Duration.ofSeconds(properties.deleteUndoSeconds()));
    }

    PendingDeletes(StorageService storage, FileSystemRegistry registry, UserData userData, Duration delay) {
        this.storage = storage;
        this.registry = registry;
        this.userData = userData;
        this.undoDelay = delay;
    }

    /** False when deletes run at once, with nothing to undo. */
    public boolean undoable() {
        return !undoDelay.isZero();
    }

    public Duration delay() {
        return undoDelay;
    }

    /** Deletes {@code entries} after the delay unless cancelled; {@code onDone} gets the outcome on another thread. */
    public Pending schedule(FsRoot root, String email, List<StorageEntry> entries, Consumer<Outcome> onDone) {
        final var pending = new Pending(UUID.randomUUID().toString(), root, email, List.copyOf(entries));
        final var waiting = new Waiting(pending, onDone);
        synchronized (this) {
            deletes.put(pending.id(), waiting);
            waiting.timer = timers.schedule(() -> start(pending.id()), undoDelay.toMillis(), TimeUnit.MILLISECONDS);
        }
        return pending;
    }

    /** Drops a delete that has not started; false once it has. */
    public boolean cancel(String id) {
        synchronized (this) {
            final var waiting = deletes.get(id);
            if (waiting == null || waiting.started) {
                return false;
            }
            deletes.remove(id);
            waiting.timer.cancel(false);
            return true;
        }
    }

    /** Whether {@code path} is, or is inside, an item {@code email} is deleting. */
    public boolean hides(String email, String rootId, String path) {
        return deletes.values().stream()
                .filter(w -> w.pending.email().equals(email))
                .anyMatch(w -> w.pending.covering(rootId, path).isPresent());
    }

    /** The pending delete, by anyone, of {@code path} or a folder it is in. */
    public Optional<Hold> holding(String rootId, String path) {
        for (final var waiting : deletes.values()) {
            final var entry = waiting.pending.covering(rootId, path);
            if (entry.isPresent()) {
                return Optional.of(new Hold(entry.orElseThrow(), waiting.pending.email()));
            }
        }
        return Optional.empty();
    }

    @Override
    public void destroy() {
        timers.shutdownNow();
    }

    private void start(String id) {
        final Waiting waiting;
        synchronized (this) {
            waiting = deletes.get(id);
            if (waiting == null) {
                return;
            }
            waiting.started = true;
        }
        // off the single timer thread, so a large folder does not hold up other deletes
        Thread.ofVirtual().name("delete-" + id).start(() -> run(waiting));
    }

    private void run(Waiting waiting) {
        Outcome outcome;
        try {
            outcome = delete(waiting.pending);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Delete " + waiting.pending.id() + " failed", e);
            outcome = new Outcome(waiting.pending, 0, List.of(), "Deleting " + waiting.pending.label() + " failed.");
        }
        deletes.remove(waiting.pending.id());
        waiting.onDone.accept(outcome);
    }

    private Outcome delete(Pending pending) {
        final var root = registry.find(pending.root().id()).orElse(null);
        if (root == null) {
            return new Outcome(pending, 0, List.of(), "The file system was removed, so " + pending.label() + " stayed.");
        }
        if (root.readOnly()) {
            return new Outcome(pending, 0, List.of(), "“" + root.name() + "” is now read only, so " + pending.label() + " stayed.");
        }
        final var provider = storage.provider(root);
        final var kept = new ArrayList<String>();
        int deleted = 0;
        for (final var entry : pending.entries()) {
            try {
                final var now = provider.stat(entry.path());
                if (now.isEmpty()) {
                    // already gone, e.g. someone else deleted or moved it during the wait
                    continue;
                }
                if (changed(entry, now.orElseThrow())) {
                    kept.add(entry.name());
                    continue;
                }
                provider.delete(entry.path());
                userData.dropStars(root.id(), entry.path());
                deleted++;
            } catch (StorageException e) {
                return new Outcome(pending, deleted, List.copyOf(kept), e.getMessage());
            }
        }
        return new Outcome(pending, deleted, List.copyOf(kept), null);
    }

    /** A file someone replaced during the wait is not the one that was confirmed; a folder's contents aren't compared. */
    private static boolean changed(StorageEntry confirmed, StorageEntry now) {
        if (confirmed.folder() != now.folder()) {
            return true;
        }
        return !confirmed.folder()
                && (!Objects.equals(confirmed.size(), now.size()) || !Objects.equals(confirmed.modified(), now.modified()));
    }
}
