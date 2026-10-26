package org.vaadin.filebrowser.data;

import java.io.Serializable;
import java.util.List;

import org.vaadin.filebrowser.storage.StorageEntry;

/**
 * An entry shown in a list. {@code location} is set when the entry lives outside the folder being
 * browsed (a starred item): the folder names from the root to its parent.
 */
public record Located(StorageEntry entry, String rootId, List<String> location, String locationLabel)
        implements Serializable {

    public static Located here(StorageEntry entry, String rootId) {
        return new Located(entry, rootId, null, null);
    }

    public boolean elsewhere() {
        return location != null;
    }

    public String key() {
        return rootId + ":" + entry.path();
    }
}
