package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.storage.StorageException;

class SecretBoxTest {

    private static String key(int fill) {
        final var bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void encryptsWithAFreshNonceAndDecryptsBack() {
        final var box = new SecretBox(key(1));
        final var first = box.encrypt("secret-access-key");
        final var second = box.encrypt("secret-access-key");
        assertThat(first).startsWith("v1:").doesNotContain("secret-access-key").isNotEqualTo(second);
        assertThat(box.decrypt(first)).isEqualTo("secret-access-key");
        assertThat(box.decrypt(second)).isEqualTo("secret-access-key");
    }

    @Test
    void anotherKeyCannotDecrypt() {
        final var stored = new SecretBox(key(1)).encrypt("secret");
        assertThatThrownBy(() -> new SecretBox(key(2)).decrypt(stored)).isInstanceOf(StorageException.class);
    }

    @Test
    void withoutAKeyNothingIsStored() {
        final var box = new SecretBox((String) null);
        assertThat(box.available()).isFalse();
        assertThatThrownBy(() -> box.encrypt("secret"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("FILEBROWSER_SECRET_KEY");
    }

    @Test
    void aMalformedKeyFailsAtStartup() {
        assertThatThrownBy(() -> new SecretBox("not base64!")).isInstanceOf(IllegalStateException.class);
        final var tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new SecretBox(tooShort)).isInstanceOf(IllegalStateException.class);
    }
}
