package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * How a file-system kind is labelled and drawn.
 *
 * @param tint       its colour, one of the tints listed in {@code data/mount-kinds.json} (a {@code fb-tint-*} class)
 * @param usageNote  shown instead of the usage bar when the storage reports none; null for the default
 * @param showSource whether the mount source tells file systems of this kind apart, as for network shares
 */
public record KindInfo(String label, String icon, String tint, String usageNote, boolean showSource)
        implements Serializable {

    /** The class that colours things of this kind. */
    public String tintClass() {
        return "fb-tint-" + tint;
    }
}
