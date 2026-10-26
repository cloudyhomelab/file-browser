package org.vaadin.filebrowser.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class FormatsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private static String modified(LocalDate date) {
        final var instant = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
        return Formats.modified(instant, TODAY, ZoneOffset.UTC);
    }

    @Test
    void recentDatesAreRelative() {
        assertThat(modified(TODAY)).isEqualTo("Today");
        assertThat(modified(TODAY.minusDays(1))).isEqualTo("Yesterday");
        assertThat(modified(TODAY.minusDays(6))).isEqualTo("6 days ago");
    }

    @Test
    void olderDatesShowTheDayAndTheYearOnlyWhenItDiffers() {
        assertThat(modified(TODAY.minusDays(7))).isEqualTo("Oct 19");
        assertThat(modified(LocalDate.of(2025, 3, 4))).isEqualTo("Mar 4, 2025");
    }

    @Test
    void missingAndFutureDates() {
        assertThat(Formats.modified(null, TODAY, ZoneOffset.UTC)).isEqualTo("—");
        assertThat(modified(TODAY.plusDays(3))).isEqualTo("Oct 29");
    }
}
