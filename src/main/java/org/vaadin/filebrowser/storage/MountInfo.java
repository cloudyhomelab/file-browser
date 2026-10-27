package org.vaadin.filebrowser.storage;

import java.io.Serializable;

/**
 * What backs a file system path, as reported by the OS.
 *
 * @param kind          the kind it is shown as, a key of {@code data/mount-kinds.json}
 * @param fileStoreType the raw file-store type, e.g. {@code ext4}, {@code nfs4}, {@code cifs}
 * @param source        the mount source, e.g. {@code 10.0.4.5:/exports/team}
 */
public record MountInfo(String kind, String fileStoreType, String source) implements Serializable {
}
