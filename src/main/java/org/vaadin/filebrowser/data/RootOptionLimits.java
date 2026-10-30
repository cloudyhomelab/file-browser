package org.vaadin.filebrowser.data;

import java.io.Serializable;

import org.vaadin.filebrowser.storage.LimitedProvider;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What admins may set the connection options of a file system to, from {@code data/root-options.json}. */
public record RootOptionLimits(@JsonProperty("max_connections") Range maxConnections,
                               @JsonProperty("min_browsing_reserve") int minBrowsingReserve,
                               @JsonProperty("idle_stream_seconds") Range idleStreamSeconds) implements Serializable {

    public record Range(@JsonProperty("min") int min, @JsonProperty("max") int max) implements Serializable {

        boolean contains(int value) {
            return value >= min && value <= max;
        }
    }

    public RootOptionLimits {
        if (maxConnections.min() < minBrowsingReserve + LimitedProvider.COPY_CONNECTIONS) {
            throw new IllegalStateException("data/root-options.json must allow at least "
                    + (minBrowsingReserve + LimitedProvider.COPY_CONNECTIONS) + " connections, so a copy fits next to "
                    + "the ones kept for browsing");
        }
    }

    /** The most connections that may be kept for browsing out of {@code maxConnections}. */
    public int maxBrowsingReserve(int maxConnections) {
        return maxConnections - LimitedProvider.COPY_CONNECTIONS;
    }

    /** Why {@code options} can't be saved, or null when they can. */
    public String problem(RootOptions options) {
        final var connections = maxConnectionsProblem(options.maxConnections());
        if (connections != null) {
            return "Maximum open connections: " + connections;
        }
        final var reserve = browsingReserveProblem(options.maxConnections(), options.browsingReserve());
        if (reserve != null) {
            return "Connections kept for browsing: " + reserve;
        }
        final var idle = idleStreamProblem(options.idleStreamSeconds());
        return idle == null ? null : "Idle stream timeout: " + idle;
    }

    /** @return null when allowed */
    public String maxConnectionsProblem(int value) {
        return maxConnections.contains(value) ? null : bounds(maxConnections.min(), maxConnections.max());
    }

    /** @return null when allowed */
    public String browsingReserveProblem(int connections, int value) {
        final var most = maxBrowsingReserve(connections);
        return value >= minBrowsingReserve && value <= most ? null : bounds(minBrowsingReserve, most);
    }

    /** @return null when allowed */
    public String idleStreamProblem(int value) {
        return idleStreamSeconds.contains(value) ? null : bounds(idleStreamSeconds.min(), idleStreamSeconds.max());
    }

    private static String bounds(int min, int max) {
        return "Min: " + min + ", Max: " + max;
    }
}
