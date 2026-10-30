package org.vaadin.filebrowser.data;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Stream;

/** What the user picked or dropped at once: loose files and whole folders. */
public record Incoming(List<IncomingFile> files, List<IncomingFolder> folders) implements Serializable {

    public Incoming {
        files = List.copyOf(files);
        folders = List.copyOf(folders);
    }

    public boolean isEmpty() {
        return files.isEmpty() && folders.isEmpty();
    }

    public int itemCount() {
        return files.size() + folders.size();
    }

    /** Its files larger than {@code maxBytes}, in folders too. */
    public List<IncomingFile> filesOver(long maxBytes) {
        final var inFolders = folders.stream().flatMap(f -> f.files().stream());
        return Stream.concat(files.stream(), inFolders).filter(f -> f.size() > maxBytes).toList();
    }

    /** Without the files larger than {@code maxBytes}; folders stay, even when none of their files is left. */
    public Incoming withoutFilesOver(long maxBytes) {
        final var kept = files.stream().filter(f -> f.size() <= maxBytes).toList();
        final var keptFolders = folders.stream()
                .map(d -> d.withFiles(d.files().stream().filter(f -> f.size() <= maxBytes).toList()))
                .toList();
        return new Incoming(kept, keptFolders);
    }

    /** Every file and folder in it, at every depth. */
    public int entryCount() {
        return files.size() + folders.stream().mapToInt(IncomingFolder::entryCount).sum();
    }
}
