package org.vaadin.filebrowser.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

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
public class FileStreamController {

    public static final String BASE_PATH = "/files";
    /** Requests that reach this controller, for the security configuration. */
    public static final String PATH_PATTERN = BASE_PATH + "/**";

    private static final System.Logger LOG = System.getLogger(FileStreamController.class.getName());
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String PDF = "application/pdf";
    private static final String BYTES = "bytes";
    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String RESOURCE_POLICY = "Cross-Origin-Resource-Policy";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final String BASE_POLICY = "default-src 'none'; img-src 'self'; media-src 'self'; "
            + "style-src 'unsafe-inline'";
    private static final String SANDBOXED = BASE_POLICY + "; sandbox";
    /** Chrome's PDF viewer shows a blank page when the response is sandboxed. */
    private static final String PDF_POLICY = BASE_POLICY;
    /** The browser revalidates with the ETag before reusing a copy, so a changed file is never shown stale. */
    private static final String CACHE_POLICY = "private, no-cache";

    private final FileSystemRegistry registry;
    private final StorageService storage;
    private final Catalog catalog;

    public FileStreamController(FileSystemRegistry registry, StorageService storage, Catalog catalog) {
        this.registry = registry;
        this.storage = storage;
        this.catalog = catalog;
    }

    @RequestMapping(value = BASE_PATH + "/{rootId}/{*path}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void stream(@PathVariable String rootId, @PathVariable String path, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
        response.setHeader(CONTENT_SECURITY_POLICY, SANDBOXED);
        response.setHeader(RESOURCE_POLICY, "same-origin");
        response.setHeader(REFERRER_POLICY, "no-referrer");
        final var relative = path.startsWith(StoragePaths.SEPARATOR) ? path.substring(1) : path;
        if (relative.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        try {
            StoragePaths.requireValidPath(relative);
        } catch (StorageException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }
        final var root = registry.find(rootId);
        if (root.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        if (root.get().locked()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }
        final StorageProvider provider;
        final Optional<StorageEntry> found;
        try {
            provider = storage.provider(root.get());
            found = provider.stat(relative);
        } catch (StorageException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        if (found.isEmpty() || found.get().folder()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        send(provider, found.get(), request, response);
    }

    private void send(StorageProvider provider, StorageEntry entry, HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        final var etag = etag(entry);
        final var lastModified = entry.modified() == null ? -1 : entry.modified().toEpochMilli();
        final var webRequest = new ServletWebRequest(request, response);
        if (webRequest.checkNotModified(etag, lastModified)) {
            return;
        }
        final var size = entry.size();
        final ByteRange.Request asked;
        if (size == null) {
            asked = new ByteRange.Whole();
        } else {
            response.setHeader(HttpHeaders.ACCEPT_RANGES, BYTES);
            asked = rangeStillValid(request, etag, entry.modified())
                    ? ByteRange.parse(request.getHeader(HttpHeaders.RANGE), size)
                    : new ByteRange.Whole();
        }
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
            describe(entry, response);
            if (range != null) {
                response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE,
                        BYTES + " " + range.start() + "-" + range.end() + "/" + size);
                response.setContentLengthLong(range.length());
            } else if (size != null) {
                response.setContentLengthLong(size);
            }
            if (in != null) {
                in.transferTo(response.getOutputStream());
            }
        } finally {
            close(in, entry);
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

    private void describe(StorageEntry entry, HttpServletResponse response) {
        final var viewType = catalog.viewType(entry.name());
        final var disposition = viewType.isPresent() ? ContentDisposition.inline() : ContentDisposition.attachment();
        final var header = disposition.filename(entry.name(), StandardCharsets.UTF_8).build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, header.toString());
        response.setContentType(viewType.orElse(OCTET_STREAM));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_POLICY);
        if (viewType.filter(PDF::equals).isPresent()) {
            response.setHeader(CONTENT_SECURITY_POLICY, PDF_POLICY);
        }
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
