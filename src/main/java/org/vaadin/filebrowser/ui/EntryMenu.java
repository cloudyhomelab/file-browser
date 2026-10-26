package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.List;

import org.vaadin.filebrowser.data.Located;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableBiConsumer;

/**
 * Right-click / "⋯" menu for an entry, positioned at the pointer like the design's. A full-screen
 * backdrop closes it on any outside click.
 */
public class EntryMenu extends Div {

    private static final int ASSUMED_MENU_HEIGHT = 320;
    private static final int ASSUMED_MENU_WIDTH = 220;

    private record Item(EntryAction action, String label, String icon, boolean danger) {
    }

    private final Div menu = Ui.div("fb-menu fb-entry-menu");

    public EntryMenu() {
        setClassName("fb-entry-menu-layer");
        setVisible(false);
        final var backdrop = Ui.div("fb-entry-menu-backdrop");
        backdrop.addClickListener(e -> close());
        backdrop.getElement().addEventListener("contextmenu", e -> close()).preventDefault();
        add(backdrop, menu);
    }

    public void open(Located located, MenuPosition pos, boolean starredMode, boolean starred, boolean readOnly,
                     SerializableBiConsumer<EntryAction, Located> onAction) {
        final var folder = located.entry().folder();
        final var items = new ArrayList<Item>();
        final var star = new Item(EntryAction.STAR, starred ? "Remove from starred" : "Add to starred",
                starred ? "star" : "star-outline", false);
        if (starredMode) {
            final var open = folder ? new Item(EntryAction.OPEN, "Go to folder", "folder", false)
                    : new Item(EntryAction.OPEN, "Go to file", "arrow-right", false);
            final var download = new Item(EntryAction.DOWNLOAD, "Download", "download", false);
            items.add(open);
            items.add(download);
            items.add(null);
            items.add(star);
        } else {
            final var open = folder ? new Item(EntryAction.OPEN, "Open", "folder", false)
                    : new Item(EntryAction.OPEN, "Preview", "eye", false);
            final var download = new Item(EntryAction.DOWNLOAD, "Download", "download", false);
            final var rename = new Item(EntryAction.RENAME, "Rename", "edit", false);
            final var move = new Item(EntryAction.MOVE, "Move to…", "move", false);
            final var duplicate = new Item(EntryAction.COPY, "Duplicate", "copy", false);
            final var delete = new Item(EntryAction.DELETE, "Delete", "trash", true);
            items.add(open);
            items.add(download);
            items.add(null);
            items.addAll(List.of(rename, move, duplicate, star));
            items.add(null);
            items.add(delete);
        }

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
        final var top = Math.max(0, Math.min(pos.y(), pos.viewportHeight() - ASSUMED_MENU_HEIGHT));
        final var left = Math.max(0, Math.min(pos.x(), pos.viewportWidth() - ASSUMED_MENU_WIDTH));
        menu.getStyle().setTop(top + "px").setLeft(left + "px");
        setVisible(true);
    }

    public void close() {
        setVisible(false);
    }
}
