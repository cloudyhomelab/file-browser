package org.vaadin.filebrowser.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.vaadin.filebrowser.storage.StorageEntry;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;

import com.vaadin.flow.server.streams.DownloadHandler;

/** Streams files out of a storage provider: one file as-is, anything else as a zip built on the fly. */
public final class Downloads {

    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String ZIP = "application/zip";

    private Downloads() {
    }

    /**
     * @param zipName file name used when more than one entry or a folder is downloaded
     */
    public static DownloadHandler of(StorageProvider provider, List<StorageEntry> entries, String zipName) {
        if (entries.size() == 1 && !entries.getFirst().folder()) {
            final var file = entries.getFirst();
            return event -> {
                event.setFileName(file.name());
                event.setContentType(OCTET_STREAM);
                if (file.size() != null) {
                    event.setContentLength(file.size());
                }
                try (var in = provider.openRead(file.path())) {
                    in.transferTo(event.getOutputStream());
                }
            };
        }
        return event -> {
            event.setFileName(zipName);
            event.setContentType(ZIP);
            writeZip(provider, entries, event.getOutputStream());
        };
    }

    static void writeZip(StorageProvider provider, List<StorageEntry> entries, OutputStream out) throws IOException {
        try (var zip = new ZipOutputStream(out)) {
            for (final var entry : entries) {
                add(provider, entry, entry.name(), zip);
            }
        }
    }

    private static void add(StorageProvider provider, StorageEntry entry, String nameInZip, ZipOutputStream zip)
            throws IOException {
        if (entry.folder()) {
            final var folderEntry = new ZipEntry(nameInZip + StoragePaths.SEPARATOR);
            zip.putNextEntry(folderEntry);
            zip.closeEntry();
            for (final var child : provider.list(entry.path())) {
                add(provider, child, StoragePaths.child(nameInZip, child.name()), zip);
            }
            return;
        }
        final var fileEntry = new ZipEntry(nameInZip);
        zip.putNextEntry(fileEntry);
        try (var in = provider.openRead(entry.path())) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }
}
