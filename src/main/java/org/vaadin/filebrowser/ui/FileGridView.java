package org.vaadin.filebrowser.ui;

import java.util.List;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.Located;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;

public class FileGridView extends Composite<Div> {

    public FileGridView(List<Located> entries, EntryHandler handler, Catalog catalog) {
        getContent().setClassName("fb-cards");
        for (final var located : entries) {
            final var card = card(located, handler, catalog);
            getContent().add(card);
        }
    }

    private static Div card(Located located, EntryHandler handler, Catalog catalog) {
        final var entry = located.entry();
        final var selected = handler.selectable() && handler.selected(located);
        final var starred = handler.starred(located);
        final var card = Ui.div("fb-card");
        card.getElement().getClassList().set("fb-card--selected", selected);
        card.getElement().getClassList().set("fb-card--starred", starred);

        if (handler.selectable()) {
            final var check = new CheckMark(selected, false, () -> handler.toggleSelected(located));
            final var checkCorner = Ui.span("fb-card-check", check);
            card.add(checkCorner);
        }
        if (starred) {
            final var starIcon = new Icon("star");
            final var starMark = Ui.span("fb-card-starmark", starIcon);
            card.add(starMark);
        }
        final var dotsIcon = new Icon("dots");
        final var dots = Ui.button("fb-icon-btn fb-icon-btn--s", () -> { }, dotsIcon);
        dots.getElement().setAttribute("aria-label", "More actions");
        MenuPosition.onClick(dots, pos -> handler.contextMenu(located, pos));
        final var star = new StarToggle(starred, () -> handler.toggleStar(located));
        final var actions = Ui.span("fb-card-actions", star, dots);
        card.add(actions);

        final var sub = located.elsewhere() ? located.locationLabel()
                : entry.folder() ? entry.children().size() + " items" : Formats.size(entry.size());
        final var tile = new FileTile(entry, catalog);
        final var tileFrame = Ui.span("fb-card-tile", tile);
        final var name = Ui.span("fb-card-name", entry.name());
        final var subLine = Ui.span("fb-card-sub", sub);
        final var text = Ui.div("fb-card-text", name, subLine);
        card.add(tileFrame, text);

        card.addClickListener(e -> {
            if (entry.folder()) {
                handler.open(located);
            } else {
                handler.selectRow(located);
            }
        });
        card.addDoubleClickListener(e -> {
            if (!entry.folder()) {
                handler.open(located);
            }
        });
        MenuPosition.onContextMenu(card, pos -> handler.contextMenu(located, pos));
        return card;
    }
}
