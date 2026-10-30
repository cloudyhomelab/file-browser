package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.AbstractHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializablePredicate;

/**
 * A lazily loaded folder tree. Items are paths from the root, which is the single top item; a folder's
 * subfolders are read the first time it is opened.
 */
public class FolderTreeGrid extends Composite<TreeGrid<List<String>>> {

    private static final String TEMPLATE = """
            <span class="fb-folder-tree-row">
              <vaadin-grid-tree-toggle .leaf=${!item.children} .expanded=${model.expanded} .level=${model.level}
                  @click=${toggle}></vaadin-grid-tree-toggle>
              <span class="fb-icon" aria-hidden="true"><svg><use href="icons.svg#${item.icon}"></use></svg></span>
              <span class="fb-ellipsis">${item.label}</span>
              <vaadin-badge ?hidden=${!item.badge}>${item.badge}</vaadin-badge>
            </span>""";

    private final SubfolderLoader loader;
    private final Map<List<String>, List<String>> loaded = new HashMap<>();
    private final Folders folders = new Folders();
    private SerializablePredicate<List<String>> disabled = p -> false;
    private SerializablePredicate<List<String>> badged = p -> false;
    private String badge = "";
    private List<String> active;

    /**
     * @param rootLabel what the root row says, e.g. "All files"
     * @param rootIcon  the root row's icon in icons.svg
     */
    public FolderTreeGrid(SubfolderLoader loader, String rootLabel, String rootIcon) {
        this.loader = loader;
        final var grid = getContent();
        grid.addClassName("fb-folder-tree");
        grid.setAriaLabel("Folders");
        // the active folder is the app's own state, shown with a part name; Grid selection would mark a second row
        grid.setSelectionMode(Grid.SelectionMode.NONE);
        grid.setDataProvider(folders);
        final var renderer = LitRenderer.<List<String>>of(TEMPLATE)
                .withProperty("children", p -> !isLeaf(p))
                .withProperty("icon", p -> p.isEmpty() ? rootIcon : "folder")
                .withProperty("label", p -> p.isEmpty() ? rootLabel : p.getLast())
                .withProperty("badge", p -> badged.test(p) ? badge : "")
                .withFunction("toggle", this::toggle);
        grid.addColumn(renderer);
        grid.setPartNameGenerator(p -> {
            final var parts = new ArrayList<String>(2);
            if (p.equals(active)) {
                parts.add("fb-tree-active");
            }
            if (disabled.test(p)) {
                parts.add("fb-tree-disabled");
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        });
        grid.expand(List.of(List.<String>of()));
    }

    /** Rows that can't be chosen, e.g. a folder that is being moved. */
    public void setDisabled(SerializablePredicate<List<String>> rule) {
        disabled = rule;
        refresh();
    }

    /** Rows that carry {@code text} as a badge, e.g. folders already connected. */
    public void setBadge(SerializablePredicate<List<String>> rule, String text) {
        badged = rule;
        badge = text;
        refresh();
    }

    /** Highlights {@code path} and opens its ancestors; null highlights nothing. */
    public void setActive(List<String> path) {
        active = path;
        if (path != null) {
            final var ancestors = new ArrayList<List<String>>();
            for (int i = 0; i < path.size(); i++) {
                ancestors.add(List.copyOf(path.subList(0, i)));
            }
            getContent().expand(ancestors);
        }
        refresh();
    }

    public List<String> getActive() {
        return active;
    }

    /** Scrolls the active row into view, e.g. a folder deep in the tree that a dialog opens at. */
    public void scrollToActive() {
        if (active != null) {
            getContent().scrollToItem(active);
        }
    }

    /** {@code onClick} gets the path of a clicked row that is not disabled. */
    public void addFolderClickListener(SerializableConsumer<List<String>> onClick) {
        getContent().addItemClickListener(e -> {
            if (!disabled.test(e.getItem())) {
                onClick.accept(e.getItem());
            }
        });
    }

    /**
     * {@code onDrop} gets the folder something was dropped onto. Drops are taken only while switched on with
     * {@link #setDropActive}, as for {@link DragMove.Targets}.
     */
    public void addFolderDropListener(SerializableConsumer<List<String>> onDrop) {
        getContent().addDropListener(e -> e.getDropTargetItem().ifPresent(onDrop));
    }

    public void setDropActive(boolean on) {
        getContent().setDropMode(on ? GridDropMode.ON_TOP : null);
    }

    /** Forgets what was read, so the tree shows changes made since. */
    public void reload() {
        loaded.clear();
        refresh();
    }

    /** Rows show at their natural height instead of scrolling inside the tree. */
    public void setAllRowsVisible() {
        getContent().setAllRowsVisible(true);
    }

    private void refresh() {
        folders.refreshAll();
    }

    private void toggle(List<String> path) {
        final var grid = getContent();
        if (grid.isExpanded(path)) {
            grid.collapse(List.of(path));
        } else {
            grid.expand(List.of(path));
            if (subfolders(path).isEmpty()) {
                folders.refreshItem(path);
            }
        }
    }

    private boolean isLeaf(List<String> path) {
        final var known = loaded.get(path);
        return known != null && known.isEmpty();
    }

    private List<String> subfolders(List<String> path) {
        return loaded.computeIfAbsent(path, p -> {
            try {
                return loader.subfolders(p);
            } catch (RuntimeException e) {
                return List.of();
            }
        });
    }

    private static List<String> append(List<String> path, String name) {
        final var out = new ArrayList<>(path);
        out.add(name);
        return List.copyOf(out);
    }

    /** The root is the only top-level item; every other level is read from the loader. */
    private final class Folders extends AbstractHierarchicalDataProvider<List<String>, Void> {

        @Override
        public boolean isInMemory() {
            return false;
        }

        @Override
        public List<String> getParent(List<String> path) {
            return path.isEmpty() ? null : List.copyOf(path.subList(0, path.size() - 1));
        }

        @Override
        public int getItemIndex(List<String> path, HierarchicalQuery<List<String>, Void> query) {
            return children(query.getParent()).indexOf(path);
        }

        @Override
        public boolean hasChildren(List<String> path) {
            return !isLeaf(path);
        }

        @Override
        public int getChildCount(HierarchicalQuery<List<String>, Void> query) {
            return children(query.getParent()).size();
        }

        @Override
        public Stream<List<String>> fetchChildren(HierarchicalQuery<List<String>, Void> query) {
            return children(query.getParent()).stream().skip(query.getOffset()).limit(query.getLimit());
        }

        private List<List<String>> children(List<String> parent) {
            if (parent == null) {
                return List.of(List.of());
            }
            return subfolders(parent).stream().map(name -> append(parent, name)).toList();
        }
    }
}
