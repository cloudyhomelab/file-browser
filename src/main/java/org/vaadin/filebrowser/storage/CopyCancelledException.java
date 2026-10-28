package org.vaadin.filebrowser.storage;

/** A streamed copy was stopped through its {@link CopyProgress}; what it wrote is already removed. */
public class CopyCancelledException extends StorageException {

    public CopyCancelledException(String name) {
        super("The copy of “" + name + "” was cancelled.");
    }
}
