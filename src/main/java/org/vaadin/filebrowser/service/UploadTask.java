package org.vaadin.filebrowser.service;

import java.io.Serializable;
import java.util.List;

import org.vaadin.filebrowser.data.FsRoot;

/**
 * One file being uploaded. Progress is written by the request thread that receives the bytes and
 * read by the UI, so the changing fields are volatile.
 */
public final class UploadTask implements Serializable {

    public enum State { QUEUED, RECEIVING, DONE, FAILED, CANCELLED }

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
    private volatile long received;
    private volatile State state = State.QUEUED;
    private volatile String error;
    private boolean announced;

    UploadTask(String id, String clientId, String name, String type, long size, FsRoot root, List<String> folder,
               boolean replace, String url) {
        this.id = id;
        this.clientId = clientId;
        this.name = name;
        this.type = type;
        this.size = size;
        this.root = root;
        this.folder = folder;
        this.replace = replace;
        this.url = url;
    }

    public String url() {
        return url;
    }

    /** For display: null when unknown (a link that didn't say). */
    public Long knownSize() {
        return size < 0 ? null : size;
    }

    void received(long bytes) {
        this.received = bytes;
    }

    void state(State newState, String message) {
        this.state = newState;
        this.error = message;
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

    public int progress() {
        if (state == State.DONE) {
            return PERCENT;
        }
        return size <= 0 ? 0 : (int) Math.min(PERCENT - 1, received * PERCENT / size);
    }

    public String id() {
        return id;
    }

    public String clientId() {
        return clientId;
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public long size() {
        return size;
    }

    public FsRoot root() {
        return root;
    }

    public List<String> folder() {
        return folder;
    }

    public boolean replace() {
        return replace;
    }

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }
}
