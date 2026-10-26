package org.vaadin.filebrowser.data;

import java.io.Serializable;

/** An archive or compressed file the upload can extract; the name suffixes are in {@code data/archive-formats.json}. */
public enum ArchiveFormat {
    ZIP, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, TAR_LZ, SEVEN_Z,
    /** Single compressed files: they hold one file, named after the archive without its suffix. */
    GZ, BZ2, XZ, LZ;

    public boolean singleFile() {
        return this == GZ || this == BZ2 || this == XZ || this == LZ;
    }

    /** What an upload's name says about it: the format, and the name with the format's suffix removed. */
    public record Match(ArchiveFormat format, String baseName) implements Serializable {
    }
}
