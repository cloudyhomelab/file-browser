package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.storage.StorageException;

/** A view keeps the {@link FsRoot} it opened with; changes an admin makes later must still apply. */
class WorkspaceTest {

    @TempDir
    Path dir;

    private Path folder;
    private FileSystemRegistry registry;
    private Workspace workspace;
    private FsRoot held;

    @BeforeEach
    void setUp() throws IOException {
        final var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        final var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        final var jdbc = JdbcClient.create(dataSource);

        final var catalog = new Catalog();
        final var storage = new StorageService(catalog);
        registry = new FileSystemRegistry(jdbc, () -> true, () -> "admin@example.com", Clock.systemUTC(),
                storage::status);
        // no one is signed in during the test, so nothing is saved per user
        final var currentUser = new CurrentUser(null, null);
        final var s3Connections = new S3Connections(jdbc, new SecretBox((String) null));
        final var links = new LinkFetcher(address -> false, SystemDefaultDnsResolver.INSTANCE);
        workspace = new Workspace(catalog, registry, storage, s3Connections, links, new UserData(jdbc), currentUser);

        folder = Files.createDirectory(dir.resolve("projects"));
        Files.writeString(folder.resolve("notes.txt"), "notes");
        final var definition = new FsRoot("a", "Projects", RootType.DISK, folder.toString(), "", null, true,
                Access.READ_WRITE, MountInfo.UNKNOWN);
        registry.add(definition);
        held = registry.find("a").orElseThrow();
    }

    @Test
    void writesAreRefusedOnceTheFileSystemIsMadeReadOnly() {
        registry.update("a", "Projects", Access.READ_ONLY);

        assertThatThrownBy(() -> workspace.createFolder(held, List.of(), "Research"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("read only");
        final var file = new IncomingFile("c1", "upload.txt", "text", 5);
        final var planned = List.of(new UploadQueue.Planned(file, "upload.txt", false));
        assertThatThrownBy(() -> workspace.upload(held, List.of(), planned, Long.MAX_VALUE)).isInstanceOf(StorageException.class);
        assertThat(folder.resolve("Research")).doesNotExist();
        assertThat(workspace.list(held, List.of())).extracting(e -> e.name()).containsExactly("notes.txt");
    }

    @Test
    void aRemovedFileSystemCanNoLongerBeListedOrChanged() {
        registry.remove("a");

        assertThatThrownBy(() -> workspace.list(held, List.of()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("removed");
        assertThatThrownBy(() -> workspace.createFolder(held, List.of(), "Research"))
                .isInstanceOf(StorageException.class);
        assertThat(folder.resolve("notes.txt")).hasContent("notes");
    }

    @Test
    void currentReflectsARename() {
        registry.update("a", "Team projects", Access.READ_WRITE);
        assertThat(workspace.current(held).name()).isEqualTo("Team projects");
    }

    @Test
    void onlyAdminsMayBrowseTheServersFolders() {
        // no one is signed in here, so the current user is not an admin
        assertThatThrownBy(() -> workspace.serverFolders(dir.toString())).isInstanceOf(AccessDeniedException.class);
    }
}
