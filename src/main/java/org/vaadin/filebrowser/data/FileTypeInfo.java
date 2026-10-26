package org.vaadin.filebrowser.data;

import java.io.Serializable;

import java.util.List;

public record FileTypeInfo(String label, String icon, List<String> extensions) implements Serializable {
}
