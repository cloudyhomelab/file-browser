package org.vaadin.filebrowser.data;

import java.io.Serializable;

/** Sizes in GB; {@code total} is null for metered object storage. */
public record Capacity(long used, Long total) implements Serializable {

    public Integer percentUsed() {
        if (total == null || total == 0) {
            return null;
        }
        return (int) Math.min(100, Math.round(used * 100.0 / total));
    }
}
