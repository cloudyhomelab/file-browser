package org.vaadin.filebrowser.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.SortKey;
import org.vaadin.filebrowser.service.Workspace.Density;
import org.vaadin.filebrowser.storage.StorageEntry;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.dataview.GridListDataView;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;

/** The file table: sortable columns, multi-select, a right-click menu and dragging onto folders. */
public class FileListView extends Composite<Grid<Located>> {

    private static final String NAME_WIDTH = "170px";      // smallest name column
    private static final int NAME_GROW = 22;                // name : owner share of the spare width
    private static final String OWNER_WIDTH = "96px";      // smallest owner column
    private static final int OWNER_GROW = 10;
    private static final String MODIFIED_WIDTH = "108px";
    private static final String SIZE_WIDTH = "78px";
    private static final String ACTIONS_WIDTH = "76px";    // star and "⋯" side by side

    private static final String NAME_TEMPLATE = """
            <span class="fb-row-main">
              <span class="fb-glyph fb-type-${item.type}">
                <span class="fb-icon" aria-hidden="true" ?hidden=${!item.icon}><svg><use href="icons.svg#${item.icon}"></use></svg></span>
                <span class="fb-glyph-label" ?hidden=${!item.glyphLabel}>${item.glyphLabel}</span>
              </span>
              <span class="fb-row-names">
                <span class="fb-row-name">${item.name}</span>
                <span class="fb-row-sub fb-row-loc" ?hidden=${!item.location}>
                  <span class="fb-icon" aria-hidden="true"><svg><use href="icons.svg#folder"></use></svg></span>
                  <span class="fb-ellipsis">${item.location}</span>
                </span>
                <span class="fb-row-sub" ?hidden=${!item.count}>${item.count}</span>
              </span>
            </span>""";

    private static final String OWNER_TEMPLATE = """
            <span class="fb-row-owner">
              <vaadin-avatar class="fb-avatar--xs" theme="filled" .name=${item.owner} .abbr=${item.initials}
                  .colorIndex=${item.colorIndex}></vaadin-avatar>
              <span class="fb-ellipsis">${item.firstName}</span>
            </span>""";

    private final EntryHandler handler;
    private final List<Located> items = new ArrayList<>();
    private final GridListDataView<Located> dataView;
    private final Map<SortKey, Grid.Column<Located>> sortColumns = new EnumMap<>(SortKey.class);
    private Located dragged;
    /** The row holding keyboard focus, which Enter opens. */
    private Located focusedRow;

    public FileListView(List<Located> entries, EntryHandler handler, Density density, Catalog catalog) {
        this.handler = handler;
        final var grid = getContent();
        grid.addClassNames("fb-file-grid", "fb-file-grid--" + density.name().toLowerCase(Locale.ROOT));
        grid.setAriaLabel("Files");
        dataView = grid.setItems(items);
        addColumns(grid, catalog);
        addSorting(grid);
        addClicks(grid);
        if (handler.selectable()) {
            addSelection(grid);
        }
        if (handler.draggable()) {
            addDragging(grid);
        }
        update(entries);
    }

    /** Shows {@code entries} in place, keeping the scroll position. */
    public void update(List<Located> entries) {
        items.clear();
        items.addAll(entries);
        dataView.refreshAll();
        final var sort = handler.sort();
        final var direction = sort.ascending() ? SortDirection.ASCENDING : SortDirection.DESCENDING;
        final var order = new GridSortOrder<>(sortColumns.get(sort.key()), direction);
        getContent().sort(List.of(order));
        if (handler.selectable()) {
            final var selected = Set.copyOf(entries.stream().filter(handler::selected).toList());
            getContent().asMultiSelect().setValue(selected);
        }
    }

    /** Re-applies the focus highlight. */
    public void refreshFocus() {
        dataView.refreshAll();
    }

    /** Grows to show every row instead of scrolling, for a list among others on a page. */
    public void setAllRowsVisible() {
        getContent().setAllRowsVisible(true);
    }

    /** Folders accept drops only while one of our own entries is dragged; see {@link DragMove.Targets}. */
    public void setDropActive(boolean on) {
        getContent().setDropMode(on ? GridDropMode.ON_TOP : null);
    }

    private void addColumns(Grid<Located> grid, Catalog catalog) {
        final var nameRenderer = LitRenderer.<Located>of(NAME_TEMPLATE)
                .withProperty("type", l -> l.entry().type())
                .withProperty("icon", l -> glyphIcon(l.entry(), catalog))
                .withProperty("glyphLabel", l -> glyphLabel(l.entry(), catalog))
                .withProperty("name", l -> l.entry().name())
                .withProperty("location", l -> l.elsewhere() ? l.locationLabel() : "")
                .withProperty("count", FileListView::count);
        final var name = grid.addColumn(nameRenderer).setHeader("Name").setWidth(NAME_WIDTH).setFlexGrow(NAME_GROW);
        final var ownerRenderer = LitRenderer.<Located>of(OWNER_TEMPLATE)
                .withProperty("owner", l -> l.entry().owner())
                .withProperty("initials", l -> Formats.initials(l.entry().owner()))
                .withProperty("colorIndex", l -> UserAvatar.colorIndex(l.entry().owner()))
                .withProperty("firstName", l -> Formats.firstName(l.entry().owner()));
        grid.addColumn(ownerRenderer).setHeader("Owner").setWidth(OWNER_WIDTH).setFlexGrow(OWNER_GROW);
        final var modified = grid.addColumn(l -> Formats.modified(l.entry().modified())).setHeader("Modified")
                .setWidth(MODIFIED_WIDTH).setFlexGrow(0).setPartNameGenerator(l -> "fb-meta");
        final var size = grid.addColumn(l -> l.entry().folder() ? "—" : Formats.size(l.entry().size())).setHeader("Size")
                .setWidth(SIZE_WIDTH).setFlexGrow(0).setTextAlign(ColumnTextAlign.END).setPartNameGenerator(l -> "fb-meta fb-size");
        final var actionsRenderer = new ComponentRenderer<Span, Located>(this::actions);
        grid.addColumn(actionsRenderer).setWidth(ACTIONS_WIDTH).setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
        sortColumns.put(SortKey.NAME, name);
        sortColumns.put(SortKey.MODIFIED, modified);
        sortColumns.put(SortKey.SIZE, size);
    }

    private void addSorting(Grid<Located> grid) {
        // the handler sorts; the columns only show and change which key is active
        sortColumns.values().forEach(c -> c.setComparator((a, b) -> 0));
        grid.addSortListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            final var orders = e.getSortOrder();
            final var key = orders.isEmpty() ? handler.sort().key() : keyOf(orders.getFirst().getSorted());
            handler.sortBy(key);
        });
    }

    private void addClicks(Grid<Located> grid) {
        grid.setPartNameGenerator(l -> handler.focused(l) ? "fb-focused" : null);
        grid.addItemClickListener(e -> handler.selectRow(e.getItem()));
        grid.addItemDoubleClickListener(e -> handler.open(e.getItem()));
        grid.addCellFocusListener(e -> focusedRow = e.getItem().orElse(null));
        Shortcuts.addShortcutListener(grid, () -> {
            if (focusedRow != null) {
                handler.open(focusedRow);
            }
        }, Key.ENTER).listenOn(grid);
        final var menu = grid.addContextMenu();
        menu.setDynamicContentHandler(l -> {
            if (l == null) {
                return false;
            }
            handler.fillContextMenu(menu, l);
            return true;
        });
    }

    private void addSelection(Grid<Located> grid) {
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        // a selection copied from the handler in update() is not from the client, so it is not reported back
        grid.asMultiSelect().addSelectionListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            final var added = e.getAddedSelection();
            final var removed = e.getRemovedSelection();
            if (added.size() + removed.size() > 1) {
                handler.toggleAll(!added.isEmpty());
                return;
            }
            added.forEach(handler::toggleSelected);
            removed.forEach(handler::toggleSelected);
        });
    }

    private void addDragging(Grid<Located> grid) {
        grid.setRowsDraggable(true);
        grid.setDropFilter(l -> l.entry().folder());
        grid.addDragStartListener(e -> {
            dragged = e.getDraggedItems().getFirst();
            handler.dragStarted(dragged);
        });
        grid.addDragEndListener(e -> handler.dragEnded());
        grid.addDropListener(e -> e.getDropTargetItem()
                .filter(folder -> dragged != null)
                .ifPresent(folder -> handler.dropOnto(dragged, folder)));
    }

    private Span actions(Located located) {
        final var starred = handler.starred(located);
        final var star = new StarToggle(starred, () -> handler.toggleStar(located));
        final var dots = Ui.iconButton("dots", "More actions");
        dots.addClassName("fb-icon-btn--s");
        dots.addClickListener(e -> handler.contextMenu(located, dots));
        return Ui.span("fb-row-actions", star, dots);
    }

    private SortKey keyOf(Grid.Column<Located> column) {
        return sortColumns.entrySet().stream().filter(e -> e.getValue().equals(column)).map(Map.Entry::getKey)
                .findFirst().orElseGet(() -> handler.sort().key());
    }

    private static String glyphIcon(StorageEntry entry, Catalog catalog) {
        if (entry.folder()) {
            return "folder";
        }
        final var icon = catalog.fileType(entry.type()).icon();
        return icon == null ? "" : icon;
    }

    private static String glyphLabel(StorageEntry entry, Catalog catalog) {
        if (entry.folder()) {
            return "";
        }
        final var info = catalog.fileType(entry.type());
        return info.icon() == null ? info.label() : "";
    }

    /** A folder's item count, unless the row shows where a starred entry lives instead. */
    private static String count(Located located) {
        final var entry = located.entry();
        if (located.elsewhere() || !entry.folder() || entry.childCount() == null) {
            return "";
        }
        return Formats.plural(entry.childCount(), "item");
    }
}
