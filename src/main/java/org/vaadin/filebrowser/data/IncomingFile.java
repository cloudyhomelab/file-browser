package org.vaadin.filebrowser.data;

import java.io.Serializable;

/** A file offered for upload: a sample file or the metadata of a file the user dropped. */
public record IncomingFile(String name, String type, long size) implements Serializable {
}
