package org.vaadin.filebrowser.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.RootTypeInfo;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StorageType;

/**
 * Every {@link StorageType} the app knows. Each must be described in {@code data/root-types.json},
 * and every kind it shows in {@code data/mount-kinds.json}; a missing
 * entry stops the app at startup rather than breaking a page later.
 */
@Component
public class StorageTypes {

    private final Map<String, StorageType> types;
    private final Catalog catalog;

    public StorageTypes(List<StorageType> types, Catalog catalog) {
        this.catalog = catalog;
        this.types = types.stream().collect(Collectors.toUnmodifiableMap(StorageType::key, Function.identity()));
        for (final var type : types) {
            final var info = catalog.rootType(type.key());
            if (info == null) {
                throw missing(type, "data/root-types.json", type.key());
            }
            if (info.checkTimeout() == null || info.checkFailed() == null) {
                throw missing(type, "data/root-types.json", "checkTimeout and checkFailed of " + type.key());
            }
            for (final var kind : type.kinds()) {
                if (catalog.kind(kind) == null) {
                    throw missing(type, "data/mount-kinds.json", kind);
                }
            }
        }
    }

    private static IllegalStateException missing(StorageType type, String file, String entry) {
        return new IllegalStateException("Storage type " + type.key() + " needs \"" + entry + "\" in " + file);
    }

    public StorageType get(String key) {
        return find(key).orElseThrow(() -> new StorageException("This kind of file system is no longer supported."));
    }

    /** How {@code key} is described in {@code data/root-types.json}; known for every type found here. */
    public RootTypeInfo info(String key) {
        return catalog.rootType(key);
    }

    public Optional<StorageType> find(String key) {
        return Optional.ofNullable(types.get(key));
    }
}
