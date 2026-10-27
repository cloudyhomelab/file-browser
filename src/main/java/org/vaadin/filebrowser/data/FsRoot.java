package org.vaadin.filebrowser.data;

import java.io.Serializable;
import java.util.Map;

/**
 * A configured file system with its current status.
 *
 * @param type     the key of its {@link org.vaadin.filebrowser.storage.StorageType}
 * @param settings what its type needs besides the endpoint, without secrets
 */
public record FsRoot(String id, String name, String type, String endpoint, Map<String, String> settings,
                     Access access, RootStatus status) implements Serializable {

    public FsRoot {
        settings = Map.copyOf(settings);
    }

    /** null when unknown or unavailable */
    public Capacity capacity() {
        return status.capacity();
    }

    public boolean online() {
        return status.online();
    }

    /** Key for the kind shown to users ({@code disk}, {@code other}, {@code nfs}, {@code smb}, {@code s3}, …). */
    public String kindKey() {
        return status.kind();
    }

    public boolean locked() {
        return access == Access.LOCKED;
    }

    public boolean readOnly() {
        return access == Access.READ_ONLY;
    }

    public boolean browsable() {
        return !locked() && online();
    }
}
