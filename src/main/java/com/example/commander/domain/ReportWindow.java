package com.example.commander.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A UTC reporting window for a scheduled report execution.
 *
 * <p>Represents the time range that a report should cover, from start (inclusive)
 * to end (exclusive). All timestamps are in UTC to ensure consistent behavior
 * across distributed systems and timezone boundaries.
 *
 * <p>Instances are immutable and validated at construction to ensure
 * a non-empty, forward-moving window.
 *
 * @param windowStartUtc start of the reporting window (inclusive)
 * @param windowEndUtc end of the reporting window (exclusive)
 */
public record ReportWindow(Instant windowStartUtc, Instant windowEndUtc) {

    public ReportWindow {
        Objects.requireNonNull(windowStartUtc, "windowStartUtc");
        Objects.requireNonNull(windowEndUtc, "windowEndUtc");
        if (windowStartUtc.isAfter(windowEndUtc)) {
            throw new IllegalArgumentException(
                    "windowStartUtc (%s) must not be after windowEndUtc (%s)".formatted(windowStartUtc, windowEndUtc));
        }
    }

    /**
     * Creates a ReportWindow from ZonedDateTime values.
     *
     * <p>Converts the provided local times to UTC for consistent storage.
     *
     * @param startLocal the start time in local timezone
     * @param endLocal the end time in local timezone
     * @return a new ReportWindow in UTC
     * @throws NullPointerException if either parameter is null
     */
    public static ReportWindow fromLocal(ZonedDateTime startLocal, ZonedDateTime endLocal) {
        return new ReportWindow(startLocal.toInstant(), endLocal.toInstant());
    }

    /**
     * Returns the duration of this reporting window.
     *
     * @return the duration between start and end
     */
    public Duration duration() {
        return Duration.between(windowStartUtc, windowEndUtc);
    }

    /**
     * Checks if a given instant falls within this reporting window.
     *
     * <p>The window is inclusive of the start and exclusive of the end,
     * consistent with typical time range semantics.
     *
     * @param instant the instant to check
     * @return true if the instant is within [start, end)
     * @throws NullPointerException if instant is null
     */
    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(windowStartUtc) && instant.isBefore(windowEndUtc);
    }
}
