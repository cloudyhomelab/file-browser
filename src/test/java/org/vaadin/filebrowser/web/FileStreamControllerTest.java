package org.vaadin.filebrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootOptions;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.data.TestRoots;
import org.vaadin.filebrowser.service.FileSystemRegistry;
import org.vaadin.filebrowser.service.StorageService;
import org.vaadin.filebrowser.storage.LocalDiskProvider;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StorageProvider;

class FileStreamControllerTest {

    private static final String TEXT = "0123456789";
    private static final String SANDBOXED = "default-src 'none'; img-src 'self'; media-src 'self'; "
            + "style-src 'unsafe-inline'; frame-ancestors 'self'; sandbox";

    @TempDir
    Path dir;

    private FileSystemRegistry registry;
    private StorageService storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dir.resolve("notes.txt"), TEXT);
        Files.writeString(dir.resolve("page.html"), "<script>alert(1)</script>");
        Files.writeString(dir.resolve("logo.svg"), "<svg onload=\"alert(1)\"/>");
        Files.writeString(dir.resolve("data.bin"), "bin");
        Files.writeString(dir.resolve("doc.pdf"), "%PDF-1.4");
        Files.writeString(dir.resolve("clip.mp4"), "mp4");
        Files.createDirectory(dir.resolve("Folder"));
        registry = mock(FileSystemRegistry.class);
        storage = mock(StorageService.class);
        final var root = root(Access.READ_WRITE);
        when(registry.find(any())).thenAnswer(a -> "r1".equals(a.getArgument(0)) ? Optional.of(root) : Optional.empty());
        final var provider = new LocalDiskProvider(dir, name -> "generic", type -> "disk");
        when(storage.provider(any())).thenReturn(provider);
        final var catalog = new Catalog();
        final var controller = new FileStreamController(registry, storage, catalog);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static FsRoot root(Access access) {
        return new FsRoot("r1", "Disk", "disk", "/srv", Map.of(), access, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);
    }

    private ResultActions fetch(String path, String... headers) throws Exception {
        final var request = get("/files/r1/" + path);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(request);
    }

    private String etag() throws Exception {
        return fetch("notes.txt").andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private String lastModified() throws Exception {
        return fetch("notes.txt").andReturn().getResponse().getHeader(HttpHeaders.LAST_MODIFIED);
    }

    /** Replaces the disk with a provider that knows only {@code entry}. */
    private StorageProvider only(StorageEntry entry) {
        final var provider = mock(StorageProvider.class);
        when(provider.stat(entry.path())).thenReturn(Optional.of(entry));
        when(storage.provider(any())).thenReturn(provider);
        return provider;
    }

    /** Counts how often it is closed, and can fail on read or on close. */
    private static final class Tracked extends FilterInputStream {

        private final boolean failRead;
        private final boolean failClose;
        private int closed;

        Tracked(String content, boolean failRead, boolean failClose) {
            super(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            this.failRead = failRead;
            this.failClose = failClose;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (failRead) {
                throw new IOException("connection reset");
            }
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed++;
            if (failClose) {
                throw new IOException("426 Failure writing network stream.");
            }
        }
    }

    @Test
    void sendsTheWholeFileInlineWithItsTypeAndValidators() throws Exception {
        fetch("notes.txt")
                .andExpect(status().isOk())
                .andExpect(content().string(TEXT))
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, TEXT.length()))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"notes.txt\"; filename*=UTF-8''notes.txt"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED));
    }

    @Test
    void sandboxesEveryResponse() throws Exception {
        fetch("notes.txt")
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", SANDBOXED))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
        fetch("missing.txt")
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Security-Policy", SANDBOXED));
    }

    @Test
    void sendsPagesAndImagesThatCouldRunScriptAsPlainText() throws Exception {
        fetch("page.html").andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Security-Policy", SANDBOXED));
        fetch("logo.svg").andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Security-Policy", SANDBOXED));
    }

    @Test
    void sendsUnlistedTypesAsAttachments() throws Exception {
        fetch("data.bin")
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"data.bin\"; filename*=UTF-8''data.bin"));
    }

    @Test
    void sandboxesPdfsAndMediaToo() throws Exception {
        fetch("doc.pdf")
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Security-Policy", SANDBOXED))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        fetch("clip.mp4")
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string("Content-Security-Policy", SANDBOXED));
    }

    @Test
    void sendsOneRange() throws Exception {
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=2-5")
                .andExpect(status().isPartialContent())
                .andExpect(content().string("2345"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4));
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=-3")
                .andExpect(status().isPartialContent())
                .andExpect(content().string("789"));
    }

    @Test
    void refusesARangePastTheEnd() throws Exception {
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=10-")
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */10"))
                .andExpect(content().string(""));
    }

    @Test
    void sendsTheWholeFileForSeveralRanges() throws Exception {
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=0-1,4-5")
                .andExpect(status().isOk())
                .andExpect(content().string(TEXT));
    }

    @Test
    void sendsTheRangeOnlyWhileIfRangeStillMatches() throws Exception {
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=0-1", HttpHeaders.IF_RANGE, etag())
                .andExpect(status().isPartialContent());
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=0-1", HttpHeaders.IF_RANGE, "\"stale\"")
                .andExpect(status().isOk())
                .andExpect(content().string(TEXT));
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=0-1", HttpHeaders.IF_RANGE, "W/" + etag())
                .andExpect(status().isOk());
    }

    @Test
    void answersNotModifiedForAMatchingETag() throws Exception {
        fetch("notes.txt", HttpHeaders.IF_NONE_MATCH, etag())
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    @Test
    void keepsThePolicyAndCacheHeadersOnNotModified() throws Exception {
        fetch("notes.txt", HttpHeaders.IF_NONE_MATCH, etag())
                .andExpect(status().isNotModified())
                .andExpect(header().string("Content-Security-Policy", SANDBOXED))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }

    @Test
    void answersNotModifiedForAnUnchangedDate() throws Exception {
        fetch("notes.txt", HttpHeaders.IF_MODIFIED_SINCE, lastModified())
                .andExpect(status().isNotModified());
    }

    @Test
    void sendsTheRangeOnlyWhileAnIfRangeDateStillMatches() throws Exception {
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=0-1", HttpHeaders.IF_RANGE, lastModified())
                .andExpect(status().isPartialContent());
        fetch("notes.txt", HttpHeaders.RANGE, "bytes=0-1", HttpHeaders.IF_RANGE, "Thu, 01 Jan 1970 00:00:00 GMT")
                .andExpect(status().isOk())
                .andExpect(content().string(TEXT));
    }

    @Test
    void answersHeadWithARange() throws Exception {
        mvc.perform(head("/files/r1/notes.txt").header(HttpHeaders.RANGE, "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4))
                .andExpect(content().string(""));
    }

    @Test
    void sendsAFileOfUnknownSizeWholeWithoutValidators() throws Exception {
        final var entry = new StorageEntry("stream.txt", "stream.txt", false, "text", null, null, null, null);
        final var provider = only(entry);
        when(provider.openRead("stream.txt")).thenReturn(new Tracked("abc", false, false));
        fetch("stream.txt", HttpHeaders.RANGE, "bytes=0-0")
                .andExpect(status().isOk())
                .andExpect(content().string("abc"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCEPT_RANGES))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_LENGTH))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG));
    }

    @Test
    void reportsAFileThatCantBeOpened() throws Exception {
        final var entry = new StorageEntry("gone.txt", "gone.txt", false, "text", 3L, Instant.EPOCH, null, null);
        final var provider = only(entry);
        when(provider.openRead("gone.txt")).thenThrow(new StorageException("gone"));
        fetch("gone.txt").andExpect(status().isServiceUnavailable());
    }

    @Test
    void closesTheStreamWhenSendingFails() {
        final var entry = new StorageEntry("reset.txt", "reset.txt", false, "text", 3L, Instant.EPOCH, null, null);
        final var provider = only(entry);
        final var stream = new Tracked("abc", true, false);
        when(provider.openRead("reset.txt")).thenReturn(stream);
        assertThatThrownBy(() -> fetch("reset.txt")).isInstanceOf(Exception.class);
        assertThat(stream.closed).isEqualTo(1);
    }

    @Test
    void onlyLogsAFailureToCloseAfterTheFileWasSent() throws Exception {
        final var entry = new StorageEntry("ftp.txt", "ftp.txt", false, "text", 3L, Instant.EPOCH, null, null);
        final var provider = only(entry);
        final var stream = new Tracked("abc", false, true);
        when(provider.openRead("ftp.txt")).thenReturn(stream);
        fetch("ftp.txt").andExpect(status().isOk()).andExpect(content().string("abc"));
        assertThat(stream.closed).isEqualTo(1);
    }

    @Test
    void answersHeadWithoutABody() throws Exception {
        mvc.perform(head("/files/r1/notes.txt"))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, TEXT.length()))
                .andExpect(content().string(""));
    }

    @Test
    void refusesInvalidPaths() throws Exception {
        mvc.perform(get(URI.create("/files/r1/a/../notes.txt"))).andExpect(status().isBadRequest());
        mvc.perform(get(URI.create("/files/r1/bad%01name.txt"))).andExpect(status().isBadRequest());
    }

    @Test
    void findsNothingForFoldersMissingFilesAndUnknownFileSystems() throws Exception {
        fetch("Folder").andExpect(status().isNotFound());
        fetch("missing.txt").andExpect(status().isNotFound());
        mvc.perform(get("/files/r2/notes.txt")).andExpect(status().isNotFound());
        mvc.perform(get("/files/r1/")).andExpect(status().isNotFound());
    }

    @Test
    void refusesLockedFileSystems() throws Exception {
        when(registry.find(any())).thenReturn(Optional.of(root(Access.LOCKED)));
        fetch("notes.txt").andExpect(status().isForbidden()).andExpect(content().string(""));
        verifyNoInteractions(storage);
    }

    @Test
    void refusesFileSystemsThatDontAllowOpeningFiles() throws Exception {
        final var closed = new FsRoot("r1", "Disk", "disk", "/srv", Map.of(), Access.READ_WRITE,
                TestRoots.features(true, true, false), RootStatus.UNKNOWN);
        when(registry.find(any())).thenReturn(Optional.of(closed));
        fetch("notes.txt").andExpect(status().isForbidden()).andExpect(content().string(""));
        verifyNoInteractions(storage);
    }

    @Test
    void reportsStorageThatCantBeReached() throws Exception {
        when(storage.provider(any())).thenThrow(new StorageException("offline"));
        fetch("notes.txt").andExpect(status().isServiceUnavailable());
    }
}
