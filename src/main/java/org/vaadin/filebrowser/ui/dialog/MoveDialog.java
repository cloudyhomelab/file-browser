package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.vaadin.filebrowser.data.FsEntry;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableConsumer;

public class MoveDialog extends FbDialog {

    private final FsRoot root;
    private final List<FsEntry> tree;
    private final List<String> currentPath;
    private final Set<String> movedFolderKeys = new HashSet<>();
    private final Set<String> expanded = new HashSet<>();
    private final Div picker = Ui.div("fb-move-tree");
    private final Div status = Ui.div("fb-move-status");
    private final Button confirm;
    private List<String> dest = List.of();

    public MoveDialog(List<FsEntry> entries, FsRoot root, List<FsEntry> tree, List<String> currentPath,
                      SerializableConsumer<List<String>> onConfirm) {
        super("Move " + (entries.size() > 1 ? entries.size() + " items" : "“" + entries.getFirst().name() + "”"),
                "move", Tone.ACCENT, Width.MEDIUM);
        this.root = root;
        this.tree = tree;
        this.currentPath = currentPath;
        entries.stream().filter(FsEntry::folder).forEach(e -> movedFolderKeys.add(key(append(currentPath, e.name()))));
        tree.stream().filter(FsEntry::folder).forEach(e -> expanded.add(key(List.of(e.name()))));

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

    private boolean intoSelf() {
        final var k = key(dest);
        return movedFolderKeys.stream().anyMatch(d -> k.equals(d) || k.startsWith(d + "/"));
    }

    private boolean sameAsCurrent() {
        return key(dest).equals(key(currentPath));
    }

    private boolean valid() {
        return !intoSelf() && !sameAsCurrent();
    }

    private void render() {
        picker.removeAll();
        final var homeIcon = new Icon("home");
        final var rootRow = row(List.of(), 0, homeIcon, root.name() + " (root)", currentPath.isEmpty());
        rootRow.addClassName("fb-move-row--root");
        picker.add(rootRow);
        tree.stream().filter(FsEntry::folder).forEach(f -> addNode(f, List.of(f.name()), 0));

        status.removeAll();
        final var bad = intoSelf() || sameAsCurrent();
        status.getElement().getClassList().set("fb-move-status--error", bad);
        final var statusIcon = new Icon(bad ? "warn" : "info");
        status.add(statusIcon);
        if (intoSelf()) {
            final var message = new Span("Can't move a folder into itself.");
            status.add(message);
        } else if (sameAsCurrent()) {
            final var message = new Span("Items are already here.");
            status.add(message);
        } else {
            final var label = new Span("Destination: ");
            final var destName = Ui.span("fb-strong", dest.isEmpty() ? root.name() : dest.getLast());
            status.add(label, destName);
        }
        confirm.setEnabled(valid());
    }

    private void addNode(FsEntry folder, List<String> path, int depth) {
        final var subfolders = folder.children().stream().filter(FsEntry::folder).toList();
        final var pathKey = key(path);
        final var open = expanded.contains(pathKey);
        final var folderIcon = new Icon("folder");
        final var row = row(path, depth, folderIcon, folder.name(), movedFolderKeys.contains(pathKey));
        if (!subfolders.isEmpty()) {
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
        if (open) {
            subfolders.forEach(sub -> addNode(sub, append(path, sub.name()), depth + 1));
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
