package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.List;

import org.vaadin.filebrowser.data.Located;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.function.SerializableBiConsumer;

/** Right-click / "⋯" menu for an entry. One instance serves every row or tile of a view. */
public class EntryMenu extends Popover {

    private final Div menu = Ui.div("fb-menu fb-entry-menu");

    private record Item(EntryAction action, String label, String icon, boolean danger) {
    }

    public EntryMenu() {
        setPosition(PopoverPosition.BOTTOM_END);
        addThemeVariants(PopoverVariant.NO_PADDING);
        setAriaRole("menu");
        setAriaLabel("Actions");
        // opened from the row, tile or "⋯" button, which also have to stop the click or right-click going further
        setOpenOnClick(false);
        add(menu);
    }

    /**
     * @param anchor     the entry's "⋯" button; right-click opens the menu there too
     * @param canPreview whether a file offers Preview; its file system may not allow opening files
     */
    public void open(Located located, Component anchor, boolean starredMode, boolean starred, boolean readOnly,
                     boolean canPreview, SerializableBiConsumer<EntryAction, Located> onAction) {
        final var items = items(located.entry().folder(), starredMode, starred, canPreview);
        menu.removeAll();
        for (final var item : items) {
            if (item == null) {
                final var separator = Ui.divider("fb-menu-sep");
                menu.add(separator);
                continue;
            }
            final var disabled = readOnly && item.action().writes();
            final var icon = new Icon(item.icon());
            final var label = Ui.span("", item.label());
            final var button = Ui.button("fb-menu-item" + (item.danger() ? " fb-menu-item--danger" : ""), () -> {
                close();
                onAction.accept(item.action(), located);
            }, icon, label);
            button.setEnabled(!disabled);
            menu.add(button);
        }
        setTarget(anchor);
        open();
    }

    /** Fills a list's right-click menu with the same items as this menu. */
    public static void fill(GridContextMenu<Located> menu, Located located, boolean starredMode, boolean starred,
                            boolean readOnly, boolean canPreview, SerializableBiConsumer<EntryAction, Located> onAction) {
        menu.removeAll();
        for (final var item : items(located.entry().folder(), starredMode, starred, canPreview)) {
            if (item == null) {
                menu.addSeparator();
                continue;
            }
            final var icon = new Icon(item.icon());
            final var label = Ui.span("", item.label());
            final var content = Ui.span("fb-context-item", icon, label);
            final var menuItem = menu.addItem(content, e -> onAction.accept(item.action(), located));
            menuItem.setEnabled(!(readOnly && item.action().writes()));
            menuItem.getElement().getClassList().set("fb-context-item--danger", item.danger());
        }
    }

    /** The menu's items in order; null stands for a separator. */
    private static List<Item> items(boolean folder, boolean starredMode, boolean starred, boolean canPreview) {
        final var items = new ArrayList<Item>();
        final var star = new Item(EntryAction.STAR, starred ? "Remove from starred" : "Add to starred",
                starred ? "star" : "star-outline", false);
        final var download = new Item(EntryAction.DOWNLOAD, "Download", "download", false);
        if (starredMode) {
            final var open = folder ? new Item(EntryAction.OPEN, "Go to folder", "folder", false)
                    : new Item(EntryAction.OPEN, "Go to file", "arrow-right", false);
            items.add(open);
            items.add(download);
            items.add(null);
            items.add(star);
            return items;
        }
        final var open = folder ? new Item(EntryAction.OPEN, "Open", "folder", false)
                : new Item(EntryAction.OPEN, "Preview", "eye", false);
        final var rename = new Item(EntryAction.RENAME, "Rename", "edit", false);
        final var move = new Item(EntryAction.MOVE, "Move to…", "move", false);
        final var copy = new Item(EntryAction.COPY, "Copy", "copy", false);
        final var delete = new Item(EntryAction.DELETE, "Delete", "trash", true);
        if (folder || canPreview) {
            items.add(open);
        }
        items.add(download);
        items.add(null);
        items.addAll(List.of(rename, move, copy, star));
        items.add(null);
        items.add(delete);
        return items;
    }
}
