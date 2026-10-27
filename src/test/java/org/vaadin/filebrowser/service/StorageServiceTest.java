package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.StorageConfig;

class StorageServiceTest {

    @TempDir
    Path dir;

    private final Catalog catalog = new Catalog();
    private final StorageService storage = new StorageService(new StorageTypes(List.of(new DiskStorageType(catalog)), catalog));

    private static FsRoot root(Path path) {
        return new FsRoot("id-" + path.getFileName(), "Test", DiskStorageType.KEY, path.toString(), Map.of(),
                Access.READ_WRITE, RootStatus.UNKNOWN);
    }

    @Test
    void reportsAReadableFolderAsOnlineWithCapacity() {
        final var status = storage.status(root(dir));
        assertThat(status.online()).isTrue();
        assertThat(status.capacity()).isNotNull();
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
    void listsReadableServerFoldersWithoutFilesOrHiddenFolders() throws IOException {
        Files.createDirectories(dir.resolve("projects/design"));
        Files.createDirectory(dir.resolve("Archive"));
        Files.createDirectory(dir.resolve(".cache"));
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.createSymbolicLink(dir.resolve("shared"), dir.resolve("projects"));

        assertThat(storage.serverFolders(dir.toString())).containsExactly("Archive", "projects", "shared");
        assertThat(storage.serverFolders(dir.resolve("projects").toString())).containsExactly("design");
        assertThat(storage.serverFolders(dir.resolve("missing").toString())).isEmpty();
        assertThat(storage.serverFolders("relative/path")).isEmpty();
    }

    @Test
    void showsTheKindItsTypeReportsEvenWhileOffline() {
        assertThat(storage.status(root(dir)).kind()).isEqualTo("disk");
        assertThat(storage.status(root(dir.resolve("missing"))).kind()).isEqualTo("disk");
    }

    @Test
    void reportsAFileSystemOfAnUnknownTypeAsOffline() {
        final var gone = new FsRoot("g", "Gone", "ftp", "ftp://host", Map.of(), Access.READ_WRITE, RootStatus.UNKNOWN);
        final var status = storage.status(gone);
        assertThat(status.online()).isFalse();
        assertThat(status.kind()).isEqualTo("unsupported");
    }

    @Test
    void checksANewFileSystemBeforeItIsAdded() {
        final var config = new StorageConfig(dir.toString(), Map.of(), Map.of());
        assertThat(storage.check(DiskStorageType.KEY, config)).isNull();
        final var missing = new StorageConfig(dir.resolve("missing").toString(), Map.of(), Map.of());
        assertThat(storage.check(DiskStorageType.KEY, missing)).isNotNull();
        assertThat(storage.check("ftp", config)).isNotNull();
    }

    @Test
    void mapsNetworkFileStoreTypesToMountKinds() {
        assertThat(catalog.mountKind("nfs4")).isEqualTo("nfs");
        assertThat(catalog.mountKind("cifs")).isEqualTo("smb");
        assertThat(catalog.mountKind("ext4")).isEqualTo("disk");
        assertThat(catalog.mountKind("fuse.sshfs")).isEqualTo("other");
    }
}
