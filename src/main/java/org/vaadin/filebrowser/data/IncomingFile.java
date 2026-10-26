package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * A file the user picked or dropped, before it is uploaded.
 *
 * @param clientId the browser's handle for the file's contents, which stay in the browser until uploaded
 */
public record IncomingFile(String clientId, String name, String type, long size) implements Serializable {
}
