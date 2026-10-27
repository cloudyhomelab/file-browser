package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.ArchiveFormat;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.ExtractOptions;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.storage.DiskStorageType;
import org.vaadin.filebrowser.storage.StorageException;

import com.sun.net.httpserver.HttpServer;

class UploadQueueTest {

    private static final long LIMIT = 1024;

    @TempDir
    Path dir;

    private StorageService storage;
    private UploadQueue queue;
    private FsRoot root;

    @BeforeEach
    void setUp() {
        final var catalog = new Catalog();
        storage = new StorageService(new StorageTypes(List.of(new DiskStorageType(catalog)), catalog));
        queue = new UploadQueue(storage);
        root = new FsRoot("r", "Test", DiskStorageType.KEY, dir.toString(), Map.of(), Access.READ_WRITE,
                RootStatus.UNKNOWN);
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
        final var readOnly = new FsRoot("ro", "RO", DiskStorageType.KEY, dir.toString(), Map.of(), Access.READ_ONLY,
                RootStatus.UNKNOWN);
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

    @Test
    void linksAreDownloadedIntoTheFolderAndOversizedOnesFail() throws IOException {
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/note.txt", exchange -> {
            final var body = "from the web".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/huge.bin", exchange -> {
            // no length announced, so only the streamed limit can stop it
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[(int) LIMIT * 2]);
            exchange.close();
        });
        server.start();
        final var base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (final var links = new LinkFetcher(InetAddress::isLoopbackAddress, SystemDefaultDnsResolver.INSTANCE)) {
            final var note = IncomingFile.link(base + "/note.txt", "note.txt", "text", null);
            final var huge = IncomingFile.link(base + "/huge.bin", "huge.bin", "generic", null);
            final var tasks = queue.enqueue(root, List.of(), List.of(new UploadQueue.Planned(note, "note.txt", false),
                    new UploadQueue.Planned(huge, "huge.bin", false)));
            tasks.forEach(t -> queue.download(t, links, LIMIT));

            assertThat(Files.readString(dir.resolve("note.txt"))).isEqualTo("from the web");
            assertThat(tasks.getFirst().complete()).isTrue();
            assertThat(tasks.get(1).state()).isEqualTo(UploadTask.State.FAILED);
            assertThat(tasks.get(1).error()).contains("larger than the upload limit");
            assertThat(dir.resolve("huge.bin")).doesNotExist();
        } finally {
            server.stop(0);
        }
    }

    private static byte[] zipOf(Map<String, String> entries) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (final var zip = new ZipArchiveOutputStream(bytes)) {
            for (final var e : entries.entrySet()) {
                zip.putArchiveEntry(new ZipArchiveEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private UploadTask planArchive(String name, long size, UploadQueue.Extraction extraction) {
        final var file = new IncomingFile("f1", name, "zip", size);
        return queue.enqueue(root, List.of(), List.of(new UploadQueue.Planned(file, name, false, extraction))).getFirst();
    }

    @Test
    void archivesAreExtractedIntoANewFolderAndKeptWhenAsked() throws IOException {
        final var zip = zipOf(Map.of("photos/beach.jpg", "beach", "readme.txt", "hi"));
        final var extraction = new UploadQueue.Extraction(ArchiveFormat.ZIP, null, "holiday", true,
                ExtractOptions.Clash.KEEP_BOTH, new ArchiveExtractor.Limits(LIMIT, 100));
        final var task = planArchive("holiday.zip", zip.length, extraction);
        queue.receive(task.id(), new ByteArrayInputStream(zip), LIMIT);

        assertThat(task.complete()).isTrue();
        assertThat(task.summary()).isEqualTo("2 files extracted");
        assertThat(Files.readString(dir.resolve("holiday/photos/beach.jpg"))).isEqualTo("beach");
        assertThat(Files.readString(dir.resolve("holiday/readme.txt"))).isEqualTo("hi");
        assertThat(dir.resolve("holiday.zip")).exists();
    }

    @Test
    void archivesExtractIntoTheFolderItselfFollowingTheClashChoice() throws IOException {
        Files.writeString(dir.resolve("readme.txt"), "old");
        final var zip = zipOf(Map.of("readme.txt", "new"));
        final var extraction = new UploadQueue.Extraction(ArchiveFormat.ZIP, null, null, false,
                ExtractOptions.Clash.KEEP_BOTH, new ArchiveExtractor.Limits(LIMIT, 100));
        final var task = planArchive("update.zip", zip.length, extraction);
        queue.receive(task.id(), new ByteArrayInputStream(zip), LIMIT);

        assertThat(Files.readString(dir.resolve("readme.txt"))).isEqualTo("old");
        assertThat(Files.readString(dir.resolve("readme (1).txt"))).isEqualTo("new");
        assertThat(dir.resolve("update.zip")).doesNotExist();
    }

    @Test
    void aFailedExtractionRemovesItsNewFolderAndKeepsNoArchive() throws IOException {
        final var zip = zipOf(Map.of("big.txt", "x".repeat(4096)));
        final var extraction = new UploadQueue.Extraction(ArchiveFormat.ZIP, null, "bomb", true,
                ExtractOptions.Clash.KEEP_BOTH, new ArchiveExtractor.Limits(1024, 100));
        final var task = planArchive("bomb.zip", zip.length, extraction);
        queue.receive(task.id(), new ByteArrayInputStream(zip), LIMIT);

        assertThat(task.state()).isEqualTo(UploadTask.State.FAILED);
        assertThat(task.error()).contains("extraction limit");
        assertThat(dir.resolve("bomb")).doesNotExist();
        assertThat(dir.resolve("bomb.zip")).doesNotExist();
    }
}
