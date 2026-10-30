package org.vaadin.filebrowser.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A folder the user dropped or picked, before it is uploaded with everything in it.
 *
 * @param folders every folder below it, empty ones included, as paths relative to it, parents first
 * @param files   its files at every depth; each one's {@link IncomingFile#folder()} says where
 */
public record IncomingFolder(String name, List<List<String>> folders, List<IncomingFile> files) implements Serializable {

    public IncomingFolder {
        folders = folders.stream().map(List::copyOf).toList();
        files = List.copyOf(files);
    }

    /** With the folders the files sit in added to {@code folders}, so none is missing, sorted parents first. */
    public static IncomingFolder of(String name, List<List<String>> folders, List<IncomingFile> files) {
        final var all = new LinkedHashMap<String, List<String>>();
        folders.forEach(f -> addWithParents(all, f));
        files.forEach(f -> addWithParents(all, f.folder()));
        final var sorted = new ArrayList<>(all.values());
        sorted.sort(Comparator.comparingInt(List::size));
        return new IncomingFolder(name, sorted, files);
    }

    /** Keyed by the joined path, so each folder is listed once. */
    private static void addWithParents(Map<String, List<String>> all, List<String> folder) {
        for (int depth = 1; depth <= folder.size(); depth++) {
            final var parent = List.copyOf(folder.subList(0, depth));
            all.putIfAbsent(String.join("/", parent), parent);
        }
    }

    public IncomingFolder withFiles(List<IncomingFile> kept) {
        return new IncomingFolder(name, folders, kept);
    }

    public long size() {
        return files.stream().mapToLong(IncomingFile::size).sum();
    }

    /** It, its folders and its files. */
    public int entryCount() {
        return 1 + folders.size() + files.size();
    }
}
