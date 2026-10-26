package org.vaadin.filebrowser.storage;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * One file or folder in a file system, identified by its path relative to the file system root
 * ({@code "Projects/brief.docx"}; segments joined with {@code /}).
 *
 * @param size       bytes for files, null for folders
 * @param owner      the OS-level owner where the storage has one, else null
 * @param childCount entries directly inside a folder, null for files or when unreadable
 */
public record StorageEntry(String path, String name, boolean folder, String type, Long size, Instant modified,
                           String owner, Integer childCount) implements Serializable {

    public static final String FOLDER_TYPE = "folder";

    /** Segments of the folder this entry lives in; empty for entries at the root. */
    public List<String> parent() {
        final var segments = StoragePaths.segments(path);
        return segments.subList(0, segments.size() - 1);
    }
}
