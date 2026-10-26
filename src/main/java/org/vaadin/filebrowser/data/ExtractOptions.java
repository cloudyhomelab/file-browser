package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * How the user wants an uploaded archive extracted.
 *
 * @param newFolder   true: into a new folder named after the archive; false: into the upload's folder
 * @param keepArchive also store the archive file itself
 * @param clash       for entries whose name already exists, when extracting into the upload's folder
 */
public record ExtractOptions(boolean newFolder, boolean keepArchive, Clash clash) implements Serializable {

    /** What to do with an extracted entry whose name already exists. */
    public enum Clash { KEEP_BOTH, REPLACE, SKIP }

    public static final ExtractOptions DEFAULT = new ExtractOptions(true, false, Clash.KEEP_BOTH);
}
