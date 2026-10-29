package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.vaadin.filebrowser.ui.Badge;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.SubfolderLoader;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableConsumer;

/**
 * Pick a folder on the server, browsing from {@code /} like the move dialog's tree. Folders that are
 * already a file system are marked and can't be picked.
 */
public class FolderPickerDialog extends FbDialog {

    private final SubfolderLoader loader;
    private final Predicate<String> connected;
    private final Map<String, List<String>> loaded = new HashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private final Div picker = Ui.div("fb-move-tree fb-picker-tree");
    private final Div status = Ui.div("fb-move-status");
    private final Button choose;
    private List<String> chosen = List.of();
    /** Set while rendering, scrolled into view once the tree is built. */
    @SuppressWarnings("PMD.SingularField")
    private Div chosenRow;

    /** @param start the path typed so far; the tree opens down to it */
    public FolderPickerDialog(SubfolderLoader loader, Predicate<String> connected, String start,
                              SerializableConsumer<String> onChoose) {
        super("Choose folder", "folder", Tone.ACCENT, Width.MEDIUM);
        this.loader = loader;
        this.connected = connected;
        openAt(start);

        final var intro = Ui.div("fb-dialog-text fb-dialog-text--s", new Span("Folders the server can read."));
        add(intro, picker, status);

        choose = Buttons.create(Buttons.Kind.PRIMARY, "Choose", "check", () -> {
            if (!connected.test(absolute(chosen))) {
                close();
                onChoose.accept(absolute(chosen));
            }
        });
        final var cancel = cancelButton();
        footer(cancel, choose);
        render();
        // the folder opened at can be deep in the tree
        chosenRow.getElement().executeJs("this.scrollIntoView({block: 'center'})");
    }

    private void openAt(String start) {
        if (start == null || !start.startsWith("/")) {
            return;
        }
        final var segments = Arrays.stream(start.split("/")).filter(s -> !s.isEmpty()).toList();
        // open every ancestor that exists, and select the deepest one
        var path = List.<String>of();
        for (final var segment : segments) {
            if (!subfolders(path).contains(segment)) {
                break;
            }
            expanded.add(key(path));
            path = append(path, segment);
        }
        chosen = path;
    }

    private static String key(List<String> path) {
        return String.join("/", path);
    }

    private static String absolute(List<String> path) {
        return "/" + key(path);
    }

    private static List<String> append(List<String> path, String name) {
        final var out = new ArrayList<>(path);
        out.add(name);
        return List.copyOf(out);
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
        picker.removeAll();
        final var rootIcon = new Icon("t-disk");
        final var rootRow = row(List.of(), 0, rootIcon, "/");
        rootRow.addClassName("fb-move-row--root");
        picker.add(rootRow);
        for (final var name : subfolders(List.of())) {
            addNode(List.of(name), 0);
        }

        status.removeAll();
        final var taken = connected.test(absolute(chosen));
        status.getElement().getClassList().set("fb-move-status--error", taken);
        final var statusIcon = new Icon(taken ? "warn" : "info");
        status.add(statusIcon);
        if (taken) {
            final var message = new Span("This folder is already connected.");
            status.add(message);
        } else {
            final var label = new Span("Folder: ");
            final var path = Ui.span("fb-strong fb-mono", absolute(chosen));
            status.add(label, path);
        }
        choose.setEnabled(!taken);
    }

    private void addNode(List<String> path, int depth) {
        final var pathKey = key(path);
        final var open = expanded.contains(pathKey);
        final var subfolders = open ? subfolders(path) : null;
        final var leaf = subfolders != null && subfolders.isEmpty();
        final var folderIcon = new Icon("folder");
        final var row = row(path, depth, folderIcon, path.getLast());
        if (leaf) {
            final var noCaret = Ui.span("fb-caret fb-caret--none", "");
            row.addComponentAsFirst(noCaret);
        } else {
            final var caretIcon = new Icon("angle-right");
            final var caret = Ui.button("fb-caret" + (open ? " fb-caret--open" : ""), () -> {
                if (!expanded.remove(pathKey)) {
                    expanded.add(pathKey);
                }
                render();
            }, caretIcon);
            caret.getElement().setAttribute("aria-label", open ? "Collapse" : "Expand");
            row.addComponentAsFirst(caret);
        }
        picker.add(row);
        if (subfolders != null) {
            subfolders.forEach(sub -> addNode(append(path, sub), depth + 1));
        }
    }

    private Div row(List<String> path, int depth, Icon icon, String label) {
        final var text = Ui.span("fb-ellipsis", label);
        final var row = Ui.div("fb-move-row", icon, text);
        final var depthValue = String.valueOf(depth);
        row.getStyle().set("--depth", depthValue);
        final var selected = key(path).equals(key(chosen));
        row.getElement().getClassList().set("fb-move-row--selected", selected);
        if (selected) {
            chosenRow = row;
        }
        final var taken = connected.test(absolute(path));
        row.getElement().getClassList().set("fb-move-row--disabled", taken);
        if (taken) {
            final var badge = new Badge(Badge.Tone.NEUTRAL, "Connected");
            row.add(badge);
        } else {
            row.addClickListener(e -> {
                chosen = path;
                render();
            });
        }
        return row;
    }
}
