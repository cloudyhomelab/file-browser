package org.vaadin.filebrowser.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.vaadin.filebrowser.data.Catalog;
import org.vaadin.filebrowser.storage.StorageEntry;

class LightboxTest {

    private final Catalog catalog = new Catalog();

    private static StorageEntry file(String path) {
        final var name = path.substring(path.lastIndexOf('/') + 1);
        return new StorageEntry(path, name, false, "generic", 1L, Instant.EPOCH, null, null);
    }

    private static StorageEntry folder(String path) {
        return new StorageEntry(path, path, true, StorageEntry.FOLDER_TYPE, null, Instant.EPOCH, null, 0);
    }

    @Test
    void stepsThroughFilesOnlyInTheOrderGiven() {
        final var entries = List.of(file("c.png"), folder("Folder"), file("b.zip"), file("a;b.png"), file("a.txt"));
        assertThat(Lightbox.viewable(entries)).extracting(StorageEntry::name)
                .containsExactly("c.png", "b.zip", "a;b.png", "a.txt");
    }

    @Test
    void stepsThroughTheFilesAndStopsAtBothEnds() {
        final var files = List.of(file("a.png"), file("b.zip"), file("c;d.mp3"));
        final var shown = new ArrayList<String>();
        final var lightbox = new Lightbox(catalog, "r1", files, files.get(1), f -> shown.add(f.name()), f -> { });
        lightbox.next();
        lightbox.next();
        lightbox.previous();
        lightbox.previous();
        lightbox.previous();
        assertThat(shown).containsExactly("b.zip", "c;d.mp3", "b.zip", "a.png");
    }
}
