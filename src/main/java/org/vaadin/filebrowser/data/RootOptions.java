package org.vaadin.filebrowser.data;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What admins allow on one file system besides its access. Each file system stores its own, copied
 * from {@code data/root-options.json} when it is added.
 *
 * @param linkUploads files may be uploaded from web links
 * @param extraction  archives may be extracted and files decompressed as they are uploaded
 * @param opening     files may be shown in the page
 */
public record RootOptions(@JsonProperty("link_uploads") boolean linkUploads,
                          @JsonProperty("extraction") boolean extraction,
                          @JsonProperty("opening") boolean opening) implements Serializable {
}
