package org.vaadin.filebrowser.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.extensions.CopyDataExtension;
import org.apache.sshd.sftp.client.extensions.CopyFileExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatPathExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.vaadin.filebrowser.data.Capacity;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;
import org.vaadin.filebrowser.storage.remote.RemoteProvider;

/**
 * A folder on an SSH server, over SFTP. The server's host key must match the pinned fingerprint;
 * without one, connecting fails with {@link UntrustedServerException} so it can be confirmed first.
 */
public class SftpProvider extends RemoteProvider<SftpProvider.Session> {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;
    /** copy-data's length for "until the end of the source". */
    private static final long COPY_TO_END = 0;
    /** The fingerprint the session must see; empty when none is pinned yet. */
    private static final AttributeRepository.AttributeKey<String> PINNED = new AttributeRepository.AttributeKey<>();
    /** Where the verifier leaves the fingerprint it saw. */
    private static final AttributeRepository.AttributeKey<AtomicReference<String>> SEEN =
            new AttributeRepository.AttributeKey<>();

    /**
     * How to sign in: a password, or a private key with an optional passphrase.
     *
     * @param hostKey the pinned fingerprint, null when none is
     */
    public record Connection(RemoteLocation location, String user, String password, String privateKey,
                             String passphrase, String hostKey) {

        @Override
        public String toString() {
            return "Connection[" + location + ", hostKey=" + hostKey + "]";
        }
    }

    /** One SSH connection and the SFTP channel on it. */
    public record Session(ClientSession ssh, SftpClient sftp) {
    }

    private final SshClient client;
    private final Connection connection;

    /** @param client started, with {@link #verifier()} as its server key verifier */
    public SftpProvider(SshClient client, Connection connection, Function<String, String> typeForName) {
        super(connection.location(), typeForName);
        this.client = client;
        this.connection = connection;
    }

    /** Checks each server against the fingerprint its file system pinned. */
    public static boolean verify(ClientSession session, PublicKey key) {
        final var context = session.getConnectionContext();
        if (context == null || context.getAttribute(SEEN) == null) {
            return false;
        }
        // the key type tells admins which of the server's keys to compare with
        final var seen = KeyUtils.getKeyType(key) + " " + KeyUtils.getFingerPrint(key);
        context.getAttribute(SEEN).set(seen);
        final var pinned = context.getAttribute(PINNED);
        return !pinned.isEmpty() && pinned.equals(seen);
    }

    // ---- sessions -------------------------------------------------------------------------

    @Override
    protected Session connect() throws IOException {
        final var seen = new AtomicReference<String>();
        final var pinned = connection.hostKey() == null ? "" : connection.hostKey();
        final var context = AttributeRepository.ofAttributesMap(Map.of(PINNED, pinned, SEEN, seen));
        final ClientSession ssh;
        try {
            ssh = client.connect(connection.user(), location.host(), location.port(), context)
                    .verify(CONNECT_TIMEOUT)
                    .getSession();
        } catch (IOException e) {
            throw new StorageException("Could not reach " + location.host() + ":" + location.port() + ".", e);
        }
        try {
            if (connection.privateKey() != null) {
                for (final var pair : keys(ssh)) {
                    ssh.addPublicKeyIdentity(pair);
                }
            } else {
                ssh.addPasswordIdentity(connection.password() == null ? "" : connection.password());
            }
            try {
                ssh.auth().verify(CONNECT_TIMEOUT);
            } catch (IOException e) {
                throw signInFailure(seen.get(), pinned, e);
            }
            return new Session(ssh, SftpClientFactory.instance().createSftpClient(ssh));
        } catch (IOException | RuntimeException e) {
            closeQuietly(ssh);
            throw e;
        }
    }

    private Iterable<KeyPair> keys(ClientSession ssh) {
        final var bytes = connection.privateKey().getBytes(StandardCharsets.UTF_8);
        final var passphrase = FilePasswordProvider.of(connection.passphrase());
        try {
            final var pairs = SecurityUtils.loadKeyPairIdentities(ssh, NamedResource.ofName("private key"),
                    new ByteArrayInputStream(bytes), passphrase);
            if (pairs == null || !pairs.iterator().hasNext()) {
                throw new StorageException("The private key can't be read.");
            }
            return pairs;
        } catch (IOException | GeneralSecurityException e) {
            throw new StorageException("The private key can't be read; check its passphrase.", e);
        }
    }

    /** Tells a host key that isn't trusted from a refused sign-in. */
    private static StorageException signInFailure(String seen, String pinned, IOException e) {
        if (seen != null && pinned.isEmpty()) {
            return new UntrustedServerException(seen);
        }
        if (seen != null && !seen.equals(pinned)) {
            return new StorageException("The server's host key changed. If that is expected, remove this "
                    + "file system and add it again.", e);
        }
        if (seen == null) {
            return new StorageException("The server didn't complete the connection.", e);
        }
        return new StorageException("The server refused the user name or credentials.", e);
    }

    @Override
    protected boolean alive(Session session) {
        return session.ssh().isOpen() && session.sftp().isOpen();
    }

    @Override
    protected void disconnect(Session session) {
        try {
            session.sftp().close();
        } catch (IOException e) {
            // the session closes it as well
        }
        closeQuietly(session.ssh());
    }

    private static void closeQuietly(ClientSession ssh) {
        try {
            ssh.close();
        } catch (IOException e) {
            // nothing more to do with it
        }
    }

    @Override
    protected StorageException failure(String action, IOException e) {
        if (e instanceof SftpRefusal refusal && refusal.status == SftpConstants.SSH_FX_PERMISSION_DENIED) {
            return new StorageException("You don't have permission to " + action + ".", e);
        }
        if (e instanceof SftpRefusal refusal && refusal.status == SftpConstants.SSH_FX_NO_SUCH_FILE) {
            return new StorageException("Could not " + action + ": it no longer exists.", e);
        }
        return super.failure(action, e);
    }

    // ---- primitives -----------------------------------------------------------------------

    @Override
    protected List<Node> listNodes(Session session, String folder) throws IOException {
        final var out = new ArrayList<Node>();
        try {
            for (final var entry : session.sftp().readDir(folder)) {
                final var name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                var attributes = entry.getAttributes();
                if (attributes.isSymbolicLink()) {
                    // shown as what it points to; a broken link is left out
                    final var target = statNode(session, "/".equals(folder) ? "/" + name : folder + "/" + name);
                    if (target != null) {
                        out.add(new Node(name, target.folder(), target.size(), target.modified()));
                    }
                    continue;
                }
                out.add(node(name, attributes));
            }
        } catch (SftpException e) {
            throw refusal(e);
        }
        return out;
    }

    private static Node node(String name, SftpClient.Attributes attributes) {
        final var modified = attributes.getModifyTime() == null ? null : attributes.getModifyTime().toInstant();
        return attributes.isDirectory() ? new Node(name, true, null, modified)
                : new Node(name, false, attributes.getSize(), modified);
    }

    @Override
    protected Node statNode(Session session, String path) throws IOException {
        try {
            return node(path.substring(path.lastIndexOf('/') + 1), session.sftp().stat(path));
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw refusal(e);
        }
    }

    @Override
    protected void makeFolder(Session session, String path) throws IOException {
        try {
            session.sftp().mkdir(path);
        } catch (SftpException e) {
            throw refusal(e);
        }
    }

    @Override
    protected void renameNode(Session session, String from, String to) throws IOException {
        try {
            session.sftp().rename(from, to);
        } catch (SftpException e) {
            throw refusal(e);
        }
    }

    // OpenSSH replaces in one step; elsewhere the old file is deleted first
    @Override
    protected void replaceNode(Session session, String from, String to) throws IOException {
        final var posix = session.sftp().getExtension(OpenSSHPosixRenameExtension.class);
        if (posix == null || !posix.isSupported()) {
            super.replaceNode(session, from, to);
            return;
        }
        try {
            posix.posixRename(from, to);
        } catch (SftpException e) {
            throw refusal(e);
        }
    }

    @Override
    protected void deleteFile(Session session, String path) throws IOException {
        try {
            session.sftp().remove(path);
        } catch (SftpException e) {
            throw refusal(e);
        }
    }

    @Override
    protected void deleteEmptyFolder(Session session, String path) throws IOException {
        try {
            session.sftp().rmdir(path);
        } catch (SftpException e) {
            throw refusal(e);
        }
    }

    @Override
    protected InputStream readStream(Session session, String path) throws IOException {
        try {
            return session.sftp().read(path);
        } catch (SftpException e) {
            throw refusal(e);
        }
    }

    @Override
    protected OutputStream writeStream(Session session, String path) throws IOException {
        try {
            return session.sftp().write(path);
        } catch (SftpException e) {
            throw refusal(e);
        }
    }

    // copy-file copies in one request; OpenSSH (9.0+) offers copy-data instead, which copies between two open
    // handles. The extensions' own isSupported() only reads the SFTP v5+ lists, so what the server advertised
    // at version 3 is checked here.
    @Override
    protected boolean copyOnServer(Session session, String from, String to) throws IOException {
        final var sftp = session.sftp();
        final var offered = sftp.getServerExtensions().keySet();
        try {
            if (offered.contains(SftpConstants.EXT_COPY_FILE)) {
                sftp.getExtension(CopyFileExtension.class).copyFile(from, to, false);
                return true;
            }
            if (offered.contains(SftpConstants.EXT_COPY_DATA)) {
                try (final var source = sftp.open(from, SftpClient.OpenMode.Read);
                     final var target = sftp.open(to, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create,
                             SftpClient.OpenMode.Exclusive)) {
                    sftp.getExtension(CopyDataExtension.class).copyData(source, 0, COPY_TO_END, target, 0);
                }
                return true;
            }
        } catch (SftpException e) {
            throw refusal(e);
        }
        return false;
    }

    /** A status the server sent back; the session stays usable. */
    private static IOException refusal(SftpException e) {
        final var refusal = new SftpRefusal(e);
        refusal.initCause(e);
        return refusal;
    }

    private static final class SftpRefusal extends Refusal {

        private final int status;

        SftpRefusal(SftpException e) {
            super(e.getMessage());
            this.status = e.getStatus();
        }
    }

    // ---- status ---------------------------------------------------------------------------

    /** From {@code statvfs@openssh.com}; unknown on servers without it. */
    @Override
    public Capacity capacity() {
        try {
            return call("read the server's usage", session -> {
                final var stat = session.sftp().getExtension(OpenSSHStatPathExtension.class);
                if (stat == null || !stat.isSupported()) {
                    return null;
                }
                final var info = stat.stat(location.folder());
                final var total = info.f_blocks * info.f_frsize;
                final var used = total - info.f_bavail * info.f_frsize;
                return new Capacity(used / BYTES_PER_GB, total / BYTES_PER_GB);
            });
        } catch (StorageException e) {
            return null;
        }
    }
}
