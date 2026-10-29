package org.vaadin.filebrowser.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Encrypts secrets kept in the database (e.g. S3 keys) with AES-256-GCM under a key read from the file
 * named by {@code FILEBROWSER_SECRET_KEY_FILE} (e.g. a Docker or Kubernetes secret); the key itself
 * is never taken from the environment. Each value is bound to a context (which file system and
 * which setting), so a stored value can't be moved to another one. Without a key nothing can be
 * stored or read back.
 */
@Service
public class SecretBox {

    /** Values bound to their context. */
    private static final String PREFIX = "v2:";
    /** Values stored before contexts existed; still read, never written. */
    private static final String UNBOUND_PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SecretBox(FileBrowserProperties properties) {
        this(readKeyFile(properties.secretKeyFile()));
    }

    /** @param base64Key 32 bytes, base64-encoded; null when none is configured */
    SecretBox(String base64Key) {
        this.key = base64Key == null ? null : parse(base64Key.trim());
    }

    private static String readKeyFile(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("FILEBROWSER_SECRET_KEY_FILE can't be read: " + file, e);
        }
    }

    private static SecretKeySpec parse(String base64Key) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The secret key file must hold base64 (e.g. `openssl rand -base64 32`)");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("The secret key file must hold 32 bytes, base64-encoded (e.g. `openssl rand -base64 32`)");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public boolean available() {
        return key != null;
    }

    /** @param context what the value belongs to; the same context is needed to decrypt it */
    public String encrypt(String plain, String context) {
        requireKey();
        final var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            final var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            final var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            final var out = ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored, String context) {
        requireKey();
        final var bound = stored != null && stored.startsWith(PREFIX);
        if (!bound && (stored == null || !stored.startsWith(UNBOUND_PREFIX))) {
            throw new StorageException("Stored credentials are in an unknown format.");
        }
        final var bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        try {
            final var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            if (bound) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            final var plain = cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // another key than the one it was stored with, or a value moved from another context
            throw new StorageException("Stored credentials can't be read with the server's secret key.", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new StorageException("Set FILEBROWSER_SECRET_KEY_FILE on the server to store credentials.");
        }
    }
}
