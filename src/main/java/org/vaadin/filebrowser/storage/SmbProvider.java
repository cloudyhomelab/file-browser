package org.vaadin.filebrowser.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.vaadin.filebrowser.data.Capacity;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;
import org.vaadin.filebrowser.storage.remote.RemoteProvider;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.commons.buffer.Buffer;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

/**
 * A folder in a share on an SMB 3 server, signed in to with NTLM. Every message is signed; it is
 * also encrypted when the server offers it, and a server that doesn't is refused when the file
 * system requires encryption. Guest sign-ins are refused: they can't be signed.
 */
public class SmbProvider extends RemoteProvider<SmbProvider.Session> {

    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;
    private static final Set<NtStatus> MISSING = Set.of(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE);
    /** STATUS_INVALID_DEVICE_REQUEST, which smbj has no constant for. */
    private static final long INVALID_DEVICE_REQUEST = 0xC0000010L;
    /** What servers answer when they can't copy on their side. */
    private static final Set<Long> NO_SERVER_COPY = Set.of(NtStatus.STATUS_NOT_SUPPORTED.getValue(),
            INVALID_DEVICE_REQUEST);

    /**
     * Where and how to sign in.
     *
     * @param location the folder, relative to the share
     * @param domain   blank for the server's own accounts
     */
    public record Connection(RemoteLocation location, String share, String domain, String user, String password,
                             boolean requireEncryption) {

        @Override
        public String toString() {
            return "Connection[" + location + ", share=" + share + ", requireEncryption=" + requireEncryption + "]";
        }
    }

    /** One connection on a client of its own, signed in, with the share open. */
    public record Session(SMBClient client, com.hierynomus.smbj.connection.Connection connection,
                          com.hierynomus.smbj.session.Session session, DiskShare share) {
    }

    private final SmbConfig config;
    private final Connection connection;

    /** @param config speaks only SMB 3, with signing required */
    public SmbProvider(SmbConfig config, Connection connection, Function<String, String> typeForName) {
        super(connection.location(), typeForName);
        this.config = config;
        this.connection = connection;
    }

    // ---- sessions -------------------------------------------------------------------------

    // a client shares one connection per host and remembers each server's identity for good, so a
    // shared one would tie the pooled sessions together and refuse a reinstalled server until restart
    @Override
    protected Session connect() throws IOException {
        final var client = new SMBClient(config);
        final com.hierynomus.smbj.connection.Connection smb;
        try {
            smb = client.connect(location.host(), location.port());
        } catch (IOException e) {
            client.close();
            throw new StorageException("Could not reach " + location.host() + ":" + location.port() + ".", e);
        } catch (SMBApiException e) {
            client.close();
            if (e.getStatus() == NtStatus.STATUS_NOT_SUPPORTED) {
                throw new StorageException("The server doesn't offer SMB 3.", e);
            }
            throw new StorageException("The server didn't complete the connection.", e);
        }
        try {
            final var password = connection.password() == null ? new char[0] : connection.password().toCharArray();
            final var domain = connection.domain() == null ? "" : connection.domain();
            final var credentials = new AuthenticationContext(connection.user(), password, domain);
            final var session = signIn(smb, credentials);
            if (connection.requireEncryption() && !session.shouldEncryptData()) {
                throw new StorageException("The server doesn't encrypt SMB connections, and this file system "
                        + "requires it.");
            }
            return new Session(client, smb, session, openShare(session));
        } catch (RuntimeException e) {
            closeQuietly(client);
            throw e;
        }
    }

    private static com.hierynomus.smbj.session.Session signIn(com.hierynomus.smbj.connection.Connection smb,
                                                              AuthenticationContext credentials) {
        final com.hierynomus.smbj.session.Session session;
        try {
            session = smb.authenticate(credentials);
        } catch (SMBApiException e) {
            if (e.getStatus() == NtStatus.STATUS_LOGON_FAILURE || e.getStatus() == NtStatus.STATUS_ACCOUNT_DISABLED) {
                throw new StorageException("The server refused the user name or password.", e);
            }
            throw new StorageException("The server didn't complete the sign-in.", e);
        } catch (SMBRuntimeException e) {
            throw new StorageException("The server didn't complete the sign-in.", e);
        }
        // Samba maps unknown users to guest by default, which would look like a successful sign-in
        if (session.isGuest() || session.isAnonymous()) {
            throw new StorageException("The server refused the user name or password.");
        }
        return session;
    }

    private DiskShare openShare(com.hierynomus.smbj.session.Session session) {
        final var name = connection.share();
        try {
            if (session.connectShare(name) instanceof DiskShare share) {
                return share;
            }
            throw new StorageException("“" + name + "” is not a shared folder.");
        } catch (SMBApiException e) {
            if (e.getStatus() == NtStatus.STATUS_BAD_NETWORK_NAME) {
                throw new StorageException("The server has no share named “" + name + "”.", e);
            }
            if (e.getStatus() == NtStatus.STATUS_ACCESS_DENIED) {
                throw new StorageException("This user can't open the share “" + name + "”.", e);
            }
            throw new StorageException("Could not open the share “" + name + "”.", e);
        }
    }

    @Override
    protected boolean alive(Session session) {
        return session.connection().isConnected();
    }

    @Override
    protected void disconnect(Session session) {
        closeQuietly(session.client());
    }

    // closing the client closes its connection, sessions and shares too
    private static void closeQuietly(SMBClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            // nothing more to do with it
        }
    }

    @Override
    protected StorageException failure(String action, IOException e) {
        if (e instanceof SmbRefusal refusal && refusal.status == NtStatus.STATUS_ACCESS_DENIED) {
            return new StorageException("You don't have permission to " + action + ".", e);
        }
        if (e instanceof SmbRefusal refusal && MISSING.contains(refusal.status)) {
            return new StorageException("Could not " + action + ": it no longer exists.", e);
        }
        return super.failure(action, e);
    }

    // ---- primitives -----------------------------------------------------------------------

    @FunctionalInterface
    private interface SmbCall<T> {
        T run() throws IOException;
    }

    /**
     * Runs one call to smbj, which throws unchecked exceptions: a status the server sent becomes a
     * {@link Refusal}, anything else an {@link IOException} that drops the session.
     */
    private static <T> T smb(SmbCall<T> call) throws IOException {
        try {
            return call.run();
        } catch (SMBApiException e) {
            throw refusal(e);
        } catch (SMBRuntimeException e) {
            throw new IOException("The connection to the server failed.", e);
        }
    }

    /** The server path as smbj takes it: relative to the share, with backslashes. */
    private static String smb(String path) {
        return path.equals("/") ? "" : path.substring(1).replace('/', '\\');
    }

    @Override
    protected List<Node> listNodes(Session session, String folder) throws IOException {
        final var out = new ArrayList<Node>();
        for (final var entry : smb(() -> session.share().list(smb(folder)))) {
            final var name = entry.getFileName();
            if (!name.equals(".") && !name.equals("..")) {
                out.add(node(name, entry.getFileAttributes(), entry.getEndOfFile(), entry.getLastWriteTime()));
            }
        }
        return out;
    }

    private static Node node(String name, long attributes, long size, FileTime modified) {
        final var folder = (attributes & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
        return folder ? new Node(name, true, null, modified.toInstant())
                : new Node(name, false, size, modified.toInstant());
    }

    @Override
    protected Node statNode(Session session, String path) throws IOException {
        final FileAllInformation info;
        try {
            info = smb(() -> session.share().getFileInformation(smb(path)));
        } catch (SmbRefusal e) {
            if (MISSING.contains(e.status)) {
                return null;
            }
            throw e;
        }
        final var basic = info.getBasicInformation();
        return node(path.substring(path.lastIndexOf('/') + 1), basic.getFileAttributes(),
                info.getStandardInformation().getEndOfFile(), basic.getLastWriteTime());
    }

    @Override
    protected void makeFolder(Session session, String path) throws IOException {
        smb(() -> {
            session.share().mkdir(smb(path));
            return null;
        });
    }

    @Override
    protected void renameNode(Session session, String from, String to) throws IOException {
        rename(session, from, to, false);
    }

    @Override
    protected void replaceNode(Session session, String from, String to) throws IOException {
        rename(session, from, to, true);
    }

    private static void rename(Session session, String from, String to, boolean replace) throws IOException {
        final var access = EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES);
        smb(() -> {
            try (final var entry = session.share().open(smb(from), access, null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, null)) {
                entry.rename(smb(to), replace);
            }
            return null;
        });
    }

    @Override
    protected void deleteFile(Session session, String path) throws IOException {
        smb(() -> {
            session.share().rm(smb(path));
            return null;
        });
    }

    @Override
    protected void deleteEmptyFolder(Session session, String path) throws IOException {
        smb(() -> {
            session.share().rmdir(smb(path), false);
            return null;
        });
    }

    @Override
    protected InputStream readStream(Session session, String path) throws IOException {
        return readStream(session, path, 0);
    }

    // reads start at any offset, so nothing before it is transferred
    @Override
    protected InputStream readStream(Session session, String path, long offset) throws IOException {
        final var file = openFile(session, path, AccessMask.GENERIC_READ, SMB2CreateDisposition.FILE_OPEN);
        return new FileInputStream(file, offset);
    }

    @Override
    protected OutputStream writeStream(Session session, String path) throws IOException {
        final var file = openFile(session, path, AccessMask.GENERIC_WRITE, SMB2CreateDisposition.FILE_OVERWRITE_IF);
        return new FileOutputStream(file);
    }

    // FILE_CREATE fails when the target exists, so nothing is ever copied over
    @Override
    protected boolean copyOnServer(Session session, String from, String to) throws IOException {
        try {
            smb(() -> {
                try (final var source = openFile(session, from, AccessMask.GENERIC_READ, SMB2CreateDisposition.FILE_OPEN);
                     final var target = openFile(session, to, AccessMask.GENERIC_ALL, SMB2CreateDisposition.FILE_CREATE)) {
                    source.remoteCopyTo(target);
                } catch (Buffer.BufferException e) {
                    throw new IOException("The server's copy reply can't be read.", e);
                }
                return null;
            });
            return true;
        } catch (SmbRefusal e) {
            if (!NO_SERVER_COPY.contains(e.code)) {
                throw e;
            }
        }
        deleteFile(session, to);
        return false;
    }

    private static File openFile(Session session, String path, AccessMask access, SMB2CreateDisposition disposition)
            throws IOException {
        return smb(() -> session.share().openFile(smb(path), EnumSet.of(access), null, SMB2ShareAccess.ALL,
                disposition, null));
    }

    /** A status the server sent back; the session stays usable. */
    private static IOException refusal(SMBApiException e) {
        final var refusal = new SmbRefusal(e);
        refusal.initCause(e);
        return refusal;
    }

    private static final class SmbRefusal extends Refusal {

        private final NtStatus status;
        private final long code;

        SmbRefusal(SMBApiException e) {
            super(e.getMessage());
            this.status = e.getStatus();
            this.code = e.getStatusCode();
        }
    }

    /** Reads a file from an offset on; closing it closes the file. */
    private static final class FileInputStream extends InputStream {

        private final File file;
        private long position;

        FileInputStream(File file, long position) {
            this.file = file;
            this.position = position;
        }

        @Override
        public int read() throws IOException {
            final var one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            final int read = smb(() -> file.read(buffer, position, offset, length));
            if (read > 0) {
                position += read;
            }
            return read;
        }

        @Override
        public long skip(long n) {
            final var skipped = Math.max(0, n);
            position += skipped;
            return skipped;
        }

        @Override
        public void close() throws IOException {
            closeFile(file);
        }
    }

    /** Writes a file from the start; closing it closes the file. */
    private static final class FileOutputStream extends OutputStream {

        private final File file;
        private final OutputStream out;

        FileOutputStream(File file) {
            this.file = file;
            this.out = file.getOutputStream();
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            smb(() -> {
                out.write(bytes, offset, length);
                return null;
            });
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            try {
                smb(() -> {
                    out.close();
                    return null;
                });
            } finally {
                closeFile(file);
            }
        }
    }

    private static void closeFile(File file) throws IOException {
        smb(() -> {
            file.close();
            return null;
        });
    }

    // ---- status ---------------------------------------------------------------------------

    /** The size and free space of the volume behind the share, as the server reports them. */
    @Override
    public Capacity capacity() {
        try {
            return call("read the share's usage", session -> {
                final var info = smb(() -> session.share().getShareInformation());
                final var total = info.getTotalSpace();
                final var used = total - info.getCallerFreeSpace();
                return new Capacity(used / BYTES_PER_GB, total / BYTES_PER_GB);
            });
        } catch (StorageException e) {
            return null;
        }
    }
}
