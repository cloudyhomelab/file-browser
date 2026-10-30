package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import org.springframework.security.access.AccessDeniedException;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.storage.CopyProgress;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;
import org.vaadin.filebrowser.storage.VerifiedCopy;

import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.VaadinSessionScope;

import lombok.Getter;

/**
 * The signed-in user's view of the shared file systems (from {@link FileSystemRegistry}): file
 * operations through {@link StorageService}, the user's starred items and view preferences (saved
 * through {@link UserData}), uploads, transfers and deletes waiting to be undone. Lives for the Vaadin session.
 */
// holds app-wide services; sessions are never serialized. Both go with #76. Views reach every file operation
// through this one class, so its public surface is wide on purpose
@SuppressWarnings({"PMD.NonSerializableClass", "PMD.TooManyMethods", "PMD.ExcessivePublicCount"})
@SpringComponent
@VaadinSessionScope
public class Workspace implements Serializable {

    public enum Layout { LIST, GRID }

    public enum Density { DENSE, COMFORTABLE }

    public enum ConflictChoice { REPLACE, KEEP_BOTH, SKIP }

    @Getter
    private final Catalog catalog;
    private final FileSystemRegistry registry;
    private final StorageService storage;
    private final RootSecrets secrets;
    private final LinkFetcher links;
    // transfers move stars from their worker thread
    private final Set<String> starred = ConcurrentHashMap.newKeySet();
    @Getter
    private final UploadQueue uploads;
    @Getter
    private final TransferQueue transfers = new TransferQueue(new TransferWork());
    private final UserData userData;
    private final CurrentUser currentUser;
    private final String email;
    private final PendingDeletes pendingDeletes;
    /** Ids of this session's deletes that have not finished. */
    private final Set<String> waitingDeletes = ConcurrentHashMap.newKeySet();
    private final Queue<PendingDeletes.Outcome> finishedDeletes = new ConcurrentLinkedQueue<>();

    @Getter
    private Layout layout = UserData.Preferences.DEFAULT.layout();
    @Getter
    private Density density = UserData.Preferences.DEFAULT.density();
    @Getter
    private Sort sort = UserData.Preferences.DEFAULT.sort();
    @Getter
    private boolean groupByType = UserData.Preferences.DEFAULT.groupByType();

    public Workspace(Catalog catalog, FileSystemRegistry registry, StorageService storage, RootSecrets secrets,
                     LinkFetcher links, UserData userData, CurrentUser currentUser, PendingDeletes pendingDeletes) {
        this.catalog = catalog;
        this.registry = registry;
        this.storage = storage;
        this.secrets = secrets;
        this.links = links;
        this.uploads = new UploadQueue(storage);
        this.userData = userData;
        this.currentUser = currentUser;
        this.pendingDeletes = pendingDeletes;
        // the session is created after sign-in, so the user is known here
        this.email = currentUser.get().map(CurrentUser.User::email).orElse(null);
        if (email != null) {
            starred.addAll(userData.starred(email));
            final var preferences = userData.preferences(email).orElse(UserData.Preferences.DEFAULT);
            layout = preferences.layout();
            density = preferences.density();
            sort = preferences.sort();
            groupByType = preferences.groupByType();
        }
    }

    // ---- roots ----------------------------------------------------------------------------

    public List<FsRoot> roots() {
        return registry.all();
    }

    public Optional<FsRoot> root(String id) {
        return registry.find(id);
    }

    /**
     * The file system as it is registered now. Views hold the copy they opened with, which an admin
     * may since have renamed, made read only or removed.
     */
    public FsRoot current(FsRoot root) {
        return registry.find(root.id()).orElseThrow(() -> new StorageException("This file system was removed."));
    }

    public List<FsRoot> browsableRoots() {
        return registry.all().stream().filter(FsRoot::browsable).toList();
    }

    public boolean endpointTaken(String endpoint) {
        return registry.endpointTaken(endpoint);
    }

    /** @param rootSecrets what its type stores encrypted, e.g. access keys; empty for none */
    public void addRoot(FsRoot root, Map<String, String> rootSecrets) {
        // sealing first: without a secret key nothing is saved; then one insert, so the file system is
        // never seen without its secrets
        final var sealed = secrets.seal(root.id(), rootSecrets);
        registry.add(root, sealed);
    }

    /** Whether a file system can be added with these settings, or which server key to confirm first; admins only. */
    public StorageService.Check checkRoot(String type, StorageConfig config) {
        if (!currentUser.isAdmin()) {
            throw new AccessDeniedException("Only admins can add file systems");
        }
        if (!config.secrets().isEmpty() && !secrets.canStore()) {
            return StorageService.Check.failed("Set FILEBROWSER_SECRET_KEY_FILE on the server to store credentials.");
        }
        return storage.check(type, config);
    }

    /** The names of the secrets stored for {@code root}, for showing what is set; admins only. */
    public Set<String> secretNames(FsRoot root) {
        if (!currentUser.isAdmin()) {
            throw new AccessDeniedException("Only admins can edit file systems");
        }
        return secrets.names(root.id());
    }

    public void updateRoot(FsRoot root, String name, Access access) {
        registry.update(root.id(), name, access);
    }

    public void updateOptions(FsRoot root, RootOptions options) {
        final var problem = catalog.getRootOptionLimits().problem(options);
        if (problem != null) {
            throw new StorageException(problem);
        }
        registry.updateOptions(root.id(), options);
        storage.optionsChanged(root, options);
    }

    public void removeRoot(FsRoot root) {
        registry.remove(root.id());
        storage.forget(root);
        final var prefix = starKey(root.id(), "");
        starred.removeIf(key -> key.startsWith(prefix));
    }

    public String newRootId() {
        return registry.newId();
    }

    /** Folders inside a server path, for choosing one to add; only admins may see the server's layout. */
    public List<String> serverFolders(String path) {
        if (!currentUser.isAdmin()) {
            throw new AccessDeniedException("Only admins can browse the server's folders");
        }
        return storage.serverFolders(path);
    }

    public StorageService.PathProbe probe(String path) {
        return storage.probe(path);
    }

    // ---- reading --------------------------------------------------------------------------

    /** The provider behind a file system, e.g. for streaming downloads outside the UI thread. */
    public StorageProvider provider(FsRoot root) {
        return storage.provider(root);
    }

    /** Without the items this user is deleting, which stay on the storage until the delete runs. */
    public List<StorageEntry> list(FsRoot root, List<String> folder) {
        return provider(current(root)).list(StoragePaths.join(folder)).stream()
                .filter(e -> !beingDeleted(root.id(), e.path()))
                .toList();
    }

    private boolean beingDeleted(String rootId, String path) {
        return pendingDeletes.hides(owner(), rootId, path);
    }

    /** Pending deletes are keyed by email; with no one signed in (tests), by the empty string. */
    private String owner() {
        return Objects.requireNonNullElse(email, "");
    }

    /** Whether {@code folder} exists and is a folder; false when the file system can't be read. */
    public boolean isFolder(FsRoot root, List<String> folder) {
        try {
            if (folder.isEmpty()) {
                return provider(root).healthy();
            }
            return provider(root).stat(StoragePaths.join(folder)).map(StorageEntry::folder).orElse(Boolean.FALSE);
        } catch (StorageException e) {
            return false;
        }
    }

    public List<String> subfolders(FsRoot root, List<String> folder) {
        return list(root, folder).stream()
                .filter(StorageEntry::folder)
                .map(StorageEntry::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // ---- starred --------------------------------------------------------------------------

    private static String starKey(String rootId, String path) {
        return rootId + ":" + path;
    }

    public boolean isStarred(String rootId, String path) {
        return starred.contains(starKey(rootId, path));
    }

    public void setStarred(String rootId, String path, boolean on) {
        final var key = starKey(rootId, path);
        if (on) {
            starred.add(key);
            if (email != null) {
                userData.star(email, rootId, path);
            }
        } else {
            starred.remove(key);
            if (email != null) {
                userData.unstar(email, rootId, path);
            }
        }
    }

    /** Starred items that still exist; stars on items deleted outside the app are dropped. */
    public List<Located> starredIn(FsRoot root) {
        final var prefix = starKey(root.id(), "");
        final var out = new ArrayList<Located>();
        for (final var key : List.copyOf(starred)) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            final var path = key.substring(prefix.length());
            if (beingDeleted(root.id(), path)) {
                continue;
            }
            final Optional<StorageEntry> entry;
            try {
                entry = provider(root).stat(path);
            } catch (StorageException e) {
                continue;
            }
            if (entry.isEmpty()) {
                // deleted outside the app: the star is gone for everyone
                starred.remove(key);
                userData.dropStars(root.id(), path);
                continue;
            }
            final var location = entry.orElseThrow().parent();
            final var label = String.join(" / ", Stream.concat(Stream.of(root.name()), location.stream()).toList());
            final var located = new Located(entry.orElseThrow(), root.id(), location, label);
            out.add(located);
        }
        return sort.apply(out);
    }

    private void moveStars(String rootId, String oldPath, String newPath) {
        userData.moveStars(rootId, oldPath, newPath);
        final var exact = starKey(rootId, oldPath);
        final var nested = exact + StoragePaths.SEPARATOR;
        for (final var key : List.copyOf(starred)) {
            if (key.equals(exact) || key.startsWith(nested)) {
                starred.remove(key);
                starred.add(starKey(rootId, newPath) + key.substring(exact.length()));
            }
        }
    }

    /** Moves the stars on exactly {@code path} to {@code newPath} on another file system. */
    private void moveStarAcross(FsRoot from, String path, FsRoot to, String newPath) {
        userData.moveStarAcross(from.id(), path, to.id(), newPath);
        if (starred.remove(starKey(from.id(), path))) {
            starred.add(starKey(to.id(), newPath));
        }
    }

    private void dropStars(String rootId, String path) {
        userData.dropStars(rootId, path);
        final var exact = starKey(rootId, path);
        starred.removeIf(key -> key.equals(exact) || key.startsWith(exact + StoragePaths.SEPARATOR));
    }

    // ---- changes --------------------------------------------------------------------------

    private FsRoot writableRoot(FsRoot root) {
        final var now = current(root);
        if (now.readOnly()) {
            throw new StorageException("This file system is read only.");
        }
        return now;
    }

    private StorageProvider writable(FsRoot root) {
        return provider(writableRoot(root));
    }

    /** Refuses a name that a pending delete still holds: whatever is put there now would go with it. */
    private void requireFree(FsRoot root, List<String> folder, String name) {
        final var path = StoragePaths.child(StoragePaths.join(folder), name);
        final var hold = pendingDeletes.holding(root.id(), path);
        if (hold.isPresent()) {
            final var held = hold.orElseThrow();
            final var undo = held.email().equals(owner()) ? "Undo it or try" : "Try";
            throw new StorageException("“" + held.entry().name() + "” is being deleted. " + undo + " again in a few seconds.");
        }
    }

    public void createFolder(FsRoot root, List<String> folder, String name) {
        final var provider = writable(root);
        requireFree(root, folder, name);
        provider.createFolder(StoragePaths.join(folder), name);
    }

    public void rename(FsRoot root, StorageEntry entry, String newName) {
        final var provider = writable(root);
        requireFree(root, entry.parent(), newName);
        provider.rename(entry.path(), newName);
        final var renamed = StoragePaths.child(StoragePaths.join(entry.parent()), newName);
        moveStars(root.id(), entry.path(), renamed);
    }

    public void delete(FsRoot root, StorageEntry entry) {
        writable(root).delete(entry.path());
        dropStars(root.id(), entry.path());
    }

    /** Whether deletes wait so they can be undone, rather than running at once through {@link #delete}. */
    public boolean deletesUndoable() {
        return pendingDeletes.undoable();
    }

    public Duration deleteDelay() {
        return pendingDeletes.delay();
    }

    /** Deletes {@code entries} once the delay is over; until then they are hidden from this user and can be undone. */
    public PendingDeletes.Pending deleteLater(FsRoot root, List<StorageEntry> entries) {
        final var now = writableRoot(root);
        final var pending = pendingDeletes.schedule(now, owner(), entries, outcome -> {
            finishedDeletes.add(outcome);
            waitingDeletes.remove(outcome.pending().id());
        });
        waitingDeletes.add(pending.id());
        return pending;
    }

    /** False when the delete has already started. */
    public boolean undoDelete(PendingDeletes.Pending pending) {
        final var cancelled = pendingDeletes.cancel(pending.id());
        if (cancelled) {
            waitingDeletes.remove(pending.id());
        }
        return cancelled;
    }

    public boolean deletesWaiting() {
        return !waitingDeletes.isEmpty();
    }

    public List<PendingDeletes.Outcome> takeFinishedDeletes() {
        final var out = new ArrayList<PendingDeletes.Outcome>();
        for (var outcome = finishedDeletes.poll(); outcome != null; outcome = finishedDeletes.poll()) {
            out.add(outcome);
        }
        return out;
    }

    /**
     * Moves into {@code destination} as {@code targetName}. With {@code replace}, an existing file of that name is
     * deleted first; a folder is never replaced, nor replaced by one.
     */
    public void move(FsRoot root, StorageEntry entry, List<String> destination, String targetName, boolean replace) {
        final var provider = writable(root);
        requireFree(root, destination, targetName);
        final var destinationPath = StoragePaths.join(destination);
        final var moved = StoragePaths.child(destinationPath, targetName);
        if (replace) {
            replaceFile(root, provider, entry, moved, targetName);
        }
        provider.move(entry.path(), destinationPath, targetName);
        moveStars(root.id(), entry.path(), moved);
    }

    /**
     * Copies into {@code destination} as {@code targetName}. With {@code replace}, an existing file of that name is
     * deleted first; a folder is never replaced, nor replaced by one.
     */
    public void copy(FsRoot root, StorageEntry entry, List<String> destination, String targetName, boolean replace) {
        copy(root, entry, destination, targetName, replace, CopyProgress.NONE);
    }

    private void copy(FsRoot root, StorageEntry entry, List<String> destination, String targetName, boolean replace,
                      CopyProgress progress) {
        final var provider = writable(root);
        requireFree(root, destination, targetName);
        final var destinationPath = StoragePaths.join(destination);
        if (replace) {
            replaceFile(root, provider, entry, StoragePaths.child(destinationPath, targetName), targetName);
        }
        provider.copy(entry.path(), destinationPath, targetName, progress);
    }

    /** Copies next to the original as "name copy.ext", "name copy 2.ext", … */
    public String duplicate(FsRoot root, StorageEntry entry) {
        return duplicate(root, entry, CopyProgress.NONE);
    }

    private String duplicate(FsRoot root, StorageEntry entry, CopyProgress progress) {
        final var provider = writable(root);
        final var siblings = new HashSet<String>();
        final var folder = StoragePaths.join(entry.parent());
        provider.list(folder).forEach(e -> siblings.add(e.name()));
        final var name = copyName(entry.name(), siblings);
        provider.copy(entry.path(), folder, name, progress);
        return name;
    }

    // ---- transfers ------------------------------------------------------------------------

    /**
     * Queues a move or copy from {@code root} into {@code destination} on {@code destinationRoot}; names and Replace
     * choices are fixed here. A copy may come from a read-only file system; a move's source must be writable.
     */
    public TransferTask transfer(TransferTask.Kind kind, FsRoot root, FsRoot destinationRoot, List<String> destination,
                                 List<TransferTask.Item> items) {
        final var target = writableRoot(destinationRoot);
        final var source = kind == TransferTask.Kind.MOVE ? writableRoot(root) : current(root);
        items.stream().filter(i -> !i.duplicate()).forEach(i -> requireFree(target, destination, i.name()));
        return transfers.enqueue(kind, source, target, destination, items);
    }

    /** The file systems a transfer can go to: writable and online, {@code from} first, then by name. */
    public List<FsRoot> transferTargets(FsRoot from) {
        return registry.all().stream()
                .filter(r -> r.browsable() && !r.readOnly())
                .sorted(Comparator.comparing((FsRoot r) -> !r.id().equals(from.id()))
                        .thenComparing(FsRoot::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** A file system a transfer starts on must still be registered, unlocked and online, and writable if asked. */
    private void requireReady(FsRoot root, boolean writable) {
        final var now = current(root);
        if (now.locked()) {
            throw new StorageException("“" + now.name() + "” is locked.");
        }
        if (!now.online()) {
            throw new StorageException("“" + now.name() + "” is offline.");
        }
        if (writable && now.readOnly()) {
            throw new StorageException("“" + now.name() + "” is read only.");
        }
    }

    /** Bytes of the files in {@code entry}, walking a folder. */
    private static long size(StorageProvider provider, StorageEntry entry) {
        if (!entry.folder()) {
            return entry.size() == null ? 0 : entry.size();
        }
        return provider.list(entry.path()).stream().mapToLong(child -> size(provider, child)).sum();
    }

    private final class TransferWork implements TransferQueue.Work {

        @Override
        public void check(TransferTask task) {
            final var within = !task.betweenFileSystems();
            requireReady(task.getSourceRoot(), task.getKind() == TransferTask.Kind.MOVE || within);
            if (!within) {
                requireReady(task.getDestinationRoot(), true);
            }
        }

        @Override
        public void prepare(TransferTask task) {
            if (!task.betweenFileSystems()) {
                return;
            }
            task.setStatus("Preparing…");
            final var provider = provider(current(task.getSourceRoot()));
            task.setBytesTotal(task.getItems().stream().mapToLong(i -> size(provider, i.entry())).sum());
        }

        @Override
        public void run(TransferTask task, TransferTask.Item item, CopyProgress progress) {
            final var root = task.getSourceRoot();
            if (task.betweenFileSystems()) {
                transferAcross(task, item, progress);
            } else if (item.duplicate()) {
                duplicate(root, item.entry(), progress);
            } else if (task.getKind() == TransferTask.Kind.MOVE) {
                move(root, item.entry(), task.getDestination(), item.name(), item.replace());
            } else {
                copy(root, item.entry(), task.getDestination(), item.name(), item.replace(), progress);
            }
        }
    }

    /** A verified copy to another file system; for a move, then the originals that were verified. */
    private void transferAcross(TransferTask task, TransferTask.Item item, CopyProgress progress) {
        final var sourceRoot = task.getSourceRoot();
        final var targetRoot = task.getDestinationRoot();
        final var from = provider(current(sourceRoot));
        final var to = writable(targetRoot);
        requireFree(targetRoot, task.getDestination(), item.name());
        final var folder = StoragePaths.join(task.getDestination());
        final var target = StoragePaths.child(folder, item.name());
        final var result = VerifiedCopy.copy(from, item.entry().path(), to, folder, item.name(), item.replace(), progress);
        if (item.replace()) {
            dropStars(targetRoot.id(), target);
        }
        if (task.getKind() == TransferTask.Kind.MOVE) {
            removeOriginals(task, item, from, target, result);
        }
    }

    /** Deletes the originals a move verified, moving their stars along; what stays is noted on the task. */
    private void removeOriginals(TransferTask task, TransferTask.Item item, StorageProvider from, String target,
                                 VerifiedCopy.Result result) {
        final OriginalRemoval.Outcome outcome;
        try {
            outcome = OriginalRemoval.remove(from, item.entry(), result);
        } catch (StorageException e) {
            throw new StorageException("“" + item.entry().name() + "” was copied, but the original couldn't be removed.", e);
        }
        final var top = item.entry().path();
        outcome.deleted().forEach(path -> moveStarAcross(task.getSourceRoot(), path, task.getDestinationRoot(),
                target + path.substring(top.length())));
        outcome.changed().forEach(name -> task.note("“" + name + "” changed during the move; the original was kept."));
        if (outcome.added() > 0) {
            task.note(Formats.plural(outcome.added(), "item") + " added during the move stayed in the original folder.");
        }
    }

    private void replaceFile(FsRoot root, StorageProvider provider, StorageEntry entry, String path, String name) {
        final var existing = provider.stat(path);
        if (existing.isPresent()) {
            if (entry.folder() || existing.orElseThrow().folder()) {
                throw new StorageException("“" + name + "” can't be replaced: only a file replaces a file.");
            }
            provider.delete(path);
            dropStars(root.id(), path);
        }
    }

    private static String copyName(String name, Set<String> taken) {
        final var dot = name.lastIndexOf('.');
        final var base = dot > 0 ? name.substring(0, dot) : name;
        final var ext = dot > 0 ? name.substring(dot) : "";
        var candidate = base + " copy" + ext;
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = base + " copy " + n + ext;
        }
        return candidate;
    }

    // ---- uploads --------------------------------------------------------------------------

    /** Names of everything in a folder, files and folders. */
    public Set<String> entryNames(FsRoot root, List<String> folder) {
        final var names = new HashSet<String>();
        list(root, folder).forEach(e -> names.add(e.name()));
        return names;
    }

    public Set<String> fileNames(FsRoot root, List<String> folder) {
        final var names = new HashSet<String>();
        list(root, folder).stream().filter(e -> !e.folder()).forEach(e -> names.add(e.name()));
        return names;
    }

    /** "name (1).ext", "name (2).ext", … — the first one not in {@code taken}. */
    public static String keepBothName(String name, Set<String> taken) {
        final var dot = name.lastIndexOf('.');
        final var base = dot > 0 ? name.substring(0, dot) : name;
        final var ext = dot > 0 ? name.substring(dot) : "";
        int number = 1;
        String candidate;
        do {
            candidate = base + " (" + number + ")" + ext;
            number++;
        } while (taken.contains(candidate));
        return candidate;
    }

    /**
     * Queues uploads; files from web links start downloading on the server right away. Refuses links and
     * extraction where the file system's settings turn them off.
     */
    public List<UploadTask> upload(FsRoot root, List<String> folder, List<UploadQueue.Planned> planned, long maxBytes) {
        final var now = writableRoot(root);
        if (!now.options().linkUploads() && planned.stream().anyMatch(p -> p.file().fromLink())) {
            throw linkUploadsOff(now);
        }
        if (!now.options().extraction() && planned.stream().anyMatch(p -> p.extraction() != null)) {
            throw new StorageException("Extracting archives is turned off for “" + now.name() + "”.");
        }
        for (final var p : planned) {
            requireFree(now, folder, p.finalName());
            final var extraction = p.extraction();
            if (extraction != null && extraction.folderName() != null) {
                requireFree(now, folder, extraction.folderName());
            }
            if (extraction != null && extraction.singleName() != null) {
                requireFree(now, folder, extraction.singleName());
            }
        }
        final var tasks = uploads.enqueue(now, folder, planned);
        tasks.stream()
                .filter(t -> t.getUrl() != null)
                .forEach(t -> Thread.ofVirtual().name("link-upload-" + t.getId()).start(() -> uploads.download(t, links, maxBytes)));
        return tasks;
    }

    /**
     * Queues a folder upload under its planned name; its folders are created on a thread of their own, since on
     * remote storage each one costs a round trip. Its files are sent once {@link UploadQueue#takeReady} returns them.
     */
    public FolderUpload uploadFolder(FsRoot root, List<String> destination, UploadQueue.PlannedFolder folder) {
        final var now = writableRoot(root);
        requireFree(now, destination, folder.name());
        final var upload = uploads.enqueueFolder(now, destination, folder);
        Thread.ofVirtual().name("folder-upload-" + upload.getId()).start(() -> uploads.prepare(upload));
        return upload;
    }

    /** The name and size a web link offers, read on the server with the same checks as the download. */
    public LinkFetcher.LinkInfo inspectLink(FsRoot root, String url) {
        final var now = current(root);
        if (!now.options().linkUploads()) {
            throw linkUploadsOff(now);
        }
        return links.inspect(url);
    }

    private static StorageException linkUploadsOff(FsRoot root) {
        return new StorageException("Uploads from web links are turned off for “" + root.name() + "”.");
    }

    // ---- view preferences -----------------------------------------------------------------

    public void setLayout(Layout layout) {
        this.layout = layout;
        savePreferences();
    }

    public void setDensity(Density density) {
        this.density = density;
        savePreferences();
    }

    public void setSort(Sort sort) {
        this.sort = sort;
        savePreferences();
    }

    public void setGroupByType(boolean groupByType) {
        this.groupByType = groupByType;
        savePreferences();
    }

    private void savePreferences() {
        if (email != null) {
            final var preferences = new UserData.Preferences(layout, density, sort, groupByType);
            userData.savePreferences(email, preferences);
        }
    }
}
