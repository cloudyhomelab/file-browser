package org.vaadin.filebrowser.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.storage.remote.RemoteLocation;

/**
 * A folder on an FTP server: plain, or FTPS with explicit or implicit TLS. The user name and
 * password are secrets; anonymous sign-in stores neither. A certificate the JVM doesn't trust is
 * pinned as a setting when the file system is added.
 */
@Component
public class FtpStorageType implements StorageType {

    public static final String KEY = "ftp";
    /** The kind shown for FTP over TLS. */
    public static final String TLS_KIND = "ftps";
    public static final String SCHEME = "ftp";
    public static final int DEFAULT_PORT = 21;
    public static final int IMPLICIT_TLS_PORT = 990;
    public static final String SECURITY = "security";
    public static final String ANONYMOUS = "anonymous";
    public static final String CERTIFICATE = "certificate";
    public static final String USER = "user";
    public static final String PASSWORD = "password";

    private final Catalog catalog;

    public FtpStorageType(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public StorageProvider open(StorageConfig config) {
        return new FtpProvider(connection(config), catalog::typeForFileName);
    }

    static FtpProvider.Connection connection(StorageConfig config) {
        final var security = security(config.settings());
        final var port = security == FtpProvider.Security.IMPLICIT ? IMPLICIT_TLS_PORT : DEFAULT_PORT;
        final var location = RemoteLocation.parse(config.endpoint(), SCHEME, port);
        if (location == null) {
            throw new StorageException("Use the form ftp://host/folder or ftp://host:port/folder.");
        }
        if (anonymous(config.settings())) {
            return new FtpProvider.Connection(location, security, null, null, config.setting(CERTIFICATE));
        }
        final var user = config.secret(USER);
        if (user == null || user.isBlank()) {
            throw new StorageException("Enter the user name to sign in with.");
        }
        return new FtpProvider.Connection(location, security, user, config.secret(PASSWORD),
                config.setting(CERTIFICATE));
    }

    /** Explicit TLS unless the settings say otherwise. */
    public static FtpProvider.Security security(Map<String, String> settings) {
        final var security = FtpProvider.Security.of(settings.get(SECURITY));
        return security == null ? FtpProvider.Security.EXPLICIT : security;
    }

    public static boolean anonymous(Map<String, String> settings) {
        return Boolean.parseBoolean(settings.get(ANONYMOUS));
    }

    @Override
    public String kindKey(Map<String, String> settings, Optional<MountInfo> mount) {
        return security(settings) == FtpProvider.Security.NONE ? KEY : TLS_KIND;
    }

    @Override
    public List<String> kinds() {
        return List.of(KEY, TLS_KIND);
    }

    @Override
    public String detail(Map<String, String> settings, Optional<MountInfo> mount) {
        return anonymous(settings) ? "Anonymous" : "";
    }
}
