package com.example.commander.config;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchedulingPropertiesTest {

    private SchedulingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SchedulingProperties();
    }

    @Test
    void shouldHaveDefaultTimezone() {
        assertThat(properties.getTimezone()).isEqualTo("Europe/Stockholm");
    }

    @Test
    void shouldSetTimezone() {
        properties.setTimezone("America/New_York");
        assertThat(properties.getTimezone()).isEqualTo("America/New_York");
    }

    @Test
    void shouldValidateScheduleWithCron() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("EVERY_30_MIN");
        schedule.setCron("0 */30 * ? * *");
        schedule.setWindowMinutes(30);
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));
        properties.validate();

        assertThat(properties.getSchedules()).hasSize(1);
    }

    @Test
    void shouldValidateScheduleWithBoundaries() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("FOUR_TIMES_PER_DAY");
        schedule.setBoundaries("09:00,13:00,18:00,21:00");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));
        properties.validate();

        assertThat(properties.getSchedules()).hasSize(1);
    }

    @Test
    void shouldThrowWhenScheduleMissingCronAndBoundaries() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("EVERY_30_MIN");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must have exactly one of 'cron' or 'boundaries'");
    }

    @Test
    void shouldThrowWhenScheduleHasBothCronAndBoundaries() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("EVERY_30_MIN");
        schedule.setCron("0 */30 * ? * *");
        schedule.setBoundaries("09:00,13:00");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must have exactly one of 'cron' or 'boundaries'");
    }

    @Test
    void shouldThrowWhenScheduleHasNoReportTypes() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("EVERY_30_MIN");
        schedule.setCron("0 */30 * ? * *");
        schedule.setWindowMinutes(30);

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must have at least one report-type");
    }

    @Test
    void shouldThrowWhenCronScheduleMissingWindowMinutes() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("EVERY_30_MIN");
        schedule.setCron("0 */30 * ? * *");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a positive 'window-minutes'");
    }

    @Test
    void shouldThrowWhenWindowMinutesPresentWithBoundaries() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("FOUR_TIMES_PER_DAY");
        schedule.setBoundaries("09:00,13:00,18:00,21:00");
        schedule.setWindowMinutes(30);
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not set 'window-minutes'");
    }

    @Test
    void shouldThrowWhenBoundariesNotAscending() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("FOUR_TIMES_PER_DAY");
        schedule.setBoundaries("13:00,09:00,18:00,21:00");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boundaries must be strictly ascending");
    }

    @Test
    void shouldThrowWhenBoundaryIsMidnight() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("ONE_TIME_PER_DAY");
        schedule.setBoundaries("00:00");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boundaries cannot include 00:00");
    }

    @Test
    void shouldThrowWhenDuplicateReportTypeFrequencyPair() {
        SchedulingProperties.Schedule schedule1 = new SchedulingProperties.Schedule();
        schedule1.setFrequency("DAILY");
        schedule1.setCron("0 0 12 * * ?");
        schedule1.setReportTypes(List.of("REPORT_TYPE_1"));

        SchedulingProperties.Schedule schedule2 = new SchedulingProperties.Schedule();
        schedule2.setFrequency("DAILY");
        schedule2.setCron("0 0 12 * * ?");
        schedule2.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule1, schedule2));

        assertThatThrownBy(() -> properties.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate (report-type, frequency) pair found");
    }

    @Test
    void shouldAllowSameReportTypeWithDifferentFrequencies() {
        SchedulingProperties.Schedule schedule1 = new SchedulingProperties.Schedule();
        schedule1.setFrequency("DAILY");
        schedule1.setCron("0 0 12 * * ?");
        schedule1.setReportTypes(List.of("REPORT_TYPE_1"));

        SchedulingProperties.Schedule schedule2 = new SchedulingProperties.Schedule();
        schedule2.setFrequency("EVERY_30_MIN");
        schedule2.setCron("0 */30 * ? * *");
        schedule2.setWindowMinutes(30);
        schedule2.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule1, schedule2));
        properties.validate();

        assertThat(properties.getSchedules()).hasSize(2);
    }

    @Test
    void scheduleHelpersShouldWork() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();

        assertThat(schedule.hasCronSchedule()).isFalse();
        assertThat(schedule.hasBoundarySchedule()).isFalse();
        assertThat(schedule.isValid()).isFalse();

        schedule.setCron("0 */30 * ? * *");
        assertThat(schedule.hasCronSchedule()).isTrue();
        assertThat(schedule.isValid()).isTrue();

        schedule.setBoundaries("09:00");
        assertThat(schedule.isValid()).isFalse();
    }

    @Test
    void shouldDetectWindowTimeFrequency() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setBoundaries("09:00,13:00,18:00,21:00");

        schedule.setFrequency("ONE_TIME_PER_DAY");
        assertThat(schedule.isWindowTimeFrequency()).isTrue();

        schedule.setFrequency("FOUR_TIMES_PER_DAY");
        assertThat(schedule.isWindowTimeFrequency()).isTrue();

        schedule.setFrequency("EIGHT_TIMES_PER_DAY");
        assertThat(schedule.isWindowTimeFrequency()).isTrue();

        schedule.setFrequency("DAILY");
        assertThat(schedule.isWindowTimeFrequency()).isFalse();

        schedule.setFrequency("EVERY_30_MIN");
        assertThat(schedule.isWindowTimeFrequency()).isFalse();
    }
}
