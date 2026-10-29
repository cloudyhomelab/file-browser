package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.FtpStorageType;
import org.vaadin.filebrowser.storage.S3StorageType;
import org.vaadin.filebrowser.storage.SftpStorageType;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.storage.StorageProvider;
import org.vaadin.filebrowser.storage.StorageType;

class StorageTypesTest {

    private final Catalog catalog = new Catalog();

    private static StorageType type(String key, String kind) {
        return new StorageType() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public StorageProvider open(StorageConfig config) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> kinds() {
                return List.of(key, kind);
            }
        };
    }

    @Test
    void theBuiltInTypesAreFullyDescribed() {
        final var types = new StorageTypes(List.of(new DiskStorageType(catalog), new S3StorageType(catalog),
                new SftpStorageType(catalog), new FtpStorageType(catalog)), catalog);
        assertThat(types.find(DiskStorageType.KEY)).isPresent();
        assertThat(types.find(S3StorageType.KEY)).isPresent();
        assertThat(types.find(SftpStorageType.KEY)).isPresent();
        assertThat(types.find(FtpStorageType.KEY)).isPresent();
        assertThat(types.find("tape")).isEmpty();
    }

    @Test
    void aTypeMissingFromTheDataFilesStopsStartup() {
        assertThatThrownBy(() -> new StorageTypes(List.of(type("tape", "tape")), catalog))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root-types.json");
        // described as a root type, but shown as a kind that has no label or icon
        assertThatThrownBy(() -> new StorageTypes(List.of(type(DiskStorageType.KEY, "tape")), catalog))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mount-kinds.json");
    }
}
