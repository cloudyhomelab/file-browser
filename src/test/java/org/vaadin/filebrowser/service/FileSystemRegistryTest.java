package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Capacity;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.storage.MountInfo;

class FileSystemRegistryTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @TempDir
    Path dir;

    private static final StorageService.RootStatus ONLINE =
            new StorageService.RootStatus(new Capacity(10, 100L), true, MountInfo.UNKNOWN);

    private JdbcClient jdbc;

    @BeforeEach
    void migrate() {
        final var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        final var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        final var flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    private FileSystemRegistry registry(boolean admin) {
        final var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new FileSystemRegistry(jdbc, () -> admin, () -> "admin@example.com", clock, root -> ONLINE);
    }

    private static FsRoot root(String id, String endpoint) {
        return new FsRoot(id, "Projects", RootType.DISK, endpoint, "node-1", null, true, Access.READ_ONLY,
                MountInfo.UNKNOWN);
    }

    @Test
    void addedFileSystemsAreVisibleToEveryRegistryOnTheSameDatabase() {
        registry(true).add(root("a", "/srv/projects"));

        final var other = registry(false);
        assertThat(other.all()).extracting(FsRoot::id).containsExactly("a");
        final var found = other.find("a").orElseThrow();
        assertThat(found.name()).isEqualTo("Projects");
        assertThat(found.type()).isEqualTo(RootType.DISK);
        assertThat(found.endpoint()).isEqualTo("/srv/projects");
        assertThat(found.access()).isEqualTo(Access.READ_ONLY);
        assertThat(found.online()).isTrue();
        assertThat(found.capacity()).isEqualTo(new Capacity(10, 100L));
        assertThat(other.endpointTaken("/srv/projects")).isTrue();
        assertThat(other.endpointTaken("/srv/other")).isFalse();
    }

    @Test
    void recordsWhoAddedItAndWhen() {
        registry(true).add(root("a", "/srv/projects"));
        final var createdBy = jdbc.sql("SELECT created_by FROM file_system").query(String.class).single();
        final var createdAt = jdbc.sql("SELECT created_at FROM file_system").query(String.class).single();
        assertThat(createdBy).isEqualTo("admin@example.com");
        assertThat(createdAt).isEqualTo(NOW.toString());
    }

    @Test
    void nonAdminsCannotAdd() {
        final var regular = registry(false);
        final var attempt = root("a", "/srv/projects");
        assertThatThrownBy(() -> regular.add(attempt)).isInstanceOf(AccessDeniedException.class);
        assertThat(regular.all()).isEmpty();
    }

    @Test
    void endpointsAreUnique() {
        final var admin = registry(true);
        admin.add(root("a", "/srv/projects"));
        final var duplicate = root("b", "/srv/projects");
        assertThatThrownBy(() -> admin.add(duplicate)).isInstanceOf(DataAccessException.class);
        assertThat(admin.all()).extracting(FsRoot::id).containsExactly("a");
    }

    @Test
    void updateChangesOnlyNameAndAccess() {
        final var admin = registry(true);
        admin.add(root("a", "/srv/projects"));
        admin.update("a", "Team projects", Access.READ_WRITE);

        final var found = admin.find("a").orElseThrow();
        assertThat(found.name()).isEqualTo("Team projects");
        assertThat(found.access()).isEqualTo(Access.READ_WRITE);
        assertThat(found.endpoint()).isEqualTo("/srv/projects");
        assertThat(found.region()).isEqualTo("node-1");
    }

    @Test
    void removeUnregistersAndDropsStarsButKeepsFiles() throws Exception {
        final var folder = Files.createDirectory(dir.resolve("projects"));
        Files.writeString(folder.resolve("brief.txt"), "brief");
        final var admin = registry(true);
        admin.add(root("a", folder.toString()));
        final var userData = new UserData(jdbc);
        userData.star("alice@example.com", "a", "brief.txt");

        admin.remove("a");

        assertThat(admin.all()).isEmpty();
        assertThat(userData.starred("alice@example.com")).isEmpty();
        assertThat(folder.resolve("brief.txt")).hasContent("brief");
    }

    @Test
    void nonAdminsCannotUpdateOrRemove() {
        registry(true).add(root("a", "/srv/projects"));
        final var regular = registry(false);
        assertThatThrownBy(() -> regular.update("a", "Renamed", Access.READ_WRITE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> regular.remove("a")).isInstanceOf(AccessDeniedException.class);
        final var unchanged = regular.find("a").orElseThrow();
        assertThat(unchanged.name()).isEqualTo("Projects");
        assertThat(unchanged.access()).isEqualTo(Access.READ_ONLY);
    }
}
