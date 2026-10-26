package org.vaadin.filebrowser.data;

import java.io.Serializable;

import java.util.List;

/**
 * An entry shown in a list. {@code location} is set when the entry lives outside the folder being
 * browsed (a tree-search hit or a starred item): the folder names from the root to its parent.
 */
public record Located(FsEntry entry, String rootId, List<String> location, String locationLabel) implements Serializable {

    public static Located here(FsEntry entry, String rootId) {
        return new Located(entry, rootId, null, null);
    }

    public boolean elsewhere() {
        return location != null;
    }

    public String key() {
        return rootId + ":" + entry.id();
    }
}
