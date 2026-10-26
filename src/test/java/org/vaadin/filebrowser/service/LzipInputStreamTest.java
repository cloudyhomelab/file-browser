package org.vaadin.filebrowser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class LzipInputStreamTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String decode(byte[] file) throws IOException {
        try (final var in = new LzipInputStream(new ByteArrayInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] concat(byte[]... parts) {
        final var out = new ByteArrayOutputStream();
        for (final var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Test
    void decodesOneMemberAndSeveral() throws IOException {
        assertThat(decode(LzipOutputStream.member(bytes("hello lzip")))).isEqualTo("hello lzip");
        assertThat(decode(concat(LzipOutputStream.member(bytes("first, ")), LzipOutputStream.member(bytes("second")))))
                .isEqualTo("first, second");
        assertThat(decode(LzipOutputStream.member(new byte[0]))).isEmpty();
    }

    @Test
    void ignoresDataAfterTheLastMember() throws IOException {
        assertThat(decode(concat(LzipOutputStream.member(bytes("data")), bytes("trailing garbage"))))
                .isEqualTo("data");
    }

    @Test
    void refusesFilesThatAreNotLzipOrDamaged() throws IOException {
        final var good = LzipOutputStream.member(bytes("some content to check"));
        assertThatThrownBy(() -> decode(bytes("not lzip at all"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decode(new byte[0])).isInstanceOf(IOException.class);
        final var badCrc = good.clone();
        badCrc[good.length - 20] ^= 1;
        assertThatThrownBy(() -> decode(badCrc)).isInstanceOf(IOException.class);
        final var badSize = good.clone();
        badSize[good.length - 16] ^= 1;
        assertThatThrownBy(() -> decode(badSize)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decode(Arrays.copyOf(good, good.length - 5))).isInstanceOf(IOException.class);
        final var badVersion = good.clone();
        badVersion[4] = 0;
        assertThatThrownBy(() -> decode(badVersion)).isInstanceOf(IOException.class);
    }

    @Test
    void refusesDictionariesTooLargeToAllocate() throws IOException {
        final var huge = LzipOutputStream.member(new byte[0]);
        huge[5] = 29;
        assertThatThrownBy(() -> decode(huge)).isInstanceOf(IOException.class).hasMessageContaining("dictionary");
    }
}
