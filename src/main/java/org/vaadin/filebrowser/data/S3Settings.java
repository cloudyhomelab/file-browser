package org.vaadin.filebrowser.data;

import java.io.Serializable;

/**
 * What an S3 file system needs besides its {@code s3://} location and region, as entered in the
 * add dialog. Both keys null means anonymous access to a public bucket.
 *
 * @param endpointUrl null for AWS; the URL of an S3-compatible store otherwise
 */
public record S3Settings(String endpointUrl, String accessKey, String secretKey) implements Serializable {

    public boolean anonymous() {
        return accessKey == null;
    }

    /** Never print the secret, e.g. in a log line or an error. */
    @Override
    public String toString() {
        return "S3Settings[endpointUrl=" + endpointUrl + ", accessKey=" + (anonymous() ? "none" : "set") + "]";
    }
}
