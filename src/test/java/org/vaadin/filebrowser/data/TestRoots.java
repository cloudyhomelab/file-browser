package org.vaadin.filebrowser.data;

/** Options for file systems built in tests, as {@code data/root-options.json} sets them. */
public final class TestRoots {

    public static final RootOptions ALL_ALLOWED = new RootOptions(true, true, true);

    private TestRoots() {
    }
}
