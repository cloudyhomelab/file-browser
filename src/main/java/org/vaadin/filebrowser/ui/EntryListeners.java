package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Located;

import com.vaadin.flow.component.ClickNotifier;
import com.vaadin.flow.component.Component;

/** What a card or row does when clicked, double-clicked, right-clicked or dragged; the same in every layout. */
final class EntryListeners {

    private EntryListeners() {
    }

    static <C extends Component & ClickNotifier<C>> void listen(C component, Located located, EntryHandler handler, DragMove.Targets dropTargets) {
        final var entry = located.entry();
        component.addClickListener(e -> {
            if (entry.folder()) {
                handler.open(located);
            } else {
                handler.selectRow(located);
            }
        });
        component.addDoubleClickListener(e -> {
            if (!entry.folder()) {
                handler.open(located);
            }
        });
        MenuPosition.onContextMenu(component, pos -> handler.contextMenu(located, pos));
        if (handler.draggable()) {
            DragMove.source(component, located, handler);
            if (entry.folder()) {
                dropTargets.add(component, dragged -> handler.dropOnto(dragged, located));
            }
        }
    }
}
