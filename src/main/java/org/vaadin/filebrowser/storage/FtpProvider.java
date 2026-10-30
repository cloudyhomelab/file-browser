package org.vaadin.filebrowser.storage;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.vaadin.filebrowser.storage.remote.Fingerprints;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;
import org.vaadin.filebrowser.storage.remote.RemoteProvider;

/**
 * A folder on an FTP server, plain or over TLS (FTPS). A certificate the JVM trusts is accepted as
 * usual; any other must match the pinned fingerprint, and without one connecting fails with
 * {@link UntrustedServerException} so it can be confirmed first.
 */
public class FtpProvider extends RemoteProvider<FtpProvider.Session> {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(30);
    private static final String ANONYMOUS_USER = "anonymous";
    private static final String ROOT = "/";
    /** Some servers want an e-mail-like password for anonymous sign-in. */
    private static final String ANONYMOUS_PASSWORD = "file-browser@";

    /** Replies to {@code REST} from a server that doesn't support it: unknown command, bad argument, not implemented. */
    private static final Set<Integer> REST_NOT_SUPPORTED = Set.of(500, 501, 502, 504);

    private final Connection connection;

    /** How the connection is protected; the names are what the {@code security} setting stores. */
    public enum Security {
        /** Starts plain and switches to TLS (AUTH TLS) before signing in. */
        EXPLICIT,
        /** TLS from the first byte, usually on port 990. */
        IMPLICIT,
        /** No encryption; the password crosses the network in clear text. */
        NONE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** @return null for an unknown value */
        public static Security of(String key) {
            return Arrays.stream(values()).filter(s -> s.key().equals(key)).findFirst().orElse(null);
        }
    }

    /**
     * @param user        null signs in anonymously
     * @param certificate the pinned certificate fingerprint, null when none is
     */
    public record Connection(RemoteLocation location, Security security, String user, String password,
                             String certificate) {

        @Override
        public String toString() {
            return "Connection[" + location + ", " + security + ", certificate=" + certificate + "]";
        }
    }

    /** One signed-in control connection. */
    public record Session(FTPClient ftp, boolean machineListing) {
    }

    public FtpProvider(Connection connection, int sessions, Function<String, String> typeForName) {
        super(connection.location(), sessions, typeForName);
        this.connection = connection;
    }

    // ---- sessions -------------------------------------------------------------------------

    @Override
    protected Session connect() throws IOException {
        final var seen = new AtomicReference<String>();
        final var ftp = client(seen);
        ftp.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        ftp.setDefaultTimeout((int) REPLY_TIMEOUT.toMillis());
        ftp.setDataTimeout(REPLY_TIMEOUT);
        ftp.setAutodetectUTF8(true);
        try {
            open(ftp, seen);
            signIn(ftp);
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            return new Session(ftp, ftp.hasFeature("MLST"));
        } catch (IOException | RuntimeException e) {
            quit(ftp);
            throw e;
        }
    }

    private void open(FTPClient ftp, AtomicReference<String> seen) throws IOException {
        try {
            ftp.connect(location.host(), location.port());
        } catch (SSLException e) {
            throw trustFailure(seen.get(), e);
        } catch (IOException e) {
            throw new StorageException("Could not reach " + location.host() + ":" + location.port() + ".", e);
        }
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            throw new StorageException("The server refused the connection.");
        }
        if (ftp instanceof FTPSClient ftps) {
            // data connections are encrypted as well
            ftps.execPBSZ(0);
            ftps.execPROT("P");
        }
    }

    private void signIn(FTPClient ftp) throws IOException {
        final var anonymous = connection.user() == null;
        final var user = anonymous ? ANONYMOUS_USER : connection.user();
        final var password = anonymous ? ANONYMOUS_PASSWORD : connection.password();
        if (!ftp.login(user, password == null ? "" : password)) {
            throw new StorageException(anonymous ? "The server doesn't allow anonymous sign-in."
                    : "The server refused the user name or password.");
        }
    }

    private FTPClient client(AtomicReference<String> seen) {
        if (connection.security() == Security.NONE) {
            return new FTPClient();
        }
        final var ftps = new FTPSClient(connection.security() == Security.IMPLICIT);
        ftps.setTrustManager(new PinningTrustManager(connection.certificate(), seen));
        ftps.setEndpointCheckingEnabled(true);
        return ftps;
    }

    private StorageException trustFailure(String seen, SSLException cause) {
        if (seen != null && connection.certificate() == null) {
            return new UntrustedServerException(seen);
        }
        if (seen != null && !seen.equals(connection.certificate())) {
            return new StorageException("The server's certificate changed. If that is expected, remove this "
                    + "file system and add it again.", cause);
        }
        return new StorageException("Could not set up a secure connection with the server.", cause);
    }

    /**
     * Accepts the pinned certificate, or with none pinned whatever the JVM trusts (checking the host
     * name too). Notes the certificate it was shown, so an untrusted one can be offered for pinning.
     */
    private static final class PinningTrustManager extends X509ExtendedTrustManager {

        private final String pinned;
        private final AtomicReference<String> seen;
        private final X509ExtendedTrustManager system;

        PinningTrustManager(String pinned, AtomicReference<String> seen) {
            this.pinned = pinned;
            this.seen = seen;
            this.system = systemTrustManager();
        }

        private static X509ExtendedTrustManager systemTrustManager() {
            try {
                final var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init((KeyStore) null);
                return Arrays.stream(factory.getTrustManagers())
                        .filter(X509ExtendedTrustManager.class::isInstance)
                        .map(X509ExtendedTrustManager.class::cast)
                        .findFirst()
                        .orElseThrow();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("The JVM has no default trust store", e);
            }
        }

        /** @return true when the pin decided, false when the system should */
        private boolean pinDecides(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("The server sent no certificate");
            }
            final var fingerprint = Fingerprints.sha256(chain[0].getEncoded());
            seen.set(fingerprint);
            if (pinned == null) {
                return false;
            }
            if (!pinned.equals(fingerprint)) {
                throw new CertificateException("The certificate doesn't match the pinned fingerprint");
            }
            return true;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (!pinDecides(chain)) {
                system.checkServerTrusted(chain, authType, socket);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (!pinDecides(chain)) {
                system.checkServerTrusted(chain, authType, engine);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!pinDecides(chain)) {
                system.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("Only servers are checked");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("Only servers are checked");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Only servers are checked");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system.getAcceptedIssuers();
        }
    }

    @Override
    protected boolean alive(Session session) {
        try {
            return session.ftp().isConnected() && session.ftp().sendNoOp();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void disconnect(Session session) {
        quit(session.ftp());
    }

    private static void quit(FTPClient ftp) {
        if (!ftp.isConnected()) {
            return;
        }
        try {
            ftp.logout();
        } catch (IOException ignored) {
            // closed below either way
        }
        try {
            ftp.disconnect();
        } catch (IOException ignored) {
            // nothing more to do with it
        }
    }

    // ---- primitives -----------------------------------------------------------------------

    /**
     * What the server said when it refused the last command. Any other reply means the control
     * connection is out of step, so the session is not put back in the pool.
     */
    private static IOException refusal(FTPClient ftp) {
        final var reply = ftp.getReplyString() == null ? "refused" : ftp.getReplyString().trim();
        final var code = ftp.getReplyCode();
        if (FTPReply.isNegativeTransient(code) || FTPReply.isNegativePermanent(code)) {
            return new Refusal(reply);
        }
        return new IOException("Unexpected reply: " + reply);
    }

    @Override
    protected List<Node> listNodes(Session session, String folder) throws IOException {
        final var ftp = session.ftp();
        final FTPFile[] files;
        if (session.machineListing()) {
            files = ftp.mlistDir(folder);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw refusal(ftp);
            }
        } else {
            // LIST with a path argument breaks on names with spaces on some servers
            if (!ftp.changeWorkingDirectory(folder)) {
                throw refusal(ftp);
            }
            files = ftp.listFiles();
        }
        final var out = new ArrayList<Node>();
        for (final var file : files) {
            if (file == null || file.getName() == null || ".".equals(file.getName()) || "..".equals(file.getName())) {
                continue;
            }
            out.add(node(file.getName(), file));
        }
        return out;
    }

    private static Node node(String name, FTPFile file) {
        final var modified = file.getTimestampInstant();
        return file.isDirectory() ? new Node(name, true, null, modified)
                : new Node(name, false, file.getSize() < 0 ? null : file.getSize(), modified);
    }

    @Override
    protected Node statNode(Session session, String path) throws IOException {
        final var ftp = session.ftp();
        final var name = path.substring(path.lastIndexOf('/') + 1);
        if (session.machineListing()) {
            final var file = ftp.mlistFile(path);
            return file == null ? null : node(name, file);
        }
        if (ftp.changeWorkingDirectory(path)) {
            return new Node(name, true, null, null);
        }
        if (ROOT.equals(path)) {
            throw refusal(ftp);
        }
        return statInParent(ftp, path, name);
    }

    /** Finds a file in its parent's listing, for servers without MLST. */
    private static Node statInParent(FTPClient ftp, String path, String name) throws IOException {
        final var slash = path.lastIndexOf('/');
        final var parent = slash == 0 ? ROOT : path.substring(0, slash);
        if (!ftp.changeWorkingDirectory(parent)) {
            return null;
        }
        for (final var file : ftp.listFiles()) {
            if (file != null && name.equals(file.getName())) {
                return node(name, file);
            }
        }
        return null;
    }

    @Override
    protected void makeFolder(Session session, String path) throws IOException {
        if (!session.ftp().makeDirectory(path)) {
            throw refusal(session.ftp());
        }
    }

    @Override
    protected void renameNode(Session session, String from, String to) throws IOException {
        if (!session.ftp().rename(from, to)) {
            throw refusal(session.ftp());
        }
    }

    @Override
    protected void deleteFile(Session session, String path) throws IOException {
        if (!session.ftp().deleteFile(path)) {
            throw refusal(session.ftp());
        }
    }

    @Override
    protected void deleteEmptyFolder(Session session, String path) throws IOException {
        if (!session.ftp().removeDirectory(path)) {
            throw refusal(session.ftp());
        }
    }

    /** Starts at {@code offset} with {@code REST}, so the server sends nothing before it. */
    @Override
    protected InputStream readStream(Session session, String path, long offset) throws IOException {
        if (offset == 0) {
            return readStream(session, path);
        }
        final var ftp = session.ftp();
        ftp.setRestartOffset(offset);
        try {
            return readStream(session, path);
        } catch (Refusal e) {
            if (!REST_NOT_SUPPORTED.contains(ftp.getReplyCode())) {
                throw e;
            }
        } finally {
            // commons-net clears it once REST is sent, but not when opening the transfer fails before that
            ftp.setRestartOffset(0);
        }
        return super.readStream(session, path, offset);
    }

    @Override
    protected InputStream readStream(Session session, String path) throws IOException {
        final var ftp = session.ftp();
        final var in = ftp.retrieveFileStream(path);
        if (in == null) {
            throw refusal(ftp);
        }
        return new FilterInputStream(in) {
            private boolean ended;

            @Override
            public int read() throws IOException {
                final var value = super.read();
                ended |= value < 0;
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                final var count = super.read(bytes, offset, length);
                ended |= count < 0;
                return count;
            }

            @Override
            public void close() throws IOException {
                super.close();
                // the server confirms the end of the transfer on the control connection; a reader that stopped
                // early cut the data connection, which the server answers with 426 on a control connection
                // that is still fine
                if (!ftp.completePendingCommand() && (ended || ftp.getReplyCode() != FTPReply.TRANSFER_ABORTED)) {
                    throw refusal(ftp);
                }
            }
        };
    }

    @Override
    protected OutputStream writeStream(Session session, String path) throws IOException {
        final var ftp = session.ftp();
        final var out = ftp.storeFileStream(path);
        if (out == null) {
            throw refusal(ftp);
        }
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                this.out.write(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                super.close();
                if (!ftp.completePendingCommand()) {
                    throw refusal(ftp);
                }
            }
        };
    }
}
