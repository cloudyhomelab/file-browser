package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.MoveCheck;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.SubfolderLoader;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableConsumer;

public class MoveDialog extends FbDialog {

    private final FsRoot root;
    private final SubfolderLoader loader;
    private final Map<String, List<String>> loaded = new HashMap<>();
    private final List<String> currentPath;
    private final Set<String> movedFolderKeys = new HashSet<>();
    private final Set<String> expanded = new HashSet<>();
    private final Div picker = Ui.div("fb-move-tree");
    private final Div status = Ui.div("fb-move-status");
    private final Button confirm;
    private List<String> dest = List.of();

    public MoveDialog(List<StorageEntry> entries, FsRoot root, SubfolderLoader loader, List<String> currentPath,
                      SerializableConsumer<List<String>> onConfirm) {
        super("Move " + (entries.size() > 1 ? entries.size() + " items" : "“" + entries.getFirst().name() + "”"),
                "move", Tone.ACCENT, Width.MEDIUM);
        this.root = root;
        this.loader = loader;
        this.currentPath = currentPath;
        entries.stream().filter(StorageEntry::folder).forEach(e -> movedFolderKeys.add(e.path()));

        final var introLead = new Span("Choose a destination folder in ");
        final var introRoot = Ui.span("fb-strong", root.name());
        final var introEnd = new Span(".");
        final var intro = Ui.div("fb-dialog-text fb-dialog-text--s", introLead, introRoot, introEnd);
        add(intro, picker, status);

        confirm = Buttons.create(Buttons.Kind.PRIMARY, "Move here", "move", () -> {
            if (valid()) {
                close();
                onConfirm.accept(dest);
            }
        });
        final var cancel = cancelButton();
        footer(cancel, confirm);
        render();
    }

    private static String key(List<String> path) {
        return String.join("/", path);
    }

    private static List<String> append(List<String> path, String name) {
        final var out = new ArrayList<>(path);
        out.add(name);
        return List.copyOf(out);
    }

    private MoveCheck check() {
        return MoveCheck.of(currentPath, movedFolderKeys, dest);
    }

    private boolean valid() {
        return check() == MoveCheck.OK;
    }

    private void render() {
        picker.removeAll();
        final var homeIcon = new Icon("home");
        final var rootRow = row(List.of(), 0, homeIcon, root.name() + " (root)", currentPath.isEmpty());
        rootRow.addClassName("fb-move-row--root");
        picker.add(rootRow);
        for (final var name : subfolders(List.of())) {
            addNode(List.of(name), 0);
        }

        status.removeAll();
        final var check = check();
        final var bad = check != MoveCheck.OK;
        status.getElement().getClassList().set("fb-move-status--error", bad);
        final var statusIcon = new Icon(bad ? "warn" : "info");
        status.add(statusIcon);
        if (check == MoveCheck.INTO_ITSELF) {
            final var message = new Span("Can't move a folder into itself.");
            status.add(message);
        } else if (check == MoveCheck.SAME_FOLDER) {
            final var message = new Span("Items are already here.");
            status.add(message);
        } else {
            final var label = new Span("Destination: ");
            final var destName = Ui.span("fb-strong", dest.isEmpty() ? root.name() : dest.getLast());
            status.add(label, destName);
        }
        confirm.setEnabled(valid());
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

    private void addNode(List<String> path, int depth) {
        final var pathKey = key(path);
        final var open = expanded.contains(pathKey);
        final var subfolders = open ? subfolders(path) : null;
        final var leaf = subfolders != null && subfolders.isEmpty();
        final var folderIcon = new Icon("folder");
        final var row = row(path, depth, folderIcon, path.getLast(), movedFolderKeys.contains(pathKey));
        if (!leaf) {
            final var caretIcon = new Icon("angle-right");
            final var caret = Ui.button("fb-caret" + (open ? " fb-caret--open" : ""), () -> {
                if (!expanded.remove(pathKey)) {
                    expanded.add(pathKey);
                }
                render();
            }, caretIcon);
            caret.getElement().setAttribute("aria-label", open ? "Collapse" : "Expand");
            row.addComponentAsFirst(caret);
        } else {
            final var noCaret = Ui.span("fb-caret fb-caret--none", "");
            row.addComponentAsFirst(noCaret);
        }
        picker.add(row);
        if (subfolders != null) {
            subfolders.forEach(sub -> addNode(append(path, sub), depth + 1));
        }
    }

    private Div row(List<String> path, int depth, Icon icon, String label, boolean disabled) {
        final var text = Ui.span("fb-ellipsis", label);
        final var row = Ui.div("fb-move-row", icon, text);
        final var depthValue = String.valueOf(depth);
        row.getStyle().set("--depth", depthValue);
        final var selected = key(path).equals(key(dest));
        row.getElement().getClassList().set("fb-move-row--selected", selected);
        row.getElement().getClassList().set("fb-move-row--disabled", disabled);
        if (!disabled) {
            row.addClickListener(e -> {
                dest = path;
                render();
            });
        }
        return row;
    }
}
