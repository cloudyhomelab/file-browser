package org.vaadin.filebrowser.storage;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.vaadin.filebrowser.data.Capacity;

/**
 * Access to one file system. Paths are relative to its root; {@code ""} is the root folder. Every
 * method throws {@link StorageException} with a user-facing message when the operation fails.
 */
public interface StorageProvider {

    List<StorageEntry> list(String folder);

    Optional<StorageEntry> stat(String path);

    void createFolder(String parent, String name);

    void rename(String path, String newName);

    void move(String path, String destinationFolder);

    /** Copies a file or folder (recursively) next to the original under {@code targetName}. */
    void copy(String path, String targetName);

    /** Deletes permanently; folders are deleted with their contents. */
    void delete(String path);

    InputStream openRead(String path);

    WriteHandle openWrite(String folder, String name, boolean replace);

    /** Used and total space in GB; total is null when the storage has no fixed size; null when usage isn't known. */
    Capacity capacity();

    /** True when the root can be read right now. */
    boolean healthy();

    /** {@link #healthy()} with the reason it isn't, for storage that can tell. */
    default Health health() {
        return healthy() ? Health.OK : Health.DOWN;
    }

    boolean writable();

    /** What the OS reports behind the root; empty for storage that isn't an OS mount, or when it can't be read. */
    default Optional<MountInfo> mount() {
        return Optional.empty();
    }
}
