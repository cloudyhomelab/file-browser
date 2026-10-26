package org.vaadin.filebrowser.storage;

import java.io.Serializable;

/**
 * What backs a file system path, as reported by the OS.
 *
 * @param fileStoreType the raw file-store type, e.g. {@code ext4}, {@code nfs4}, {@code cifs}
 * @param source        the mount source, e.g. {@code 10.0.4.5:/exports/team}
 */
public record MountInfo(Kind kind, String fileStoreType, String source) implements Serializable {

    public enum Kind { LOCAL, NFS, SMB, OTHER, S3 }

    /** Nothing could be read; shown like a plain local folder. */
    public static final MountInfo UNKNOWN = new MountInfo(Kind.LOCAL, "", "");
}
