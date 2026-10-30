package org.vaadin.filebrowser.storage;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.vaadin.filebrowser.data.Catalog;

/** An S3 bucket, or a folder in one, on AWS or an S3-compatible store. Both keys unset means a public bucket. */
@Component
public class S3StorageType implements StorageType {

    public static final String KEY = "s3";
    public static final String REGION = "region";
    /** Unset for AWS; the URL of an S3-compatible store otherwise. */
    public static final String ENDPOINT_URL = "endpoint_url";
    public static final String ACCESS_KEY = "access_key";
    public static final String SECRET_KEY = "secret_key";

    private final Catalog catalog;

    public S3StorageType(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public StorageProvider open(StorageConfig config) {
        return new S3Provider(connection(config), config.connections(), catalog::typeForFileName);
    }

    // no endpoint URL means AWS itself
    static S3Provider.Connection connection(StorageConfig config) {
        final var url = config.setting(ENDPOINT_URL);
        return new S3Provider.Connection(config.endpoint(), config.setting(REGION),
                url == null || url.isBlank() ? null : URI.create(url), config.secret(ACCESS_KEY),
                config.secret(SECRET_KEY));
    }

    @Override
    public String detail(Map<String, String> settings, Optional<MountInfo> mount) {
        return settings.getOrDefault(REGION, "");
    }
}
