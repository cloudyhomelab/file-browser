package org.vaadin.filebrowser.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.storage.StorageException;

/**
 * Encrypts secrets kept in the database (S3 keys) with AES-256-GCM under the server's
 * {@code FILEBROWSER_SECRET_KEY}. Without that key nothing can be stored or read back.
 */
@Service
public class SecretBox {

    private static final String PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SecretBox(FileBrowserProperties properties) {
        this(properties.secretKey());
    }

    /** @param base64Key 32 bytes, base64-encoded; null or blank when none is configured */
    SecretBox(String base64Key) {
        this.key = base64Key == null || base64Key.isBlank() ? null : parse(base64Key.trim());
    }

    private static SecretKeySpec parse(String base64Key) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("FILEBROWSER_SECRET_KEY must be base64 (e.g. `openssl rand -base64 32`)");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("FILEBROWSER_SECRET_KEY must decode to 32 bytes (e.g. `openssl rand -base64 32`)");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        final var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            final var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            final var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            final var out = ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new StorageException("Stored credentials are in an unknown format.");
        }
        final var bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        try {
            final var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            final var plain = cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // a different FILEBROWSER_SECRET_KEY than the one the credentials were stored with
            throw new StorageException("Stored credentials can't be read with the server's secret key.");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new StorageException("Set FILEBROWSER_SECRET_KEY on the server to store S3 credentials.");
        }
    }
}
