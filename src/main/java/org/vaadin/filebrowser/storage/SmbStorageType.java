package org.vaadin.filebrowser.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;

import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.smbj.SmbConfig;

/**
 * A folder in a share on an SMB 3 server ({@code smb://host/share/folder}). The user name,
 * password and domain are secrets; whether encryption is required is a setting.
 */
@Component
public class SmbStorageType implements StorageType {

    public static final String KEY = "smb";
    /** The kind shown, apart from {@code smb}: an SMB share the OS has mounted. */
    public static final String KIND = "smb-share";
    public static final String SCHEME = "smb";
    public static final int DEFAULT_PORT = 445;
    public static final String REQUIRE_ENCRYPTION = "require_encryption";
    public static final String USER = "user";
    public static final String PASSWORD = "password";
    public static final String DOMAIN = "domain";
    /** How long the server may take to answer any request. */
    private static final long TIMEOUT_SECONDS = 30;
    // signing can't be turned off; encryption is used wherever the server offers it
    private static final SmbConfig CONFIG = SmbConfig.builder()
            .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
            .withSigningRequired(true)
            .withEncryptData(true)
            .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    /** Where an endpoint points: the share, and the folder in it. */
    public record Location(RemoteLocation folder, String share) {
    }

    private final Catalog catalog;

    public SmbStorageType(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public StorageProvider open(StorageConfig config) {
        return new SmbProvider(CONFIG, connection(config), catalog::typeForFileName);
    }

    static SmbProvider.Connection connection(StorageConfig config) {
        final var location = parse(config.endpoint());
        if (location == null) {
            throw new StorageException("Use the form smb://host/share/folder or smb://host:port/share/folder.");
        }
        final var user = config.secret(USER);
        if (user == null || user.isBlank()) {
            throw new StorageException("Enter the user name to sign in with.");
        }
        return new SmbProvider.Connection(location.folder(), location.share(), config.secret(DOMAIN), user,
                config.secret(PASSWORD), requireEncryption(config.settings()));
    }

    /** @return null when {@code endpoint} names no share */
    public static Location parse(String endpoint) {
        final var server = RemoteLocation.parse(endpoint, SCHEME, DEFAULT_PORT);
        if (server == null || "/".equals(server.folder())) {
            return null;
        }
        final var path = server.folder().substring(1);
        final var slash = path.indexOf('/');
        final var share = slash < 0 ? path : path.substring(0, slash);
        final var folder = slash < 0 ? "/" : path.substring(slash);
        return new Location(new RemoteLocation(server.host(), server.port(), folder), share);
    }

    public static boolean requireEncryption(Map<String, String> settings) {
        return Boolean.parseBoolean(settings.get(REQUIRE_ENCRYPTION));
    }

    @Override
    public String kindKey(Map<String, String> settings, Optional<MountInfo> mount) {
        return KIND;
    }

    @Override
    public List<String> kinds() {
        return List.of(KIND);
    }

    @Override
    public String detail(Map<String, String> settings, Optional<MountInfo> mount) {
        return requireEncryption(settings) ? "Encrypted" : "";
    }
}
