package org.vaadin.filebrowser.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-time settings under {@code filebrowser.*}, also settable as {@code FILEBROWSER_*} env vars. */
@ConfigurationProperties("filebrowser")
public record FileBrowserProperties(Path databasePath) {
}
