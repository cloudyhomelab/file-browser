package org.vaadin.filebrowser.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SortKey {
    NAME("Name"),
    MODIFIED("Last modified"),
    SIZE("Size");

    private final String label;
}
