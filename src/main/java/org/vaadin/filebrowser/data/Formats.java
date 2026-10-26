package org.vaadin.filebrowser.data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Formats {

    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB"};
    private static final int UNIT_STEP = 1024;
    /** From this value up a size is shown without decimals ("120 MB" rather than "120.4 MB"). */
    private static final int WHOLE_NUMBER_FROM = 100;
    private static final int RELATIVE_DAYS_LIMIT = 7;
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private Formats() {
    }

    public static String size(Long bytes) {
        if (bytes == null) {
            return "—";
        }
        double n = bytes;
        int unit = 0;
        while (n >= UNIT_STEP && unit < SIZE_UNITS.length - 1) {
            n /= UNIT_STEP;
            unit++;
        }
        final var number = n >= WHOLE_NUMBER_FROM || unit == 0
                ? String.valueOf(Math.round(n))
                : String.format(Locale.US, "%.1f", n);
        return number + " " + SIZE_UNITS[unit];
    }

    public static String gigabytes(Long gb) {
        if (gb == null) {
            return "—";
        }
        if (gb >= UNIT_STEP) {
            return String.format(Locale.US, "%.1f TB", gb / (double) UNIT_STEP);
        }
        return gb + " GB";
    }

    public static String daysAgo(int days, LocalDate today) {
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        if (days < RELATIVE_DAYS_LIMIT) {
            return days + " days ago";
        }
        return today.minusDays(days).format(MONTH_DAY);
    }

    public static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    public static String initials(String name) {
        final var sb = new StringBuilder();
        for (final var part : (name == null ? "?" : name).trim().split("\\s+")) {
            if (!part.isEmpty() && sb.length() < 2) {
                sb.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return sb.isEmpty() ? "?" : sb.toString();
    }

    public static String firstName(String name) {
        return name == null ? "" : name.trim().split("\\s+")[0];
    }
}
