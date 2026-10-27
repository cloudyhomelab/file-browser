package org.vaadin.filebrowser.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.data.Catalog;

class S3StorageTypeTest {

    private static StorageConfig config(Map<String, String> settings, Map<String, String> secrets) {
        return new StorageConfig("s3://assets/team", settings, secrets);
    }

    @Test
    void aMissingOrBlankEndpointUrlMeansAws() {
        final var region = Map.of(S3StorageType.REGION, "eu-central-1");
        assertThat(S3StorageType.connection(config(region, Map.of())).endpointUrl()).isNull();
        final var blank = Map.of(S3StorageType.REGION, "eu-central-1", S3StorageType.ENDPOINT_URL, " ");
        assertThat(S3StorageType.connection(config(blank, Map.of())).endpointUrl()).isNull();
    }

    @Test
    void passesTheSettingsAndKeysToTheClient() {
        final var settings = Map.of(S3StorageType.REGION, "us-east-1", S3StorageType.ENDPOINT_URL, "http://localhost:9000");
        final var keys = Map.of(S3StorageType.ACCESS_KEY, "AKIDEXAMPLE", S3StorageType.SECRET_KEY, "wJalrXUtnFEMI");
        final var connection = S3StorageType.connection(config(settings, keys));
        assertThat(connection.endpoint()).isEqualTo("s3://assets/team");
        assertThat(connection.region()).isEqualTo("us-east-1");
        assertThat(connection.endpointUrl()).hasToString("http://localhost:9000");
        assertThat(connection.accessKey()).isEqualTo("AKIDEXAMPLE");
        assertThat(connection.secretKey()).isEqualTo("wJalrXUtnFEMI");
        // no keys is a public bucket
        assertThat(S3StorageType.connection(config(settings, Map.of())).accessKey()).isNull();
    }

    @Test
    void isShownAsABucketWithItsRegion() {
        final var type = new S3StorageType(new Catalog());
        assertThat(type.kindKey(Optional.empty())).isEqualTo("s3");
        assertThat(type.detail(Map.of(S3StorageType.REGION, "eu-central-1"), Optional.empty())).isEqualTo("eu-central-1");
    }
}
