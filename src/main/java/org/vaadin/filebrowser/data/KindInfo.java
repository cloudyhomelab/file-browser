package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * How a file-system kind is labelled and drawn.
 *
 * @param usageNote  shown instead of the usage bar when the storage reports none; null for the default
 * @param showSource whether the mount source tells file systems of this kind apart, as for network shares
 */
public record KindInfo(String label, String icon, String usageNote, boolean showSource) implements Serializable {
}
