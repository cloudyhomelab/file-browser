package org.vaadin.filebrowser.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One kind of storage a file system can live on. Implementations are Spring components, looked up
 * by {@link #key()}: the value of {@code file_system.type} and the key of the type's entry in
 * {@code data/root-types.json}.
 */
public interface StorageType {

    String key();

    /** @throws StorageException when the configuration can't describe a file system of this type */
    StorageProvider open(StorageConfig config);

    /**
     * Why a file system opened from a new configuration can't be used, or null when it can; asked
     * before it is added. Throws {@link UntrustedServerException} for a server whose key or
     * certificate must be confirmed first.
     */
    default String problem(StorageProvider provider) {
        provider.list("");
        return null;
    }

    /** The kind shown to users, a key of {@code data/mount-kinds.json}; {@code mount} is empty while offline. */
    default String kindKey(Map<String, String> settings, Optional<MountInfo> mount) {
        return key();
    }

    /** Every kind {@link #kindKey} returns without a mount, so a missing one is found at startup. */
    default List<String> kinds() {
        return List.of(key());
    }

    /** What tells file systems of this type apart besides their endpoint (a mount source, a region); blank for nothing. */
    default String detail(Map<String, String> settings, Optional<MountInfo> mount) {
        return "";
    }
}
