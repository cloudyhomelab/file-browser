package org.vaadin.filebrowser.storage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.data.Catalog;

/** A folder on the server: a local disk or a share the OS has mounted (NFS, SMB, …). */
@Component
public class DiskStorageType implements StorageType {

    public static final String KEY = "disk";

    private final Catalog catalog;

    public DiskStorageType(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public StorageProvider open(StorageConfig config) {
        return new LocalDiskProvider(Path.of(config.endpoint()), catalog::typeForFileName, catalog::mountKind);
    }

    // the add form has checked the folder as it was typed; listing a large share could take long
    @Override
    public String problem(StorageProvider provider) {
        return provider.healthy() ? null : "The server can't read this folder.";
    }

    // nothing detected is shown as a plain local disk
    @Override
    public String kindKey(Optional<MountInfo> mount) {
        return mount.map(MountInfo::kind).orElse(KEY);
    }

    @Override
    public String detail(Map<String, String> settings, Optional<MountInfo> mount) {
        return mount.filter(found -> catalog.kind(found.kind()).showSource())
                .map(MountInfo::source)
                .orElse("");
    }
}
