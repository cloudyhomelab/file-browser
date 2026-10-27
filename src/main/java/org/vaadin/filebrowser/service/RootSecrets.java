package org.vaadin.filebrowser.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.storage.StorageException;

/**
 * The secrets of file systems (e.g. S3 access keys), kept in {@code file_system.secrets} encrypted
 * by {@link SecretBox}. Decrypted values only ever go to the storage that needs them.
 */
@Service
public class RootSecrets {

    private final JdbcClient jdbc;
    private final SecretBox box;

    public RootSecrets(JdbcClient jdbc, SecretBox box) {
        this.jdbc = jdbc;
        this.box = box;
    }

    public boolean canStore() {
        return box.available();
    }

    /**
     * The secrets encrypted for the file system {@code rootId}, ready to store with it; fails
     * before anything is saved without a secret key.
     */
    public Map<String, String> seal(String rootId, Map<String, String> secrets) {
        final var sealed = new LinkedHashMap<String, String>();
        secrets.forEach((key, value) -> sealed.put(key, box.encrypt(value, context(rootId, key))));
        return sealed;
    }

    // each value only decrypts for its own file system and key
    private static String context(String rootId, String key) {
        return rootId + "/" + key;
    }

    /** The decrypted secrets of a registered file system; empty when it has none. */
    public Map<String, String> open(String rootId) {
        final var stored = stored(rootId);
        final var plain = new LinkedHashMap<String, String>();
        JsonMaps.read(stored).forEach((key, value) -> plain.put(key, box.decrypt(value, context(rootId, key))));
        return plain;
    }

    /** Which secrets a registered file system has, e.g. to show that a password is set; never their values. */
    public Set<String> names(String rootId) {
        return Set.copyOf(JsonMaps.read(stored(rootId)).keySet());
    }

    private String stored(String rootId) {
        return jdbc.sql("SELECT secrets FROM file_system WHERE id = :id")
                .param("id", rootId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new StorageException("This file system was removed."));
    }
}
