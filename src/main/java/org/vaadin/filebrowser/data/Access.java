package org.vaadin.filebrowser.data;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Access {
    @JsonProperty("rw") READ_WRITE,
    @JsonProperty("ro") READ_ONLY,
    @JsonProperty("locked") LOCKED
}
