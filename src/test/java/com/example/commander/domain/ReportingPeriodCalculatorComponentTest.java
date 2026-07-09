package com.example.commander.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commander.config.SchedulingProperties;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportingPeriodCalculatorComponentTest {

    @Test
    void shouldHandleDSTTransitionForDailyWindow() {
        // Test DST transition in Europe/Stockholm (March 31, 2024, 02:00 -> 03:00)
        SchedulingProperties properties = new SchedulingProperties();
        properties.setTimezone("Europe/Stockholm");
        ReportingPeriodCalculator calculator = new ReportingPeriodCalculator(properties);

        // Fire at 10:00 on March 31, 2024 (after DST transition)
        ZonedDateTime fireTime = ZonedDateTime.of(2024, 3, 31, 10, 0, 0, 0, ZoneId.of("UTC"));
        ReportWindow window = calculator.calculate(ReportFrequency.DAILY, fireTime.toInstant());

        // windowEnd = the actual scheduled fire time (10:00 UTC = 12:00 CEST post-transition),
        // not midnight — see the guide's calendar-day rule (§2). windowStart is still
        // midnight of the day before, so this window spans 23h on the transition day.
        ZonedDateTime expectedEnd = fireTime.withZoneSameInstant(ZoneId.of("Europe/Stockholm"));
        ZonedDateTime expectedStart = expectedEnd
                .toLocalDate()
                .atStartOfDay(ZoneId.of("Europe/Stockholm"))
                .minusDays(1);

        assertThat(window.windowStartUtc()).isEqualTo(expectedStart.toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(expectedEnd.toInstant());
    }

    @Test
    void shouldHandleDifferentTimezones() {
        SchedulingProperties properties = new SchedulingProperties();
        properties.setTimezone("America/New_York");
        ReportingPeriodCalculator calculator = new ReportingPeriodCalculator(properties);

        ZonedDateTime fireTime = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneId.of("UTC"));
        ReportWindow window = calculator.calculate(ReportFrequency.DAILY, fireTime.toInstant());

        // windowEnd = the actual scheduled fire time, converted to New York time.
        ZonedDateTime expectedEnd = fireTime.withZoneSameInstant(ZoneId.of("America/New_York"));
        ZonedDateTime expectedStart = expectedEnd
                .toLocalDate()
                .atStartOfDay(ZoneId.of("America/New_York"))
                .minusDays(1);

        assertThat(window.windowStartUtc()).isEqualTo(expectedStart.toInstant());
        assertThat(window.windowEndUtc()).isEqualTo(expectedEnd.toInstant());
    }
}
