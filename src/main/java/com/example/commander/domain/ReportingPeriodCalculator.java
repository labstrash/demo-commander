package com.example.commander.domain;

import com.example.commander.config.SchedulingProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Calculates UTC reporting windows for scheduled report executions.
 *
 * <p>All time calculations are performed in the configured business timezone and
 * converted to UTC at the end, ensuring consistent window boundaries regardless
 * of system timezone.
 *
 * <p>Supports four window calculation strategies:
 * <ul>
 *   <li><b>Rolling intervals</b> (EVERY_30_MIN, EVERY_1_HOUR, etc.): Fixed duration ending at fire time</li>
 *   <li><b>Calendar-day</b> (DAILY): Starts at midnight of the previous day, ends at fire time</li>
 *   <li><b>Boundary-based</b> (ONE_TIME_PER_DAY, FOUR_TIMES_PER_DAY, etc.): Fixed clock-time boundaries</li>
 *   <li><b>Point-in-time</b> (SNAPSHOT): Zero-duration window at fire time</li>
 * </ul>
 */
@Component
public class ReportingPeriodCalculator {

    private final ZoneId businessZone;

    private static final Set<ReportFrequency> INTERVAL_FREQUENCIES = EnumSet.of(
            ReportFrequency.EVERY_30_MIN,
            ReportFrequency.EVERY_1_HOUR,
            ReportFrequency.EVERY_2_HOURS,
            ReportFrequency.EVERY_4_HOURS);

    public ReportingPeriodCalculator(SchedulingProperties properties) {
        this.businessZone = ZoneId.of(properties.getTimezone());
    }

    /**
     * Calculates a reporting window for a non-window-time frequency.
     *
     * @param frequency the report frequency
     * @param fireTimeUtc the scheduled fire time in UTC
     * @return the calculated reporting window
     */
    public ReportWindow calculate(ReportFrequency frequency, Instant fireTimeUtc) {
        return calculate(frequency, fireTimeUtc, null, null);
    }

    /**
     * Calculates a reporting window with an optional window sequence number.
     *
     * <p>Currently unused for non-window-time frequencies, but kept for API symmetry.
     *
     * @param frequency the report frequency
     * @param fireTimeUtc the scheduled fire time in UTC
     * @param windowSequence the 0-based slot index (required for window-time frequencies)
     * @return the calculated reporting window
     */
    public ReportWindow calculate(ReportFrequency frequency, Instant fireTimeUtc, Integer windowSequence) {
        return calculate(frequency, fireTimeUtc, windowSequence, null);
    }

    /**
     * Calculates a reporting window with full parameters.
     *
     * <p>For window-time frequencies (ONE_TIME_PER_DAY, FOUR_TIMES_PER_DAY, EIGHT_TIMES_PER_DAY),
     * both {@code windowSequence} and {@code boundaries} are required. The boundaries define
     * the clock times (e.g., "09:00,13:00,17:00"), and the sequence selects which window
     * to use (0 = midnight to first boundary, 1 = first to second boundary, etc.).
     *
     * @param frequency the report frequency
     * @param fireTimeUtc the scheduled fire time in UTC
     * @param windowSequence the 0-based slot index (required for window-time frequencies)
     * @param boundaries the configured boundary times (required for window-time frequencies)
     * @return the calculated reporting window
     * @throws NullPointerException if frequency or fireTimeUtc is null
     * @throws IllegalArgumentException if required parameters are missing for window-time frequencies
     */
    public ReportWindow calculate(
            ReportFrequency frequency, Instant fireTimeUtc, Integer windowSequence, List<LocalTime> boundaries) {
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(fireTimeUtc, "fireTimeUtc");

        ZonedDateTime fireTimeLocal = fireTimeUtc.atZone(businessZone);
        ZonedDateTime start;
        ZonedDateTime end;

        if (INTERVAL_FREQUENCIES.contains(frequency)) {
            end = fireTimeLocal;
            start = fireTimeLocal.minus(intervalFor(frequency));

        } else if (frequency == ReportFrequency.DAILY) {
            end = fireTimeLocal;
            start = fireTimeLocal.toLocalDate().atStartOfDay(businessZone).minusDays(1);

        } else if (frequency.isWindowTimeFrequency()) {
            if (windowSequence == null) {
                throw new IllegalArgumentException("windowSequence is required for window-time frequency " + frequency);
            }
            if (boundaries == null || boundaries.isEmpty()) {
                throw new IllegalArgumentException("boundaries is required for window-time frequency " + frequency);
            }
            ZonedDateTime[] bounds = resolveWindowTimeBounds(fireTimeLocal.toLocalDate(), boundaries, windowSequence);
            start = bounds[0];
            end = bounds[1];

        } else if (frequency == ReportFrequency.SNAPSHOT) {
            start = fireTimeLocal;
            end = fireTimeLocal;

        } else {
            throw new IllegalArgumentException("Unhandled ReportFrequency: " + frequency);
        }

        return new ReportWindow(start.toInstant(), end.toInstant());
    }

    private ZonedDateTime[] resolveWindowTimeBounds(
            LocalDate fireDate, List<LocalTime> windowTimes, int windowSequence) {

        if (windowSequence < 0 || windowSequence >= windowTimes.size()) {
            throw new IllegalArgumentException("windowSequence " + windowSequence + " out of range for "
                    + windowTimes.size() + " configured window time(s)");
        }

        LocalTime endTime = windowTimes.get(windowSequence);
        LocalTime startTime = windowSequence == 0 ? LocalTime.MIDNIGHT : windowTimes.get(windowSequence - 1);

        return new ZonedDateTime[] {
            ZonedDateTime.of(fireDate, startTime, businessZone), ZonedDateTime.of(fireDate, endTime, businessZone)
        };
    }

    private Duration intervalFor(ReportFrequency frequency) {
        return switch (frequency) {
            case EVERY_30_MIN -> Duration.ofMinutes(30);
            case EVERY_1_HOUR -> Duration.ofHours(1);
            case EVERY_2_HOURS -> Duration.ofHours(2);
            case EVERY_4_HOURS -> Duration.ofHours(4);
            default -> throw new IllegalArgumentException("Not an interval frequency: " + frequency);
        };
    }
}
