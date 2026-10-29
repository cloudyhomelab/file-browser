package org.vaadin.filebrowser.ui;

import java.io.Serializable;
import java.util.List;

import org.vaadin.filebrowser.data.AutoRefresh;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;
import org.vaadin.filebrowser.service.Workspace.Density;
import org.vaadin.filebrowser.service.Workspace.Layout;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.function.SerializableRunnable;

/**
 * Search, sort, auto refresh, density, layout and create/upload actions. The search field is kept across
 * refreshes so typing never loses focus; everything else is re-rendered from {@link State}.
 */
public class Toolbar extends Composite<Div> {

    private static final int SEARCH_DEBOUNCE_MS = 250;

    private final TextField search = new TextField();
    private final Div actions = Ui.div("fb-toolbar-actions");
    private final Callbacks callbacks;

    public record State(Sort sort, AutoRefresh autoRefresh, Density density, Layout layout, boolean readOnly,
                        Component bulkSlot) {
    }

    public record Callbacks(SerializableConsumer<String> onQuery, SerializableConsumer<SortKey> onSort,
                            SerializableConsumer<AutoRefresh> onAutoRefresh, SerializableRunnable onToggleDensity,
                            SerializableConsumer<Layout> onLayout, SerializableRunnable onNewFolder,
                            SerializableRunnable onUpload) implements Serializable {
    }

    public Toolbar(Callbacks callbacks) {
        this.callbacks = callbacks;
        search.addClassNames("fb-field", "fb-search");
        final var searchIcon = new Icon("search");
        search.setPrefixComponent(searchIcon);
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(SEARCH_DEBOUNCE_MS);
        search.setAriaLabel("Search this folder");
        search.setPlaceholder("Search this folder");
        search.addValueChangeListener(e -> callbacks.onQuery().accept(e.getValue()));
        getContent().setClassName("fb-toolbar");
        final var spacer = Ui.spacer();
        getContent().add(search, spacer, actions);
    }

    public void clearSearch() {
        search.clear();
    }

    public void render(State state) {
        actions.removeAll();
        if (state.bulkSlot() != null) {
            final var bulkSeparator = Ui.divider("fb-vsep");
            actions.add(state.bulkSlot(), bulkSeparator);
        }
        final var sort = sortMenu(state.sort());
        final var autoRefresh = autoRefreshMenu(state.autoRefresh());
        actions.add(sort, autoRefresh);
        final var dense = state.density() == Density.DENSE;
        final var densityToggle = Ui.iconButton(dense ? "list" : "menu", dense ? "Comfortable rows" : "Dense rows",
                callbacks.onToggleDensity());
        actions.add(densityToggle);
        final var listOption = Segmented.Option.iconOnly(Layout.LIST, "list", "List");
        final var gridOption = Segmented.Option.iconOnly(Layout.GRID, "grid", "Grid");
        final var layoutToggle = new Segmented<>("fb-seg--view", List.of(listOption, gridOption), state.layout(),
                callbacks.onLayout());
        actions.add(layoutToggle);
        final var separator = Ui.divider("fb-vsep");
        actions.add(separator);
        final var newFolder = Buttons.create(Buttons.Kind.SECONDARY, "New folder", "folder", callbacks.onNewFolder());
        newFolder.setEnabled(!state.readOnly());
        final var upload = Buttons.create(Buttons.Kind.PRIMARY, "Upload", "upload", callbacks.onUpload());
        upload.setEnabled(!state.readOnly());
        actions.add(newFolder, upload);
    }

    private Component sortMenu(Sort sort) {
        final var trigger = new NativeButton();
        trigger.setClassName("fb-ghost-btn");
        final var sortIcon = new Icon("sort");
        final var label = Ui.span("", sort.key().getLabel());
        final var direction = new Icon(sort.ascending() ? "angle-up" : "angle-down");
        trigger.add(sortIcon, label, direction);
        trigger.getElement().setAttribute("aria-label", "Sort by");

        final var popover = new Popover();
        popover.setTarget(trigger);
        popover.setPosition(PopoverPosition.BOTTOM_END);
        popover.addThemeVariants(PopoverVariant.NO_PADDING);
        final var menu = Ui.div("fb-menu fb-sort-menu");
        for (final var key : SortKey.values()) {
            final var keyLabel = Ui.span("fb-grow", key.getLabel());
            final var item = Ui.button("fb-menu-item", () -> {
                popover.close();
                callbacks.onSort().accept(key);
            }, keyLabel);
            if (sort.key() == key) {
                final var markIcon = new Icon(sort.ascending() ? "angle-up" : "angle-down");
                final var mark = Ui.span("fb-menu-item-mark", markIcon);
                item.add(mark);
            }
            menu.add(item);
        }
        popover.add(menu);
        return Ui.span("fb-sort-wrap", trigger, popover);
    }

    private Component autoRefreshMenu(AutoRefresh current) {
        final var trigger = new NativeButton();
        trigger.setClassName("fb-ghost-btn" + (current.on() ? " fb-ghost-btn--on" : ""));
        final var refreshIcon = new Icon("refresh");
        final var label = Ui.span("", current.getLabel());
        trigger.add(refreshIcon, label);
        trigger.getElement().setAttribute("aria-label", "Auto refresh: " + current.getLabel());
        trigger.getElement().setAttribute("title", "Auto refresh");

        final var popover = new Popover();
        popover.setTarget(trigger);
        popover.setPosition(PopoverPosition.BOTTOM_END);
        popover.addThemeVariants(PopoverVariant.NO_PADDING);
        final var menu = Ui.div("fb-menu fb-sort-menu");
        final var heading = Ui.div("fb-menu-heading", Ui.span("", "Auto refresh"));
        menu.add(heading);
        for (final var option : AutoRefresh.values()) {
            final var optionLabel = Ui.span("fb-grow", option == AutoRefresh.OFF ? "Off" : "Every " + option.getLabel());
            final var item = Ui.button("fb-menu-item", () -> {
                popover.close();
                callbacks.onAutoRefresh().accept(option);
            }, optionLabel);
            if (option == current) {
                final var markIcon = new Icon("check");
                final var mark = Ui.span("fb-menu-item-mark", markIcon);
                item.add(mark);
            }
            menu.add(item);
            if (option == AutoRefresh.OFF) {
                final var separator = Ui.divider("fb-menu-sep");
                menu.add(separator);
            }
        }
        popover.add(menu);
        return Ui.span("fb-sort-wrap", trigger, popover);
    }
}
