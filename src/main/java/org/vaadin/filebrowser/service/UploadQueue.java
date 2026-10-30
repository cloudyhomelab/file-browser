package org.vaadin.filebrowser.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.vaadin.filebrowser.data.ArchiveFormat;
import org.vaadin.filebrowser.data.ExtractOptions;
import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.IncomingFile;
import org.vaadin.filebrowser.data.IncomingFolder;
import org.vaadin.filebrowser.storage.StorageException;
import org.vaadin.filebrowser.storage.StoragePaths;
import org.vaadin.filebrowser.storage.StorageProvider;

/**
 * The session's uploads. Bytes arrive on upload request threads (outside the Vaadin session lock),
 * so everything here is safe to call from any thread.
 */
// holds app-wide services; sessions are never serialized. Remove with #76
@SuppressWarnings("PMD.NonSerializableClass")
public class UploadQueue implements Serializable {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final StorageService storage;
    private final List<UploadTask> tasks = new CopyOnWriteArrayList<>();
    private final List<FolderUpload> folderUploads = new CopyOnWriteArrayList<>();

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

    /** A folder to upload, under the name chosen for it in the destination. */
    public record PlannedFolder(String name, IncomingFolder folder) implements Serializable {

        /** The folders left after the user's choices, each under a name not in {@code taken}, which it is added to. */
        public static List<PlannedFolder> plan(List<IncomingFolder> folders, Map<String, Workspace.ConflictChoice> choices,
                                               Set<String> taken) {
            final var planned = new ArrayList<PlannedFolder>();
            for (final var folder : folders) {
                final var choice = choices.get(folder.name());
                if (choice == Workspace.ConflictChoice.SKIP) {
                    continue;
                }
                final var name = choice == Workspace.ConflictChoice.KEEP_BOTH
                        ? Workspace.keepBothName(folder.name(), taken) : folder.name();
                taken.add(name);
                planned.add(new PlannedFolder(name, folder));
            }
            return planned;
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
                    p.extraction(), null);
            added.add(task);
        }
        tasks.addAll(added);
        return added;
    }

    /**
     * Queues a folder and its files under {@code name} in {@code destination}; nothing is created yet. Its
     * files wait until {@link #prepare} has created the folders.
     */
    public FolderUpload enqueueFolder(FsRoot root, List<String> destination, PlannedFolder planned) {
        final var name = planned.name();
        final var folder = planned.folder();
        if (root.readOnly()) {
            throw new StorageException("This file system is read only.");
        }
        final var id = UUID.randomUUID().toString();
        final var files = new ArrayList<UploadTask>();
        for (final var file : folder.files()) {
            final var where = new ArrayList<>(destination);
            where.add(name);
            where.addAll(file.folder());
            final var task = new UploadTask(UUID.randomUUID().toString(), file.clientId(), file.name(), file.type(),
                    file.size(), root, List.copyOf(where), false, null, null, id);
            files.add(task);
        }
        final var upload = new FolderUpload(id, name, root, List.copyOf(destination), folder.folders(),
                List.copyOf(files));
        tasks.addAll(files);
        folderUploads.add(upload);
        return upload;
    }

    /**
     * Creates a folder upload's folders, parents first, then lets its files be sent. On a failure the
     * folders created so far stay, and the upload and all its files fail.
     */
    public void prepare(FolderUpload upload) {
        final var provider = storage.provider(upload.getRoot());
        final var parent = StoragePaths.join(upload.getDestination());
        try {
            if (upload.getState() != FolderUpload.State.PREPARING) {
                return;
            }
            provider.createFolder(parent, upload.getName());
            final var top = StoragePaths.child(parent, upload.getName());
            for (final var folder : upload.getFolders()) {
                if (upload.getState() != FolderUpload.State.PREPARING) {
                    return;
                }
                final var above = folder.subList(0, folder.size() - 1);
                final var inside = above.isEmpty() ? top : StoragePaths.child(top, StoragePaths.join(above));
                provider.createFolder(inside, folder.getLast());
            }
        } catch (StorageException e) {
            if (upload.transition(FolderUpload.State.PREPARING, FolderUpload.State.FAILED, e.getMessage())) {
                upload.getFiles().forEach(t -> t.transition(UploadTask.State.PREPARING, UploadTask.State.FAILED, e.getMessage()));
            }
            return;
        }
        // files first: the browser may send them as soon as the upload is ready
        upload.getFiles().forEach(t -> t.transition(UploadTask.State.PREPARING, UploadTask.State.QUEUED, null));
        upload.transition(FolderUpload.State.PREPARING, FolderUpload.State.READY, null);
    }

    public List<UploadTask> all() {
        return List.copyOf(tasks);
    }

    /** Uploads of single files, outside any folder upload. */
    public List<UploadTask> loose() {
        return tasks.stream().filter(t -> t.getFolderUploadId() == null).toList();
    }

    public List<FolderUpload> folders() {
        return List.copyOf(folderUploads);
    }

    public boolean active() {
        return tasks.stream().anyMatch(t -> !t.finished()) || folderUploads.stream().anyMatch(f -> !f.finished());
    }

    /** Cancels unfinished uploads and forgets all of them. */
    public void clear() {
        folderUploads.forEach(f -> f.transition(FolderUpload.State.PREPARING, FolderUpload.State.CANCELLED, "Cancelled"));
        tasks.forEach(t -> {
            if (!t.finished()) {
                t.transition(UploadTask.State.CANCELLED, "Cancelled");
            }
        });
        tasks.clear();
        folderUploads.clear();
    }

    /** The browser no longer has the file (e.g. the page was reloaded before the upload started). */
    public void lost(UploadTask task) {
        task.transition(UploadTask.State.QUEUED, UploadTask.State.FAILED,
                "“" + task.getName() + "” is no longer available to upload.");
    }

    /** Finished single-file uploads not yet reported to the user; each is returned once. UI thread only. */
    public List<UploadTask> takeFinished() {
        return tasks.stream().filter(t -> t.getFolderUploadId() == null).filter(UploadTask::announce).toList();
    }

    /** Finished folder uploads not yet reported to the user; each is returned once. UI thread only. */
    public List<FolderUpload> takeFinishedFolders() {
        return folderUploads.stream().filter(FolderUpload::announce).toList();
    }

    /** Files of folder uploads whose folders now exist, for the browser to send; each is returned once. UI thread only. */
    public List<UploadTask> takeReady() {
        return folderUploads.stream()
                .flatMap(f -> f.takeReady().stream())
                .flatMap(List::stream)
                .toList();
    }

    private Optional<UploadTask> find(String id) {
        return tasks.stream().filter(t -> t.getId().equals(id)).findFirst();
    }

    /**
     * Streams an upload into storage. Only a queued task can receive bytes, once; the file appears in
     * its folder only after all of it arrived.
     */
    public void receive(String taskId, InputStream in, long maxBytes) {
        final var task = find(taskId).orElseThrow(() -> new StorageException("Unknown upload."));
        if (!task.transition(UploadTask.State.QUEUED, UploadTask.State.RECEIVING, null)) {
            throw new StorageException("This upload was already handled.");
        }
        final var provider = storage.provider(task.getRoot());
        final var folder = StoragePaths.join(task.getFolder());
        if (task.getExtraction() != null) {
            receiveArchive(task, in, maxBytes, provider, folder);
            return;
        }
        try (var write = provider.openWrite(folder, task.getName(), task.isReplace())) {
            final var buffer = new byte[BUFFER_SIZE];
            long total = 0;
            for (var read = in.read(buffer); read != -1; read = in.read(buffer)) {
                if (task.getState() == UploadTask.State.CANCELLED) {
                    return;
                }
                total += read;
                if (total > maxBytes) {
                    throw new StorageException("“" + task.getName() + "” is larger than the upload limit.");
                }
                write.getStream().write(buffer, 0, read);
                task.setReceived(total);
            }
            write.commit();
            task.transition(UploadTask.State.DONE, null);
        } catch (StorageException e) {
            task.transition(UploadTask.State.FAILED, e.getMessage());
        } catch (LinkFetcher.TooLargeException e) {
            task.transition(UploadTask.State.FAILED, "“" + task.getName() + "” is larger than the upload limit.");
        } catch (IOException e) {
            task.transition(UploadTask.State.FAILED, "The upload of “" + task.getName() + "” was interrupted.");
        }
    }

    /**
     * An archive to unpack is first received into a temp file (zip and 7z need to jump around in
     * it), then extracted; the temp file is deleted whatever happens.
     */
    private void receiveArchive(UploadTask task, InputStream in, long maxBytes, StorageProvider provider, String folder) {
        final var extraction = task.getExtraction();
        Path temp = null;
        try {
            temp = Files.createTempFile("filebrowser-", ".upload");
            if (!copyToTemp(task, in, maxBytes, temp)) {
                return;
            }
            task.transition(UploadTask.State.EXTRACTING, null);
            final var target = extraction.folderName() == null ? folder : StoragePaths.child(folder, extraction.folderName());
            if (extraction.folderName() != null) {
                provider.createFolder(folder, extraction.folderName());
            }
            final ArchiveExtractor.Result result;
            try {
                result = ArchiveExtractor.extract(temp, extraction.format(), extraction.singleName(), provider, target,
                        extraction.clash(), extraction.limits(), task.getName());
            } catch (StorageException e) {
                if (extraction.folderName() != null) {
                    deleteQuietly(provider, target);
                }
                throw e;
            }
            if (extraction.keepArchive()) {
                storeArchive(task, temp, provider, folder);
            }
            task.setSummary(Formats.plural(result.written(), "file") + " extracted"
                    + (result.skipped() > 0 ? ", " + result.skipped() + " skipped" : ""));
            task.transition(UploadTask.State.DONE, null);
        } catch (StorageException e) {
            task.transition(UploadTask.State.FAILED, e.getMessage());
        } catch (IOException e) {
            task.transition(UploadTask.State.FAILED, "The upload of “" + task.getName() + "” was interrupted.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the OS cleans its temp folder eventually
                }
            }
        }
    }

    /** @return false when the upload ended (cancelled or too large) before all of it arrived */
    private static boolean copyToTemp(UploadTask task, InputStream in, long maxBytes, Path temp) throws IOException {
        try (var out = Files.newOutputStream(temp)) {
            final var buffer = new byte[BUFFER_SIZE];
            long total = 0;
            for (var read = in.read(buffer); read != -1; read = in.read(buffer)) {
                if (task.getState() == UploadTask.State.CANCELLED) {
                    return false;
                }
                total += read;
                if (total > maxBytes) {
                    task.transition(UploadTask.State.FAILED, "“" + task.getName() + "” is larger than the upload limit.");
                    return false;
                }
                out.write(buffer, 0, read);
                task.setReceived(total);
            }
        } catch (LinkFetcher.TooLargeException e) {
            task.transition(UploadTask.State.FAILED, "“" + task.getName() + "” is larger than the upload limit.");
            return false;
        }
        return true;
    }

    private static void storeArchive(UploadTask task, Path temp, StorageProvider provider, String folder) throws IOException {
        try (var write = provider.openWrite(folder, task.getName(), task.isReplace());
             var in = Files.newInputStream(temp)) {
            in.transferTo(write.getStream());
            write.commit();
        }
    }

    private static void deleteQuietly(StorageProvider provider, String path) {
        try {
            provider.delete(path);
        } catch (StorageException ignored) {
            // reporting the extraction error matters more
        }
    }

    /** Downloads a link task's file on the calling thread and streams it in like any upload. */
    public void download(UploadTask task, LinkFetcher links, long maxBytes) {
        final InputStream in;
        try {
            in = links.fetch(task.getUrl(), maxBytes);
        } catch (StorageException e) {
            task.transition(UploadTask.State.QUEUED, UploadTask.State.FAILED, e.getMessage());
            return;
        }
        try (in) {
            receive(task.getId(), in, maxBytes);
        } catch (IOException ignored) {
            // closing the download; the task's state is already settled
        }
    }
}
