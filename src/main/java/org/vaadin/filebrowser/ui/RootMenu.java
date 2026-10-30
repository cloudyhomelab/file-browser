package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.FsRoot;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.function.SerializableConsumer;

/** The admin "⋯" menu of a drive card: edit, settings and remove. One instance serves every card on the page. */
public class RootMenu extends Popover {

    private final Div menu = Ui.div("fb-menu fb-root-menu");

    public RootMenu() {
        setPosition(PopoverPosition.BOTTOM_END);
        addThemeVariants(PopoverVariant.NO_PADDING);
        setAriaRole("menu");
        setAriaLabel("File system actions");
        // opened from the card's button, which also has to stop the click from opening the card
        setOpenOnClick(false);
        add(menu);
    }

    /** @param onEdit null leaves out "Edit…", e.g. for a type the app can no longer edit */
    public void open(FsRoot root, Component anchor, SerializableConsumer<FsRoot> onEdit,
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

        setTarget(anchor);
        open();
    }
}
