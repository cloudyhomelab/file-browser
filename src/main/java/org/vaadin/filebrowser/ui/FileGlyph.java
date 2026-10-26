package org.vaadin.filebrowser.ui;

import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.storage.StorageEntry;

import com.vaadin.flow.component.html.Span;

/** Small square type marker used in list rows and dialogs. Size comes from CSS. */
public class FileGlyph extends Span {

    public FileGlyph(String type, Catalog catalog) {
        addClassNames("fb-glyph", "fb-type-" + type);
        if (StorageEntry.FOLDER_TYPE.equals(type)) {
            final var folderIcon = new Icon("folder");
            add(folderIcon);
            return;
        }
        final var info = catalog.fileType(type);
        if (info.icon() != null) {
            final var typeIcon = new Icon(info.icon());
            add(typeIcon);
        } else {
            final var label = Ui.span("fb-glyph-label", info.label());
            add(label);
        }
    }
}
