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
import org.vaadin.filebrowser.data.TestRoots;
import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.FtpStorageType;
import org.vaadin.filebrowser.storage.Health;
import org.vaadin.filebrowser.storage.LocalDiskProvider;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.storage.StorageProvider;
import org.vaadin.filebrowser.storage.UntrustedServerException;

class StorageServiceTest {

    @TempDir
    Path dir;

    private final Catalog catalog = new Catalog();
    private final StorageService storage = new StorageService(new StorageTypes(List.of(new DiskStorageType(catalog)), catalog));

    private static FsRoot root(Path path) {
        return new FsRoot("id-" + path.getFileName(), "Test", DiskStorageType.KEY, path.toString(), Map.of(),
                Access.READ_WRITE, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
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
        final var gone = new FsRoot("g", "Gone", "ftp", "ftp://host", Map.of(), Access.READ_WRITE,
                TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        final var status = storage.status(gone);
        assertThat(status.online()).isFalse();
        assertThat(status.kind()).isEqualTo("unsupported");
    }

    @Test
    void checksANewFileSystemBeforeItIsAdded() {
        final var config = new StorageConfig(dir.toString(), Map.of(), Map.of());
        assertThat(storage.check(DiskStorageType.KEY, config)).isEqualTo(StorageService.Check.OK);
        final var missing = new StorageConfig(dir.resolve("missing").toString(), Map.of(), Map.of());
        assertThat(storage.check(DiskStorageType.KEY, missing).problem()).isNotNull();
        assertThat(storage.check("tape", config).problem()).isNotNull();
    }

    @Test
    void mapsNetworkFileStoreTypesToMountKinds() {
        assertThat(catalog.mountKind("nfs4")).isEqualTo("nfs");
        assertThat(catalog.mountKind("cifs")).isEqualTo("smb");
        assertThat(catalog.mountKind("ext4")).isEqualTo("disk");
        assertThat(catalog.mountKind("fuse.sshfs")).isEqualTo("other");
    }

    @Test
    void anUntrustedServerIsReportedWithItsFingerprint() {
        final var ftp = new FtpStorageType(catalog) {
            @Override
            public StorageProvider open(StorageConfig config) {
                throw new UntrustedServerException("SHA256:abc");
            }
        };
        final var withFtp = new StorageService(new StorageTypes(List.of(ftp), catalog));
        final var check = withFtp.check(FtpStorageType.KEY, new StorageConfig("ftp://example.com", Map.of(), Map.of()));
        assertThat(check.fingerprint()).isEqualTo("SHA256:abc");
        assertThat(check.problem()).isNotNull();
    }

    @Test
    void showsAnFtpServerAsFtpsUnlessItIsPlain() {
        final var withFtp = new StorageService(new StorageTypes(List.of(new FtpStorageType(catalog)), catalog));
        final var tls = new FsRoot("f1", "Shop", FtpStorageType.KEY, "ftp://127.0.0.1:1/x",
                Map.of(FtpStorageType.ANONYMOUS, "true"), Access.READ_ONLY, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        final var plain = new FsRoot("f2", "Shop", FtpStorageType.KEY, "ftp://127.0.0.1:1/x",
                Map.of(FtpStorageType.SECURITY, "none"), Access.READ_ONLY, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        assertThat(withFtp.status(tls)).extracting(RootStatus::kind, RootStatus::detail, RootStatus::online)
                .containsExactly("ftps", "Anonymous", false);
        assertThat(withFtp.status(plain).kind()).isEqualTo("ftp");
    }

    @Test
    void anOfflineFileSystemShowsTheReasonItsStorageGave() {
        final var down = new FtpStorageType(catalog) {
            @Override
            public StorageProvider open(StorageConfig config) {
                return new LocalDiskProvider(dir, name -> "generic", store -> "disk") {
                    @Override
                    public Health health() {
                        return Health.down("The server's host key changed.");
                    }
                };
            }
        };
        final var withDown = new StorageService(new StorageTypes(List.of(down), catalog));
        final var root = new FsRoot("f3", "Shop", FtpStorageType.KEY, "ftp://example.com/x", Map.of(),
                Access.READ_ONLY, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        assertThat(withDown.status(root)).extracting(RootStatus::online, RootStatus::reason)
                .containsExactly(false, "The server's host key changed.");
    }
}
