package org.vaadin.filebrowser.storage;

/** A storage operation failed; the message is safe to show to the user. */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
