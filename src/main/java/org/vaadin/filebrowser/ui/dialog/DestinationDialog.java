package org.vaadin.filebrowser.ui.dialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.MoveCheck;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.Icon;
import org.vaadin.filebrowser.ui.RootChip;
import org.vaadin.filebrowser.ui.Ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.function.SerializableBiConsumer;
import com.vaadin.flow.function.SerializableBiFunction;

/** Picks the file system and folder that items are moved or copied into. */
public class DestinationDialog extends FbDialog {

    public enum Mode { MOVE, COPY }

    private final Mode mode;
    private final FsRoot source;
    private final List<FsRoot> targets;
    private final Catalog catalog;
    private final SerializableBiFunction<FsRoot, List<String>, List<String>> loader;
    private final Map<String, List<String>> loaded = new HashMap<>();
    private final List<String> currentPath;
    private final Set<String> movedFolderKeys = new HashSet<>();
    private final Set<String> expanded = new HashSet<>();
    private final Div rootPicker = Ui.div("fb-switcher-wrap fb-move-roots");
    private final Div intro = Ui.div("fb-dialog-text fb-dialog-text--s");
    private final Div picker = Ui.div("fb-move-tree");
    private final Div status = Ui.div("fb-move-status");
    private final Button confirm;
    private FsRoot target;
    private List<String> dest = List.of();

    /**
     * @param source  the file system the items are in
     * @param targets the file systems they can go to, {@code source} first when it is one of them; not empty
     * @param loader  the names of the folders inside a folder of a file system
     */
    public DestinationDialog(Mode mode, List<StorageEntry> entries, FsRoot source, List<FsRoot> targets, Catalog catalog,
                             SerializableBiFunction<FsRoot, List<String>, List<String>> loader, List<String> currentPath,
                             SerializableBiConsumer<FsRoot, List<String>> onConfirm) {
        super(verb(mode) + " " + (entries.size() > 1 ? entries.size() + " items" : "“" + entries.getFirst().name() + "”"),
                icon(mode), Tone.ACCENT, Width.MEDIUM);
        this.mode = mode;
        this.source = source;
        this.targets = targets;
        this.catalog = catalog;
        this.loader = loader;
        this.currentPath = currentPath;
        this.target = targets.getFirst();
        entries.stream().filter(StorageEntry::folder).forEach(e -> movedFolderKeys.add(e.path()));
        add(rootPicker, intro, picker, status);

        confirm = Buttons.create(Buttons.Kind.PRIMARY, verb(mode) + " here", icon(mode), () -> {
            if (valid()) {
                close();
                onConfirm.accept(target, dest);
            }
        });
        final var cancel = cancelButton();
        footer(cancel, confirm);
        render();
    }

    private static String verb(Mode mode) {
        return mode == Mode.MOVE ? "Move" : "Copy";
    }

    private static String icon(Mode mode) {
        return mode == Mode.MOVE ? "move" : "copy";
    }

    private static String key(List<String> path) {
        return String.join("/", path);
    }

    private static List<String> append(List<String> path, String name) {
        final var out = new ArrayList<>(path);
        out.add(name);
        return List.copyOf(out);
    }

    private boolean sameFileSystem() {
        return target.id().equals(source.id());
    }

    // "into itself" and "already here" only mean something on the items' own file system
    private MoveCheck check() {
        return sameFileSystem() ? MoveCheck.of(currentPath, movedFolderKeys, dest) : MoveCheck.OK;
    }

    private void pick(FsRoot root) {
        target = root;
        dest = List.of();
        loaded.clear();
        expanded.clear();
        render();
    }

    private void renderRootPicker() {
        rootPicker.removeAll();
        final var trigger = new NativeButton();
        trigger.setClassName("fb-switcher");
        final var chip = RootChip.of(target, catalog, RootChip.Size.S);
        final var name = Ui.span("fb-switcher-name", target.name());
        final var type = Ui.span("fb-switcher-type", catalog.kind(target).label());
        final var text = Ui.span("fb-switcher-text", name, type);
        final var caretIcon = new Icon("angle-down");
        final var caret = Ui.span("fb-switcher-caret", caretIcon);
        trigger.add(chip, text, caret);
        trigger.getElement().setAttribute("aria-label", "Destination file system");

        final var popover = new Popover();
        popover.setTarget(trigger);
        popover.setPosition(PopoverPosition.BOTTOM_START);
        popover.addThemeVariants(PopoverVariant.NO_PADDING);
        popover.addOpenedChangeListener(e -> trigger.getElement().getClassList().set("fb-open", e.isOpened()));
        final var menu = Ui.div("fb-menu fb-switcher-menu fb-move-roots-menu");
        for (final var root : targets) {
            final var active = root.id().equals(target.id());
            final var itemChip = RootChip.of(root, catalog, RootChip.Size.XS);
            final var itemName = Ui.span("fb-switcher-item-name", root.name());
            final var itemEndpoint = Ui.span("fb-switcher-item-endpoint", root.endpoint());
            final var itemText = Ui.span("fb-switcher-text", itemName, itemEndpoint);
            final var item = Ui.button("fb-switcher-item" + (active ? " fb-switcher-item--active" : ""), () -> {
                popover.close();
                if (!active) {
                    pick(root);
                }
            }, itemChip, itemText);
            if (active) {
                final var checkIcon = new Icon("check");
                final var check = Ui.span("fb-switcher-check", checkIcon);
                item.add(check);
            }
            menu.add(item);
        }
        popover.add(menu);
        rootPicker.add(trigger, popover);
    }

    private boolean valid() {
        final var check = check();
        return check == MoveCheck.OK || mode == Mode.COPY && check == MoveCheck.SAME_FOLDER;
    }

    private void render() {
        renderRootPicker();
        intro.removeAll();
        final var introLead = new Span("Choose a destination folder in ");
        final var introRoot = Ui.span("fb-strong", target.name());
        final var introEnd = new Span(".");
        intro.add(introLead, introRoot, introEnd);

        picker.removeAll();
        final var homeIcon = new Icon("home");
        final var rootRow = row(List.of(), 0, homeIcon, target.name() + " (root)",
                mode == Mode.MOVE && sameFileSystem() && currentPath.isEmpty());
        rootRow.addClassName("fb-move-row--root");
        picker.add(rootRow);
        for (final var name : subfolders(List.of())) {
            addNode(List.of(name), 0);
        }

        status.removeAll();
        final var check = check();
        final var bad = !valid();
        status.getElement().getClassList().set("fb-move-status--error", bad);
        final var statusIcon = new Icon(bad ? "warn" : "info");
        status.add(statusIcon);
        if (check == MoveCheck.INTO_ITSELF) {
            final var message = new Span("Can't " + verb(mode).toLowerCase(Locale.ROOT) + " a folder into itself.");
            status.add(message);
        } else if (bad) {
            final var message = new Span("Items are already here.");
            status.add(message);
        } else if (check == MoveCheck.SAME_FOLDER) {
            final var message = new Span("Copies are made here, named “… copy”.");
            status.add(message);
        } else {
            final var label = new Span("Destination: ");
            final var destName = Ui.span("fb-strong", dest.isEmpty() ? target.name() : dest.getLast());
            status.add(label, destName);
        }
        confirm.setEnabled(valid());
    }

    private List<String> subfolders(List<String> path) {
        return loaded.computeIfAbsent(key(path), k -> {
            try {
                return loader.apply(target, path);
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
        final var row = row(path, depth, folderIcon, path.getLast(), sameFileSystem() && movedFolderKeys.contains(pathKey));
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
