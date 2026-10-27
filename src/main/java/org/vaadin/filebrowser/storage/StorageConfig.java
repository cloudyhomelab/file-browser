package org.vaadin.filebrowser.storage;

import java.util.Map;

/**
 * What a {@link StorageType} needs to open a file system: its endpoint, plain settings (stored as
 * they are) and secrets (stored encrypted). Which keys go where is up to each type.
 */
public record StorageConfig(String endpoint, Map<String, String> settings, Map<String, String> secrets) {

    public StorageConfig {
        settings = Map.copyOf(settings);
        secrets = Map.copyOf(secrets);
    }

    /** @return null when not set */
    public String setting(String key) {
        return settings.get(key);
    }

    /** @return null when not set */
    public String secret(String key) {
        return secrets.get(key);
    }

    /** Never print the secrets, e.g. in a log line or an error. */
    @Override
    public String toString() {
        return "StorageConfig[endpoint=" + endpoint + ", settings=" + settings + ", secrets=" + secrets.keySet() + "]";
    }
}
