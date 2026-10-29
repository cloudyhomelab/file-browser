package org.vaadin.filebrowser.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StorageProvider;
import org.vaadin.filebrowser.storage.VerifiedCopy;

/**
 * Deletes the originals of a move between file systems once their copies are verified. A file is deleted only if
 * it is unchanged since it was read, a folder only if it is empty by then, so nothing that arrived or changed
 * during the move is lost.
 */
final class OriginalRemoval {

    /**
     * @param deleted source paths that were deleted, files and folders
     * @param changed names of files kept because they changed after they were read
     * @param added   files found in the original folder that weren't part of the copy
     */
    record Outcome(List<String> deleted, List<String> changed, int added) {
    }

    private OriginalRemoval() {
    }

    /** @param original the moved item as it was picked */
    static Outcome remove(StorageProvider from, StorageEntry original, VerifiedCopy.Result result) {
        final var deleted = new ArrayList<String>();
        final var changed = new ArrayList<String>();
        for (final var file : result.files()) {
            final var now = from.stat(file.path());
            if (now.isEmpty()) {
                continue;
            }
            if (!Objects.equals(now.orElseThrow().size(), file.size()) || !Objects.equals(now.orElseThrow().modified(), file.modified())) {
                changed.add(file.name());
                continue;
            }
            from.delete(file.path());
            deleted.add(file.path());
        }
        for (final var folder : result.folders().reversed()) {
            if (from.stat(folder).isPresent() && from.deleteEmptyFolder(folder)) {
                deleted.add(folder);
            }
        }
        final var left = original.folder() && from.stat(original.path()).isPresent()
                ? fileCount(from, original.path()) - changed.size() : 0;
        return new Outcome(List.copyOf(deleted), List.copyOf(changed), left);
    }

    private static int fileCount(StorageProvider provider, String folder) {
        return provider.list(folder).stream()
                .mapToInt(child -> child.folder() ? fileCount(provider, child.path()) : 1)
                .sum();
    }
}
