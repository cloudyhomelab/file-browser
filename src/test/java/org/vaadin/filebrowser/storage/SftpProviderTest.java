package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.TestRoots;

/** Runs against an OpenSSH server; skipped where Docker isn't available (e.g. the image build). */
@Testcontainers(disabledWithoutDocker = true)
class SftpProviderTest extends RemoteProviderContract {

    // OpenSSH offers copy-data
    @Override
    protected boolean copiesOnServer() {
        return true;
    }

    private static final int SSH_PORT = 2222;
    private static final String USER = "test";
    private static final String PASSWORD = "test-password";
    private static final TestKeys.SshKey KEY = TestKeys.ed25519();

    @Container
    static final GenericContainer<?> SERVER = new GenericContainer<>("linuxserver/openssh-server:10.3_p1-r1-ls238")
            .withEnv("PUID", "1000")
            .withEnv("PGID", "1000")
            .withEnv("USER_NAME", USER)
            .withEnv("USER_PASSWORD", PASSWORD)
            .withEnv("PASSWORD_ACCESS", "true")
            .withEnv("PUBLIC_KEY", KEY.publicKey())
            .withExposedPorts(SSH_PORT)
            .waitingFor(Wait.forLogMessage(".*\\[ls.io-init\\] done.*", 1));

    private static final SftpStorageType TYPE = new SftpStorageType(new Catalog());
    private static String hostKey;

    private static String endpoint(String folder) {
        return "sftp://" + SERVER.getHost() + ":" + SERVER.getMappedPort(SSH_PORT) + folder;
    }

    private static StorageProvider open(String folder, Map<String, String> secrets, String pin) {
        final Map<String, String> settings = pin == null ? Map.of() : Map.of(SftpStorageType.HOST_KEY, pin);
        return TYPE.open(new StorageConfig(endpoint(folder), settings, secrets, TestRoots.MAX_CONNECTIONS));
    }

    private static Map<String, String> password(String password) {
        return Map.of(SftpStorageType.USER, USER, SftpStorageType.PASSWORD, password);
    }

    @BeforeAll
    static void learnHostKey() {
        final var untrusted = open("/config", password(PASSWORD), null);
        try {
            untrusted.list("");
            throw new AssertionError("an unpinned host key was trusted");
        } catch (UntrustedServerException e) {
            hostKey = e.getFingerprint();
        }
        assertThat(hostKey).matches("[a-z0-9-]+ SHA256:\\S+");
    }

    @Override
    protected StorageProvider provider(String folder) {
        return open(folder, password(PASSWORD), hostKey);
    }

    @Override
    protected String baseFolder() {
        return "/config";
    }

    @Override
    protected List<String> serverNames(String folder) throws Exception {
        final var result = SERVER.execInContainer("ls", "-A", folder);
        return Arrays.stream(result.getStdout().split("\n")).filter(s -> !s.isBlank()).toList();
    }

    @Test
    void signsInWithAPrivateKey() throws IOException {
        final var secrets = Map.of(SftpStorageType.USER, USER, SftpStorageType.PRIVATE_KEY, KEY.privateKey());
        try (final var keyed = closeable(open(folder, secrets, hostKey))) {
            assertThat(keyed.provider().list("")).extracting(StorageEntry::name).contains("notes.txt");
        }
    }

    @Test
    void refusesAChangedHostKeyAndAWrongPassword() {
        final var changed = open(folder, password(PASSWORD), "SHA256:somethingElse");
        assertThatThrownBy(() -> changed.list("")).hasMessageContaining("host key changed");
        assertThat(changed.healthy()).isFalse();
        final var wrong = open(folder, password("wrong"), hostKey);
        assertThatThrownBy(() -> wrong.list("")).hasMessageContaining("refused");
    }

    @Test
    void reportsTheServersDiskUsage() {
        final var capacity = provider.capacity();
        assertThat(capacity).isNotNull();
        assertThat(capacity.total()).isPositive();
    }
}
