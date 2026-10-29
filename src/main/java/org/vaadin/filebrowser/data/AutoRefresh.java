package org.vaadin.filebrowser.data;

import java.time.Duration;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How often the folder view re-reads the open folder on its own. */
@Getter
@RequiredArgsConstructor
public enum AutoRefresh {
    OFF("Off", null),
    S5("5 s", Duration.ofSeconds(5)),
    S10("10 s", Duration.ofSeconds(10)),
    S30("30 s", Duration.ofSeconds(30)),
    M1("1 min", Duration.ofMinutes(1)),
    M2("2 min", Duration.ofMinutes(2)),
    M5("5 min", Duration.ofMinutes(5));

    private final String label;
    /** Null for {@link #OFF}. */
    private final Duration interval;

    public boolean on() {
        return interval != null;
    }
}
