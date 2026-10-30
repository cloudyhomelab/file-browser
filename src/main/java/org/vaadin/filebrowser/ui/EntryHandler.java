package org.vaadin.filebrowser.ui;

import java.io.Serializable;

import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;

/** What the list and grid views ask of the page that hosts them. */
public interface EntryHandler extends Serializable {

    boolean selectable();

    boolean selected(Located entry);

    boolean focused(Located entry);

    boolean starred(Located entry);

    void toggleSelected(Located entry);

    void toggleAll(boolean on);

    /** Folder click, file double-click. */
    void open(Located entry);

    /** File single click. */
    void selectRow(Located entry);

    /** @param anchor the entry's "⋯" button, which the menu opens at */
    void contextMenu(Located entry, Component anchor);

    /** Fills the list's right-click menu for {@code entry} just before it opens. */
    void fillContextMenu(GridContextMenu<Located> menu, Located entry);

    void toggleStar(Located entry);

    Sort sort();

    void sortBy(SortKey key);

    /** Whether entries can be dragged onto folders to move them. */
    default boolean draggable() {
        return false;
    }

    default void dragStarted(Located dragged) {
        // only views that move entries by dragging react
    }

    default void dragEnded() {
        // only views that move entries by dragging react
    }

    /** {@code dragged} was dropped onto the folder {@code folder}. */
    default void dropOnto(Located dragged, Located folder) {
        // only views that move entries by dragging react
    }
}
