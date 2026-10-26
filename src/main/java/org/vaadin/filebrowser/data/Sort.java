package org.vaadin.filebrowser.data;

import java.io.Serializable;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.vaadin.filebrowser.storage.StorageEntry;

public record Sort(SortKey key, boolean ascending) implements Serializable {

    public static final Sort DEFAULT = new Sort(SortKey.NAME, true);

    /** Selecting the active key flips the direction; a new key starts ascending. */
    public Sort toggle(SortKey newKey) {
        return newKey == key ? new Sort(key, !ascending) : new Sort(newKey, true);
    }

    public List<Located> apply(List<Located> entries) {
        final Comparator<StorageEntry> byKey = switch (key) {
            case NAME -> Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
            case SIZE -> Comparator.comparingLong(e -> e.size() == null ? 0 : e.size());
            // ascending means newest first, as in the design
            case MODIFIED -> Comparator.comparing((StorageEntry e) -> e.modified() == null ? Instant.EPOCH : e.modified())
                    .reversed();
        };
        final var directed = ascending ? byKey : byKey.reversed();
        final Comparator<StorageEntry> foldersFirst = Comparator.comparing(e -> !e.folder());
        final var order = foldersFirst.thenComparing(directed);
        return entries.stream().sorted(Comparator.comparing(Located::entry, order)).toList();
    }
}
