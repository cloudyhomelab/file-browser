package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiskProviderTest {

    @TempDir
    Path base;

    private Path root;
    private LocalDiskProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(base.resolve("root"));
        Files.createDirectories(root.resolve("Projects/Design"));
        Files.writeString(root.resolve("Projects/brief.txt"), "brief");
        Files.writeString(root.resolve("notes.txt"), "notes");
        provider = new LocalDiskProvider(root, name -> name.endsWith(".txt") ? "text" : "generic",
                type -> MountInfo.Kind.LOCAL);
    }

    private static String read(LocalDiskProvider p, String path) throws IOException {
        try (final var in = p.openRead(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void listsEntriesWithRelativePathsTypesAndChildCounts() {
        final var entries = provider.list("");
        assertThat(entries).extracting(StorageEntry::path).containsExactlyInAnyOrder("Projects", "notes.txt");
        final var projects = entries.stream().filter(StorageEntry::folder).findFirst().orElseThrow();
        assertThat(projects.type()).isEqualTo(StorageEntry.FOLDER_TYPE);
        assertThat(projects.childCount()).isEqualTo(2);
        assertThat(projects.size()).isNull();
        final var notes = provider.stat("notes.txt").orElseThrow();
        assertThat(notes.type()).isEqualTo("text");
        assertThat(notes.size()).isEqualTo(5L);
        assertThat(notes.modified()).isNotNull();
        assertThat(provider.list("Projects")).extracting(StorageEntry::path)
                .containsExactlyInAnyOrder("Projects/Design", "Projects/brief.txt");
        assertThat(provider.stat("Projects/brief.txt").orElseThrow().parent()).containsExactly("Projects");
    }

    @Test
    void refusesPathsThatLeaveTheRoot() throws IOException {
        Files.writeString(base.resolve("secret.txt"), "secret");
        assertThatThrownBy(() -> provider.list("..")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.openRead("../secret.txt")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.createFolder("", "..")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.rename("notes.txt", "../escaped.txt")).isInstanceOf(StorageException.class);
        assertThat(base.resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void hidesAndRefusesSymlinksThatLeaveTheRoot() throws IOException {
        Files.writeString(base.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("outside.txt"), base.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("inside.txt"), root.resolve("notes.txt"));
        assertThat(provider.list("")).extracting(StorageEntry::name).contains("inside.txt").doesNotContain("outside.txt");
        assertThatThrownBy(() -> provider.openRead("outside.txt")).isInstanceOf(StorageException.class);
    }

    @Test
    void createsRenamesAndRefusesNameClashes() {
        provider.createFolder("Projects", "Research");
        assertThat(root.resolve("Projects/Research")).isDirectory();
        assertThatThrownBy(() -> provider.createFolder("Projects", "Research")).isInstanceOf(StorageException.class);

        provider.rename("notes.txt", "todo.txt");
        assertThat(root.resolve("todo.txt")).exists();
        assertThat(root.resolve("notes.txt")).doesNotExist();
        assertThatThrownBy(() -> provider.rename("todo.txt", "Projects")).isInstanceOf(StorageException.class);
    }

    @Test
    void movesAndRefusesMovingAFolderIntoItself() {
        provider.move("notes.txt", "Projects/Design");
        assertThat(root.resolve("Projects/Design/notes.txt")).exists();
        assertThatThrownBy(() -> provider.move("Projects", "Projects/Design")).isInstanceOf(StorageException.class);
        assertThat(root.resolve("Projects")).isDirectory();
    }

    @Test
    void copiesFoldersRecursively() throws IOException {
        provider.copy("Projects", "Projects copy");
        assertThat(read(provider, "Projects copy/brief.txt")).isEqualTo("brief");
        assertThat(root.resolve("Projects copy/Design")).isDirectory();
        assertThatThrownBy(() -> provider.copy("notes.txt", "Projects")).isInstanceOf(StorageException.class);
    }

    @Test
    void deletesFoldersWithTheirContentsButNeverTheRoot() {
        provider.delete("Projects");
        assertThat(root.resolve("Projects")).doesNotExist();
        assertThatThrownBy(() -> provider.delete("")).isInstanceOf(StorageException.class);
        assertThat(root).isDirectory();
    }

    @Test
    void writesBecomeVisibleOnlyOnCommit() throws IOException {
        final var write = provider.openWrite("", "upload.txt", false);
        write.stream().write("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(provider.list("")).extracting(StorageEntry::name).doesNotContain("upload.txt");
        write.commit();
        assertThat(read(provider, "upload.txt")).isEqualTo("hello");
        try (final var leftover = Files.list(root)) {
            assertThat(leftover.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".uploading"));
        }
    }

    @Test
    void abortedWritesLeaveNothingBehind() throws IOException {
        final var write = provider.openWrite("", "upload.txt", false);
        write.stream().write("partial".getBytes(StandardCharsets.UTF_8));
        write.abort();
        try (final var files = Files.list(root)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("Projects", "notes.txt");
        }
    }

    @Test
    void replaceOverwritesAndPlainWriteRefusesAnExistingFile() throws IOException {
        assertThatThrownBy(() -> provider.openWrite("", "notes.txt", false)).isInstanceOf(StorageException.class);
        try (final var write = provider.openWrite("", "notes.txt", true)) {
            write.stream().write("new".getBytes(StandardCharsets.UTF_8));
            write.commit();
        }
        assertThat(read(provider, "notes.txt")).isEqualTo("new");
    }

    @Test
    void reportsStatusOfTheRoot() {
        assertThat(provider.healthy()).isTrue();
        assertThat(provider.writable()).isTrue();
        assertThat(provider.capacity()).isNotNull();
        assertThat(provider.mount().kind()).isEqualTo(MountInfo.Kind.LOCAL);
        final var missing = new LocalDiskProvider(base.resolve("missing"), n -> "generic", t -> MountInfo.Kind.LOCAL);
        assertThat(missing.healthy()).isFalse();
        assertThatThrownBy(() -> missing.list("")).isInstanceOf(StorageException.class);
    }
}
