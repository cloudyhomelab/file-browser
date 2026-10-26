package org.vaadin.filebrowser.data;

public enum SortKey {
    NAME("Name"),
    MODIFIED("Last modified"),
    SIZE("Size");

    private final String label;

    SortKey(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
