package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedCopyTest {

    @TempDir
    Path base;

    private Path sourceRoot;
    private Path destinationRoot;
    private LocalDiskProvider source;
    private LocalDiskProvider destination;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = Files.createDirectory(base.resolve("source"));
        destinationRoot = Files.createDirectory(base.resolve("destination"));
        Files.createDirectories(sourceRoot.resolve("Projects/Design/Empty"));
        Files.writeString(sourceRoot.resolve("Projects/brief.txt"), "brief");
        Files.writeString(sourceRoot.resolve("Projects/Design/logo.txt"), "logo");
        Files.writeString(sourceRoot.resolve("notes.txt"), "notes");
        Files.createDirectory(destinationRoot.resolve("Inbox"));
        source = disk(sourceRoot);
        destination = disk(destinationRoot);
    }

    private static LocalDiskProvider disk(Path root) {
        return new LocalDiskProvider(root, name -> "generic", type -> "disk");
    }

    /** Hands back different bytes when a temporary copy is read, as a storage that corrupted it would. */
    private static LocalDiskProvider corrupting(Path root) {
        return new LocalDiskProvider(root, name -> "generic", type -> "disk") {
            @Override
            public InputStream openRead(String path) {
                if (path.endsWith(".partial")) {
                    return new ByteArrayInputStream("corrupted".getBytes());
                }
                return super.openRead(path);
            }
        };
    }

    /** Counts what goes through, and cancels once {@code cancelAfter} bytes were written (never when negative). */
    private static final class Counting implements CopyProgress {

        private final long cancelAfter;
        private long copied;
        private long verified;

        Counting(long cancelAfter) {
            this.cancelAfter = cancelAfter;
        }

        @Override
        public void copied(long bytes) {
            copied += bytes;
        }

        @Override
        public void verified(long bytes) {
            verified += bytes;
        }

        @Override
        public boolean cancelled() {
            return cancelAfter >= 0 && copied >= cancelAfter;
        }
    }

    private static String names(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return String.join(",", files.map(p -> root.relativize(p).toString()).sorted().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void copiesAFolderTreeAndReadsEveryFileBack() throws IOException {
        final var progress = new Counting(-1);

        VerifiedCopy.copy(source, "Projects", destination, "Inbox", "Projects", false, progress);

        assertThat(destinationRoot.resolve("Inbox/Projects/brief.txt")).hasContent("brief");
        assertThat(destinationRoot.resolve("Inbox/Projects/Design/logo.txt")).hasContent("logo");
        assertThat(destinationRoot.resolve("Inbox/Projects/Design/Empty")).isEmptyDirectory();
        assertThat(names(destinationRoot)).doesNotContain(".partial");
        assertThat(progress.copied).isEqualTo("brief".length() + "logo".length());
        assertThat(progress.verified).isEqualTo(progress.copied);
        assertThat(sourceRoot.resolve("Projects/brief.txt")).hasContent("brief");
    }

    @Test
    void reportsWhatItVerifiedAndEachPhase() {
        final var phases = new ArrayList<String>();
        final var progress = new CopyProgress() {
            @Override
            public void copied(long bytes) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public void phase(String name, Phase phase) {
                phases.add(phase + " " + name);
            }
        };

        final var result = VerifiedCopy.copy(source, "Projects", destination, "Inbox", "Projects", false, progress);

        assertThat(result.files()).extracting(StorageEntry::path)
                .containsExactlyInAnyOrder("Projects/brief.txt", "Projects/Design/logo.txt");
        assertThat(result.folders()).containsExactlyInAnyOrder("Projects", "Projects/Design", "Projects/Design/Empty");
        assertThat(result.folders().getFirst()).isEqualTo("Projects");
        assertThat(phases).containsExactlyInAnyOrder("COPYING brief.txt", "VERIFYING brief.txt",
                "COPYING logo.txt", "VERIFYING logo.txt");
    }

    @Test
    void replacesAFileOnlyWhenAskedAndNeverAFolder() throws IOException {
        Files.writeString(destinationRoot.resolve("Inbox/notes.txt"), "older");
        Files.createDirectory(destinationRoot.resolve("Inbox/Projects"));

        assertThatThrownBy(() -> VerifiedCopy.copy(source, "notes.txt", destination, "Inbox", "notes.txt", false,
                CopyProgress.NONE)).hasMessage("“notes.txt” already exists in the destination.");
        assertThatThrownBy(() -> VerifiedCopy.copy(source, "Projects", destination, "Inbox", "Projects", true,
                CopyProgress.NONE)).hasMessageContaining("can't be replaced");
        VerifiedCopy.copy(source, "notes.txt", destination, "Inbox", "notes.txt", true, CopyProgress.NONE);

        assertThat(destinationRoot.resolve("Inbox/notes.txt")).hasContent("notes");
        assertThat(names(destinationRoot)).doesNotContain(".partial");
    }

    @Test
    void aCopyThatDoesNotMatchIsRemovedAndTheExistingFileKept() throws IOException {
        Files.writeString(destinationRoot.resolve("Inbox/notes.txt"), "older");
        final var corrupting = corrupting(destinationRoot);

        assertThatThrownBy(() -> VerifiedCopy.copy(source, "notes.txt", corrupting, "Inbox", "notes.txt", true,
                CopyProgress.NONE)).hasMessage("The copy of “notes.txt” didn't match the original.");
        assertThatThrownBy(() -> VerifiedCopy.copy(source, "Projects", corrupting, "Inbox", "Projects", false,
                CopyProgress.NONE)).hasMessageContaining("didn't match the original.");

        assertThat(destinationRoot.resolve("Inbox/notes.txt")).hasContent("older");
        assertThat(names(destinationRoot)).isEqualTo(",Inbox,Inbox/notes.txt");
    }

    @Test
    void aCancelledCopyLeavesNothingBehind() {
        assertThatThrownBy(() -> VerifiedCopy.copy(source, "Projects", destination, "Inbox", "Projects", false,
                new Counting(1))).isInstanceOf(CopyCancelledException.class);

        assertThat(names(destinationRoot)).isEqualTo(",Inbox");
    }

    @Test
    void aMissingSourceIsReported() {
        assertThatThrownBy(() -> VerifiedCopy.copy(source, "gone.txt", destination, "Inbox", "gone.txt", false,
                CopyProgress.NONE)).hasMessage("“gone.txt” no longer exists.");
    }
}
