package org.vaadin.filebrowser.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.service.FileSystemRegistry;
import org.vaadin.filebrowser.service.StorageService;
import org.vaadin.filebrowser.storage.LocalDiskProvider;
import org.vaadin.filebrowser.storage.StorageException;

class FileStreamControllerTest {

    private static final String TEXT = "0123456789";
    private static final String SANDBOXED = "default-src 'none'; img-src 'self'; media-src 'self'; "
            + "style-src 'unsafe-inline'; sandbox";

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
        when(registry.find("r1")).thenReturn(Optional.of(root));
        when(registry.find(any())).thenAnswer(a -> "r1".equals(a.getArgument(0)) ? Optional.of(root) : Optional.empty());
        final var provider = new LocalDiskProvider(dir, name -> "generic", type -> "disk");
        when(storage.provider(any())).thenReturn(provider);
        final var controller = new FileStreamController(registry, storage, new Catalog());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static FsRoot root(Access access) {
        return new FsRoot("r1", "Disk", "disk", "/srv", Map.of(), access, RootStatus.UNKNOWN);
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
    void leavesOnlyPdfsUnsandboxed() throws Exception {
        fetch("doc.pdf")
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'"))
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
    }

    @Test
    void reportsStorageThatCantBeReached() throws Exception {
        when(storage.provider(any())).thenThrow(new StorageException("offline"));
        fetch("notes.txt").andExpect(status().isServiceUnavailable());
    }
}
