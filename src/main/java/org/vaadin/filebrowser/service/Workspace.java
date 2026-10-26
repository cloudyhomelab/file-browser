package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsEntry;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IdSource;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.Sort;

import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.VaadinSessionScope;

/**
 * The signed-in user's view of the shared file systems (from {@link FileSystemRegistry}): folder
 * contents, starred items, view preferences and simulated uploads. Lives for the Vaadin session.
 */
@SpringComponent
@VaadinSessionScope
public class Workspace implements Serializable {

    public enum Layout { LIST, GRID }

    public enum Density { DENSE, COMFORTABLE }

    public enum ConflictChoice { REPLACE, KEEP_BOTH, SKIP }

    public record PlannedUpload(IncomingFile file, String finalName, boolean replace) {
    }

    private static final int UPLOAD_STEP_MIN = 8;
    private static final int UPLOAD_STEP_SPREAD = 22;
    private static final String NEW_ITEM_OWNER = "Maya Lindqvist";

    private final Catalog catalog;
    private final FileSystemRegistry registry;
    private final IdSource ids = new IdSource();
    // folder contents are still per-session and in memory until the storage providers exist (plan 01, item 2)
    private final Map<String, List<FsEntry>> trees;
    private final Set<String> starred = new LinkedHashSet<>();
    private final List<UploadTask> uploads = new ArrayList<>();

    private Layout layout = Layout.LIST;
    private Density density = Density.DENSE;
    private Sort sort = Sort.DEFAULT;

    public Workspace(Catalog catalog, FileSystemRegistry registry) {
        this.catalog = catalog;
        this.registry = registry;
        this.trees = new LinkedHashMap<>();
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

    public List<FsRoot> browsableRoots() {
        return registry.all().stream().filter(FsRoot::browsable).toList();
    }

    public boolean endpointTaken(String endpoint) {
        return registry.endpointTaken(endpoint);
    }

    public void addRoot(FsRoot root) {
        registry.add(root);
    }

    public String newRootId() {
        return registry.newId();
    }

    // ---- reading --------------------------------------------------------------------------

    public List<FsEntry> tree(String rootId) {
        final var contents = contents(rootId);
        return contents == null ? List.of() : contents;
    }

    /** The in-memory contents of a registered file system, or null for an unknown id. */
    private List<FsEntry> contents(String rootId) {
        final var existing = trees.get(rootId);
        if (existing != null || registry.find(rootId).isEmpty()) {
            return existing;
        }
        final var created = new ArrayList<FsEntry>();
        trees.put(rootId, created);
        return created;
    }

    /** Children of the folder at {@code path}, or null when the folder does not exist. */
    public List<FsEntry> dir(String rootId, List<String> path) {
        List<FsEntry> level = contents(rootId);
        if (level == null) {
            return null;
        }
        for (final var segment : path) {
            final var next = level.stream().filter(e -> e.folder() && e.name().equals(segment)).findFirst();
            if (next.isEmpty()) {
                return null;
            }
            level = next.get().children();
        }
        return level;
    }

    public List<Located> search(FsRoot root, String query) {
        final var q = query.toLowerCase(Locale.ROOT);
        final var hits = new ArrayList<Located>();
        walk(tree(root.id()), new ArrayList<>(), (entry, location) -> {
            if (entry.name().toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(locate(root, entry, location));
            }
        });
        return hits;
    }

    private interface Visitor {
        void visit(FsEntry entry, List<String> location);
    }

    private static void walk(List<FsEntry> nodes, List<String> location, Visitor visitor) {
        for (final var n : nodes) {
            visitor.visit(n, List.copyOf(location));
            if (n.folder()) {
                location.add(n.name());
                walk(n.children(), location, visitor);
                location.removeLast();
            }
        }
    }

    private static Located locate(FsRoot root, FsEntry entry, List<String> location) {
        final var label = String.join(" / ", Stream.concat(Stream.of(root.name()), location.stream()).toList());
        return new Located(entry, root.id(), location, label);
    }

    // ---- starred --------------------------------------------------------------------------

    private static String starKey(String rootId, String entryId) {
        return rootId + ":" + entryId;
    }

    public boolean isStarred(String rootId, String entryId) {
        return starred.contains(starKey(rootId, entryId));
    }

    public void setStarred(String rootId, String entryId, boolean on) {
        final var key = starKey(rootId, entryId);
        if (on) {
            starred.add(key);
        } else {
            starred.remove(key);
        }
    }

    public List<Located> starredIn(FsRoot root) {
        final var out = new ArrayList<Located>();
        walk(tree(root.id()), new ArrayList<>(), (entry, location) -> {
            if (isStarred(root.id(), entry.id())) {
                out.add(locate(root, entry, location));
            }
        });
        return sort.apply(out);
    }

    // ---- mutations ------------------------------------------------------------------------

    public void createFolder(String rootId, List<String> path, String name) {
        final var newFolder = FsEntry.folder(ids.next(), name, 0, NEW_ITEM_OWNER);
        requireDir(rootId, path).add(newFolder);
    }

    public void rename(String rootId, List<String> path, String entryId, String name) {
        requireDir(rootId, path).stream().filter(e -> e.id().equals(entryId)).findFirst()
                .ifPresent(e -> e.rename(name));
    }

    public void delete(String rootId, List<String> path, Collection<String> entryIds) {
        requireDir(rootId, path).removeIf(e -> entryIds.contains(e.id()));
    }

    public void move(String rootId, List<String> from, Collection<String> entryIds, List<String> to) {
        final var source = requireDir(rootId, from);
        final var moved = source.stream().filter(e -> entryIds.contains(e.id())).toList();
        source.removeAll(moved);
        requireDir(rootId, to).addAll(moved);
    }

    public void duplicate(String rootId, List<String> path, String entryId) {
        final var dir = requireDir(rootId, path);
        for (int i = 0; i < dir.size(); i++) {
            final var original = dir.get(i);
            if (original.id().equals(entryId)) {
                final var copyName = copyName(original.name());
                final var copy = original.deepCopy(ids::next, copyName);
                dir.add(i + 1, copy);
                return;
            }
        }
    }

    private static String copyName(String name) {
        final var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " copy" + name.substring(dot) : name + " copy";
    }

    private List<FsEntry> requireDir(String rootId, List<String> path) {
        final var dir = dir(rootId, path);
        if (dir == null) {
            throw new IllegalArgumentException("No folder " + path + " in " + rootId);
        }
        return dir;
    }

    // ---- uploads --------------------------------------------------------------------------

    public Set<String> fileNames(String rootId, List<String> path) {
        final var dir = requireDir(rootId, path);
        final var names = new HashSet<String>();
        dir.stream().filter(e -> !e.folder()).forEach(e -> names.add(e.name()));
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

    public List<UploadTask> uploads() {
        return List.copyOf(uploads);
    }

    public boolean uploading() {
        return uploads.stream().anyMatch(u -> !u.complete());
    }

    public void clearUploads() {
        uploads.clear();
    }

    public void enqueue(String rootId, List<String> path, List<PlannedUpload> planned) {
        for (final var p : planned) {
            final var task = new UploadTask(ids.next(), p.finalName(), p.file().type(), p.file().size(), rootId,
                    List.copyOf(path), p.replace());
            uploads.add(task);
        }
    }

    /** Advances the simulated transfers and stores files that finished; returns those. */
    public List<UploadTask> advanceUploads() {
        final var random = ThreadLocalRandom.current();
        final var finished = new ArrayList<UploadTask>();
        for (final var task : uploads) {
            if (task.advance(UPLOAD_STEP_MIN + random.nextInt(UPLOAD_STEP_SPREAD + 1))) {
                finished.add(task);
            }
        }
        finished.forEach(this::store);
        return finished;
    }

    private void store(UploadTask task) {
        final var dir = dir(task.rootId(), task.path());
        if (dir == null) {
            return;
        }
        if (task.replace()) {
            dir.removeIf(e -> !e.folder() && e.name().equals(task.name()));
        }
        final var file = FsEntry.file(ids.next(), task.name(), task.type(), task.size(), 0, NEW_ITEM_OWNER);
        dir.add(file);
    }

    // ---- view preferences -----------------------------------------------------------------

    public Layout layout() {
        return layout;
    }

    public void setLayout(Layout layout) {
        this.layout = layout;
    }

    public Density density() {
        return density;
    }

    public void setDensity(Density density) {
        this.density = density;
    }

    public Sort sort() {
        return sort;
    }

    public void setSort(Sort sort) {
        this.sort = sort;
    }
}
