package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.storage.StorageException;

class UploadQueueTest {

    private static final long LIMIT = 1024;

    @TempDir
    Path dir;

    private StorageService storage;
    private UploadQueue queue;
    private FsRoot root;

    @BeforeEach
    void setUp() {
        storage = new StorageService(new Catalog());
        queue = new UploadQueue(storage);
        root = new FsRoot("r", "Test", RootType.DISK, dir.toString(), "", null, true, Access.READ_WRITE,
                MountInfo.UNKNOWN);
    }

    private UploadTask plan(String name, long size, boolean replace) {
        final var file = new IncomingFile("f1", name, "text", size);
        final var planned = new UploadQueue.Planned(file, name, replace);
        return queue.enqueue(root, List.of(), List.of(planned)).getFirst();
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void receivedFilesAreStoredAndReportedOnce() throws IOException {
        final var task = plan("hello.txt", 5, false);
        queue.receive(task.id(), bytes("hello"), LIMIT);
        assertThat(Files.readString(dir.resolve("hello.txt"))).isEqualTo("hello");
        assertThat(task.complete()).isTrue();
        assertThat(task.progress()).isEqualTo(100);
        assertThat(queue.takeFinished()).containsExactly(task);
        assertThat(queue.takeFinished()).isEmpty();
        assertThat(queue.active()).isFalse();
    }

    @Test
    void aTaskCanOnlyReceiveOnce() {
        final var task = plan("once.txt", 1, false);
        queue.receive(task.id(), bytes("1"), LIMIT);
        assertThatThrownBy(() -> queue.receive(task.id(), bytes("2"), LIMIT)).isInstanceOf(StorageException.class);
    }

    @Test
    void uploadsOverTheLimitFailAndLeaveNothingBehind() throws IOException {
        final var task = plan("big.txt", 2, false);
        queue.receive(task.id(), bytes("x".repeat((int) LIMIT + 1)), LIMIT);
        assertThat(task.state()).isEqualTo(UploadTask.State.FAILED);
        try (final var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void replaceOverwritesWhilePlainUploadsRefuseExistingNames() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "old");
        final var plain = plan("a.txt", 3, false);
        queue.receive(plain.id(), bytes("new"), LIMIT);
        assertThat(plain.state()).isEqualTo(UploadTask.State.FAILED);
        final var replacing = plan("a.txt", 3, true);
        queue.receive(replacing.id(), bytes("new"), LIMIT);
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("new");
    }

    @Test
    void readOnlyFileSystemsRefuseUploads() {
        final var readOnly = new FsRoot("ro", "RO", RootType.DISK, dir.toString(), "", null, true, Access.READ_ONLY,
                MountInfo.UNKNOWN);
        final var file = new IncomingFile("f", "x.txt", "text", 1);
        final var planned = List.of(new UploadQueue.Planned(file, "x.txt", false));
        assertThatThrownBy(() -> queue.enqueue(readOnly, List.of(), planned)).isInstanceOf(StorageException.class);
    }

    @Test
    void foldersDownloadAsAZipWithTheirContents() throws IOException {
        Files.createDirectories(dir.resolve("Docs/Inner"));
        Files.writeString(dir.resolve("Docs/a.txt"), "a");
        Files.writeString(dir.resolve("Docs/Inner/b.txt"), "b");
        final var provider = storage.provider(root);
        final var docs = provider.stat("Docs").orElseThrow();
        final var out = new ByteArrayOutputStream();
        Downloads.writeZip(provider, List.of(docs), out);
        final var names = new ArrayList<String>();
        try (final var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactlyInAnyOrder("Docs/", "Docs/a.txt", "Docs/Inner/", "Docs/Inner/b.txt");
    }
}
