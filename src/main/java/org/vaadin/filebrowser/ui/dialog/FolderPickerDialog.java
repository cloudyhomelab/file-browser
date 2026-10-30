package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.FolderTreeGrid;
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
    private final FolderTreeGrid tree;
    private final Div status = Ui.div("fb-move-status");
    private final Button choose;
    private List<String> chosen;

    /** @param start the path typed so far; the tree opens down to it */
    public FolderPickerDialog(SubfolderLoader loader, Predicate<String> connected, String start,
                              SerializableConsumer<String> onChoose) {
        super("Choose folder", "folder", Tone.ACCENT, Width.MEDIUM);
        this.loader = loader;
        this.connected = connected;
        chosen = openAt(start);

        tree = new FolderTreeGrid(loader, "/", "t-disk");
        tree.addClassNames("fb-move-tree", "fb-picker-tree");
        tree.setDisabled(path -> connected.test(absolute(path)));
        tree.setBadge(path -> connected.test(absolute(path)), "Connected");
        tree.addFolderClickListener(path -> {
            chosen = path;
            render();
        });

        final var intro = Ui.div("fb-dialog-text fb-dialog-text--s", new Span("Folders the server can read."));
        add(intro, tree, status);

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
        tree.scrollToActive();
    }

    /** The deepest folder of {@code start} that exists, or the root. */
    private List<String> openAt(String start) {
        if (start == null || !start.startsWith("/")) {
            return List.of();
        }
        final var segments = Arrays.stream(start.split("/")).filter(s -> !s.isEmpty()).toList();
        var path = List.<String>of();
        for (final var segment : segments) {
            if (!subfolders(path).contains(segment)) {
                break;
            }
            path = append(path, segment);
        }
        return path;
    }

    private static String absolute(List<String> path) {
        return "/" + String.join("/", path);
    }

    private static List<String> append(List<String> path, String name) {
        final var out = new ArrayList<>(path);
        out.add(name);
        return List.copyOf(out);
    }

    private List<String> subfolders(List<String> path) {
        try {
            return loader.subfolders(path);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void render() {
        tree.setActive(chosen);
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
}
