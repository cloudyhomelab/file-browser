package org.vaadin.filebrowser.data;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RootType {
    @JsonProperty("disk") DISK,
    @JsonProperty("s3") S3;

    public String key() {
        return name().toLowerCase();
    }
}
