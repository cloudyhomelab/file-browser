package org.vaadin.filebrowser.data;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What admins allow on one file system besides its access. Each file system stores its own, copied
 * from {@code data/root-options.json} when it is added.
 *
 * @param linkUploads       files may be uploaded from web links
 * @param extraction        archives may be extracted and files decompressed as they are uploaded
 * @param opening           files may be shown in the page
 * @param maxConnections    the most connections (sessions, HTTP connections, open files) used at once
 * @param browsingReserve   how many of them only short operations may use, so streams never take them all
 * @param idleStreamSeconds a file shown in the page that nobody reads from this long is closed
 */
public record RootOptions(@JsonProperty("link_uploads") boolean linkUploads,
                          @JsonProperty("extraction") boolean extraction,
                          @JsonProperty("opening") boolean opening,
                          @JsonProperty("max_connections") int maxConnections,
                          @JsonProperty("browsing_reserve") int browsingReserve,
                          @JsonProperty("idle_stream_seconds") int idleStreamSeconds) implements Serializable {

    /** How many connections reads, writes and copies may use together. */
    public int streamConnections() {
        return maxConnections - browsingReserve;
    }
}
