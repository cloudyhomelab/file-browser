package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.data.TestRoots;
import org.vaadin.filebrowser.storage.S3StorageType;
import org.vaadin.filebrowser.storage.StorageConfig;
import org.vaadin.filebrowser.storage.StorageException;

class RootSecretsTest {

    private static final Map<String, String> KEYS =
            Map.of(S3StorageType.ACCESS_KEY, "AKIDEXAMPLE", S3StorageType.SECRET_KEY, "wJalrXUtnFEMI");

    @TempDir
    Path dir;

    private JdbcClient jdbc;
    private FileSystemRegistry registry;
    private final FsRoot bucket = new FsRoot("b", "Assets", S3StorageType.KEY, "s3://assets/team",
            Map.of(S3StorageType.REGION, "eu-central-1"), Access.READ_WRITE, TestRoots.ALL_ALLOWED, RootStatus.UNKNOWN);

    private static SecretBox box() {
        return new SecretBox(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void migrate() {
        final var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        registry = new FileSystemRegistry(jdbc, () -> true, () -> "admin@example.com", Clock.systemUTC(),
                r -> RootStatus.UNKNOWN);
    }

    @Test
    void secretsAreStoredEncryptedAndComeBackForTheStorage() {
        final var secrets = new RootSecrets(jdbc, box());
        registry.add(bucket, secrets.seal(bucket.id(), KEYS));

        final var stored = jdbc.sql("SELECT secrets FROM file_system").query(String.class).single();
        assertThat(stored).doesNotContain("AKIDEXAMPLE").doesNotContain("wJalrXUtnFEMI");
        assertThat(secrets.open(bucket.id())).isEqualTo(KEYS);
        assertThat(registry.find(bucket.id()).orElseThrow().settings()).doesNotContainKeys(KEYS.keySet().toArray(String[]::new));
    }

    @Test
    void entriesWithoutAValueAreIgnored() {
        final var secrets = new RootSecrets(jdbc, box());
        registry.add(bucket, Map.of());
        jdbc.sql("UPDATE file_system SET settings = '{\"region\": \"eu-central-1\", \"endpoint_url\": null}', "
                + "secrets = '{\"access_key\": null}'").update();
        assertThat(registry.find(bucket.id()).orElseThrow().settings()).isEqualTo(bucket.settings());
        assertThat(secrets.open(bucket.id())).isEmpty();
    }

    @Test
    void fileSystemsWithoutSecretsNeedNoSecretKey() {
        final var secrets = new RootSecrets(jdbc, new SecretBox((String) null));
        registry.add(bucket, secrets.seal(bucket.id(), Map.of()));
        assertThat(secrets.open(bucket.id())).isEmpty();
    }

    @Test
    void secretsCannotBeStoredWithoutASecretKey() {
        final var secrets = new RootSecrets(jdbc, new SecretBox((String) null));
        assertThat(secrets.canStore()).isFalse();
        assertThatThrownBy(() -> secrets.seal(bucket.id(), KEYS)).isInstanceOf(StorageException.class);
        final var config = new StorageConfig("s3://assets", Map.of(), KEYS, TestRoots.MAX_CONNECTIONS);
        assertThat(config.toString()).doesNotContain("wJalrXUtnFEMI").doesNotContain("AKIDEXAMPLE");
    }

    @Test
    void storedSecretsOnlyWorkForTheirOwnFileSystemAndKey() {
        final var secrets = new RootSecrets(jdbc, box());
        final var other = new FsRoot("o", "Other", S3StorageType.KEY, "s3://other",
                Map.of(S3StorageType.REGION, "eu-central-1"), Access.READ_WRITE, TestRoots.ALL_ALLOWED,
                RootStatus.UNKNOWN);
        registry.add(other, Map.of());
        registry.add(bucket, secrets.seal(bucket.id(), KEYS));
        // copy the sealed secrets onto the other file system, as someone with database access could
        jdbc.sql("UPDATE file_system SET secrets = (SELECT secrets FROM file_system WHERE id = 'b') WHERE id = 'o'")
                .update();
        assertThatThrownBy(() -> secrets.open(other.id())).isInstanceOf(StorageException.class);
        // nor can two secrets of one file system be swapped
        jdbc.sql("""
                UPDATE file_system SET secrets = json_object(
                    'access_key', secrets ->> '$.secret_key', 'secret_key', secrets ->> '$.access_key')
                WHERE id = 'b'""").update();
        assertThatThrownBy(() -> secrets.open(bucket.id())).isInstanceOf(StorageException.class);
    }

    @Test
    void keysStoredBeforeSettingsMovedToJsonStillOpen() {
        final var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("old.db"));
        Flyway.configure().dataSource(dataSource).target("4").load().migrate();
        final var old = JdbcClient.create(dataSource);
        final var box = box();
        old.sql("""
                INSERT INTO file_system (id, name, type, endpoint, region, access, created_by, created_at,
                                         endpoint_url, access_key, secret_key)
                VALUES ('b', 'Assets', 'S3', 's3://assets', 'eu-central-1', 'READ_WRITE', 'a', 'now',
                        'http://localhost:9000', :key, :secret),
                       ('p', 'Public', 'S3', 's3://public', 'us-east-1', 'READ_ONLY', 'a', 'now', NULL, NULL, NULL),
                       ('d', 'Disk', 'DISK', '/srv/data', '', 'READ_WRITE', 'a', 'now', NULL, NULL, NULL)""")
                .param("key", box.encrypt("AKIDEXAMPLE", "b/access_key"))
                .param("secret", box.encrypt("wJalrXUtnFEMI", "b/secret_key"))
                .update();

        Flyway.configure().dataSource(dataSource).load().migrate();

        final var migrated = new FileSystemRegistry(old, () -> true, () -> "a", Clock.systemUTC(), r -> RootStatus.UNKNOWN);
        final var assets = migrated.find("b").orElseThrow();
        assertThat(assets.type()).isEqualTo(S3StorageType.KEY);
        assertThat(assets.settings()).isEqualTo(
                Map.of(S3StorageType.REGION, "eu-central-1", S3StorageType.ENDPOINT_URL, "http://localhost:9000"));
        assertThat(new RootSecrets(old, box).open("b")).isEqualTo(KEYS);
        assertThat(migrated.find("p").orElseThrow().settings()).isEqualTo(Map.of(S3StorageType.REGION, "us-east-1"));
        assertThat(new RootSecrets(old, box).open("p")).isEmpty();
        final var disk = migrated.find("d").orElseThrow();
        assertThat(disk.type()).isEqualTo("disk");
        assertThat(disk.settings()).isEmpty();
    }
}
