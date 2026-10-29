package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.vaadin.filebrowser.data.Catalog;

/** Runs against Samba; skipped where Docker isn't available (e.g. the image build). */
@Testcontainers(disabledWithoutDocker = true)
class SmbProviderTest extends RemoteProviderContract {

    // Samba copies with server-side copy chunks
    @Override
    protected boolean copiesOnServer() {
        return true;
    }

    private static final int SMB_PORT = 445;
    private static final String USER = "test";
    private static final String PASSWORD = "test-password";
    private static final String SHARE_PATH = "/shares/share";
    private static final String IMAGE =
            "ghcr.io/servercontainers/samba@sha256:349779fe8c0c619407adb8dd5dc6940c807ec1bf215ef29932fa18dd287fd66e";

    @Container
    static final GenericContainer<?> SERVER = server(Map.of());

    private static final SmbStorageType TYPE = new SmbStorageType(new Catalog());

    /** @param global extra [global] settings, by name */
    private static GenericContainer<?> server(Map<String, String> global) {
        final var container = new GenericContainer<>(IMAGE)
                .withEnv("ACCOUNT_" + USER, PASSWORD)
                .withEnv("UID_" + USER, "1000")
                .withEnv("SAMBA_VOLUME_CONFIG_share",
                        "[share]; path = " + SHARE_PATH + "; valid users = " + USER + "; read only = no")
                .withTmpFs(Map.of(SHARE_PATH, "rw,mode=1777"))
                .withExposedPorts(SMB_PORT)
                .waitingFor(Wait.forListeningPort());
        // the image turns SAMBA_GLOBAL_CONFIG_<name>=<value> into a [global] line, with _SPACE_ for each space
        global.forEach((name, value) -> container.withEnv("SAMBA_GLOBAL_CONFIG_" + name.replace(" ", "_SPACE_"), value));
        return container;
    }

    private static String endpoint(GenericContainer<?> server, String folder) {
        return "smb://" + server.getHost() + ":" + server.getMappedPort(SMB_PORT) + "/share" + folder;
    }

    private static StorageProvider open(GenericContainer<?> server, String folder, String password,
                                        boolean requireEncryption) {
        final Map<String, String> settings = requireEncryption
                ? Map.of(SmbStorageType.REQUIRE_ENCRYPTION, "true") : Map.of();
        final var secrets = Map.of(SmbStorageType.USER, USER, SmbStorageType.PASSWORD, password);
        return TYPE.open(new StorageConfig(endpoint(server, folder), settings, secrets));
    }

    @Override
    protected StorageProvider provider(String folder) {
        return open(SERVER, folder, PASSWORD, true);
    }

    @Override
    protected String baseFolder() {
        return "";
    }

    @Override
    protected List<String> serverNames(String folder) throws Exception {
        final var result = SERVER.execInContainer("ls", "-A", SHARE_PATH + folder);
        return Arrays.stream(result.getStdout().split("\n")).filter(s -> !s.isBlank()).toList();
    }

    @Test
    void refusesAWrongPasswordAndAMissingShare() {
        final var wrong = open(SERVER, folder, "wrong", false);
        assertThatThrownBy(() -> wrong.list("")).hasMessageContaining("refused the user name or password");
        assertThat(wrong.health().reason()).contains("refused");
        final var secrets = Map.of(SmbStorageType.USER, USER, SmbStorageType.PASSWORD, PASSWORD);
        final var config = new StorageConfig(endpoint(SERVER, "").replace("/share", "/missing"), Map.of(), secrets);
        final var missing = TYPE.open(config);
        assertThatThrownBy(() -> missing.list("")).hasMessageContaining("no share named “missing”");
    }

    @Test
    void readsFromAnOffset() throws IOException {
        try (final var in = provider.openRead("notes.txt", 2, 2)) {
            assertThat(new String(in.readAllBytes())).isEqualTo("te");
        }
    }

    @Test
    void replacesAFileInOneStep() throws IOException {
        write(provider, "", "draft.txt", "draft");
        provider.renameReplacing("draft.txt", "notes.txt");
        assertThat(read(provider, "notes.txt")).isEqualTo("draft");
        assertThat(provider.stat("draft.txt")).isEmpty();
    }

    @Test
    void reportsTheSharesSize() {
        final var capacity = provider.capacity();
        assertThat(capacity).isNotNull();
        assertThat(capacity.total()).isNotNull();
    }

    @Test
    void refusesAServerWithoutEncryptionOnlyWhenItIsRequired() throws IOException {
        try (final var plain = server(Map.of("server smb encrypt", "off"))) {
            plain.start();
            final var required = open(plain, "", PASSWORD, true);
            assertThatThrownBy(() -> required.list("")).hasMessageContaining("doesn't encrypt");
            try (final var optional = closeable(open(plain, "", PASSWORD, false))) {
                assertThat(optional.provider().list("")).isEmpty();
            }
        }
    }

    @Test
    void refusesAServerWithoutSmb3() {
        try (final var old = server(Map.of("server max protocol", "SMB2"))) {
            old.start();
            final var provider = open(old, "", PASSWORD, false);
            assertThatThrownBy(() -> provider.list("")).hasMessageContaining("doesn't offer SMB 3");
        }
    }
}
