package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RemoteStorageTypesTest {

    private static StorageConfig config(String endpoint, Map<String, String> settings, Map<String, String> secrets) {
        return new StorageConfig(endpoint, settings, secrets);
    }

    @Test
    void sftpTakesTheUserAndKeysFromSecretsAndThePinFromSettings() {
        final var connection = SftpStorageType.connection(config("sftp://host/srv",
                Map.of(SftpStorageType.HOST_KEY, "SHA256:pin"),
                Map.of(SftpStorageType.USER, "alice", SftpStorageType.PRIVATE_KEY, "key")));
        assertThat(connection.location().port()).isEqualTo(22);
        assertThat(connection.user()).isEqualTo("alice");
        assertThat(connection.privateKey()).isEqualTo("key");
        assertThat(connection.hostKey()).isEqualTo("SHA256:pin");
        assertThat(connection.toString()).doesNotContain("alice", "key=");
        assertThatThrownBy(() -> SftpStorageType.connection(config("sftp://host/srv", Map.of(), Map.of())))
                .hasMessageContaining("user name");
    }

    @Test
    void ftpDefaultsToExplicitTlsAndPicksThePortFromTheSecurity() {
        final var secrets = Map.of(FtpStorageType.USER, "bob", FtpStorageType.PASSWORD, "pw");
        final var explicit = FtpStorageType.connection(config("ftp://host/pub", Map.of(), secrets));
        assertThat(explicit.security()).isEqualTo(FtpProvider.Security.EXPLICIT);
        assertThat(explicit.location().port()).isEqualTo(21);
        final var implicit = FtpStorageType.connection(config("ftp://host/pub",
                Map.of(FtpStorageType.SECURITY, "implicit"), secrets));
        assertThat(implicit.location().port()).isEqualTo(990);
    }

    @Test
    void anonymousFtpNeedsNoSecrets() {
        final var connection = FtpStorageType.connection(config("ftp://host/pub",
                Map.of(FtpStorageType.ANONYMOUS, "true"), Map.of()));
        assertThat(connection.user()).isNull();
        assertThatThrownBy(() -> FtpStorageType.connection(config("ftp://host/pub", Map.of(), Map.of())))
                .hasMessageContaining("user name");
    }
}
