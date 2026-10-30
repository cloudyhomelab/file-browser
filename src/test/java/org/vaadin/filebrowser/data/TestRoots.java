package org.vaadin.filebrowser.data;

/** Options for file systems built in tests, as {@code data/root-options.json} sets them. */
public final class TestRoots {

    public static final int MAX_CONNECTIONS = 4;
    public static final int BROWSING_RESERVE = 1;
    public static final int IDLE_STREAM_SECONDS = 30;
    public static final RootOptions ALL_ALLOWED = features(true, true, true);

    private TestRoots() {
    }

    /** The default connection limits with these features. */
    public static RootOptions features(boolean linkUploads, boolean extraction, boolean opening) {
        return new RootOptions(linkUploads, extraction, opening, MAX_CONNECTIONS, BROWSING_RESERVE,
                IDLE_STREAM_SECONDS);
    }
}
