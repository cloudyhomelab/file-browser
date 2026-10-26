package org.vaadin.filebrowser.ui;

import java.util.List;

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
 * Search, sort, density, layout and create/upload actions. The search field is kept across
 * refreshes so typing never loses focus; everything else is re-rendered from {@link State}.
 */
public class Toolbar extends Composite<Div> {

    public enum Scope { FOLDER, TREE }

    public record State(Sort sort, Density density, Layout layout, Scope scope, boolean readOnly, Component bulkSlot) {
    }

    public record Callbacks(SerializableConsumer<String> onQuery, SerializableConsumer<Scope> onScope,
                            SerializableConsumer<SortKey> onSort, SerializableRunnable onToggleDensity,
                            SerializableConsumer<Layout> onLayout, SerializableRunnable onNewFolder,
                            SerializableRunnable onUpload) {
    }

    private static final int SEARCH_DEBOUNCE_MS = 250;
    private static final List<Segmented.Option<Scope>> SCOPES = List.of(
            new Segmented.Option<>(Scope.FOLDER, "Folder", null, "Search the current folder"),
            new Segmented.Option<>(Scope.TREE, "All files", null, "Search the entire file system"));

    private final TextField search = new TextField();
    private final Div actions = Ui.div("fb-toolbar-actions");
    private final Callbacks callbacks;

    public Toolbar(Callbacks callbacks) {
        this.callbacks = callbacks;
        search.addClassNames("fb-field", "fb-search");
        final var searchIcon = new Icon("search");
        search.setPrefixComponent(searchIcon);
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(SEARCH_DEBOUNCE_MS);
        search.setAriaLabel("Search");
        search.addValueChangeListener(e -> callbacks.onQuery().accept(e.getValue()));
        getContent().setClassName("fb-toolbar");
        final var spacer = Ui.spacer();
        getContent().add(search, spacer, actions);
    }

    public void clearSearch() {
        search.clear();
    }

    public void render(State state) {
        search.setPlaceholder(state.scope() == Scope.TREE ? "Search all files" : "Search this folder");
        final var scopeToggle = new Segmented<>("fb-seg--s fb-search-scope", SCOPES, state.scope(), callbacks.onScope());
        search.setSuffixComponent(scopeToggle);

        actions.removeAll();
        if (state.bulkSlot() != null) {
            final var bulkSeparator = Ui.divider("fb-vsep");
            actions.add(state.bulkSlot(), bulkSeparator);
        }
        final var sort = sortMenu(state.sort());
        actions.add(sort);
        final var dense = state.density() == Density.DENSE;
        final var densityToggle = Ui.iconButton(dense ? "list" : "menu", dense ? "Comfortable rows" : "Dense rows",
                callbacks.onToggleDensity());
        actions.add(densityToggle);
        final var listOption = Segmented.Option.icon(Layout.LIST, "list", "List");
        final var gridOption = Segmented.Option.icon(Layout.GRID, "grid", "Grid");
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
        final var label = Ui.span("", sort.key().label());
        final var direction = new Icon(sort.ascending() ? "angle-up" : "angle-down");
        trigger.add(sortIcon, label, direction);
        trigger.getElement().setAttribute("aria-label", "Sort by");

        final var popover = new Popover();
        popover.setTarget(trigger);
        popover.setPosition(PopoverPosition.BOTTOM_END);
        popover.addThemeVariants(PopoverVariant.NO_PADDING);
        final var menu = Ui.div("fb-menu fb-sort-menu");
        for (final var key : SortKey.values()) {
            final var keyLabel = Ui.span("fb-grow", key.label());
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
}
