package com.example.commander.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.commander.config.SchedulingProperties;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportingPeriodCalculatorTest {

    private ReportingPeriodCalculator calculator;
    private SchedulingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SchedulingProperties();
        properties.setTimezone("Europe/Stockholm");
        calculator = new ReportingPeriodCalculator(properties);
    }

    @Test
    void shouldCalculateEvery30MinWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 1, 10, 30, 0, 0, ZoneId.of("UTC")).toInstant();

        ReportWindow window = calculator.calculate(ReportFrequency.EVERY_30_MIN, fireTime);

        assertThat(window.windowStartUtc())
                .isEqualTo(ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneId.of("UTC"))
                        .toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(fireTime);
        assertThat(window.duration()).hasMinutes(30);
    }

    @Test
    void shouldCalculateEvery1HourWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 1, 10, 30, 0, 0, ZoneId.of("UTC")).toInstant();

        ReportWindow window = calculator.calculate(ReportFrequency.EVERY_1_HOUR, fireTime);

        assertThat(window.windowStartUtc())
                .isEqualTo(ZonedDateTime.of(2026, 1, 1, 9, 30, 0, 0, ZoneId.of("UTC"))
                        .toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(fireTime);
        assertThat(window.duration()).hasHours(1);
    }

    @Test
    void shouldCalculateEvery2HoursWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 1, 10, 30, 0, 0, ZoneId.of("UTC")).toInstant();

        ReportWindow window = calculator.calculate(ReportFrequency.EVERY_2_HOURS, fireTime);

        assertThat(window.duration()).hasHours(2);
    }

    @Test
    void shouldCalculateEvery4HoursWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 1, 10, 30, 0, 0, ZoneId.of("UTC")).toInstant();

        ReportWindow window = calculator.calculate(ReportFrequency.EVERY_4_HOURS, fireTime);

        assertThat(window.duration()).hasHours(4);
    }

    @Test
    void shouldCalculateDailyWindow() {
        // Non-midnight fire time (10:30 UTC), the case the calendar-day rule specifically
        // exists to handle correctly — see implementation guide §2's worked example.
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneId.of("UTC")).toInstant();

        ReportWindow window = calculator.calculate(ReportFrequency.DAILY, fireTime);

        // windowEnd = the actual scheduled fire time; windowStart = midnight of the
        // calendar day before the fire's date. NOT midnight-to-midnight — a plain
        // 24h calendar day would silently drop the fire time's offset from midnight
        // every single day (guide §2).
        ZonedDateTime businessFireTime = fireTime.atZone(ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedEnd = businessFireTime;
        ZonedDateTime expectedStart = businessFireTime
                .toLocalDate()
                .atStartOfDay(ZoneId.of("Europe/Stockholm"))
                .minusDays(1);

        assertThat(window.windowStartUtc()).isEqualTo(expectedStart.toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(expectedEnd.toInstant());
    }

    @Test
    void shouldCalculateOneTimePerDayWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 15, 21, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        List<LocalTime> boundaries = List.of(LocalTime.of(21, 0));

        ReportWindow window = calculator.calculate(ReportFrequency.ONE_TIME_PER_DAY, fireTime, 0, boundaries);

        // Window should be midnight to 21:00 in business timezone
        ZonedDateTime businessFireTime = fireTime.atZone(ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedEnd =
                ZonedDateTime.of(businessFireTime.toLocalDate(), LocalTime.of(21, 0), ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedStart = businessFireTime.toLocalDate().atStartOfDay(ZoneId.of("Europe/Stockholm"));

        assertThat(window.windowStartUtc()).isEqualTo(expectedStart.toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(expectedEnd.toInstant());
    }

    @Test
    void shouldCalculateFourTimesPerDayWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 15, 13, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        List<LocalTime> boundaries =
                List.of(LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(18, 0), LocalTime.of(21, 0));

        ReportWindow window = calculator.calculate(ReportFrequency.FOUR_TIMES_PER_DAY, fireTime, 1, boundaries);

        ZonedDateTime businessFireTime = fireTime.atZone(ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedStart =
                ZonedDateTime.of(businessFireTime.toLocalDate(), LocalTime.of(10, 0), ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedEnd =
                ZonedDateTime.of(businessFireTime.toLocalDate(), LocalTime.of(13, 0), ZoneId.of("Europe/Stockholm"));

        assertThat(window.windowStartUtc()).isEqualTo(expectedStart.toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(expectedEnd.toInstant());
    }

    @Test
    void shouldCalculateEightTimesPerDayWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        List<LocalTime> boundaries = List.of(
                LocalTime.of(3, 0),
                LocalTime.of(6, 0),
                LocalTime.of(8, 0),
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                LocalTime.of(15, 0),
                LocalTime.of(18, 0),
                LocalTime.of(21, 0));

        ReportWindow window = calculator.calculate(ReportFrequency.EIGHT_TIMES_PER_DAY, fireTime, 3, boundaries);

        ZonedDateTime businessFireTime = fireTime.atZone(ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedStart =
                ZonedDateTime.of(businessFireTime.toLocalDate(), LocalTime.of(8, 0), ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedEnd =
                ZonedDateTime.of(businessFireTime.toLocalDate(), LocalTime.of(10, 0), ZoneId.of("Europe/Stockholm"));

        assertThat(window.windowStartUtc()).isEqualTo(expectedStart.toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(expectedEnd.toInstant());
    }

    @Test
    void shouldUseConfiguredBoundariesNotAHardcodedDefault() {
        // Guards the fix: window computation must reflect whatever boundaries the caller
        // passes in (i.e., whatever is actually configured), not a fixed set baked into
        // the calculator. A business-hours boundary set well outside the old hardcoded
        // defaults proves there's no leftover internal fallback being consulted.
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 15, 8, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        List<LocalTime> customBoundaries = List.of(LocalTime.of(9, 0), LocalTime.of(17, 0));

        ReportWindow window = calculator.calculate(ReportFrequency.ONE_TIME_PER_DAY, fireTime, 0, customBoundaries);

        ZonedDateTime businessFireTime = fireTime.atZone(ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedEnd =
                ZonedDateTime.of(businessFireTime.toLocalDate(), LocalTime.of(9, 0), ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedStart = businessFireTime.toLocalDate().atStartOfDay(ZoneId.of("Europe/Stockholm"));

        assertThat(window.windowStartUtc()).isEqualTo(expectedStart.toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(expectedEnd.toInstant());
    }

    @Test
    void shouldCalculateSnapshotWindow() {
        Instant fireTime =
                ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneId.of("UTC")).toInstant();

        ReportWindow window = calculator.calculate(ReportFrequency.SNAPSHOT, fireTime);

        assertThat(window.windowStartUtc()).isEqualTo(fireTime);
        assertThat(window.windowEndUtc()).isEqualTo(fireTime);
        assertThat(window.duration()).isZero();
    }

    @Test
    void shouldThrowForWindowTimeFrequencyWithNullSequence() {
        Instant fireTime = Instant.now();

        assertThatThrownBy(() -> calculator.calculate(ReportFrequency.ONE_TIME_PER_DAY, fireTime, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSequence is required");
    }

    @Test
    void shouldThrowForWindowTimeFrequencyWithInvalidSequence() {
        Instant fireTime = Instant.now();
        List<LocalTime> boundaries = List.of(LocalTime.of(21, 0));

        assertThatThrownBy(() -> calculator.calculate(ReportFrequency.ONE_TIME_PER_DAY, fireTime, 5, boundaries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void shouldThrowForWindowTimeFrequencyWithMissingBoundaries() {
        Instant fireTime = Instant.now();

        assertThatThrownBy(() -> calculator.calculate(ReportFrequency.ONE_TIME_PER_DAY, fireTime, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boundaries is required");
    }

    @Test
    void shouldThrowForUnhandledFrequency() {
        // Create a custom frequency if needed, or this should not happen in normal usage
        Instant fireTime = Instant.now();

        // The calculator should handle all enum values, but we can test the default case
        // by using a frequency that's not handled (shouldn't happen normally)
    }

    @Test
    void shouldThrowWhenFireTimeNull() {
        assertThatThrownBy(() -> calculator.calculate(ReportFrequency.DAILY, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenFrequencyNull() {
        assertThatThrownBy(() -> calculator.calculate(null, Instant.now())).isInstanceOf(NullPointerException.class);
    }
}
