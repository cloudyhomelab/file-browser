package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vaadin.filebrowser.storage.CopyProgress;
import org.vaadin.filebrowser.storage.LocalDiskProvider;
import org.vaadin.filebrowser.storage.VerifiedCopy;

class OriginalRemovalTest {

    @TempDir
    Path base;

    private Path sourceRoot;
    private LocalDiskProvider source;
    private LocalDiskProvider destination;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = Files.createDirectory(base.resolve("source"));
        final var destinationRoot = Files.createDirectory(base.resolve("destination"));
        Files.createDirectories(sourceRoot.resolve("Projects/Design/Empty"));
        Files.writeString(sourceRoot.resolve("Projects/brief.txt"), "brief");
        Files.writeString(sourceRoot.resolve("Projects/Design/logo.txt"), "logo");
        source = new LocalDiskProvider(sourceRoot, name -> "generic", type -> "disk");
        destination = new LocalDiskProvider(destinationRoot, name -> "generic", type -> "disk");
    }

    @Test
    void removesEveryVerifiedFileAndTheFoldersTheyLeaveEmpty() {
        final var picked = source.stat("Projects").orElseThrow();
        final var result = VerifiedCopy.copy(source, "Projects", destination, "", "Projects", false, CopyProgress.NONE);

        final var outcome = OriginalRemoval.remove(source, picked, result);

        assertThat(sourceRoot.resolve("Projects")).doesNotExist();
        assertThat(outcome.deleted()).containsExactlyInAnyOrder("Projects/brief.txt", "Projects/Design/logo.txt",
                "Projects/Design/Empty", "Projects/Design", "Projects");
        assertThat(outcome.changed()).isEmpty();
        assertThat(outcome.added()).isZero();
    }

    @Test
    void keepsAFileChangedAndAFileAddedDuringTheMoveWithTheirFolders() throws IOException {
        final var picked = source.stat("Projects").orElseThrow();
        final var result = VerifiedCopy.copy(source, "Projects", destination, "", "Projects", false, CopyProgress.NONE);
        Files.writeString(sourceRoot.resolve("Projects/brief.txt"), "a newer brief");
        Files.writeString(sourceRoot.resolve("Projects/Design/new.txt"), "new");

        final var outcome = OriginalRemoval.remove(source, picked, result);

        assertThat(sourceRoot.resolve("Projects/brief.txt")).hasContent("a newer brief");
        assertThat(sourceRoot.resolve("Projects/Design/new.txt")).hasContent("new");
        assertThat(sourceRoot.resolve("Projects/Design/logo.txt")).doesNotExist();
        assertThat(sourceRoot.resolve("Projects/Design/Empty")).doesNotExist();
        assertThat(outcome.changed()).containsExactly("brief.txt");
        assertThat(outcome.added()).isEqualTo(1);
    }
}
