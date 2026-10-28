package org.vaadin.filebrowser.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ViewerTest {

    @Test
    void picksTheViewerFromTheMediaType() {
        assertThat(Viewer.of("image/png")).isEqualTo(Viewer.IMAGE);
        assertThat(Viewer.of("video/mp4")).isEqualTo(Viewer.VIDEO);
        assertThat(Viewer.of("audio/mpeg")).isEqualTo(Viewer.AUDIO);
        assertThat(Viewer.of("application/pdf")).isEqualTo(Viewer.PDF);
        assertThat(Viewer.of("text/plain; charset=utf-8")).isEqualTo(Viewer.TEXT);
    }

    @Test
    void hasAViewerForEveryListedType() {
        final var catalog = new Catalog();
        for (final var name : new String[] {"a.png", "a.mp4", "a.mp3", "a.pdf", "a.md", "a.svg"}) {
            final var type = catalog.viewType(name).orElseThrow();
            assertThat(Viewer.of(type)).as(name).isNotNull();
        }
        assertThat(Viewer.of(catalog.viewType("a.svg").orElseThrow())).isEqualTo(Viewer.TEXT);
    }
}
