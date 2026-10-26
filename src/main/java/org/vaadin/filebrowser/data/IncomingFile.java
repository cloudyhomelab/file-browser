package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * A file the user picked, dropped or gave as a web link, before it is uploaded.
 *
 * @param clientId the browser's handle for the file's contents, which stay in the browser until uploaded;
 *                 null for a link
 * @param size     bytes, or {@link #UNKNOWN_SIZE} for a link that doesn't say
 * @param url      the web link the server downloads it from; null for a file from the browser
 */
public record IncomingFile(String clientId, String name, String type, long size, String url) implements Serializable {

    public static final long UNKNOWN_SIZE = -1;

    public IncomingFile(String clientId, String name, String type, long size) {
        this(clientId, name, type, size, null);
    }

    public static IncomingFile link(String url, String name, String type, Long size) {
        return new IncomingFile(null, name, type, size == null ? UNKNOWN_SIZE : size, url);
    }

    public boolean fromLink() {
        return url != null;
    }

    /** For display: null when unknown. */
    public Long knownSize() {
        return size < 0 ? null : size;
    }
}
