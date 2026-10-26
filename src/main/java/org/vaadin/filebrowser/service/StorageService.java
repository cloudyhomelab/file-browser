package org.vaadin.filebrowser.service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.storage.LocalDiskProvider;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StorageProvider;

/** Hands out the storage provider for each configured file system. */
@Service
public class StorageService {

    private final Catalog catalog;
    private final Map<String, StorageProvider> providers = new ConcurrentHashMap<>();

    public StorageService(Catalog catalog) {
        this.catalog = catalog;
    }

    public StorageProvider provider(FsRoot root) {
        final var cacheKey = root.id() + "|" + root.endpoint();
        return providers.computeIfAbsent(cacheKey, k -> create(root));
    }

    private StorageProvider create(FsRoot root) {
        return switch (root.type()) {
            case DISK -> new LocalDiskProvider(Path.of(root.endpoint()), catalog::typeForFileName,
                    type -> MountInfo.Kind.OTHER);
            case NFS, S3 -> throw new StorageException("This kind of file system isn't supported yet.");
        };
    }
}
