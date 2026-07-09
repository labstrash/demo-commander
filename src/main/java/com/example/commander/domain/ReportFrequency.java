package com.example.commander.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Report generation frequencies supported by the scheduling system.
 *
 * <p>These values align with the report frequency codes defined in the CAMT system
 * and are used consistently across configuration, job scheduling, and domain logic.
 */
public enum ReportFrequency {
    SNAPSHOT,
    EVERY_30_MIN,
    EVERY_1_HOUR,
    EVERY_2_HOURS,
    EVERY_4_HOURS,
    DAILY,
    ONE_TIME_PER_DAY,
    FOUR_TIMES_PER_DAY,
    EIGHT_TIMES_PER_DAY;

    private static final Set<ReportFrequency> WINDOW_TIME_FREQUENCIES =
            EnumSet.of(ONE_TIME_PER_DAY, FOUR_TIMES_PER_DAY, EIGHT_TIMES_PER_DAY);

    /**
     * Returns the database code for this frequency.
     *
     * @return enum name as stored in the database
     */
    public String dbCode() {
        return name();
    }

    /**
     * Returns true if this frequency uses fixed clock-time boundaries.
     *
     * <p>Window-time frequencies have predefined boundary times (e.g., 09:00, 13:00, 17:00)
     * rather than rolling intervals or calendar-day windows.
     *
     * @return true for one-time-per-day, four-times-per-day, and eight-times-per-day
     */
    public boolean isWindowTimeFrequency() {
        return WINDOW_TIME_FREQUENCIES.contains(this);
    }

    /**
     * Parses a frequency from its database code.
     *
     * @param code database code (enum name)
     * @return the corresponding ReportFrequency
     * @throws IllegalArgumentException if code is null or invalid
     */
    public static ReportFrequency fromDbCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("ReportFrequency code cannot be null");
        }
        return ReportFrequency.valueOf(code);
    }

    /**
     * Parses a frequency from a configuration value.
     *
     * <p>Supports both standard enum names (e.g., "EVERY_30_MIN") and
     * kebab-case format (e.g., "every-30-min") for configuration flexibility.
     *
     * @param value configuration value (enum name or kebab-case)
     * @return the corresponding ReportFrequency
     * @throws IllegalArgumentException if value is null or cannot be parsed
     */
    public static ReportFrequency fromConfig(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ReportFrequency value cannot be null");
        }
        try {
            return ReportFrequency.valueOf(value);
        } catch (IllegalArgumentException e) {
            String enumName = value.toUpperCase().replace('-', '_');
            return ReportFrequency.valueOf(enumName);
        }
    }
}
