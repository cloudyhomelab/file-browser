package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.ui.components.ItemTile;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;

public class FileGridView extends Composite<Div> {

    private final DragMove.Targets dropTargets = new DragMove.Targets();

    public FileGridView(List<Located> entries, EntryHandler handler, Catalog catalog) {
        getContent().setClassName("fb-cards");
        for (final var located : entries) {
            final var card = card(located, handler, catalog, dropTargets);
            getContent().add(card);
        }
    }

    public void setDropActive(boolean on) {
        dropTargets.setActive(on);
    }

    private static ItemTile card(Located located, EntryHandler handler, Catalog catalog, DragMove.Targets dropTargets) {
        final var entry = located.entry();
        final var selected = handler.selectable() && handler.selected(located);
        final var starred = handler.starred(located);
        final var fileTile = new FileTile(entry, catalog);
        final var sub = subtitle(located);
        final var card = new ItemTile(fileTile, entry.name(), sub);
        card.setSelected(selected);

        if (handler.selectable()) {
            final var check = Ui.checkbox(selected, "Select " + entry.name(), () -> handler.toggleSelected(located));
            card.setStartControl(check);
        }
        if (starred) {
            final var starIcon = new Icon("star");
            card.setStartIndicator(starIcon);
        }
        final var dots = Ui.iconButton("dots", "More actions");
        dots.addClassName("fb-icon-btn--s");
        dots.addClickListener(e -> handler.contextMenu(located, dots));
        final var star = new StarToggle(starred, () -> handler.toggleStar(located));
        card.setEndControls(star, dots);

        EntryListeners.listen(card, dots, located, handler, dropTargets);
        return card;
    }

    /** Where the entry is, for a starred one shown away from its folder; otherwise its size or item count. */
    private static String subtitle(Located located) {
        final var entry = located.entry();
        if (located.elsewhere()) {
            return located.locationLabel();
        }
        if (!entry.folder()) {
            return Formats.size(entry.size());
        }
        return entry.childCount() == null ? "" : Formats.plural(entry.childCount(), "item");
    }
}
