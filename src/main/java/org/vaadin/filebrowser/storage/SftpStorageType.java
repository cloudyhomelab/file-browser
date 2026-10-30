package org.vaadin.filebrowser.storage;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;

import jakarta.annotation.PreDestroy;

/**
 * A folder on an SSH server, over SFTP. The user name and the password or private key are secrets;
 * the server's host-key fingerprint is pinned as a setting when the file system is added.
 */
@Component
public class SftpStorageType implements StorageType {

    public static final String KEY = "sftp";
    public static final String SCHEME = "sftp";
    public static final int DEFAULT_PORT = 22;
    public static final String HOST_KEY = "host_key";
    public static final String USER = "user";
    public static final String PASSWORD = "password";
    public static final String PRIVATE_KEY = "private_key";
    public static final String PASSPHRASE = "passphrase";

    private final Catalog catalog;
    private SshClient client;

    public SftpStorageType(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public StorageProvider open(StorageConfig config) {
        return new SftpProvider(sshClient(), connection(config), config.connections(), catalog::typeForFileName);
    }

    static SftpProvider.Connection connection(StorageConfig config) {
        final var location = RemoteLocation.parse(config.endpoint(), SCHEME, DEFAULT_PORT);
        if (location == null) {
            throw new StorageException("Use the form sftp://host/folder or sftp://host:port/folder.");
        }
        final var user = config.secret(USER);
        if (user == null || user.isBlank()) {
            throw new StorageException("Enter the user name to sign in with.");
        }
        return new SftpProvider.Connection(location, user, config.secret(PASSWORD), config.secret(PRIVATE_KEY),
                config.secret(PASSPHRASE), config.setting(HOST_KEY));
    }

    // one client for every SFTP file system: it holds the threads that serve all their connections
    private synchronized SshClient sshClient() {
        if (client == null) {
            client = SshClient.setUpDefaultClient();
            // only what the admin entered: never the server's own ~/.ssh config or keys
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.setServerKeyVerifier((session, address, key) -> SftpProvider.verify(session, key));
            client.start();
        }
        return client;
    }

    @PreDestroy
    synchronized void stop() {
        if (client != null) {
            client.stop();
        }
    }
}
