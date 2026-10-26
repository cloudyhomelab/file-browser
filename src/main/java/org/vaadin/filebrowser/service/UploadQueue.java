package org.vaadin.filebrowser.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.vaadin.filebrowser.data.ArchiveFormat;
import org.vaadin.filebrowser.data.ExtractOptions;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;

/**
 * The session's uploads. Bytes arrive on upload request threads (outside the Vaadin session lock),
 * so everything here is safe to call from any thread.
 */
public class UploadQueue implements Serializable {

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * @param finalName  the name the file is stored under (for an extracted archive: if kept)
     * @param extraction how to unpack it; null to store it as is
     */
    public record Planned(IncomingFile file, String finalName, boolean replace, Extraction extraction)
            implements Serializable {

        public Planned(IncomingFile file, String finalName, boolean replace) {
            this(file, finalName, replace, null);
        }
    }

    /**
     * How an archive upload is unpacked.
     *
     * @param singleName the file name for a single compressed file
     * @param folderName the new folder to extract into; null to extract into the upload's folder
     */
    public record Extraction(ArchiveFormat format, String singleName, String folderName, boolean keepArchive,
                             ExtractOptions.Clash clash, ArchiveExtractor.Limits limits) implements Serializable {
    }

    private final StorageService storage;
    private final List<UploadTask> tasks = new CopyOnWriteArrayList<>();

    public UploadQueue(StorageService storage) {
        this.storage = storage;
    }

    public List<UploadTask> enqueue(FsRoot root, List<String> folder, List<Planned> planned) {
        if (root.readOnly()) {
            throw new StorageException("This file system is read only.");
        }
        final var added = new ArrayList<UploadTask>();
        for (final var p : planned) {
            final var task = new UploadTask(UUID.randomUUID().toString(), p.file().clientId(), p.finalName(),
                    p.file().type(), p.file().size(), root, List.copyOf(folder), p.replace(), p.file().url(),
                    p.extraction());
            added.add(task);
        }
        tasks.addAll(added);
        return added;
    }

    public List<UploadTask> all() {
        return List.copyOf(tasks);
    }

    public boolean active() {
        return tasks.stream().anyMatch(t -> !t.finished());
    }

    /** Cancels unfinished uploads and forgets all of them. */
    public void clear() {
        tasks.forEach(t -> {
            if (!t.finished()) {
                t.state(UploadTask.State.CANCELLED, "Cancelled");
            }
        });
        tasks.clear();
    }

    /** The browser no longer has the file (e.g. the page was reloaded before the upload started). */
    public void lost(UploadTask task) {
        synchronized (task) {
            if (task.state() == UploadTask.State.QUEUED) {
                task.state(UploadTask.State.FAILED, "“" + task.name() + "” is no longer available to upload.");
            }
        }
    }

    /** Finished uploads not yet reported to the user; each is returned once. UI thread only. */
    public List<UploadTask> takeFinished() {
        return tasks.stream().filter(UploadTask::announce).toList();
    }

    private Optional<UploadTask> find(String id) {
        return tasks.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /**
     * Streams an upload into storage. Only a queued task can receive bytes, once; the file appears in
     * its folder only after all of it arrived.
     */
    public void receive(String taskId, InputStream in, long maxBytes) {
        final var task = find(taskId).orElseThrow(() -> new StorageException("Unknown upload."));
        synchronized (task) {
            if (task.state() != UploadTask.State.QUEUED) {
                throw new StorageException("This upload was already handled.");
            }
            task.state(UploadTask.State.RECEIVING, null);
        }
        final var provider = storage.provider(task.root());
        final var folder = StoragePaths.join(task.folder());
        if (task.extraction() != null) {
            receiveArchive(task, in, maxBytes, provider, folder);
            return;
        }
        try (final var write = provider.openWrite(folder, task.name(), task.replace())) {
            final var buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (task.state() == UploadTask.State.CANCELLED) {
                    return;
                }
                total += read;
                if (total > maxBytes) {
                    throw new StorageException("“" + task.name() + "” is larger than the upload limit.");
                }
                write.stream().write(buffer, 0, read);
                task.received(total);
            }
            write.commit();
            task.state(UploadTask.State.DONE, null);
        } catch (StorageException e) {
            task.state(UploadTask.State.FAILED, e.getMessage());
        } catch (LinkFetcher.TooLargeException e) {
            task.state(UploadTask.State.FAILED, "“" + task.name() + "” is larger than the upload limit.");
        } catch (IOException e) {
            task.state(UploadTask.State.FAILED, "The upload of “" + task.name() + "” was interrupted.");
        }
    }

    /**
     * An archive to unpack is first received into a temp file (zip and 7z need to jump around in
     * it), then extracted; the temp file is deleted whatever happens.
     */
    private void receiveArchive(UploadTask task, InputStream in, long maxBytes, StorageProvider provider, String folder) {
        final var extraction = task.extraction();
        Path temp = null;
        try {
            temp = Files.createTempFile("filebrowser-", ".upload");
            if (!copyToTemp(task, in, maxBytes, temp)) {
                return;
            }
            task.state(UploadTask.State.EXTRACTING, null);
            final var target = extraction.folderName() == null ? folder : StoragePaths.child(folder, extraction.folderName());
            if (extraction.folderName() != null) {
                provider.createFolder(folder, extraction.folderName());
            }
            final ArchiveExtractor.Result result;
            try {
                result = ArchiveExtractor.extract(temp, extraction.format(), extraction.singleName(), provider, target,
                        extraction.clash(), extraction.limits(), task.name());
            } catch (StorageException e) {
                if (extraction.folderName() != null) {
                    deleteQuietly(provider, target);
                }
                throw e;
            }
            if (extraction.keepArchive()) {
                storeArchive(task, temp, provider, folder);
            }
            task.summary(Formats.plural(result.written(), "file") + " extracted"
                    + (result.skipped() > 0 ? ", " + result.skipped() + " skipped" : ""));
            task.state(UploadTask.State.DONE, null);
        } catch (StorageException e) {
            task.state(UploadTask.State.FAILED, e.getMessage());
        } catch (IOException e) {
            task.state(UploadTask.State.FAILED, "The upload of “" + task.name() + "” was interrupted.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // the OS cleans its temp folder eventually
                }
            }
        }
    }

    /** @return false when the upload ended (cancelled or too large) before all of it arrived */
    private static boolean copyToTemp(UploadTask task, InputStream in, long maxBytes, Path temp) throws IOException {
        try (final var out = Files.newOutputStream(temp)) {
            final var buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (task.state() == UploadTask.State.CANCELLED) {
                    return false;
                }
                total += read;
                if (total > maxBytes) {
                    task.state(UploadTask.State.FAILED, "“" + task.name() + "” is larger than the upload limit.");
                    return false;
                }
                out.write(buffer, 0, read);
                task.received(total);
            }
        } catch (LinkFetcher.TooLargeException e) {
            task.state(UploadTask.State.FAILED, "“" + task.name() + "” is larger than the upload limit.");
            return false;
        }
        return true;
    }

    private static void storeArchive(UploadTask task, Path temp, StorageProvider provider, String folder) throws IOException {
        try (final var write = provider.openWrite(folder, task.name(), task.replace());
             final var in = Files.newInputStream(temp)) {
            in.transferTo(write.stream());
            write.commit();
        }
    }

    private static void deleteQuietly(StorageProvider provider, String path) {
        try {
            provider.delete(path);
        } catch (StorageException e) {
            // reporting the extraction error matters more
        }
    }

    /** Downloads a link task's file on the calling thread and streams it in like any upload. */
    public void download(UploadTask task, LinkFetcher links, long maxBytes) {
        final InputStream in;
        try {
            in = links.fetch(task.url(), maxBytes);
        } catch (StorageException e) {
            synchronized (task) {
                if (task.state() == UploadTask.State.QUEUED) {
                    task.state(UploadTask.State.FAILED, e.getMessage());
                }
            }
            return;
        }
        try (in) {
            receive(task.id(), in, maxBytes);
        } catch (IOException e) {
            // closing the download; the task's state is already settled
        }
    }
}
