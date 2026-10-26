package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.vaadin.filebrowser.data.FsEntry;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

/** Sidebar folder navigation. Folders on the current path open automatically. */
public class FolderTree extends Composite<Div> {

    private final List<FsEntry> tree;
    private final SerializableConsumer<List<String>> onNavigate;
    private final Set<String> open = new HashSet<>();
    private List<String> current = List.of();

    public FolderTree(List<FsEntry> tree, SerializableConsumer<List<String>> onNavigate) {
        this.tree = tree;
        this.onNavigate = onNavigate;
        getContent().setClassName("fb-tree");
    }

    /** {@code current} is null when no folder should be highlighted (starred view). */
    public void show(List<String> current) {
        this.current = current;
        if (current != null) {
            for (int i = 1; i <= current.size(); i++) {
                final var ancestorKey = String.join("/", current.subList(0, i));
                open.add(ancestorKey);
            }
        }
        render();
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
        tree.stream().filter(FsEntry::folder).forEach(f -> addNode(f, List.of(f.name()), 0));
    }

    private void addNode(FsEntry folder, List<String> path, int depth) {
        final var key = String.join("/", path);
        final var subfolders = folder.children().stream().filter(FsEntry::folder).toList();
        final var expanded = open.contains(key) && !subfolders.isEmpty();
        final var currentKey = current == null ? null : String.join("/", current);
        final var active = key.equals(currentKey);

        final var caretIcon = new Icon("angle-right");
        final var caret = Ui.button("fb-caret" + (expanded ? " fb-caret--open" : "") + (subfolders.isEmpty() ? " fb-caret--none" : ""),
                () -> {
                    if (!open.remove(key)) {
                        open.add(key);
                    }
                    render();
                }, caretIcon);
        caret.getElement().setAttribute("aria-label", expanded ? "Collapse" : "Expand");
        caret.setEnabled(!subfolders.isEmpty());

        final var folderIcon = new Icon("folder");
        final var name = Ui.span("fb-ellipsis", folder.name());
        final var row = Ui.div("fb-tree-row" + (active ? " fb-tree-row--active" : ""), caret, folderIcon, name);
        final var depthValue = String.valueOf(depth);
        row.getStyle().set("--depth", depthValue);
        row.addClickListener(e -> onNavigate.accept(path));
        getContent().add(row);
        if (expanded) {
            for (final var sub : subfolders) {
                final var subPath = new ArrayList<>(path);
                subPath.add(sub.name());
                final var immutableSubPath = List.copyOf(subPath);
                addNode(sub, immutableSubPath, depth + 1);
            }
        }
    }
}
