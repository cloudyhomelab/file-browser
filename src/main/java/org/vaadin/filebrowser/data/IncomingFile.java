package org.vaadin.filebrowser.data;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * A file the user picked, dropped or gave as a web link, before it is uploaded.
 *
 * @param clientId the browser's handle for the file's contents, which stay in the browser until uploaded;
 *                 null for a link
 * @param size     bytes, or {@link #UNKNOWN_SIZE} for a link that doesn't say
 * @param url      the web link the server downloads it from; null for a file from the browser
 * @param extract  how to extract it, for an archive the user wants unpacked; null to store it as is
 * @param folder   for a file in an {@link IncomingFolder}, the folders it sits in below that folder; else empty
 */
public record IncomingFile(String clientId, String name, String type, long size, String url, ExtractOptions extract,
                           List<String> folder) implements Serializable {

    public static final long UNKNOWN_SIZE = -1;

    public IncomingFile {
        Objects.requireNonNull(folder, "folder");
    }

    public IncomingFile(String clientId, String name, String type, long size) {
        this(clientId, name, type, size, null, null, List.of());
    }

    public static IncomingFile link(String url, String name, String type, Long size) {
        return new IncomingFile(null, name, type, size == null ? UNKNOWN_SIZE : size, url, null, List.of());
    }

    public IncomingFile withExtract(ExtractOptions options) {
        return new IncomingFile(clientId, name, type, size, url, options, folder);
    }

    public IncomingFile inFolder(List<String> segments) {
        final var copied = List.copyOf(segments);
        return new IncomingFile(clientId, name, type, size, url, extract, copied);
    }

    public boolean fromLink() {
        return url != null;
    }

    /** For display: null when unknown. */
    public Long knownSize() {
        return size < 0 ? null : size;
    }
}
