package com.example.commander.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Quartz scheduling configuration properties.
 *
 * <p>Defines scheduled jobs through a list of {@link Schedule} entries. Each schedule associates
 * one or more report types with a timing definition (cron or boundaries).
 *
 * <p>Configured via {@code commander.scheduling} prefix in application properties.
 */
@Validated
@ConfigurationProperties(prefix = "commander.scheduling")
public class SchedulingProperties {

    /** Timezone for cron expressions and window calculations. Defaults to Europe/Stockholm. */
    @NotNull private String timezone = "Europe/Stockholm";

    /** Schedule definitions. Each entry maps a frequency to its timing and report types. */
    @NotEmpty @Valid private List<Schedule> schedules = new ArrayList<>();

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public List<Schedule> getSchedules() {
        return schedules;
    }

    public void setSchedules(List<Schedule> schedules) {
        this.schedules = schedules;
    }

    /**
     * Validates all schedules after property binding.
     *
     * <p>Performs fail-fast validation including:
     * <ul>
     *   <li>Exactly one timing definition (cron or boundaries)</li>
     *   <li>At least one report type per schedule</li>
     *   <li>Window minutes presence/absence based on schedule type</li>
     *   <li>Boundary times are strictly ascending and not at midnight</li>
     *   <li>No duplicate (report-type, frequency) combinations</li>
     * </ul>
     *
     * @throws IllegalStateException if any validation rule is violated
     */
    public void validate() {
        Set<String> seenPairs = new HashSet<>();

        for (int i = 0; i < schedules.size(); i++) {
            Schedule schedule = schedules.get(i);

            if (!schedule.isValid()) {
                throw new IllegalStateException(String.format(
                        "Schedule[%d] (frequency=%s) must have exactly one of 'cron' or 'boundaries'",
                        i, schedule.getFrequency()));
            }

            if (schedule.getReportTypes() == null || schedule.getReportTypes().isEmpty()) {
                throw new IllegalStateException(String.format(
                        "Schedule[%d] (frequency=%s) must have at least one report-type", i, schedule.getFrequency()));
            }

            validateWindowMinutes(i, schedule);
            validateBoundaries(i, schedule);

            for (String reportType : schedule.getReportTypes()) {
                String pairKey = reportType + "|" + schedule.getFrequency();
                if (!seenPairs.add(pairKey)) {
                    throw new IllegalStateException(String.format(
                            "Duplicate (report-type, frequency) pair found: %s, %s",
                            reportType, schedule.getFrequency()));
                }
            }
        }
    }

    private void validateWindowMinutes(int index, Schedule schedule) {
        Integer windowMinutes = schedule.getWindowMinutes();
        boolean isDaily = schedule.isDailyFrequency();

        if (schedule.hasCronSchedule() && !isDaily) {
            if (windowMinutes == null || windowMinutes <= 0) {
                throw new IllegalStateException(String.format(
                        "Schedule[%d] (frequency=%s) has 'cron' but is missing a positive 'window-minutes'",
                        index, schedule.getFrequency()));
            }
        } else if (windowMinutes != null) {
            throw new IllegalStateException(String.format(
                    "Schedule[%d] (frequency=%s) must not set 'window-minutes' — %s",
                    index,
                    schedule.getFrequency(),
                    isDaily
                            ? "DAILY uses the calendar-day rule"
                            : "boundaries-based schedules derive their window from adjacent boundaries"));
        }
    }

    private void validateBoundaries(int index, Schedule schedule) {
        if (!schedule.hasBoundarySchedule()) {
            return;
        }

        List<LocalTime> times = parseBoundaries(schedule.getBoundaries());
        if (times.isEmpty()) {
            throw new IllegalStateException(
                    String.format("Schedule[%d] (frequency=%s) has empty boundaries", index, schedule.getFrequency()));
        }

        for (int j = 1; j < times.size(); j++) {
            if (times.get(j).compareTo(times.get(j - 1)) <= 0) {
                throw new IllegalStateException(String.format(
                        "Schedule[%d] (frequency=%s) boundaries must be strictly ascending: %s",
                        index, schedule.getFrequency(), schedule.getBoundaries()));
            }
        }

        if (times.stream().anyMatch(t -> t.equals(LocalTime.MIDNIGHT))) {
            throw new IllegalStateException(String.format(
                    "Schedule[%d] (frequency=%s) boundaries cannot include 00:00", index, schedule.getFrequency()));
        }
    }

    private List<LocalTime> parseBoundaries(String boundaries) {
        if (boundaries == null || boundaries.isBlank()) {
            return List.of();
        }
        return Arrays.stream(boundaries.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> LocalTime.parse(s, DateTimeFormatter.ofPattern("HH:mm")))
                .collect(Collectors.toList());
    }

    /**
     * Schedule definition linking a frequency to its timing and report types.
     *
     * <p>Two timing patterns are supported:
     * <ul>
     *   <li><b>Pattern A (cron):</b> Standard cron expression, optionally with window-minutes</li>
     *   <li><b>Pattern B (boundaries):</b> Ordered time boundaries for daily window-based scheduling</li>
     * </ul>
     */
    public static class Schedule {
        /** Frequency label (e.g., DAILY, EVERY_30_MIN). Used in job naming and pattern detection. */
        @NotNull private String frequency;

        /** Cron expression. Mutually exclusive with {@link #boundaries}. */
        private String cron;

        /**
         * Ordered boundary times in HH:mm format (e.g., "09:00,13:00,17:00").
         * Mutually exclusive with {@link #cron}. Times must be strictly ascending and not at midnight.
         */
        private String boundaries;

        /**
         * Lookback duration in minutes for interval-based schedules.
         *
         * <p>Required for non-DAILY cron schedules. Must be absent for boundaries-based schedules.
         */
        private Integer windowMinutes;

        /**
         * Days of week for boundary-based schedules.
         *
         * <p>Default: "MON-FRI". Applied as the day-of-week field in the assembled Quartz cron expression.
         */
        private String daysOfWeek = "MON-FRI";

        /** Report types that should run on this schedule. Must contain at least one entry. */
        @NotEmpty private List<String> reportTypes = new ArrayList<>();

        public String getFrequency() {
            return frequency;
        }

        public void setFrequency(String frequency) {
            this.frequency = frequency;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getBoundaries() {
            return boundaries;
        }

        public void setBoundaries(String boundaries) {
            this.boundaries = boundaries;
        }

        public Integer getWindowMinutes() {
            return windowMinutes;
        }

        public void setWindowMinutes(Integer windowMinutes) {
            this.windowMinutes = windowMinutes;
        }

        public String getDaysOfWeek() {
            return daysOfWeek;
        }

        public void setDaysOfWeek(String daysOfWeek) {
            this.daysOfWeek = daysOfWeek;
        }

        public List<String> getReportTypes() {
            return reportTypes;
        }

        public void setReportTypes(List<String> reportTypes) {
            this.reportTypes = reportTypes;
        }

        /** Returns true if a cron expression is defined and non-blank. */
        public boolean hasCronSchedule() {
            return cron != null && !cron.isBlank();
        }

        /** Returns true if boundaries are defined and non-blank. */
        public boolean hasBoundarySchedule() {
            return boundaries != null && !boundaries.isBlank();
        }

        /** Returns true if exactly one timing definition (cron or boundaries) is present. */
        public boolean isValid() {
            return hasCronSchedule() ^ hasBoundarySchedule();
        }

        /** Returns true if this is a DAILY frequency schedule. */
        public boolean isDailyFrequency() {
            return "DAILY".equals(frequency);
        }
    }
}
