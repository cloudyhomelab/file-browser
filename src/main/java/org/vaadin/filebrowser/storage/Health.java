package org.vaadin.filebrowser.storage;

import java.io.Serializable;

/**
 * Whether a file system's root can be read right now.
 *
 * @param reason why not, in words for users, when the storage said; null otherwise
 */
public record Health(boolean ok, String reason) implements Serializable {

    public static final Health OK = new Health(true, null);
    /** Unreadable, for no reason the storage gave. */
    public static final Health DOWN = new Health(false, null);

    public static Health down(String reason) {
        return new Health(false, reason);
    }
}
