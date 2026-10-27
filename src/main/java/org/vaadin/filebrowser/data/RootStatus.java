package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * What a file system's storage reports right now, and how it is shown because of it.
 *
 * @param capacity null when unknown or unavailable
 * @param kind     the kind shown to users, a key of {@code data/mount-kinds.json}
 * @param detail   what tells it apart besides its endpoint (a mount source, a region); blank for nothing
 */
public record RootStatus(Capacity capacity, boolean online, String kind, String detail)
        implements Serializable {

    /** Before the storage has been asked, e.g. for a file system that is being added. */
    public static final RootStatus UNKNOWN = new RootStatus(null, false, "", "");
}
