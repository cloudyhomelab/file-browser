package org.vaadin.filebrowser.service;

import java.net.URI;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.S3Settings;
import org.vaadin.filebrowser.storage.S3Provider;
import org.vaadin.filebrowser.storage.StorageException;

/**
 * The endpoint and access keys of S3 file systems, kept in {@code file_system} with the keys
 * encrypted by {@link SecretBox}. Decrypted keys only ever go to the S3 client.
 */
@Service
public class S3Connections {

    private final JdbcClient jdbc;
    private final SecretBox secrets;

    public S3Connections(JdbcClient jdbc, SecretBox secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    public boolean canStoreKeys() {
        return secrets.available();
    }

    /** The settings with their keys encrypted, ready to store; fails before anything is saved without a secret key. */
    public S3Settings seal(S3Settings settings) {
        if (settings.anonymous()) {
            return settings;
        }
        return new S3Settings(settings.endpointUrl(), secrets.encrypt(settings.accessKey()),
                secrets.encrypt(settings.secretKey()));
    }

    public void store(String rootId, S3Settings sealed) {
        jdbc.sql("UPDATE file_system SET endpoint_url = :url, access_key = :key, secret_key = :secret WHERE id = :id")
                .param("id", rootId)
                .param("url", sealed.endpointUrl())
                .param("key", sealed.accessKey())
                .param("secret", sealed.secretKey())
                .update();
    }

    /** How to reach a registered S3 file system, with its keys decrypted. */
    public S3Provider.Connection connection(FsRoot root) {
        final var stored = jdbc.sql("SELECT endpoint_url, access_key, secret_key FROM file_system WHERE id = :id")
                .param("id", root.id())
                .query((rs, row) -> new S3Settings(rs.getString("endpoint_url"), rs.getString("access_key"),
                        rs.getString("secret_key")))
                .optional()
                .orElseThrow(() -> new StorageException("This file system was removed."));
        final var settings = stored.anonymous() ? stored
                : new S3Settings(stored.endpointUrl(), secrets.decrypt(stored.accessKey()), secrets.decrypt(stored.secretKey()));
        return connection(root.endpoint(), root.region(), settings);
    }

    /** For a bucket that isn't registered yet, e.g. to check it before adding it. */
    public static S3Provider.Connection connection(String endpoint, String region, S3Settings settings) {
        final var url = settings.endpointUrl() == null || settings.endpointUrl().isBlank() ? null
                : URI.create(settings.endpointUrl());
        return new S3Provider.Connection(endpoint, region, url, settings.accessKey(), settings.secretKey());
    }
}
