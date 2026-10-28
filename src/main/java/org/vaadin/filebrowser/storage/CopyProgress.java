package org.vaadin.filebrowser.storage;

/**
 * Watches a copy that this app streams: it hears about each chunk written and can stop the copy.
 * Copies the storage server does itself report nothing and can't be stopped part-way.
 */
public interface CopyProgress {

    CopyProgress NONE = new CopyProgress() {
        @Override
        public void copied(long bytes) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    /** {@code bytes} more were written. */
    void copied(long bytes);

    /** {@code bytes} more of a copy were read back to verify it. */
    default void verified(long bytes) {
    }

    boolean cancelled();
}
