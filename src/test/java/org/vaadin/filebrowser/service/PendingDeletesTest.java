package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.data.TestRoots;
import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.StorageEntry;

class PendingDeletesTest {

    private static final Duration SHORT = Duration.ofMillis(50);
    private static final Duration LONG = Duration.ofMinutes(1);
    private static final String OWNER = "owner@example.com";

    @TempDir
    Path dir;

    private Path folder;
    private FileSystemRegistry registry;
    private StorageService storage;
    private UserData userData;
    private FsRoot root;
    private PendingDeletes deletes;

    @BeforeEach
    void setUp() throws IOException {
        final var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        final var jdbc = JdbcClient.create(dataSource);
        final var catalog = new Catalog();
        storage = new StorageService(new StorageTypes(List.of(new DiskStorageType(catalog)), catalog));
        registry = new FileSystemRegistry(jdbc, () -> true, () -> OWNER, Clock.systemUTC(), storage::status);
        userData = new UserData(jdbc);

        folder = Files.createDirectory(dir.resolve("projects"));
        Files.writeString(folder.resolve("notes.txt"), "notes");
        Files.createDirectories(folder.resolve("Docs/Old"));
        final var definition = new FsRoot("a", "Projects", DiskStorageType.KEY, folder.toString(), Map.of(),
                Access.READ_WRITE, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
        registry.add(definition, Map.of());
        root = registry.find("a").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        if (deletes != null) {
            deletes.destroy();
        }
    }

    private PendingDeletes deletes(Duration delay) {
        deletes = new PendingDeletes(storage, registry, userData, delay);
        return deletes;
    }

    private StorageEntry entry(String path) {
        return storage.provider(root).stat(path).orElseThrow();
    }

    private static PendingDeletes.Outcome await(CompletableFuture<PendingDeletes.Outcome> outcome) throws Exception {
        return outcome.get(5, TimeUnit.SECONDS);
    }

    @Test
    void deletesOnceTheDelayIsOverAndDropsTheStars() throws Exception {
        userData.star(OWNER, "a", "Docs/Old");
        final var outcome = new CompletableFuture<PendingDeletes.Outcome>();
        final var pending = deletes(SHORT).schedule(root, OWNER, List.of(entry("notes.txt"), entry("Docs")), outcome::complete);

        final var done = await(outcome);

        assertThat(done.deleted()).isEqualTo(2);
        assertThat(done.problem()).isNull();
        assertThat(pending.label()).isEqualTo("2 items");
        assertThat(folder.resolve("notes.txt")).doesNotExist();
        assertThat(folder.resolve("Docs")).doesNotExist();
        assertThat(userData.starred(OWNER)).isEmpty();
        assertThat(deletes.holding("a", "Docs")).isEmpty();
    }

    @Test
    void anUndoBeforeTheDelayKeepsEverything() {
        final var pending = deletes(LONG).schedule(root, OWNER, List.of(entry("notes.txt")), outcome -> { });

        assertThat(deletes.cancel(pending.id())).isTrue();
        assertThat(deletes.cancel(pending.id())).isFalse();
        assertThat(deletes.hides(OWNER, "a", "notes.txt")).isFalse();
        assertThat(folder.resolve("notes.txt")).exists();
    }

    @Test
    void anUndoAfterTheDeleteRanIsRefused() throws Exception {
        final var outcome = new CompletableFuture<PendingDeletes.Outcome>();
        final var pending = deletes(SHORT).schedule(root, OWNER, List.of(entry("notes.txt")), outcome::complete);
        await(outcome);

        assertThat(deletes.cancel(pending.id())).isFalse();
    }

    @Test
    void itemsAreHiddenOnlyFromTheirOwnerButHeldForEveryone() {
        deletes(LONG).schedule(root, OWNER, List.of(entry("Docs")), outcome -> { });

        assertThat(deletes.hides(OWNER, "a", "Docs")).isTrue();
        assertThat(deletes.hides(OWNER, "a", "Docs/Old")).isTrue();
        assertThat(deletes.hides(OWNER, "a", "Docs copy")).isFalse();
        assertThat(deletes.hides(OWNER, "b", "Docs")).isFalse();
        assertThat(deletes.hides("other@example.com", "a", "Docs")).isFalse();
        assertThat(deletes.holding("a", "Docs/Old/new.txt")).map(h -> h.entry().name()).contains("Docs");
    }

    @Test
    void aFileReplacedDuringTheWaitIsKept() throws Exception {
        final var notes = entry("notes.txt");
        final var outcome = new CompletableFuture<PendingDeletes.Outcome>();
        deletes(Duration.ofMillis(300)).schedule(root, OWNER, List.of(notes), outcome::complete);
        Files.writeString(folder.resolve("notes.txt"), "someone else's notes");

        final var done = await(outcome);

        assertThat(done.deleted()).isZero();
        assertThat(done.kept()).containsExactly("notes.txt");
        assertThat(folder.resolve("notes.txt")).hasContent("someone else's notes");
    }

    @Test
    void nothingIsDeletedOnAFileSystemMadeReadOnlyDuringTheWait() throws Exception {
        final var outcome = new CompletableFuture<PendingDeletes.Outcome>();
        deletes(Duration.ofMillis(300)).schedule(root, OWNER, List.of(entry("notes.txt")), outcome::complete);
        registry.update("a", "Projects", Access.READ_ONLY);

        final var done = await(outcome);

        assertThat(done.problem()).isEqualTo("“Projects” is now read only, so “notes.txt” stayed.");
        assertThat(folder.resolve("notes.txt")).exists();
    }

    @Test
    void aServerStopDuringTheWaitDeletesNothing() throws Exception {
        deletes(Duration.ofMillis(200)).schedule(root, OWNER, List.of(entry("notes.txt")), outcome -> { });

        deletes.destroy();
        Thread.sleep(400);

        assertThat(folder.resolve("notes.txt")).exists();
    }
}
