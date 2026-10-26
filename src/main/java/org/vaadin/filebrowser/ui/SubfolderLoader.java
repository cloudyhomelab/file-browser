package org.vaadin.filebrowser.ui;

import java.io.Serializable;
import java.util.List;

/** Reads the names of the folders directly inside a folder, on demand. */
@FunctionalInterface
public interface SubfolderLoader extends Serializable {

    /** @param folder segments from the root; empty for the root itself */
    List<String> subfolders(List<String> folder);
}
