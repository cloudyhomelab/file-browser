package org.vaadin.filebrowser.web;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.springframework.web.util.UriUtils;
import org.vaadin.filebrowser.storage.StoragePaths;

/** URLs at which {@link FileStreamController} serves files. */
public final class FileUrls {

    /**
     * Spring Security's firewall refuses {@code %} and {@code ;} in a request path even when encoded, and Tomcat
     * refuses an encoded {@code \}, so a path containing any of them can't be reached.
     */
    private static final String UNREACHABLE = "%;\\";

    private FileUrls() {
    }

    /** The path below the app's root, each segment encoded. */
    public static String of(String rootId, String path) {
        final var encodedPath = StoragePaths.segments(path).stream()
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining(StoragePaths.SEPARATOR));
        final var encodedRoot = UriUtils.encodePathSegment(rootId, StandardCharsets.UTF_8);
        return FileStreamController.BASE_PATH + "/" + encodedRoot + "/" + encodedPath;
    }

    /** Whether {@link #of} gives a URL the server will answer; folder names count as much as the file's. */
    public static boolean reachable(String path) {
        return path.chars().noneMatch(c -> UNREACHABLE.indexOf(c) >= 0);
    }
}
