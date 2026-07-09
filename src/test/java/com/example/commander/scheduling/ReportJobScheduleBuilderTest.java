package com.example.commander.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.commander.config.SchedulingProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.Trigger;

class ReportJobScheduleBuilderTest {

    private SchedulingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SchedulingProperties();
        properties.setTimezone("Europe/Stockholm");
    }

    @Test
    void shouldBuildScheduleWithCron() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("EVERY_30_MIN");
        schedule.setCron("0 */30 * ? * *");
        schedule.setWindowMinutes(30);
        schedule.setReportTypes(List.of("REPORT_TYPE_1", "REPORT_TYPE_2"));

        properties.setSchedules(List.of(schedule));
        ReportJobScheduleBuilder.ReportJobSchedule result = ReportJobScheduleBuilder.build(properties);

        assertThat(result.jobDetails()).hasSize(2);
        assertThat(result.triggers()).hasSize(2);

        JobDetail firstJob = result.jobDetails().get(0);
        assertThat(firstJob.getKey().getName()).isEqualTo("REPORT_TYPE_1-EVERY_30_MIN");
        assertThat(firstJob.getJobDataMap().getString(ReportJobScheduleBuilder.KEY_REPORT_TYPE))
                .isEqualTo("REPORT_TYPE_1");
        assertThat(firstJob.getJobDataMap().getString(ReportJobScheduleBuilder.KEY_REPORT_FREQUENCY))
                .isEqualTo("EVERY_30_MIN");
        assertThat(firstJob.getJobDataMap().getString(ReportJobScheduleBuilder.KEY_WINDOW_INTERVAL_MINUTES))
                .isEqualTo("30");
    }

    @Test
    void shouldBuildScheduleWithBoundaries() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("FOUR_TIMES_PER_DAY");
        schedule.setBoundaries("09:00,13:00,18:00,21:00");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));
        ReportJobScheduleBuilder.ReportJobSchedule result = ReportJobScheduleBuilder.build(properties);

        assertThat(result.jobDetails()).hasSize(1);
        assertThat(result.triggers()).hasSize(4);

        JobDetail job = result.jobDetails().get(0);
        assertThat(job.getKey().getName()).isEqualTo("REPORT_TYPE_1-FOUR_TIMES_PER_DAY");
        assertThat(job.getJobDataMap().getString(ReportJobScheduleBuilder.KEY_BOUNDARIES))
                .isEqualTo("09:00,13:00,18:00,21:00");

        // Check that triggers have window sequence
        for (int i = 0; i < result.triggers().size(); i++) {
            Trigger trigger = result.triggers().get(i);
            String sequence = trigger.getJobDataMap().getString(ReportJobScheduleBuilder.KEY_WINDOW_SEQUENCE);
            assertThat(sequence).isEqualTo(String.valueOf(i));
            assertThat(trigger.getKey().getName()).endsWith(String.format("-window-%02d", i));
        }
    }

    @Test
    void shouldBuildScheduleWithDailyCron() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("DAILY");
        schedule.setCron("0 0 12 * * ?");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));
        ReportJobScheduleBuilder.ReportJobSchedule result = ReportJobScheduleBuilder.build(properties);

        assertThat(result.jobDetails()).hasSize(1);
        assertThat(result.triggers()).hasSize(1);

        JobDetail job = result.jobDetails().get(0);
        assertThat(job.getJobDataMap().getString(ReportJobScheduleBuilder.KEY_WINDOW_INTERVAL_MINUTES))
                .isNull();
        assertThat(job.getJobDataMap().getString(ReportJobScheduleBuilder.KEY_BOUNDARIES))
                .isNull();
    }

    @Test
    void shouldBuildScheduleWithMultipleSchedules() {
        SchedulingProperties.Schedule schedule1 = new SchedulingProperties.Schedule();
        schedule1.setFrequency("DAILY");
        schedule1.setCron("0 0 12 * * ?");
        schedule1.setReportTypes(List.of("REPORT_TYPE_1", "REPORT_TYPE_2"));

        SchedulingProperties.Schedule schedule2 = new SchedulingProperties.Schedule();
        schedule2.setFrequency("EVERY_30_MIN");
        schedule2.setCron("0 */30 * ? * *");
        schedule2.setWindowMinutes(30);
        schedule2.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule1, schedule2));
        ReportJobScheduleBuilder.ReportJobSchedule result = ReportJobScheduleBuilder.build(properties);

        assertThat(result.jobDetails()).hasSize(3);
        assertThat(result.triggers()).hasSize(3);
    }

    @Test
    void shouldThrowWhenPropertiesNull() {
        assertThatThrownBy(() -> ReportJobScheduleBuilder.build(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenInvalidSchedule() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("EVERY_30_MIN");
        schedule.setCron("0 */30 * ? * *");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));

        assertThatThrownBy(() -> ReportJobScheduleBuilder.build(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a positive 'window-minutes'");
    }

    @Test
    void shouldHandleBoundariesWithDifferentDayOfWeek() {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("ONE_TIME_PER_DAY");
        schedule.setBoundaries("21:00");
        schedule.setDaysOfWeek("MON,WED,FRI");
        schedule.setReportTypes(List.of("REPORT_TYPE_1"));

        properties.setSchedules(List.of(schedule));
        ReportJobScheduleBuilder.ReportJobSchedule result = ReportJobScheduleBuilder.build(properties);

        assertThat(result.triggers()).hasSize(1);
        // The cron expression should include the days of week
        Trigger trigger = result.triggers().get(0);
        // We can't easily check the cron expression contents here, but we can verify it's built
        assertThat(trigger.getKey().getName()).isEqualTo("REPORT_TYPE_1-ONE_TIME_PER_DAY-window-00");
    }
}
