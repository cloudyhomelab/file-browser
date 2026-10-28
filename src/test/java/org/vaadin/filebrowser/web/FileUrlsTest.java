package org.vaadin.filebrowser.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileUrlsTest {

    @Test
    void encodesEachSegmentButKeepsTheSlashes() {
        assertThat(FileUrls.of("r1", "Photos/2024 trip/a#b?c+d.jpg"))
                .isEqualTo("/files/r1/Photos/2024%20trip/a%23b%3Fc+d.jpg");
        assertThat(FileUrls.of("r1", "Café/日本.txt")).isEqualTo("/files/r1/Caf%C3%A9/%E6%97%A5%E6%9C%AC.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100% done.pdf", "a;b.txt", "a\\b.txt", "50%/notes.txt", "x;y/notes.txt"})
    void findsPathsTheServerWontAnswer(String path) {
        assertThat(FileUrls.reachable(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "a b/c#d?e+f.txt", "Café/日本.txt", "'quoted' [1] {2}.md"})
    void allowsEverythingElse(String path) {
        assertThat(FileUrls.reachable(path)).isTrue();
    }
}
