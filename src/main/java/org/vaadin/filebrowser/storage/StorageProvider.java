package org.vaadin.filebrowser.storage;

import java.io.IOException;
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

    /**
     * Renames a file within its folder, replacing a file already called {@code newName}; in one step where the
     * storage can. A folder is never replaced, nor renamed this way.
     */
    void renameReplacing(String path, String newName);

    /** Moves into {@code destinationFolder} under {@code targetName}; never replaces what is already there. */
    void move(String path, String destinationFolder, String targetName);

    /**
     * Copies a file or folder (recursively) into {@code destinationFolder} under {@code targetName}; never replaces
     * what is already there.
     */
    void copy(String path, String destinationFolder, String targetName);

    /** As {@link #copy(String, String, String)}, reporting to {@code progress} where this app streams the bytes. */
    default void copy(String path, String destinationFolder, String targetName, CopyProgress progress) {
        copy(path, destinationFolder, targetName);
    }

    /** Deletes permanently; folders are deleted with their contents. */
    void delete(String path);

    /**
     * Deletes a folder only if it is empty, in the same step as the check where the storage allows it, so a
     * file written into it meanwhile is never lost.
     *
     * @return false when the folder isn't empty and was left alone
     */
    boolean deleteEmptyFolder(String path);

    InputStream openRead(String path);

    /**
     * {@code length} bytes from {@code offset}, fewer where the file ends first. The default reads from the start
     * and skips, which costs a full read up to {@code offset} on remote storage; providers that can start part-way
     * through override it.
     */
    default InputStream openRead(String path, long offset, long length) {
        final var in = openRead(path);
        try {
            in.skipNBytes(offset);
        } catch (IOException e) {
            try {
                in.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            final var name = StoragePaths.segments(path).getLast();
            throw new StorageException("“" + name + "” cannot be read.", e);
        }
        return new LimitedInputStream(in, length);
    }

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
