package org.vaadin.filebrowser.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.data.TestRoots;

/**
 * vsftpd test servers, set up like the dev ones, with a certificate made for the run. Passive data ports are announced by the server,
 * so they are bound to the same ports on the host.
 */
final class FtpServers {

    static final String USER = "test";
    static final String PASSWORD = "test-password";
    static final String HOME = "/ftp/test";
    static final int CONTROL_PORT = 21;
    static final int IMPLICIT_PORT = 990;
    private static final String IMAGE =
            "delfer/alpine-ftp-server@sha256:60bb774d8408d9d4d5c74d05d1c086a34ce192c6c1a142ffac268cac0dbc6fac";
    private static final TestKeys.Certificate CERT = TestKeys.selfSigned(tempDir());
    static final FtpStorageType TYPE = new FtpStorageType(new Catalog());

    private FtpServers() {
    }

    /** Plain FTP and explicit TLS on one port, plus read-only anonymous access to {@code /ftp/public}. */
    static GenericContainer<?> explicit(int firstPassivePort) {
        return server(CONTROL_PORT, firstPassivePort, List.of(
                "-oanonymous_enable=YES", "-ono_anon_password=YES", "-oanon_root=/ftp/public",
                "-oallow_anon_ssl=YES", "-oforce_local_logins_ssl=NO", "-oforce_local_data_ssl=NO"));
    }

    static GenericContainer<?> implicit(int firstPassivePort) {
        return server(IMPLICIT_PORT, firstPassivePort, List.of("-olisten_port=" + IMPLICIT_PORT, "-oimplicit_ssl=YES"));
    }

    @SuppressWarnings({"deprecation", "resource"})
    private static GenericContainer<?> server(int port, int firstPassivePort, List<String> extra) {
        final var passivePorts = 5;
        final var command = new ArrayList<>(List.of("vsftpd", "/etc/vsftpd/vsftpd.conf", "-obackground=NO",
                "-opasv_address=" + DockerClientFactory.instance().dockerHostIpAddress(),
                "-opasv_min_port=" + firstPassivePort, "-opasv_max_port=" + (firstPassivePort + passivePorts - 1),
                "-ochroot_local_user=YES", "-oallow_writeable_chroot=YES", "-ossl_enable=YES",
                "-orequire_ssl_reuse=NO", "-orsa_cert_file=/certs/dev-cert.pem",
                "-orsa_private_key_file=/certs/dev-key.pem"));
        command.addAll(extra);
        final var container = new FixedHostPortGenericContainer<>(IMAGE);
        for (int p = firstPassivePort; p < firstPassivePort + passivePorts; p++) {
            container.withFixedExposedPort(p, p);
        }
        return container
                .withEnv("USERS", USER + "|" + PASSWORD + "|" + HOME + "|1000")
                .withCopyFileToContainer(MountableFile.forHostPath(CERT.cert()), "/certs/dev-cert.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(CERT.key()), "/certs/dev-key.pem")
                .withCreateContainerCmdModifier(cmd -> cmd.withCmd(command))
                .withExposedPorts(port)
                .waitingFor(Wait.forListeningPort());
    }

    /** Makes the anonymous folder, which vsftpd wants owned by root and not writable. */
    static void seedAnonymous(GenericContainer<?> server) throws IOException, InterruptedException {
        server.execInContainer("sh", "-c", "mkdir -p /ftp/public && echo hello > /ftp/public/README.md");
    }

    static String endpoint(GenericContainer<?> server, int port, String folder) {
        return "ftp://" + server.getHost() + ":" + server.getMappedPort(port) + folder;
    }

    static StorageProvider open(String endpoint, FtpProvider.Security security, Map<String, String> secrets,
                                String pin) {
        final var settings = new java.util.HashMap<String, String>();
        settings.put(FtpStorageType.SECURITY, security.key());
        if (pin != null) {
            settings.put(FtpStorageType.CERTIFICATE, pin);
        }
        if (secrets.isEmpty()) {
            settings.put(FtpStorageType.ANONYMOUS, "true");
        }
        return TYPE.open(new StorageConfig(endpoint, settings, secrets, TestRoots.MAX_CONNECTIONS));
    }

    static Map<String, String> signIn(String password) {
        return Map.of(FtpStorageType.USER, USER, FtpStorageType.PASSWORD, password);
    }

    /** The fingerprint of the certificate the servers use. */
    static String certificateFingerprint() {
        return CERT.fingerprint();
    }

    private static Path tempDir() {
        try {
            final var dir = Files.createTempDirectory("ftp-test-cert");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> names(GenericContainer<?> server, String folder) throws Exception {
        final var result = server.execInContainer("ls", "-A", folder);
        return Arrays.stream(result.getStdout().split("\n")).filter(s -> !s.isBlank()).toList();
    }
}
