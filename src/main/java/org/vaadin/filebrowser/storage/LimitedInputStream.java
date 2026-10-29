package org.vaadin.filebrowser.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Ends after a given number of bytes; closing it closes the stream underneath. */
public final class LimitedInputStream extends FilterInputStream {

    private long remaining;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        final var value = super.read();
        if (value >= 0) {
            remaining--;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        final var count = super.read(buffer, off, (int) Math.min(len, remaining));
        if (count > 0) {
            remaining -= count;
        }
        return count;
    }

    @Override
    public long skip(long count) throws IOException {
        final var skipped = super.skip(Math.min(count, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), remaining);
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
