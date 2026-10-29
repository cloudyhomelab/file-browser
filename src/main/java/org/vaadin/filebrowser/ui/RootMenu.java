package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.FsRoot;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.function.SerializableConsumer;

/** The admin "⋯" menu of a drive card: edit, settings and remove. Looks like {@link EntryMenu}. */
public class RootMenu extends Div {

    private static final int ASSUMED_MENU_HEIGHT = 140;
    private static final int ASSUMED_MENU_WIDTH = 220;

    private final Div menu = Ui.div("fb-menu fb-entry-menu");

    public RootMenu() {
        setClassName("fb-entry-menu-layer");
        setVisible(false);
        final var backdrop = Ui.div("fb-entry-menu-backdrop");
        backdrop.addClickListener(e -> close());
        backdrop.getElement().addEventListener("contextmenu", e -> close()).preventDefault();
        add(backdrop, menu);
    }

    /** @param onEdit null leaves out "Edit…", e.g. for a type the app can no longer edit */
    public void open(FsRoot root, MenuPosition pos, SerializableConsumer<FsRoot> onEdit,
                     SerializableConsumer<FsRoot> onSettings, SerializableConsumer<FsRoot> onRemove) {
        menu.removeAll();
        if (onEdit != null) {
            final var editIcon = new Icon("edit");
            final var editLabel = Ui.span("", "Edit…");
            final var edit = Ui.button("fb-menu-item", () -> {
                close();
                onEdit.accept(root);
            }, editIcon, editLabel);
            menu.add(edit);
        }
        final var settingsIcon = new Icon("cog");
        final var settingsLabel = Ui.span("", "Settings…");
        final var settings = Ui.button("fb-menu-item", () -> {
            close();
            onSettings.accept(root);
        }, settingsIcon, settingsLabel);
        final var separator = Ui.divider("fb-menu-sep");
        menu.add(settings, separator);
        final var removeIcon = new Icon("trash");
        final var removeLabel = Ui.span("", "Remove…");
        final var remove = Ui.button("fb-menu-item fb-menu-item--danger", () -> {
            close();
            onRemove.accept(root);
        }, removeIcon, removeLabel);
        menu.add(remove);

        final var top = Math.max(0, Math.min(pos.y(), pos.viewportHeight() - ASSUMED_MENU_HEIGHT));
        final var left = Math.max(0, Math.min(pos.x(), pos.viewportWidth() - ASSUMED_MENU_WIDTH));
        menu.getStyle().setTop(top + "px").setLeft(left + "px");
        setVisible(true);
    }

    public void close() {
        setVisible(false);
    }
}
