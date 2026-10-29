package org.vaadin.filebrowser.storage;

import java.io.OutputStream;

/** A file being written: data goes to {@link #getStream()}, and only {@link #commit()} makes it visible. */
public interface WriteHandle extends AutoCloseable {

    OutputStream getStream();

    /** Puts the written file in place, replacing an existing one only if the write was opened with replace. */
    void commit();

    /** Discards whatever was written; safe to call after commit. */
    void abort();

    @Override
    default void close() {
        abort();
    }
}
