package org.vaadin.filebrowser.data;

import java.io.Serializable;

import org.vaadin.filebrowser.storage.MountInfo;

/**
 * A configured file system with its current status.
 *
 * @param capacity null when unknown or unavailable
 * @param mount    what the OS reports behind the path; {@link MountInfo#UNKNOWN} when not yet known
 */
public record FsRoot(String id, String name, RootType type, String endpoint, String region,
                     Capacity capacity, boolean online, Access access, MountInfo mount) implements Serializable {

    public boolean locked() {
        return access == Access.LOCKED;
    }

    public boolean readOnly() {
        return access == Access.READ_ONLY;
    }

    public boolean browsable() {
        return !locked() && online;
    }

    /** Key for the kind shown to users ({@code disk}, {@code other}, {@code nfs}, {@code smb}, {@code s3}). */
    public String kindKey() {
        if (type == RootType.S3) {
            return "s3";
        }
        return switch (mount.kind()) {
            case NFS -> "nfs";
            case SMB -> "smb";
            case OTHER -> "other";
            case LOCAL, S3 -> "disk";
        };
    }
}
