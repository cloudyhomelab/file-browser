package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.security.access.AccessDeniedException;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;

import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.VaadinSessionScope;

/**
 * The signed-in user's view of the shared file systems (from {@link FileSystemRegistry}): file
 * operations through {@link StorageService}, the user's starred items and view preferences (saved
 * through {@link UserData}) and uploads. Lives for the Vaadin session.
 */
@SpringComponent
@VaadinSessionScope
public class Workspace implements Serializable {

    public enum Layout { LIST, GRID }

    public enum Density { DENSE, COMFORTABLE }

    public enum ConflictChoice { REPLACE, KEEP_BOTH, SKIP }


    private final Catalog catalog;
    private final FileSystemRegistry registry;
    private final StorageService storage;
    private final RootSecrets secrets;
    private final LinkFetcher links;
    private final Set<String> starred = new LinkedHashSet<>();
    private final UploadQueue uploads;
    private final UserData userData;
    private final CurrentUser currentUser;
    private final String email;

    private Layout layout = UserData.Preferences.DEFAULT.layout();
    private Density density = UserData.Preferences.DEFAULT.density();
    private Sort sort = UserData.Preferences.DEFAULT.sort();
    private boolean groupByType = UserData.Preferences.DEFAULT.groupByType();

    public Workspace(Catalog catalog, FileSystemRegistry registry, StorageService storage, RootSecrets secrets,
                     LinkFetcher links, UserData userData, CurrentUser currentUser) {
        this.catalog = catalog;
        this.registry = registry;
        this.storage = storage;
        this.secrets = secrets;
        this.links = links;
        this.uploads = new UploadQueue(storage);
        this.userData = userData;
        this.currentUser = currentUser;
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

    public Catalog catalog() {
        return catalog;
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
            return StorageService.Check.problem("Set FILEBROWSER_SECRET_KEY_FILE on the server to store credentials.");
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

    private StorageProvider provider(FsRoot root) {
        return storage.provider(root);
    }

    /** The provider behind a file system, for streaming downloads outside the UI thread. */
    public StorageProvider storage(FsRoot root) {
        return provider(root);
    }

    public List<StorageEntry> list(FsRoot root, List<String> folder) {
        return provider(current(root)).list(StoragePaths.join(folder));
    }

    /** Whether {@code folder} exists and is a folder; false when the file system can't be read. */
    public boolean isFolder(FsRoot root, List<String> folder) {
        try {
            if (folder.isEmpty()) {
                return provider(root).healthy();
            }
            return provider(root).stat(StoragePaths.join(folder)).map(StorageEntry::folder).orElse(false);
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
            final var location = entry.get().parent();
            final var label = String.join(" / ", Stream.concat(Stream.of(root.name()), location.stream()).toList());
            final var located = new Located(entry.get(), root.id(), location, label);
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

    public void createFolder(FsRoot root, List<String> folder, String name) {
        writable(root).createFolder(StoragePaths.join(folder), name);
    }

    public void rename(FsRoot root, StorageEntry entry, String newName) {
        writable(root).rename(entry.path(), newName);
        final var renamed = StoragePaths.child(StoragePaths.join(entry.parent()), newName);
        moveStars(root.id(), entry.path(), renamed);
    }

    public void delete(FsRoot root, StorageEntry entry) {
        writable(root).delete(entry.path());
        dropStars(root.id(), entry.path());
    }

    public void move(FsRoot root, StorageEntry entry, List<String> destination) {
        final var destinationPath = StoragePaths.join(destination);
        writable(root).move(entry.path(), destinationPath);
        final var moved = StoragePaths.child(destinationPath, entry.name());
        moveStars(root.id(), entry.path(), moved);
    }

    /** Copies next to the original as "name copy.ext", "name copy 2.ext", … */
    public String duplicate(FsRoot root, StorageEntry entry) {
        final var provider = writable(root);
        final var siblings = new HashSet<String>();
        provider.list(StoragePaths.join(entry.parent())).forEach(e -> siblings.add(e.name()));
        final var name = copyName(entry.name(), siblings);
        provider.copy(entry.path(), name);
        return name;
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
        int n = 1;
        String candidate;
        do {
            candidate = base + " (" + n + ")" + ext;
            n++;
        } while (taken.contains(candidate));
        return candidate;
    }

    /** Queues uploads; files from web links start downloading on the server right away. */
    public List<UploadTask> upload(FsRoot root, List<String> folder, List<UploadQueue.Planned> planned, long maxBytes) {
        final var tasks = uploads.enqueue(writableRoot(root), folder, planned);
        tasks.stream()
                .filter(t -> t.url() != null)
                .forEach(t -> Thread.ofVirtual().name("link-upload-" + t.id()).start(() -> uploads.download(t, links, maxBytes)));
        return tasks;
    }

    /** The name and size a web link offers, read on the server with the same checks as the download. */
    public LinkFetcher.LinkInfo inspectLink(String url) {
        return links.inspect(url);
    }

    public UploadQueue uploads() {
        return uploads;
    }

    // ---- view preferences -----------------------------------------------------------------

    public Layout layout() {
        return layout;
    }

    public void setLayout(Layout layout) {
        this.layout = layout;
        savePreferences();
    }

    public Density density() {
        return density;
    }

    public void setDensity(Density density) {
        this.density = density;
        savePreferences();
    }

    public Sort sort() {
        return sort;
    }

    public void setSort(Sort sort) {
        this.sort = sort;
        savePreferences();
    }

    /** Whether the file systems page shows one section per storage type. */
    public boolean groupByType() {
        return groupByType;
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
