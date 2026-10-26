package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsEntry;

import com.vaadin.flow.component.html.Span;

/** Large page- or folder-shaped tile used in the grid and the details preview. */
public class FileTile extends Span {

    public FileTile(FsEntry entry, Catalog catalog) {
        addClassName("fb-tile");
        if (entry.folder()) {
            addClassName("fb-tile--folder");
            final var folderTile = new Icon("tile-folder");
            add(folderTile);
            return;
        }
        addClassName("fb-type-" + entry.type());
        final var fileTile = new Icon("tile-file");
        final var labelText = catalog.fileType(entry.type()).label();
        final var label = Ui.span("fb-tile-label", labelText);
        add(fileTile, label);
    }
}
