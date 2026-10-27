package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.vaadin.filebrowser.storage.FtpProvider.Security;

/**
 * Runs against vsftpd: plain FTP, explicit TLS and implicit TLS; skipped where Docker isn't
 * available (e.g. the image build). The servers list with LIST, as vsftpd has no MLSD.
 */
@Testcontainers(disabledWithoutDocker = true)
class FtpProviderTest {

    @Container
    static final GenericContainer<?> EXPLICIT = FtpServers.explicit(31100);
    @Container
    static final GenericContainer<?> IMPLICIT = FtpServers.implicit(31110);

    @BeforeAll
    static void seed() throws IOException, InterruptedException {
        FtpServers.seedAnonymous(EXPLICIT);
    }

    private static String endpoint(String folder) {
        return FtpServers.endpoint(EXPLICIT, FtpServers.CONTROL_PORT, folder);
    }

    @Nested
    class Plain extends RemoteProviderContract {

        @Override
        protected StorageProvider provider(String folder) {
            return FtpServers.open(endpoint(folder), Security.NONE, FtpServers.signIn(FtpServers.PASSWORD), null);
        }

        @Override
        protected String baseFolder() {
            return "";
        }

        @Override
        protected List<String> serverNames(String folder) throws Exception {
            return FtpServers.names(EXPLICIT, FtpServers.HOME + folder);
        }

        @Test
        void refusesAWrongPassword() {
            final var wrong = FtpServers.open(endpoint(folder), Security.NONE, FtpServers.signIn("wrong"), null);
            assertThatThrownBy(() -> wrong.list("")).hasMessageContaining("refused the user name or password");
            assertThat(wrong.healthy()).isFalse();
        }

        @Test
        void anonymousSignInReadsButCannotWrite() throws IOException {
            final var anonymous = FtpServers.open(endpoint("/"), Security.NONE, Map.of(), null);
            assertThat(anonymous.list("")).extracting(StorageEntry::name).containsExactly("README.md");
            assertThat(read(anonymous, "README.md")).isEqualTo("hello\n");
            assertThatThrownBy(() -> anonymous.createFolder("", "x")).isInstanceOf(StorageException.class);
        }
    }

    @Nested
    class ExplicitTls extends RemoteProviderContract {

        @Override
        protected StorageProvider provider(String folder) {
            return FtpServers.open(endpoint(folder), Security.EXPLICIT, FtpServers.signIn(FtpServers.PASSWORD),
                    FtpServers.certificateFingerprint());
        }

        @Override
        protected String baseFolder() {
            return "";
        }

        @Override
        protected List<String> serverNames(String folder) throws Exception {
            return FtpServers.names(EXPLICIT, FtpServers.HOME + folder);
        }

        @Test
        void anUntrustedCertificateIsOfferedAndAChangedOneRefused() {
            final var unpinned = FtpServers.open(endpoint(folder), Security.EXPLICIT,
                    FtpServers.signIn(FtpServers.PASSWORD), null);
            assertThatThrownBy(() -> unpinned.list(""))
                    .isInstanceOfSatisfying(UntrustedServerException.class,
                            e -> assertThat(e.fingerprint()).isEqualTo(FtpServers.certificateFingerprint()));
            final var changed = FtpServers.open(endpoint(folder), Security.EXPLICIT,
                    FtpServers.signIn(FtpServers.PASSWORD), "SHA256:somethingElse");
            assertThatThrownBy(() -> changed.list("")).hasMessageContaining("certificate changed");
        }
    }

    @Test
    void implicitTlsConnectsWithAPinnedCertificate() throws IOException {
        final var endpoint = FtpServers.endpoint(IMPLICIT, FtpServers.IMPLICIT_PORT, "");
        final var provider = FtpServers.open(endpoint, Security.IMPLICIT, FtpServers.signIn(FtpServers.PASSWORD),
                FtpServers.certificateFingerprint());
        RemoteProviderContract.write(provider, "", "hello.txt", "hello");
        assertThat(RemoteProviderContract.read(provider, "hello.txt")).isEqualTo("hello");
        assertThat(provider.list("")).extracting(StorageEntry::name).contains("hello.txt");
    }
}
