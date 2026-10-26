package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

/**
 * Sidebar folder navigation. A folder's subfolders are read only when it is expanded; folders on
 * the current path expand automatically.
 */
public class FolderTree extends Composite<Div> {

    private final SubfolderLoader loader;
    private final SerializableConsumer<List<String>> onNavigate;
    private final Set<String> open = new HashSet<>();
    private final Map<String, List<String>> loaded = new HashMap<>();
    private List<String> current = List.of();

    public FolderTree(SubfolderLoader loader, SerializableConsumer<List<String>> onNavigate) {
        this.loader = loader;
        this.onNavigate = onNavigate;
        getContent().setClassName("fb-tree");
    }

    /** {@code current} is null when no folder should be highlighted (starred view). */
    public void show(List<String> current) {
        this.current = current;
        if (current != null) {
            for (int i = 1; i <= current.size(); i++) {
                final var ancestorKey = key(current.subList(0, i));
                open.add(ancestorKey);
            }
        }
        render();
    }

    /** Forgets what was read, so the next render shows changes made since. */
    public void reload() {
        loaded.clear();
        render();
    }

    private static String key(List<String> path) {
        return String.join("/", path);
    }

    private List<String> subfolders(List<String> path) {
        return loaded.computeIfAbsent(key(path), k -> {
            try {
                return loader.subfolders(path);
            } catch (RuntimeException e) {
                return List.of();
            }
        });
    }

    private void render() {
        final var div = getContent();
        div.removeAll();
        final var rootActive = current != null && current.isEmpty();
        final var homeIcon = new Icon("home");
        final var allLabel = Ui.span("", "All files");
        final var all = Ui.div("fb-tree-row fb-tree-row--all" + (rootActive ? " fb-tree-row--active" : ""),
                homeIcon, allLabel);
        all.addClickListener(e -> onNavigate.accept(List.of()));
        div.add(all);
        for (final var name : subfolders(List.of())) {
            addNode(List.of(name), 0);
        }
    }

    private void addNode(List<String> path, int depth) {
        final var key = key(path);
        final var expanded = open.contains(key);
        final var children = expanded ? subfolders(path) : null;
        final var leaf = children != null && children.isEmpty();
        final var currentKey = current == null ? null : key(current);
        final var active = key.equals(currentKey);

        final var caretIcon = new Icon("angle-right");
        final var caretClass = "fb-caret" + (expanded && !leaf ? " fb-caret--open" : "") + (leaf ? " fb-caret--none" : "");
        final var caret = Ui.button(caretClass, () -> {
            if (!open.remove(key)) {
                open.add(key);
            }
            render();
        }, caretIcon);
        caret.getElement().setAttribute("aria-label", expanded ? "Collapse" : "Expand");
        caret.setEnabled(!leaf);

        final var folderIcon = new Icon("folder");
        final var name = Ui.span("fb-ellipsis", path.getLast());
        final var row = Ui.div("fb-tree-row" + (active ? " fb-tree-row--active" : ""), caret, folderIcon, name);
        final var depthValue = String.valueOf(depth);
        row.getStyle().set("--depth", depthValue);
        row.addClickListener(e -> onNavigate.accept(path));
        getContent().add(row);
        if (children != null) {
            for (final var child : children) {
                final var childPath = new ArrayList<>(path);
                childPath.add(child);
                final var immutableChildPath = List.copyOf(childPath);
                addNode(immutableChildPath, depth + 1);
            }
        }
    }
}
