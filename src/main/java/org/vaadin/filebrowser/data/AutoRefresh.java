package org.vaadin.filebrowser.data;

import java.time.Duration;

/** How often the folder view re-reads the open folder on its own. */
public enum AutoRefresh {
    OFF("Off", null),
    S5("5 s", Duration.ofSeconds(5)),
    S10("10 s", Duration.ofSeconds(10)),
    S30("30 s", Duration.ofSeconds(30)),
    M1("1 min", Duration.ofMinutes(1)),
    M2("2 min", Duration.ofMinutes(2)),
    M5("5 min", Duration.ofMinutes(5));

    private final String label;
    private final Duration interval;

    AutoRefresh(String label, Duration interval) {
        this.label = label;
        this.interval = interval;
    }

    public String label() {
        return label;
    }

    /** Null for {@link #OFF}. */
    public Duration interval() {
        return interval;
    }

    public boolean on() {
        return interval != null;
    }
}
