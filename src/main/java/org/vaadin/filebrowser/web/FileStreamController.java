package org.vaadin.filebrowser.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.ServletWebRequest;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.service.FileSystemRegistry;
import org.vaadin.filebrowser.service.StorageService;
import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves one file for viewing in the page, with byte ranges so media can seek. Uploaded files are untrusted, so
 * nothing served here may run script on the app's origin: types that could are listed as plain text in
 * {@code data/view-types.json}, unlisted types are sent as attachments, and every response is sandboxed.
 */
@Controller
public class FileStreamController implements DisposableBean {

    public static final String BASE_PATH = "/files";
    /** Requests that reach this controller, for the security configuration. */
    public static final String PATH_PATTERN = BASE_PATH + "/**";

    private static final System.Logger LOG = System.getLogger(FileStreamController.class.getName());
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String BYTES = "bytes";
    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String RESOURCE_POLICY = "Cross-Origin-Resource-Policy";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    /** The app's own pages may frame a file (text is shown in an iframe); no other site may. */
    private static final String SANDBOXED = "default-src 'none'; img-src 'self'; media-src 'self'; "
            + "style-src 'unsafe-inline'; frame-ancestors 'self'; sandbox";
    /**
     * The browser revalidates with the ETag before reusing a copy. Where the storage's times are coarse (minutes
     * on FTP without MLSD, seconds on SFTP and S3), a rewrite of the same size within that time keeps its ETag.
     */
    private static final String CACHE_POLICY = "private, no-cache";

    private final FileSystemRegistry registry;
    private final StorageService storage;
    private final Catalog catalog;
    /** Watches every file being sent for a client that stopped reading; see {@link IdleTransfer}. */
    private final ScheduledExecutorService idleWatcher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("idle-stream-watcher").factory());

    public FileStreamController(FileSystemRegistry registry, StorageService storage, Catalog catalog) {
        this.registry = registry;
        this.storage = storage;
        this.catalog = catalog;
    }

    @Override
    public void destroy() {
        idleWatcher.shutdownNow();
    }

    @RequestMapping(value = BASE_PATH + "/{rootId}/{*path}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void stream(@PathVariable String rootId, @PathVariable String path, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
        response.setHeader(CONTENT_SECURITY_POLICY, SANDBOXED);
        response.setHeader(RESOURCE_POLICY, "same-origin");
        response.setHeader(REFERRER_POLICY, "no-referrer");
        final var relative = path.startsWith(StoragePaths.SEPARATOR) ? path.substring(1) : path;
        final var badPath = pathRefusal(relative);
        if (badPath.isPresent()) {
            response.setStatus(badPath.orElseThrow().value());
            return;
        }
        final var root = registry.find(rootId);
        final var badRoot = rootRefusal(root);
        if (badRoot.isPresent()) {
            response.setStatus(badRoot.orElseThrow().value());
            return;
        }
        final StorageProvider provider;
        final Optional<StorageEntry> found;
        try {
            provider = storage.provider(root.orElseThrow());
            found = provider.stat(relative);
        } catch (StorageException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        if (found.isEmpty() || found.orElseThrow().folder()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        final var idleLimit = Duration.ofSeconds(root.orElseThrow().options().idleStreamSeconds());
        send(provider, found.orElseThrow(), idleLimit, request, response);
    }

    private static Optional<HttpStatus> pathRefusal(String relative) {
        if (relative.isEmpty()) {
            return Optional.of(HttpStatus.NOT_FOUND);
        }
        try {
            StoragePaths.requireValidPath(relative);
        } catch (StorageException e) {
            return Optional.of(HttpStatus.BAD_REQUEST);
        }
        return Optional.empty();
    }

    private static Optional<HttpStatus> rootRefusal(Optional<FsRoot> root) {
        if (root.isEmpty()) {
            return Optional.of(HttpStatus.NOT_FOUND);
        }
        if (root.orElseThrow().locked() || !root.orElseThrow().options().opening()) {
            return Optional.of(HttpStatus.FORBIDDEN);
        }
        return Optional.empty();
    }

    // close() reports a failure to close instead of throwing it, and the stream is null for HEAD
    @SuppressWarnings("PMD.UseTryWithResources")
    private void send(StorageProvider provider, StorageEntry entry, Duration idleLimit, HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        final var etag = etag(entry);
        final var lastModified = entry.modified() == null ? -1 : entry.modified().toEpochMilli();
        final var viewType = catalog.viewType(entry.name());
        // a 304's headers replace the cached copy's, so they must be the same as on the full response
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_POLICY);
        final var webRequest = new ServletWebRequest(request, response);
        if (webRequest.checkNotModified(etag, lastModified)) {
            return;
        }
        final var size = entry.size();
        final var asked = requestedRange(entry, etag, request, response);
        if (asked instanceof ByteRange.Unsatisfiable) {
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, BYTES + " */" + size);
            return;
        }
        final var range = asked instanceof ByteRange.Part part ? part.range() : null;
        final var head = HttpMethod.HEAD.matches(request.getMethod());
        final InputStream in;
        try {
            in = head ? null : open(provider, entry, range);
        } catch (StorageException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        // closing gives a remote session back to its pool, so it must happen whatever fails below
        try {
            describe(entry, viewType, response);
            setLength(range, size, response);
            if (in != null) {
                IdleTransfer.transfer(in, response.getOutputStream(), idleLimit, idleWatcher);
            }
        } finally {
            close(in, entry);
        }
    }

    /** The whole file where the size is unknown or the file changed since the range was asked for. */
    private static ByteRange.Request requestedRange(StorageEntry entry, String etag, HttpServletRequest request,
                                                    HttpServletResponse response) {
        final var size = entry.size();
        if (size == null) {
            return new ByteRange.Whole();
        }
        response.setHeader(HttpHeaders.ACCEPT_RANGES, BYTES);
        return rangeStillValid(request, etag, entry.modified())
                ? ByteRange.parse(request.getHeader(HttpHeaders.RANGE), size)
                : new ByteRange.Whole();
    }

    private static void setLength(ByteRange range, Long size, HttpServletResponse response) {
        if (range != null) {
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    BYTES + " " + range.start() + "-" + range.end() + "/" + size);
            response.setContentLengthLong(range.length());
        } else if (size != null) {
            response.setContentLengthLong(size);
        }
    }

    /** The bytes were sent, or the transfer already failed, before this runs; a failure to close is only logged. */
    private static void close(InputStream in, StorageEntry entry) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException | StorageException e) {
            LOG.log(System.Logger.Level.WARNING, "Closing the stream of “" + entry.path() + "” failed", e);
        }
    }

    private static InputStream open(StorageProvider provider, StorageEntry entry, ByteRange range) {
        return range == null
                ? provider.openRead(entry.path())
                : provider.openRead(entry.path(), range.start(), range.length());
    }

    private static void describe(StorageEntry entry, Optional<String> viewType, HttpServletResponse response) {
        final var disposition = viewType.isPresent() ? ContentDisposition.inline() : ContentDisposition.attachment();
        final var header = disposition.filename(entry.name(), StandardCharsets.UTF_8).build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, header.toString());
        response.setContentType(viewType.orElse(OCTET_STREAM));
    }

    /** Strong, from size and modified time; null when either is unknown. */
    static String etag(StorageEntry entry) {
        if (entry.size() == null || entry.modified() == null) {
            return null;
        }
        return "\"" + Long.toHexString(entry.size()) + "-" + Long.toHexString(entry.modified().toEpochMilli()) + "\"";
    }

    /** {@code If-Range} asks for the range only while the file is unchanged; otherwise the whole file is sent. */
    private static boolean rangeStillValid(HttpServletRequest request, String etag, Instant modified) {
        final var ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        if (ifRange == null) {
            return true;
        }
        if (ifRange.startsWith("\"")) {
            return ifRange.equals(etag);
        }
        if (ifRange.startsWith("W/") || modified == null) {
            return false;
        }
        try {
            return request.getDateHeader(HttpHeaders.IF_RANGE) == modified.truncatedTo(ChronoUnit.SECONDS).toEpochMilli();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
