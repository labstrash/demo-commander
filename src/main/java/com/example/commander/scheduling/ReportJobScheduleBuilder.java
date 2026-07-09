package com.example.commander.scheduling;

import com.example.commander.config.SchedulingProperties;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds Quartz jobs and triggers from {@link SchedulingProperties}.
 *
 * <p>Translates declarative schedule definitions into executable Quartz artifacts:
 * <ul>
 *   <li><b>Pattern A (cron):</b> Single trigger with cron expression</li>
 *   <li><b>Pattern B (boundaries):</b> Multiple triggers, one per boundary time</li>
 * </ul>
 *
 * <p>Each schedule generates one {@link JobDetail} per report type, with associated
 * triggers for each execution time. Job data carries runtime parameters like report type,
 * frequency, and window boundaries.
 */
public final class ReportJobScheduleBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReportJobScheduleBuilder.class);

    private static final String JOB_GROUP = "camt-scheduling";
    static final String TRIGGER_GROUP = "camt-scheduling";
    private static final String TRIGGER_NAME_SUFFIX = "-trigger";
    private static final String WINDOW_TRIGGER_TEMPLATE = "-window-%02d";

    /** Job data key for the report type to generate. */
    public static final String KEY_REPORT_TYPE = "reportType";

    /** Job data key for the frequency label. */
    public static final String KEY_REPORT_FREQUENCY = "reportFrequency";

    /** Job data key for window interval in minutes (Pattern A, non-DAILY). */
    public static final String KEY_WINDOW_INTERVAL_MINUTES = "windowIntervalMinutes";

    /** Job data key for boundary times string (Pattern B). */
    public static final String KEY_BOUNDARIES = "boundaries";

    /** Job data key for window sequence number (0-based). */
    public static final String KEY_WINDOW_SEQUENCE = "windowSequence";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private ReportJobScheduleBuilder() {}

    /**
     * Builds job details and triggers from configuration properties.
     *
     * @param properties validated scheduling configuration
     * @return container with all generated JobDetails and Triggers
     * @throws NullPointerException if properties is null
     */
    public static ReportJobSchedule build(SchedulingProperties properties) {
        Objects.requireNonNull(properties, "properties");

        properties.validate();

        List<JobDetail> jobDetails = new ArrayList<>();
        List<Trigger> triggers = new ArrayList<>();

        for (SchedulingProperties.Schedule schedule : properties.getSchedules()) {
            for (String reportType : schedule.getReportTypes()) {
                String identity = buildJobName(reportType, schedule.getFrequency());

                JobDetail jobDetail = buildJobDetail(identity, reportType, schedule);
                jobDetails.add(jobDetail);

                if (schedule.hasBoundarySchedule()) {
                    triggers.addAll(buildWindowTriggers(jobDetail, identity, schedule, properties.getTimezone()));
                } else {
                    triggers.add(buildCronTrigger(jobDetail, identity, schedule, properties.getTimezone()));
                }
            }
        }

        log.info("Built {} job details and {} triggers", jobDetails.size(), triggers.size());
        return new ReportJobSchedule(jobDetails, triggers);
    }

    private static JobDetail buildJobDetail(
            String identity, String reportType, SchedulingProperties.Schedule schedule) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(KEY_REPORT_TYPE, reportType);
        jobDataMap.put(KEY_REPORT_FREQUENCY, schedule.getFrequency());

        if (schedule.hasCronSchedule() && !schedule.isDailyFrequency()) {
            jobDataMap.put(KEY_WINDOW_INTERVAL_MINUTES, String.valueOf(schedule.getWindowMinutes()));
        } else if (schedule.hasBoundarySchedule()) {
            jobDataMap.put(KEY_BOUNDARIES, schedule.getBoundaries());
        }

        return JobBuilder.newJob(ReportSchedulingJob.class)
                .withIdentity(identity, JOB_GROUP)
                .setJobData(jobDataMap)
                .storeDurably(true)
                .requestRecovery(true)
                .build();
    }

    private static Trigger buildCronTrigger(
            JobDetail jobDetail, String identity, SchedulingProperties.Schedule schedule, String timezone) {
        return TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(identity + TRIGGER_NAME_SUFFIX, TRIGGER_GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(schedule.getCron())
                        .inTimeZone(TimeZone.getTimeZone(ZoneId.of(timezone)))
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    private static List<Trigger> buildWindowTriggers(
            JobDetail jobDetail, String identity, SchedulingProperties.Schedule schedule, String timezone) {
        List<LocalTime> boundaries = parseBoundaries(schedule.getBoundaries());
        List<Trigger> triggers = new ArrayList<>(boundaries.size());

        for (int sequence = 0; sequence < boundaries.size(); sequence++) {
            LocalTime boundary = boundaries.get(sequence);
            String triggerName = identity + String.format(WINDOW_TRIGGER_TEMPLATE, sequence);
            String cronExpression =
                    String.format("0 %d %d ? * %s", boundary.getMinute(), boundary.getHour(), schedule.getDaysOfWeek());

            JobDataMap triggerDataMap = new JobDataMap();
            triggerDataMap.put(KEY_WINDOW_SEQUENCE, String.valueOf(sequence));

            triggers.add(TriggerBuilder.newTrigger()
                    .forJob(jobDetail)
                    .withIdentity(triggerName, TRIGGER_GROUP)
                    .usingJobData(triggerDataMap)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                            .inTimeZone(TimeZone.getTimeZone(ZoneId.of(timezone)))
                            .withMisfireHandlingInstructionFireAndProceed())
                    .build());
        }

        return triggers;
    }

    /**
     * Parses a comma-separated list of HH:mm boundary times.
     *
     * @param boundaries comma-separated time strings (e.g., "09:00,13:00,17:00")
     * @return list of parsed LocalTime objects, or empty list if input is null/blank
     */
    static List<LocalTime> parseBoundaries(String boundaries) {
        if (boundaries == null || boundaries.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(boundaries.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> LocalTime.parse(s, TIME_FORMATTER))
                .collect(Collectors.toList());
    }

    private static String buildJobName(String reportType, String frequency) {
        return reportType + "-" + frequency;
    }

    /**
     * Container for built Quartz artifacts.
     *
     * @param jobDetails all generated JobDetail instances
     * @param triggers all generated Trigger instances
     */
    public record ReportJobSchedule(List<JobDetail> jobDetails, List<Trigger> triggers) {
        public ReportJobSchedule {
            Objects.requireNonNull(jobDetails, "jobDetails");
            Objects.requireNonNull(triggers, "triggers");
        }
    }
}
