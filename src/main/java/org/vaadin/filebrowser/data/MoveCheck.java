package org.vaadin.filebrowser.data;

import java.util.Collection;
import java.util.List;

import org.vaadin.filebrowser.storage.StoragePaths;

/** Whether items can be moved into a folder of the same file system. */
public enum MoveCheck {
    OK, SAME_FOLDER, INTO_ITSELF;

    /**
     * @param from         the folder the items are in
     * @param movedFolders paths of the folders among the items
     */
    public static MoveCheck of(List<String> from, Collection<String> movedFolders, List<String> destination) {
        final var dest = StoragePaths.join(destination);
        if (movedFolders.stream().anyMatch(f -> dest.equals(f) || dest.startsWith(f + StoragePaths.SEPARATOR))) {
            return INTO_ITSELF;
        }
        return dest.equals(StoragePaths.join(from)) ? SAME_FOLDER : OK;
    }
}
