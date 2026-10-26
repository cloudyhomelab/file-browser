package org.vaadin.filebrowser.service;

import java.io.Serializable;

import java.util.List;

public final class UploadTask implements Serializable {

    private static final int DONE = 100;

    private final String id;
    private final String name;
    private final String type;
    private final long size;
    private final String rootId;
    private final List<String> path;
    private final boolean replace;
    private int progress;

    UploadTask(String id, String name, String type, long size, String rootId, List<String> path, boolean replace) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.size = size;
        this.rootId = rootId;
        this.path = path;
        this.replace = replace;
    }

    /** Returns true when this step completed the upload. */
    boolean advance(int step) {
        if (complete()) {
            return false;
        }
        progress = Math.min(DONE, progress + step);
        return complete();
    }

    public boolean complete() {
        return progress >= DONE;
    }

    public String id() {
        return id;
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

    public String rootId() {
        return rootId;
    }

    public List<String> path() {
        return path;
    }

    public boolean replace() {
        return replace;
    }

    public int progress() {
        return progress;
    }
}
