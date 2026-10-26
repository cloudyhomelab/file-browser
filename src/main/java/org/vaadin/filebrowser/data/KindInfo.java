package org.vaadin.filebrowser.data;

import java.io.Serializable;

/** How a file-system kind is labelled and drawn. */
public record KindInfo(String label, String icon) implements Serializable {
}
