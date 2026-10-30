package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.List;

import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.storage.StoragePaths;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * One file being uploaded. Progress is written by the request thread that receives the bytes and
 * read by the UI, so the changing fields are volatile.
 */
@Getter
public final class UploadTask implements Serializable {

    /** {@code PREPARING}: waiting for the folders of its {@link FolderUpload} to be created. */
    public enum State { PREPARING, QUEUED, RECEIVING, EXTRACTING, DONE, FAILED, CANCELLED }

    private static final int PERCENT = 100;

    private final String id;
    private final String clientId;
    private final String name;
    private final String type;
    private final long size;
    private final FsRoot root;
    private final List<String> folder;
    private final boolean replace;
    /** The web link the server downloads it from; null for a file sent by the browser. */
    private final String url;
    /** How to unpack it once received; null to store it as is. */
    private final UploadQueue.Extraction extraction;
    /** The {@link FolderUpload} it belongs to; null for a loose file. */
    private final String folderUploadId;
    /** What came of it, e.g. how many files an extraction wrote; null for a plain upload. */
    @Setter(AccessLevel.PACKAGE)
    private volatile String summary;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.PACKAGE)
    private volatile long received;
    private volatile State state;
    private volatile String error;
    @Getter(AccessLevel.NONE)
    private boolean announced;

    UploadTask(String id, String clientId, String name, String type, long size, FsRoot root, List<String> folder,
               boolean replace, String url, UploadQueue.Extraction extraction, String folderUploadId) {
        this.id = id;
        this.clientId = clientId;
        this.name = name;
        this.type = type;
        this.size = size;
        this.root = root;
        this.folder = folder;
        this.replace = replace;
        this.url = url;
        this.extraction = extraction;
        this.folderUploadId = folderUploadId;
        this.state = folderUploadId == null ? State.QUEUED : State.PREPARING;
    }

    /** "trip/empty · Dev bucket", or "Dev bucket" at the top of the file system. */
    public String destinationName() {
        return placeName(root, folder);
    }

    /** "“trip/empty” in “Dev bucket”", or "“Dev bucket”" at the top of the file system. */
    public String destinationLabel() {
        return placeLabel(root, folder);
    }

    // the whole path: a folder's own name can be in many places, and each one is somewhere else
    static String placeName(FsRoot root, List<String> folder) {
        return folder.isEmpty() ? root.name() : StoragePaths.join(folder) + " · " + root.name();
    }

    static String placeLabel(FsRoot root, List<String> folder) {
        final var top = "“" + root.name() + "”";
        return folder.isEmpty() ? top : "“" + StoragePaths.join(folder) + "” in " + top;
    }

    /** For display: null when unknown (a link that didn't say). */
    public Long knownSize() {
        return size < 0 ? null : size;
    }

    synchronized void transition(State newState, String message) {
        this.state = newState;
        this.error = message;
    }

    /** Moves to {@code newState} only from {@code expected}, in one step; false when it was in another state. */
    synchronized boolean transition(State expected, State newState, String message) {
        if (state != expected) {
            return false;
        }
        transition(newState, message);
        return true;
    }

    /** True once, for the UI to report a finished upload exactly one time. Called on the UI thread only. */
    boolean announce() {
        if (announced || !finished()) {
            return false;
        }
        announced = true;
        return true;
    }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    public boolean complete() {
        return state == State.DONE;
    }

    /** Bytes received so far; all of them once done. */
    public long receivedBytes() {
        return state == State.DONE ? Math.max(size, received) : received;
    }

    public int progress() {
        if (state == State.DONE) {
            return PERCENT;
        }
        return size <= 0 ? 0 : (int) Math.min(PERCENT - 1, received * PERCENT / size);
    }
}
