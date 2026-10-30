package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.vaadin.filebrowser.data.Formats;
import org.vaadin.filebrowser.data.FsRoot;

import lombok.AccessLevel;
import lombok.Getter;

/**
 * A folder being uploaded with everything in it. Its folders are created first, on a thread of their own;
 * its files wait in {@link UploadTask.State#PREPARING} until then and are uploaded as {@link UploadTask}s.
 */
@Getter
public final class FolderUpload implements Serializable {

    public enum State { PREPARING, READY, FAILED, CANCELLED }

    private static final int PERCENT = 100;

    private final String id;
    /** When it was queued, so the newest can be shown first. */
    private final Instant queuedAt = Instant.now();
    /** The name it is stored under in {@link #destination}. */
    private final String name;
    private final FsRoot root;
    private final List<String> destination;
    /** The folders below it, relative to it, parents first. */
    private final List<List<String>> folders;
    private final List<UploadTask> files;
    private volatile State state = State.PREPARING;
    private volatile String error;
    @Getter(AccessLevel.NONE)
    private boolean sent;
    @Getter(AccessLevel.NONE)
    private boolean announced;

    FolderUpload(String id, String name, FsRoot root, List<String> destination, List<List<String>> folders,
                 List<UploadTask> files) {
        this.id = id;
        this.name = name;
        this.root = root;
        this.destination = destination;
        this.folders = folders;
        this.files = files;
    }

    synchronized boolean transition(State expected, State newState, String message) {
        if (state != expected) {
            return false;
        }
        this.state = newState;
        this.error = message;
        return true;
    }

    /** Its files once its folders exist, the first time this is asked; else empty. Called on the UI thread only. */
    Optional<List<UploadTask>> takeReady() {
        if (sent || state != State.READY) {
            return Optional.empty();
        }
        sent = true;
        return Optional.of(files);
    }

    /** True once, for the UI to report a finished folder upload exactly one time. Called on the UI thread only. */
    boolean announce() {
        if (announced || !finished()) {
            return false;
        }
        announced = true;
        return true;
    }

    public boolean finished() {
        return switch (state) {
            case PREPARING -> false;
            case READY -> files.stream().allMatch(UploadTask::finished);
            case FAILED, CANCELLED -> true;
        };
    }

    public long size() {
        return files.stream().mapToLong(f -> Math.max(0, f.getSize())).sum();
    }

    public int filesDone() {
        return (int) files.stream().filter(UploadTask::complete).count();
    }

    public int filesFailed() {
        return (int) files.stream().filter(f -> f.getState() == UploadTask.State.FAILED).count();
    }

    /** Why it, or the first of its files that failed, failed; null when nothing failed. */
    public String firstError() {
        if (error != null) {
            return error;
        }
        return files.stream()
                .filter(f -> f.getState() == UploadTask.State.FAILED)
                .map(UploadTask::getError)
                .findFirst()
                .orElse(null);
    }

    /** "trip/empty · Dev bucket", or "Dev bucket" at the top of the file system. */
    public String destinationName() {
        return UploadTask.placeName(root, destination);
    }

    /** What came of it once finished, for the user. */
    public String outcome() {
        if (state == State.FAILED) {
            return error;
        }
        final var count = Formats.plural(files.size(), "file");
        final var failed = filesFailed();
        final var where = UploadTask.placeLabel(root, destination);
        return failed == 0 ? "Uploaded “" + name + "” (" + count + ") to " + where
                : "“" + name + "” in " + where + ": " + failed + " of " + count + " failed";
    }

    public int progress() {
        if (finished()) {
            return PERCENT;
        }
        final var total = size();
        final var received = files.stream().mapToLong(UploadTask::receivedBytes).sum();
        return total <= 0 ? 0 : (int) Math.min(PERCENT - 1, received * PERCENT / total);
    }
}
