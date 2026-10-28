package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What every remote provider must do, run against a real server by each subclass. Each test gets
 * a fresh folder on the server as its file system.
 */
abstract class RemoteProviderContract {

    /** A file system rooted at {@code folder}, an absolute path on the server, signed in properly. */
    protected abstract StorageProvider provider(String folder);

    /** The folder on the server the tests may write in. */
    protected abstract String baseFolder();

    /** Every name in {@code folder} on the server, hidden ones included. */
    protected abstract List<String> serverNames(String folder) throws Exception;

    protected String folder;
    protected StorageProvider provider;

    @BeforeEach
    void setUpFolder() throws IOException {
        final var name = "t-" + UUID.randomUUID().toString().substring(0, 8);
        try (final var base = closeable(provider(baseFolder()))) {
            base.provider().createFolder("", name);
        }
        folder = baseFolder() + "/" + name;
        provider = provider(folder);
        provider.createFolder("", "Projects");
        provider.createFolder("Projects", "Design");
        write(provider, "Projects/Design", "logo.txt", "logo");
        write(provider, "Projects", "brief.txt", "brief");
        write(provider, "", "notes.txt", "notes");
    }

    @AfterEach
    void closeProvider() throws Exception {
        if (provider instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    protected record Closing(StorageProvider provider) implements AutoCloseable {
        @Override
        public void close() {
            if (provider instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    protected static Closing closeable(StorageProvider provider) {
        return new Closing(provider);
    }

    protected static void write(StorageProvider p, String folder, String name, String content) throws IOException {
        try (final var handle = p.openWrite(folder, name, false)) {
            handle.stream().write(content.getBytes(StandardCharsets.UTF_8));
            handle.commit();
        }
    }

    protected static String read(StorageProvider p, String path) throws IOException {
        try (final var in = p.openRead(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void listsFoldersAndFilesWithRelativePaths() {
        assertThat(provider.list("")).extracting(StorageEntry::path).containsExactlyInAnyOrder("Projects", "notes.txt");
        final var inside = provider.list("Projects");
        assertThat(inside).extracting(StorageEntry::path).containsExactlyInAnyOrder("Projects/Design", "Projects/brief.txt");
        final var brief = inside.stream().filter(e -> e.name().equals("brief.txt")).findFirst().orElseThrow();
        assertThat(brief.folder()).isFalse();
        assertThat(brief.size()).isEqualTo(5);
        assertThat(brief.type()).isEqualTo("text");
        assertThat(brief.modified()).isNotNull();
        assertThat(provider.stat("Projects/Design")).hasValueSatisfying(e -> assertThat(e.folder()).isTrue());
        assertThat(provider.stat("missing.txt")).isEmpty();
    }

    @Test
    void aFolderRootSeesOnlyItsFolder() throws IOException {
        try (final var projects = closeable(provider(folder + "/Projects"))) {
            assertThat(projects.provider().list("")).extracting(StorageEntry::path)
                    .containsExactlyInAnyOrder("Design", "brief.txt");
            assertThat(read(projects.provider(), "Design/logo.txt")).isEqualTo("logo");
        }
    }

    @Test
    void createsFoldersAndRefusesNameClashes() {
        provider.createFolder("Projects", "Empty");
        assertThat(provider.list("Projects/Empty")).isEmpty();
        assertThatThrownBy(() -> provider.createFolder("Projects", "brief.txt"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> provider.createFolder("Missing", "x")).hasMessageContaining("no longer exists");
    }

    @Test
    void refusesControlCharactersAndDotSegmentsBeforeReachingTheServer() throws Exception {
        assertThatThrownBy(() -> provider.createFolder("", "x\r\nDELE notes.txt"))
                .isInstanceOf(StorageException.class).hasMessageContaining("not a valid name");
        assertThatThrownBy(() -> provider.rename("notes.txt", "a\nb")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.openRead("notes.txt\r\nDELE notes.txt")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.list("..")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.stat("Projects/../..")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.delete("Projects//Design")).isInstanceOf(StorageException.class);
        assertThat(serverNames(folder)).containsExactlyInAnyOrder("Projects", "notes.txt");
        assertThat(read(provider, "notes.txt")).isEqualTo("notes");
    }

    @Test
    void renamesMovesAndCopiesFilesAndFolders() throws IOException {
        provider.rename("notes.txt", "todo.txt");
        assertThat(read(provider, "todo.txt")).isEqualTo("notes");
        assertThatThrownBy(() -> provider.rename("todo.txt", "Projects")).hasMessageContaining("already exists");

        provider.move("todo.txt", "Projects/Design", "todo.txt");
        assertThat(read(provider, "Projects/Design/todo.txt")).isEqualTo("notes");
        assertThatThrownBy(() -> provider.move("Projects", "Projects/Design", "Projects")).hasMessageContaining("into itself");
        assertThatThrownBy(() -> provider.move("Projects/brief.txt", "Projects/Design", "todo.txt"))
                .hasMessageContaining("already exists");
        provider.move("Projects/brief.txt", "Projects/Design", "brief (1).txt");
        assertThat(read(provider, "Projects/Design/brief (1).txt")).isEqualTo("brief");
        provider.move("Projects/Design/brief (1).txt", "Projects", "brief.txt");

        provider.copy("Projects", "", "Projects copy");
        assertThat(read(provider, "Projects copy/Design/logo.txt")).isEqualTo("logo");
        assertThat(read(provider, "Projects/Design/logo.txt")).isEqualTo("logo");
        provider.copy("Projects/brief.txt", "Projects", "brief copy.txt");
        assertThat(read(provider, "Projects/brief copy.txt")).isEqualTo("brief");
        provider.copy("Projects/brief.txt", "Projects/Design", "brief.txt");
        assertThat(read(provider, "Projects/Design/brief.txt")).isEqualTo("brief");
        assertThatThrownBy(() -> provider.copy("Projects/brief.txt", "Projects/Design", "brief.txt"))
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> provider.copy("Projects", "Projects/Design", "Projects")).hasMessageContaining("into itself");
    }

    @Test
    void deletesFoldersWithTheirContentsButNeverTheRoot() {
        provider.delete("Projects");
        assertThat(provider.list("")).extracting(StorageEntry::path).containsExactly("notes.txt");
        assertThatThrownBy(() -> provider.delete("")).hasMessageContaining("root");
        assertThatThrownBy(() -> provider.delete("Projects")).hasMessageContaining("no longer exists");
    }

    @Test
    void writesBecomeVisibleOnlyOnCommitAndAbortedOnesLeaveNothing() throws Exception {
        try (final var handle = provider.openWrite("", "draft.txt", false)) {
            handle.stream().write("draft".getBytes(StandardCharsets.UTF_8));
            handle.stream().flush();
            assertThat(provider.list("")).extracting(StorageEntry::name).doesNotContain("draft.txt");
            handle.commit();
        }
        assertThat(read(provider, "draft.txt")).isEqualTo("draft");

        final var big = new byte[3 * 1024 * 1024 + 7];
        try (final var handle = provider.openWrite("", "big.bin", false)) {
            handle.stream().write(big);
            handle.abort();
        }
        assertThat(provider.stat("big.bin")).isEmpty();
        assertThat(serverNames(folder)).containsExactlyInAnyOrder("Projects", "notes.txt", "draft.txt");
    }

    @Test
    void replaceOverwritesAndPlainWriteRefusesAnExistingFile() throws IOException {
        assertThatThrownBy(() -> provider.openWrite("", "notes.txt", false)).hasMessageContaining("already exists");
        assertThatThrownBy(() -> provider.openWrite("", "Projects", true)).hasMessageContaining("folder");
        try (final var handle = provider.openWrite("", "notes.txt", true)) {
            handle.stream().write("new notes".getBytes(StandardCharsets.UTF_8));
            handle.commit();
        }
        assertThat(read(provider, "notes.txt")).isEqualTo("new notes");
    }

    @Test
    void reusesSessionsAcrossManyOperations() throws IOException {
        for (int i = 0; i < 12; i++) {
            assertThat(read(provider, "notes.txt")).isEqualTo("notes");
            assertThat(provider.list("Projects")).hasSize(2);
        }
        assertThat(provider.healthy()).isTrue();
    }
}
