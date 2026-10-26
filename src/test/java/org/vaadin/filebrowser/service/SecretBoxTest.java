package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vaadin.filebrowser.config.FileBrowserProperties;
import org.vaadin.filebrowser.storage.StorageException;

class SecretBoxTest {

    @TempDir
    Path dir;

    private static String key(int fill) {
        final var bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static FileBrowserProperties withKeyFile(Path file) {
        return new FileBrowserProperties(null, null, null, null, true, null, null, file, null);
    }

    @Test
    void encryptsWithAFreshNonceAndDecryptsBackInTheSameContext() {
        final var box = new SecretBox(key(1));
        final var first = box.encrypt("secret-access-key", "a/secret_key");
        final var second = box.encrypt("secret-access-key", "a/secret_key");
        assertThat(first).startsWith("v2:").doesNotContain("secret-access-key").isNotEqualTo(second);
        assertThat(box.decrypt(first, "a/secret_key")).isEqualTo("secret-access-key");
        assertThat(box.decrypt(second, "a/secret_key")).isEqualTo("secret-access-key");
    }

    @Test
    void aValueOnlyDecryptsInItsOwnContext() {
        final var box = new SecretBox(key(1));
        final var stored = box.encrypt("secret", "a/secret_key");
        assertThatThrownBy(() -> box.decrypt(stored, "b/secret_key")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> box.decrypt(stored, "a/access_key")).isInstanceOf(StorageException.class);
    }

    @Test
    void anotherKeyCannotDecrypt() {
        final var stored = new SecretBox(key(1)).encrypt("secret", "a/secret_key");
        assertThatThrownBy(() -> new SecretBox(key(2)).decrypt(stored, "a/secret_key"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void readsTheKeyFromTheFileNamedInTheSettings() throws IOException {
        final var file = Files.writeString(dir.resolve("secret-key"), key(1) + "\n");
        final var stored = new SecretBox(key(1)).encrypt("secret", "a/secret_key");
        assertThat(new SecretBox(withKeyFile(file)).decrypt(stored, "a/secret_key")).isEqualTo("secret");
    }

    @Test
    void withoutAKeyFileNothingIsStored() {
        final var box = new SecretBox(withKeyFile(null));
        assertThat(box.available()).isFalse();
        assertThatThrownBy(() -> box.encrypt("secret", "a/secret_key"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("FILEBROWSER_SECRET_KEY_FILE");
    }

    @Test
    void aMissingOrMalformedKeyFileFailsAtStartup() throws IOException {
        final var missing = withKeyFile(dir.resolve("missing"));
        assertThatThrownBy(() -> new SecretBox(missing)).isInstanceOf(IllegalStateException.class);
        final var malformed = withKeyFile(Files.writeString(dir.resolve("bad"), "not base64!"));
        assertThatThrownBy(() -> new SecretBox(malformed)).isInstanceOf(IllegalStateException.class);
        final var tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new SecretBox(tooShort)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stillReadsValuesStoredBeforeTheyWereBound() throws Exception {
        // the v1 format: AES-GCM without associated data
        final var nonce = new byte[12];
        final var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(key(1)), "AES"),
                new GCMParameterSpec(128, nonce));
        final var sealed = cipher.doFinal("secret".getBytes(StandardCharsets.UTF_8));
        final var stored = "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + sealed.length)
                .put(nonce).put(sealed).array());
        assertThat(new SecretBox(key(1)).decrypt(stored, "any/secret_key")).isEqualTo("secret");
    }
}
