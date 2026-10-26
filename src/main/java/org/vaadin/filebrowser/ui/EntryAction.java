package org.vaadin.filebrowser.ui;

public enum EntryAction {
    OPEN, DOWNLOAD, RENAME, MOVE, COPY, STAR, DELETE;

    /** Actions that change the file system and are refused on read-only roots. */
    public boolean writes() {
        return this == RENAME || this == MOVE || this == COPY || this == DELETE;
    }
}
