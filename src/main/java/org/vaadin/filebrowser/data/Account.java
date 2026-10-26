package org.vaadin.filebrowser.data;

import java.io.Serializable;

public record Account(String email, String name) implements Serializable {
}
