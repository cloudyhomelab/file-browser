package org.vaadin.filebrowser.service;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.tukaani.xz.LZMAInputStream;

/**
 * Decompresses lzip ({@code .lz}), which neither Commons Compress nor XZ for Java reads, using XZ for
 * Java's LZMA decoder for the data. A file is one or more members, each a header, an LZMA stream
 * and a trailer whose CRC32 and sizes are checked. Data after the last member is ignored, as lzip does.
 */
final class LzipInputStream extends InputStream {

    private static final byte[] MAGIC = {'L', 'Z', 'I', 'P'};
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 6;
    private static final int TRAILER_SIZE = 20;
    /** lc=3, lp=0, pb=2: the only LZMA properties lzip uses. */
    private static final byte LZMA_PROPERTIES = 0x5D;
    private static final int MIN_DICT_LOG = 12;
    private static final int MAX_DICT_LOG = 29;
    /** The decoder allocates the whole dictionary up front; lzip's own -9 uses 32 MiB. */
    private static final int MAX_DICT_SIZE = 64 * 1024 * 1024;

    private final Counting in;
    private final CRC32 crc = new CRC32();
    private LZMAInputStream member;
    private long memberStart;
    private long dataSize;
    private boolean first = true;
    private boolean finished;

    LzipInputStream(InputStream in) {
        this.in = new Counting(in);
    }

    @Override
    public int read() throws IOException {
        final var one = new byte[1];
        final var read = read(one, 0, 1);
        return read == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (!finished) {
            if (member == null && !startMember()) {
                finished = true;
                break;
            }
            final var read = member.read(buffer, offset, length);
            if (read > 0) {
                crc.update(buffer, offset, read);
                dataSize += read;
                return read;
            }
            endMember();
        }
        return -1;
    }

    /** Reads the next member's header; false at the end of the file. */
    private boolean startMember() throws IOException {
        memberStart = in.count;
        final var header = in.readNBytes(HEADER_SIZE);
        if (!first && (header.length < MAGIC.length || !Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length))) {
            return false;
        }
        if (header.length < HEADER_SIZE || !Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException("Not an lzip file.");
        }
        if (header[4] != VERSION) {
            throw new IOException("Unsupported lzip version " + header[4] + ".");
        }
        final var coded = header[5] & 0xFF;
        final var log = coded & 0x1F;
        if (log < MIN_DICT_LOG || log > MAX_DICT_LOG) {
            throw new IOException("Invalid lzip dictionary size.");
        }
        final var dictSize = (1 << log) - (1 << log) / 16 * (coded >>> 5);
        if (dictSize > MAX_DICT_SIZE) {
            throw new IOException("lzip dictionary too large.");
        }
        first = false;
        crc.reset();
        dataSize = 0;
        member = new LZMAInputStream(in, -1, LZMA_PROPERTIES, dictSize);
        return true;
    }

    /** Checks the trailer against what was decoded. */
    private void endMember() throws IOException {
        final var trailer = in.readNBytes(TRAILER_SIZE);
        if (trailer.length < TRAILER_SIZE) {
            throw new EOFException("Truncated lzip file.");
        }
        if (littleEndian(trailer, 0, 4) != crc.getValue() || littleEndian(trailer, 4, 8) != dataSize
                || littleEndian(trailer, 12, 8) != in.count - memberStart) {
            throw new IOException("Corrupt lzip file.");
        }
        member = null;
    }

    private static long littleEndian(byte[] bytes, int offset, int length) {
        long value = 0;
        for (int i = length - 1; i >= 0; i--) {
            value = value << Byte.SIZE | bytes[offset + i] & 0xFF;
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Counts what the decoder takes, for the trailer's member size; it reads only what it needs. */
    static final class Counting extends FilterInputStream {

        private long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            final var read = super.read();
            if (read != -1) {
                count++;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            final var read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        long count() {
            return count;
        }

        @Override
        public long skip(long bytes) throws IOException {
            final var skipped = super.skip(bytes);
            count += skipped;
            return skipped;
        }
    }
}
