package org.vaadin.filebrowser.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RootOptionLimitsTest {

    private final Catalog catalog = new Catalog();
    private final RootOptionLimits limits = catalog.getRootOptionLimits();

    @Test
    void theDefaultsAreAllowed() {
        assertThat(limits.problem(catalog.getRootOptions())).isNull();
        assertThat(catalog.getRootOptions()).isEqualTo(TestRoots.ALL_ALLOWED);
    }

    @Test
    void browsingLeavesRoomForACopy() {
        assertThat(limits.browsingReserveProblem(4, 2)).isNull();
        assertThat(limits.browsingReserveProblem(4, 3)).isEqualTo("Min: 1, Max: 2");
        assertThat(limits.browsingReserveProblem(4, 0)).isEqualTo("Min: 1, Max: 2");
        assertThat(limits.browsingReserveProblem(3, 2)).isEqualTo("Min: 1, Max: 1");
    }

    @Test
    void aProblemNamesItsSetting() {
        final var tooFew = new RootOptions(true, true, true, 2, 1, 30);
        assertThat(limits.problem(tooFew)).isEqualTo("Maximum open connections: Min: 3, Max: 256");
        final var tooQuick = new RootOptions(true, true, true, 4, 1, 5);
        assertThat(limits.problem(tooQuick)).isEqualTo("Idle stream timeout: Min: 10, Max: 3600");
    }

    @Test
    void limitsWithoutRoomForACopyAreRefused() {
        final var connections = new RootOptionLimits.Range(2, 256);
        final var idle = new RootOptionLimits.Range(10, 3600);
        assertThatThrownBy(() -> new RootOptionLimits(connections, 1, idle)).isInstanceOf(IllegalStateException.class);
    }
}
