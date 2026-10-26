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

    /** A single path segment that every storage accepts: no separators, no {@code .}/{@code ..}. */
    public static void requireValidName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains(SEPARATOR) || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new StorageException("“" + name + "” is not a valid name.");
        }
    }

    public static List<String> segments(String path) {
        if (path.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(path.split(SEPARATOR));
    }
}
