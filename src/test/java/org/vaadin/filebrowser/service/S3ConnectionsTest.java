package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.Capacity;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootType;
import org.vaadin.filebrowser.data.S3Settings;
import org.vaadin.filebrowser.storage.MountInfo;
import org.vaadin.filebrowser.storage.StorageException;

class S3ConnectionsTest {

    @TempDir
    Path dir;

    private JdbcClient jdbc;
    private FileSystemRegistry registry;
    private final FsRoot bucket = new FsRoot("b", "Assets", RootType.S3, "s3://assets/team", "eu-central-1", null, true,
            Access.READ_WRITE, MountInfo.UNKNOWN);

    private static SecretBox box() {
        return new SecretBox(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void migrate() {
        final var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        final var online = new StorageService.RootStatus(new Capacity(0, null), true, MountInfo.UNKNOWN);
        registry = new FileSystemRegistry(jdbc, () -> true, () -> "admin@example.com", Clock.systemUTC(), r -> online);
        registry.add(bucket);
    }

    @Test
    void keysAreStoredEncryptedAndComeBackForTheClient() {
        final var connections = new S3Connections(jdbc, box());
        final var settings = new S3Settings("http://localhost:9000", "AKIDEXAMPLE", "wJalrXUtnFEMI");
        connections.store(bucket.id(), connections.seal(bucket.id(), settings));

        final var stored = jdbc.sql("SELECT access_key || ' ' || secret_key FROM file_system").query(String.class).single();
        assertThat(stored).doesNotContain("AKIDEXAMPLE").doesNotContain("wJalrXUtnFEMI");

        final var connection = connections.connection(bucket);
        assertThat(connection.endpoint()).isEqualTo("s3://assets/team");
        assertThat(connection.region()).isEqualTo("eu-central-1");
        assertThat(connection.endpointUrl()).hasToString("http://localhost:9000");
        assertThat(connection.accessKey()).isEqualTo("AKIDEXAMPLE");
        assertThat(connection.secretKey()).isEqualTo("wJalrXUtnFEMI");
    }

    @Test
    void anonymousBucketsNeedNoSecretKey() {
        final var connections = new S3Connections(jdbc, new SecretBox((String) null));
        connections.store(bucket.id(), connections.seal(bucket.id(), new S3Settings(null, null, null)));
        final var connection = connections.connection(bucket);
        assertThat(connection.accessKey()).isNull();
        assertThat(connection.endpointUrl()).isNull();
    }

    @Test
    void keysCannotBeStoredWithoutASecretKey() {
        final var connections = new S3Connections(jdbc, new SecretBox((String) null));
        assertThat(connections.canStoreKeys()).isFalse();
        final var settings = new S3Settings(null, "AKIDEXAMPLE", "wJalrXUtnFEMI");
        assertThatThrownBy(() -> connections.seal(bucket.id(), settings)).isInstanceOf(StorageException.class);
        assertThat(settings.toString()).doesNotContain("wJalrXUtnFEMI");
    }

    @Test
    void storedKeysOnlyWorkForTheirOwnFileSystem() {
        final var connections = new S3Connections(jdbc, box());
        final var other = new FsRoot("o", "Other", RootType.S3, "s3://other", "eu-central-1", null, true,
                Access.READ_WRITE, MountInfo.UNKNOWN);
        registry.add(other);
        connections.store(bucket.id(), connections.seal(bucket.id(), new S3Settings(null, "AKIDEXAMPLE", "wJalrXUtnFEMI")));
        // copy the sealed keys onto the other file system, as someone with database access could
        jdbc.sql("UPDATE file_system SET access_key = (SELECT access_key FROM file_system WHERE id = 'b'), "
                + "secret_key = (SELECT secret_key FROM file_system WHERE id = 'b') WHERE id = 'o'").update();
        assertThatThrownBy(() -> connections.connection(other)).isInstanceOf(StorageException.class);
        // nor can the two keys of one file system be swapped
        jdbc.sql("UPDATE file_system SET access_key = secret_key, secret_key = access_key WHERE id = 'b'").update();
        assertThatThrownBy(() -> connections.connection(bucket)).isInstanceOf(StorageException.class);
    }
}
