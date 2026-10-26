package org.vaadin.filebrowser.data;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class FsEntry implements Serializable {

    public static final String FOLDER_TYPE = "folder";

    private final String id;
    private String name;
    private final String type;
    private final Long size;
    private final int modifiedDaysAgo;
    private final String owner;
    private final List<FsEntry> children;

    private FsEntry(String id, String name, String type, Long size, int modifiedDaysAgo, String owner,
                    List<FsEntry> children) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.size = size;
        this.modifiedDaysAgo = modifiedDaysAgo;
        this.owner = owner;
        this.children = children;
    }

    public static FsEntry folder(String id, String name, int modifiedDaysAgo, String owner) {
        return new FsEntry(id, name, FOLDER_TYPE, null, modifiedDaysAgo, owner, new ArrayList<>());
    }

    public static FsEntry file(String id, String name, String type, long size, int modifiedDaysAgo, String owner) {
        return new FsEntry(id, name, type, size, modifiedDaysAgo, owner, null);
    }

    public FsEntry deepCopy(Supplier<String> ids, String newName) {
        final var copy = new FsEntry(ids.get(), newName, type, size, modifiedDaysAgo, owner,
                children == null ? null : new ArrayList<>());
        if (children != null) {
            children.forEach(child -> copy.children.add(child.deepCopy(ids, child.name)));
        }
        return copy;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public boolean folder() {
        return children != null;
    }

    public String type() {
        return type;
    }

    public Long size() {
        return size;
    }

    public int modifiedDaysAgo() {
        return modifiedDaysAgo;
    }

    public String owner() {
        return owner;
    }

    /** Mutable child list of a folder; empty for files. */
    public List<FsEntry> children() {
        return children == null ? List.of() : children;
    }
}
