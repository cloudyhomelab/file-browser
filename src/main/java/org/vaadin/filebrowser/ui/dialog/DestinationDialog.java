package org.vaadin.filebrowser.ui.dialog;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.MoveCheck;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.ui.Buttons;
import org.vaadin.filebrowser.ui.FolderTreeGrid;
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
    private final List<String> currentPath;
    private final Set<String> movedFolderKeys = new HashSet<>();
    private final Div rootPicker = Ui.div("fb-switcher-wrap fb-move-roots");
    private final Div intro = Ui.div("fb-dialog-text fb-dialog-text--s");
    private final Div picker = Ui.div("fb-move-tree-slot");
    private final Div status = Ui.div("fb-move-status");
    private final Button confirm;
    private FsRoot target;
    private List<String> dest = List.of();
    private FolderTreeGrid tree;

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
        renderTree();
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
        renderTree();
        render();
    }

    /** A new tree for each file system, since its folders come from that file system. */
    private void renderTree() {
        picker.removeAll();
        final var root = target;
        tree = new FolderTreeGrid(path -> loader.apply(root, path), root.name() + " (root)", "home");
        tree.addClassName("fb-move-tree");
        tree.setDisabled(this::disabled);
        tree.addFolderClickListener(path -> {
            dest = path;
            render();
        });
        picker.add(tree);
    }

    /** The root when the items are already there, and the folders being moved, can't take them. */
    private boolean disabled(List<String> path) {
        if (!sameFileSystem()) {
            return false;
        }
        if (path.isEmpty()) {
            return mode == Mode.MOVE && currentPath.isEmpty();
        }
        return movedFolderKeys.contains(key(path));
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

        tree.setActive(dest);

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
}
