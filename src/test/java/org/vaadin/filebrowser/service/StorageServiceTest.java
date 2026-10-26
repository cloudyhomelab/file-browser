package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.storage.MountInfo;

class StorageServiceTest {

    @TempDir
    Path dir;

    private final Catalog catalog = new Catalog();
    private final StorageService storage = new StorageService(catalog);

    private static FsRoot root(Path path) {
        return new FsRoot("id-" + path.getFileName(), "Test", RootType.DISK, path.toString(), "", null, false,
                Access.READ_WRITE, MountInfo.UNKNOWN);
    }

    @Test
    void reportsAReadableFolderAsOnlineWithCapacityAndMount() {
        final var status = storage.status(root(dir));
        assertThat(status.online()).isTrue();
        assertThat(status.capacity()).isNotNull();
        assertThat(status.mount().fileStoreType()).isNotBlank();
    }

    @Test
    void reportsAMissingFolderAsOffline() {
        final var status = storage.status(root(dir.resolve("missing")));
        assertThat(status.online()).isFalse();
        assertThat(status.capacity()).isNull();
    }

    @Test
    void probesPathsBeforeTheyAreRegistered() throws IOException {
        final var file = Files.writeString(dir.resolve("file.txt"), "x");
        assertThat(storage.probe(dir.toString())).isEqualTo(new StorageService.PathProbe(true, true, true, true));
        assertThat(storage.probe(file.toString()).directory()).isFalse();
        assertThat(storage.probe(dir.resolve("missing").toString()).exists()).isFalse();
        assertThat(storage.probe("relative/path").exists()).isFalse();
    }

    @Test
    void mapsNetworkFileStoreTypesToMountKinds() {
        assertThat(catalog.mountKind("nfs4")).isEqualTo(MountInfo.Kind.NFS);
        assertThat(catalog.mountKind("cifs")).isEqualTo(MountInfo.Kind.SMB);
        assertThat(catalog.mountKind("ext4")).isEqualTo(MountInfo.Kind.LOCAL);
        assertThat(catalog.mountKind("fuse.sshfs")).isEqualTo(MountInfo.Kind.OTHER);
    }
}
