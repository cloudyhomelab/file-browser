package org.vaadin.filebrowser.ui;

import java.io.Serializable;

import org.vaadin.filebrowser.data.Located;
import org.vaadin.filebrowser.data.Sort;
import org.vaadin.filebrowser.data.SortKey;

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

    void contextMenu(Located entry, MenuPosition position);

    void toggleStar(Located entry);

    Sort sort();

    void sortBy(SortKey key);
}
