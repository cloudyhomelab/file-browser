package org.vaadin.filebrowser.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

/** Writes one lzip member, for building test files; the data is held in memory until closed. */
final class LzipOutputStream extends OutputStream {

    /** 2^23 = 8 MiB, XZ for Java's default dictionary. */
    static final int DICT_LOG = 23;

    private final OutputStream out;
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private boolean closed;

    LzipOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) {
        data.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        data.write(b, off, len);
    }

    static byte[] member(byte[] content) throws IOException {
        final var lzma = new ByteArrayOutputStream();
        final var options = new LZMA2Options();
        options.setDictSize(1 << DICT_LOG);
        try (final var encoder = new LZMAOutputStream(lzma, options, true)) {
            encoder.write(content);
        }
        final var crc = new CRC32();
        crc.update(content);
        final var member = new ByteArrayOutputStream();
        member.write(new byte[]{'L', 'Z', 'I', 'P', 1, DICT_LOG});
        lzma.writeTo(member);
        final var memberSize = member.size() + 20L;
        member.write(littleEndian(crc.getValue(), 4));
        member.write(littleEndian(content.length, 8));
        member.write(littleEndian(memberSize, 8));
        return member.toByteArray();
    }

    private static byte[] littleEndian(long value, int length) {
        final var bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (value >>> (8 * i));
        }
        return bytes;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        out.write(member(data.toByteArray()));
        out.close();
    }
}
