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

    @Test
    void smbSplitsTheShareFromTheFolder() {
        final var connection = SmbStorageType.connection(config("smb://nas/media/photos/2024",
                Map.of(SmbStorageType.REQUIRE_ENCRYPTION, "true"),
                Map.of(SmbStorageType.USER, "carol", SmbStorageType.PASSWORD, "pw", SmbStorageType.DOMAIN, "OFFICE")));
        assertThat(connection.share()).isEqualTo("media");
        assertThat(connection.location().folder()).isEqualTo("/photos/2024");
        assertThat(connection.location().port()).isEqualTo(445);
        assertThat(connection.domain()).isEqualTo("OFFICE");
        assertThat(connection.requireEncryption()).isTrue();
        assertThat(connection.toString()).doesNotContain("carol", "pw", "OFFICE");
        assertThat(SmbStorageType.parse("smb://nas:1445/media").folder().folder()).isEqualTo("/");
        assertThat(SmbStorageType.parse("smb://nas:1445/media").folder().port()).isEqualTo(1445);
    }

    @Test
    void smbNeedsAShareAndAUser() {
        assertThat(SmbStorageType.parse("smb://nas")).isNull();
        assertThat(SmbStorageType.parse("smb://nas/")).isNull();
        assertThat(SmbStorageType.parse("smb://user@nas/media")).isNull();
        assertThatThrownBy(() -> SmbStorageType.connection(config("smb://nas", Map.of(), Map.of())))
                .hasMessageContaining("smb://host/share/folder");
        assertThatThrownBy(() -> SmbStorageType.connection(config("smb://nas/media", Map.of(), Map.of())))
                .hasMessageContaining("user name");
    }
}
