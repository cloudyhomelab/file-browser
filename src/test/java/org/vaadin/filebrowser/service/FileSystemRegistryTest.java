package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

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
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.data.TestRoots;
import org.vaadin.filebrowser.storage.DiskStorageType;

class FileSystemRegistryTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @TempDir
    Path dir;

    private static final RootStatus ONLINE = new RootStatus(new Capacity(10, 100L), true, "disk", "");

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
        return new FsRoot(id, "Projects", DiskStorageType.KEY, endpoint, Map.of("node", "node-1"), Access.READ_ONLY,
                TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
    }

    @Test
    void addedFileSystemsAreVisibleToEveryRegistryOnTheSameDatabase() {
        registry(true).add(root("a", "/srv/projects"), Map.of());

        final var other = registry(false);
        assertThat(other.all()).extracting(FsRoot::id).containsExactly("a");
        final var found = other.find("a").orElseThrow();
        assertThat(found.name()).isEqualTo("Projects");
        assertThat(found.type()).isEqualTo(DiskStorageType.KEY);
        assertThat(found.endpoint()).isEqualTo("/srv/projects");
        assertThat(found.access()).isEqualTo(Access.READ_ONLY);
        assertThat(found.online()).isTrue();
        assertThat(found.capacity()).isEqualTo(new Capacity(10, 100L));
        assertThat(other.endpointTaken("/srv/projects")).isTrue();
        assertThat(other.endpointTaken("/srv/other")).isFalse();
    }

    @Test
    void recordsWhoAddedItAndWhen() {
        registry(true).add(root("a", "/srv/projects"), Map.of());
        final var createdBy = jdbc.sql("SELECT created_by FROM file_system").query(String.class).single();
        final var createdAt = jdbc.sql("SELECT created_at FROM file_system").query(String.class).single();
        assertThat(createdBy).isEqualTo("admin@example.com");
        assertThat(createdAt).isEqualTo(NOW.toString());
    }

    @Test
    void nonAdminsCannotAdd() {
        final var regular = registry(false);
        final var attempt = root("a", "/srv/projects");
        assertThatThrownBy(() -> regular.add(attempt, Map.of())).isInstanceOf(AccessDeniedException.class);
        assertThat(regular.all()).isEmpty();
    }

    @Test
    void endpointsAreUnique() {
        final var admin = registry(true);
        admin.add(root("a", "/srv/projects"), Map.of());
        final var duplicate = root("b", "/srv/projects");
        assertThatThrownBy(() -> admin.add(duplicate, Map.of())).isInstanceOf(DataAccessException.class);
        assertThat(admin.all()).extracting(FsRoot::id).containsExactly("a");
    }

    @Test
    void updateChangesOnlyNameAndAccess() {
        final var admin = registry(true);
        admin.add(root("a", "/srv/projects"), Map.of());
        admin.update("a", "Team projects", Access.READ_WRITE);

        final var found = admin.find("a").orElseThrow();
        assertThat(found.name()).isEqualTo("Team projects");
        assertThat(found.access()).isEqualTo(Access.READ_WRITE);
        assertThat(found.endpoint()).isEqualTo("/srv/projects");
        assertThat(found.settings()).containsExactly(Map.entry("node", "node-1"));
    }

    @Test
    void removeUnregistersAndDropsStarsButKeepsFiles() throws Exception {
        final var folder = Files.createDirectory(dir.resolve("projects"));
        Files.writeString(folder.resolve("brief.txt"), "brief");
        final var admin = registry(true);
        admin.add(root("a", folder.toString()), Map.of());
        final var userData = new UserData(jdbc);
        userData.star("alice@example.com", "a", "brief.txt");

        admin.remove("a");

        assertThat(admin.all()).isEmpty();
        assertThat(userData.starred("alice@example.com")).isEmpty();
        assertThat(folder.resolve("brief.txt")).hasContent("brief");
    }

    @Test
    void nonAdminsCannotUpdateOrRemove() {
        registry(true).add(root("a", "/srv/projects"), Map.of());
        final var regular = registry(false);
        assertThatThrownBy(() -> regular.update("a", "Renamed", Access.READ_WRITE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> regular.remove("a")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> regular.updateOptions("a", new RootOptions(false, false, false)))
                .isInstanceOf(AccessDeniedException.class);
        final var unchanged = regular.find("a").orElseThrow();
        assertThat(unchanged.name()).isEqualTo("Projects");
        assertThat(unchanged.access()).isEqualTo(Access.READ_ONLY);
        assertThat(unchanged.options()).isEqualTo(TestRoots.ALL_ALLOWED);
    }

    @Test
    void storesItsOwnOptions() {
        final var admin = registry(true);
        final var noLinks = new RootOptions(false, true, true);
        admin.add(new FsRoot("a", "Projects", DiskStorageType.KEY, "/srv/projects", Map.of(), Access.READ_WRITE,
                noLinks, RootStatus.UNKNOWN), Map.of());
        assertThat(admin.find("a").orElseThrow().options()).isEqualTo(noLinks);

        final var nothing = new RootOptions(false, false, false);
        admin.updateOptions("a", nothing);
        assertThat(registry(false).find("a").orElseThrow().options()).isEqualTo(nothing);
    }

    @Test
    void anOptionMissingFromTheDatabaseIsAnError() {
        registry(true).add(root("a", "/srv/projects"), Map.of());
        jdbc.sql("UPDATE file_system SET options = '{\"link_uploads\": true}'").update();
        assertThatThrownBy(() -> registry(false).find("a")).hasMessageContaining("extraction");
    }

    @Test
    void theMigrationAllowsEverythingOnExistingFileSystems() {
        final var config = new SQLiteConfig();
        final var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("old.db"));
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        final var old = JdbcClient.create(dataSource);
        old.sql("""
                INSERT INTO file_system (id, name, type, endpoint, access, created_by, created_at)
                VALUES ('a', 'Projects', 'disk', '/srv/projects', 'READ_WRITE', 'admin@example.com', '2026-01-01')
                """).update();
        Flyway.configure().dataSource(dataSource).load().migrate();
        final var registry = new FileSystemRegistry(old, () -> false, () -> "", Clock.fixed(NOW, ZoneOffset.UTC),
                root -> ONLINE);
        assertThat(registry.find("a").orElseThrow().options()).isEqualTo(new RootOptions(true, true, true));
    }
}
