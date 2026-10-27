package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * What a file system's storage reports right now, and how it is shown because of it.
 *
 * @param capacity null when unknown or unavailable
 * @param kind     the kind shown to users, a key of {@code data/mount-kinds.json}
 * @param detail   what tells it apart besides its endpoint (a mount source, a region); blank for nothing
 * @param reason   why it is offline, when the storage said; null otherwise
 */
public record RootStatus(Capacity capacity, boolean online, String kind, String detail, String reason)
        implements Serializable {

    public RootStatus(Capacity capacity, boolean online, String kind, String detail) {
        this(capacity, online, kind, detail, null);
    }

    /** Before the storage has been asked, e.g. for a file system that is being added. */
    public static final RootStatus UNKNOWN = new RootStatus(null, false, "", "");
}
