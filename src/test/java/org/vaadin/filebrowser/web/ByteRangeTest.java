package org.vaadin.filebrowser.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ByteRangeTest {

    private static final long SIZE = 100;

    private static ByteRange.Request parse(String header) {
        return ByteRange.parse(header, SIZE);
    }

    private static ByteRange.Request part(long start, long end) {
        return new ByteRange.Part(new ByteRange(start, end));
    }

    @Test
    void readsTheThreeFormsOfASingleRange() {
        assertThat(parse("bytes=10-19")).isEqualTo(part(10, 19));
        assertThat(parse("bytes=90-")).isEqualTo(part(90, 99));
        assertThat(parse("bytes=-5")).isEqualTo(part(95, 99));
    }

    @Test
    void clampsRangesThatRunPastTheEnd() {
        assertThat(parse("bytes=50-1000")).isEqualTo(part(50, 99));
        assertThat(parse("bytes=-1000")).isEqualTo(part(0, 99));
    }

    @Test
    void refusesRangesThatStartPastTheEnd() {
        assertThat(parse("bytes=100-")).isInstanceOf(ByteRange.Unsatisfiable.class);
        assertThat(parse("bytes=-0")).isInstanceOf(ByteRange.Unsatisfiable.class);
        assertThat(ByteRange.parse("bytes=0-", 0)).isInstanceOf(ByteRange.Unsatisfiable.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "items=0-1", "bytes=", "bytes=-", "bytes=5", "bytes=9-3", "bytes=a-b", "bytes=1-x",
            "bytes=0-1,5-6", "bytes=99999999999999999999-", "bytes=١-٢"})
    void sendsTheWholeFileForAnythingElse(String header) {
        assertThat(parse(header)).isInstanceOf(ByteRange.Whole.class);
    }

    @Test
    void sendsTheWholeFileWithoutAHeader() {
        assertThat(parse(null)).isInstanceOf(ByteRange.Whole.class);
    }

    @Test
    void measuresBothEndsIncluded() {
        assertThat(new ByteRange(10, 19).length()).isEqualTo(10);
    }
}
