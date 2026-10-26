package org.vaadin.filebrowser.data;

import java.io.Serializable;

public record FsRoot(String id, String name, RootType type, String endpoint, String region,
                     Capacity capacity, boolean online, Access access) implements Serializable {

    public boolean locked() {
        return access == Access.LOCKED;
    }

    public boolean readOnly() {
        return access == Access.READ_ONLY;
    }

    public boolean browsable() {
        return !locked() && online;
    }
}
