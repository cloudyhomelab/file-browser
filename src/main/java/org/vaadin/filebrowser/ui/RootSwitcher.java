package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

public class RootSwitcher extends Composite<Div> {

    public RootSwitcher(FsRoot current, List<FsRoot> browsable, Catalog catalog,
                        SerializableConsumer<FsRoot> onPick, SerializableRunnable onAll) {
        final var trigger = new NativeButton();
        trigger.setClassName("fb-switcher");
        final var chip = RootChip.of(current, catalog, RootChip.Size.S);
        final var name = Ui.span("fb-switcher-name", current.name());
        final var typeName = catalog.kind(current).label();
        final var type = Ui.span("fb-switcher-type", typeName);
        final var text = Ui.span("fb-switcher-text", name, type);
        final var caretIcon = new Icon("angle-down");
        final var caret = Ui.span("fb-switcher-caret", caretIcon);
        trigger.add(chip, text, caret);
        trigger.getElement().setAttribute("aria-label", "Switch file system");

        final var popover = new Popover();
        popover.setTarget(trigger);
        popover.setPosition(PopoverPosition.BOTTOM_START);
        popover.addThemeVariants(PopoverVariant.NO_PADDING);
        popover.addClassName("fb-switcher-popover");
        popover.addOpenedChangeListener(e -> trigger.getElement().getClassList().set("fb-open", e.isOpened()));

        final var menu = Ui.div("fb-menu fb-switcher-menu");
        for (final var root : browsable) {
            final var active = root.id().equals(current.id());
            final var itemChip = RootChip.of(root, catalog, RootChip.Size.XS);
            final var itemName = Ui.span("fb-switcher-item-name", root.name());
            final var itemEndpoint = Ui.span("fb-switcher-item-endpoint", root.endpoint());
            final var itemText = Ui.span("fb-switcher-text", itemName, itemEndpoint);
            final var item = Ui.button("fb-switcher-item" + (active ? " fb-switcher-item--active" : ""), () -> {
                popover.close();
                if (!active) {
                    onPick.accept(root);
                }
            }, itemChip, itemText);
            if (root.readOnly()) {
                final var readOnlyBadge = new Badge("RO");
                readOnlyBadge.addThemeVariants(BadgeVariant.WARNING);
                item.add(readOnlyBadge);
            }
            if (active) {
                final var checkIcon = new Icon("check");
                final var check = Ui.span("fb-switcher-check", checkIcon);
                item.add(check);
            }
            menu.add(item);
        }
        final var separator = Ui.divider("fb-menu-sep");
        final var gridIcon = new Icon("grid");
        final var allLabel = Ui.span("", "All file systems");
        final var all = Ui.button("fb-menu-item fb-menu-item--muted", () -> {
            popover.close();
            onAll.run();
        }, gridIcon, allLabel);
        menu.add(separator, all);
        popover.add(menu);

        getContent().setClassName("fb-switcher-wrap");
        getContent().add(trigger, popover);
    }
}
