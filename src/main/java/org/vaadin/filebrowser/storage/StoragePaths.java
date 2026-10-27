package org.vaadin.filebrowser.storage;

import java.util.Arrays;
import java.util.List;

/** Converts between folder segments ({@code ["Projects", "Design"]}) and relative paths ({@code "Projects/Design"}). */
public final class StoragePaths {

    public static final String SEPARATOR = "/";

    private StoragePaths() {
    }

    public static String join(List<String> segments) {
        return String.join(SEPARATOR, segments);
    }

    public static String child(String folder, String name) {
        return folder.isEmpty() ? name : folder + SEPARATOR + name;
    }

    /**
     * A single path segment that every storage accepts: no separators, no {@code .}/{@code ..}, and no
     * control characters, which some protocols (FTP) would send as part of their command line.
     */
    public static void requireValidName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains(SEPARATOR) || name.contains("\\") || hasControlCharacter(name)) {
            throw new StorageException("“" + name + "” is not a valid name.");
        }
    }

    /** A relative path whose every segment is a valid name; {@code ""} is the root. */
    public static void requireValidPath(String path) {
        if (path == null) {
            throw new StorageException("“null” is not a valid name.");
        }
        if (!path.isEmpty()) {
            // -1 keeps trailing empty segments, so "a/" is refused like "a//b"
            for (final var segment : path.split(SEPARATOR, -1)) {
                requireValidName(segment);
            }
        }
    }

    private static boolean hasControlCharacter(String name) {
        return name.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    public static List<String> segments(String path) {
        if (path.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(path.split(SEPARATOR));
    }
}
