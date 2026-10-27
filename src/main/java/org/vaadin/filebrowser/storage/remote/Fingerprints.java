package org.vaadin.filebrowser.storage.remote;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** SHA-256 fingerprints written the way OpenSSH shows them: {@code SHA256:} and unpadded base64. */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(byte[] encoded) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }
}
