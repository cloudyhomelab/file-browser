package org.vaadin.filebrowser.web;

import java.util.Optional;

/** One byte range of a file, both ends included, as asked for in a {@code Range} header. */
record ByteRange(long start, long end) {

    private static final String UNIT = "bytes=";

    /** What a {@code Range} header asks of a file of a given size. */
    sealed interface Request {
    }

    /** No usable range: send the whole file. */
    record Whole() implements Request {
    }

    /** A range that starts past the end of the file. */
    record Unsatisfiable() implements Request {
    }

    record Part(ByteRange range) implements Request {
    }

    long length() {
        return end - start + 1;
    }

    /**
     * Headers that aren't a single, well-formed byte range are ignored, which HTTP allows; browsers ask for one
     * range at a time, so several ranges in one request are answered with the whole file.
     */
    static Request parse(String header, long size) {
        if (header == null || !header.startsWith(UNIT) || header.contains(",")) {
            return new Whole();
        }
        final var spec = header.substring(UNIT.length()).trim();
        final var dash = spec.indexOf('-');
        if (dash < 0) {
            return new Whole();
        }
        final var firstText = spec.substring(0, dash);
        final var lastText = spec.substring(dash + 1);
        final var last = number(lastText);
        if (firstText.isEmpty()) {
            return last.map(suffix -> suffix(suffix, size)).orElseGet(Whole::new);
        }
        final var first = number(firstText);
        if (first.isEmpty() || (!lastText.isEmpty() && last.isEmpty())) {
            return new Whole();
        }
        final long start = first.get();
        if (last.isPresent() && last.get() < start) {
            return new Whole();
        }
        if (start >= size) {
            return new Unsatisfiable();
        }
        final var end = last.map(l -> Math.min(l, size - 1)).orElse(size - 1);
        return new Part(new ByteRange(start, end));
    }

    private static Request suffix(long suffix, long size) {
        if (suffix == 0 || size == 0) {
            return new Unsatisfiable();
        }
        return new Part(new ByteRange(Math.max(0, size - suffix), size - 1));
    }

    private static Optional<Long> number(String digits) {
        // ASCII only: Long.parseLong would also accept other scripts' digits
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
