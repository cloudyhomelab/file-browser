package org.vaadin.filebrowser.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.SortKey;
import org.vaadin.filebrowser.service.Workspace.Density;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/** The design's table: sortable header, rows with hover-revealed checkbox, star and menu. */
public class FileListView extends Composite<Div> {

    private final EntryHandler handler;
    private final Map<Located, Div> rows;
    private final DragMove.Targets dropTargets = new DragMove.Targets();

    public FileListView(List<Located> entries, EntryHandler handler, Density density, Catalog catalog) {
        this.handler = handler;
        this.rows = LinkedHashMap.newLinkedHashMap(entries.size());
        final var table = getContent();
        table.addClassNames("fb-table", "fb-table--" + density.name().toLowerCase(Locale.ROOT));
        table.getElement().getClassList().set("fb-table--selectable", handler.selectable());
        table.getElement().setAttribute("role", "table");

        final var header = Ui.div("fb-table-row fb-table-head");
        header.getElement().setAttribute("role", "row");
        if (handler.selectable()) {
            final var all = !entries.isEmpty() && entries.stream().allMatch(handler::selected);
            final var some = entries.stream().anyMatch(handler::selected);
            final var allCheck = Ui.checkbox(all, "Select all", () -> handler.toggleAll(!all));
            allCheck.setIndeterminate(!all && some);
            header.add(allCheck);
        }
        final var nameHead = colHead("Name", SortKey.NAME, handler);
        final var ownerHead = Ui.span("fb-col-label", "Owner");
        final var modifiedHead = colHead("Modified", SortKey.MODIFIED, handler);
        final var sizeHead = colHead("Size", SortKey.SIZE, handler);
        final var actionsHead = Ui.span("");
        header.add(nameHead, ownerHead, modifiedHead, sizeHead, actionsHead);
        table.add(header);

        for (final var entry : entries) {
            final var row = row(entry, handler, catalog, dropTargets);
            rows.put(entry, row);
            table.add(row);
        }
    }

    /**
     * Re-applies the focus highlight without rebuilding rows, so a double-click still lands on
     * the element that received the first click.
     */
    public void refreshFocus() {
        rows.forEach((entry, row) -> row.getElement().getClassList().set("fb-row--focused", handler.focused(entry)));
    }

    public void setDropActive(boolean on) {
        dropTargets.setActive(on);
    }

    private static Component colHead(String label, SortKey key, EntryHandler handler) {
        final var sort = handler.sort();
        final var active = sort.key() == key;
        final var up = Ui.span("fb-sort-up" + (active && sort.ascending() ? " fb-sort--on" : ""));
        final var down = Ui.span("fb-sort-down" + (active && !sort.ascending() ? " fb-sort--on" : ""));
        final var labelText = Ui.span("", label);
        final var arrows = Ui.span("fb-sort-arrows", up, down);
        return Ui.button("fb-col-head" + (active ? " fb-col-head--active" : "")
                + (key == SortKey.SIZE ? " fb-col-head--end" : ""), () -> handler.sortBy(key), labelText, arrows);
    }

    private static Div row(Located located, EntryHandler handler, Catalog catalog, DragMove.Targets dropTargets) {
        final var entry = located.entry();
        final var selected = handler.selectable() && handler.selected(located);
        final var starred = handler.starred(located);
        final var row = Ui.div("fb-table-row fb-row");
        row.getElement().setAttribute("role", "row");
        row.getElement().getClassList().set("fb-row--selected", selected);
        row.getElement().getClassList().set("fb-row--focused", handler.focused(located));

        if (handler.selectable()) {
            final var check = Ui.checkbox(selected, "Select " + entry.name(), () -> handler.toggleSelected(located));
            final var checkCell = Ui.span("fb-row-check", check);
            row.add(checkCell);
        }

        final var names = names(located);
        final var glyph = new FileGlyph(entry.type(), catalog);
        final var main = Ui.div("fb-row-main", glyph, names);
        row.add(main);
        final var avatar = new UserAvatar(entry.owner(), UserAvatar.Size.XS);
        final var firstName = Formats.firstName(entry.owner());
        final var ownerName = Ui.span("fb-ellipsis", firstName);
        final var owner = Ui.div("fb-row-owner", avatar, ownerName);
        row.add(owner);
        final var modifiedText = Formats.modified(entry.modified());
        final var modified = Ui.span("fb-row-meta", modifiedText);
        row.add(modified);
        final var sizeText = entry.folder() ? "—" : Formats.size(entry.size());
        final var size = Ui.span("fb-row-meta fb-mono fb-row-size", sizeText);
        row.add(size);

        final var star = new StarToggle(starred, () -> handler.toggleStar(located));
        star.getElement().getClassList().set("fb-reveal", !starred);
        final var dotsIcon = new Icon("dots");
        final var dots = Ui.button("fb-icon-btn fb-icon-btn--s fb-reveal", () -> { }, dotsIcon);
        dots.getElement().setAttribute("aria-label", "More actions");
        dots.getElement().addEventListener("click", e -> handler.contextMenu(located, dots));
        final var actions = Ui.span("fb-row-actions", star, dots);
        row.add(actions);

        EntryListeners.listen(row, dots, located, handler, dropTargets);
        return row;
    }

    /** The name, with where it is for a starred entry shown away from its folder, or a folder's item count. */
    private static Span names(Located located) {
        final var entry = located.entry();
        final var name = Ui.span("fb-row-name", entry.name());
        final var names = Ui.span("fb-row-names", name);
        if (located.elsewhere()) {
            final var folderIcon = new Icon("folder");
            final var locationText = Ui.span("fb-ellipsis", located.locationLabel());
            final var location = Ui.span("fb-row-sub fb-row-loc", folderIcon, locationText);
            names.add(location);
        } else if (entry.folder()) {
            final var itemCount = Ui.span("fb-row-sub", entry.childCount() == null ? "" : Formats.plural(entry.childCount(), "item"));
            names.add(itemCount);
        }
        return names;
    }
}
