package com.example.commander.config;

import com.example.commander.audit.AuditRetentionProperties;
import com.example.commander.mq.MqResilienceProperties;
import com.example.commander.scheduling.AuditRetentionJob;
import com.example.commander.scheduling.DeadLetterRecoveryJob;
import com.example.commander.scheduling.ReportJobScheduleBuilder;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Quartz scheduler with report generation jobs/triggers, the dead-letter
 * recovery job/trigger, and the audit retention job/trigger.
 *
 * <p>Builds job and trigger definitions from {@link SchedulingProperties} (report jobs),
 * {@link MqResilienceProperties} (recovery job cadence), and {@link AuditRetentionProperties}
 * (retention job cadence), and registers all of them with Spring Boot's auto-configured
 * {@code SchedulerFactoryBean} — deliberately through a single {@link
 * SchedulerFactoryBeanCustomizer} bean: {@code setJobDetails}/{@code setTriggers} are plain
 * replace-the-array setters, so a second customizer calling them again would silently
 * discard whatever the first one registered rather than adding to it.
 *
 * <p>The recovery and retention jobs/triggers live in their own Quartz group ({@link
 * #RECOVERY_GROUP}), separate from {@link ReportJobScheduleBuilder}'s {@code
 * camt-scheduling} group — {@code OrphanedTriggerCleanupRunner} only reconciles the
 * report-scheduling group, so keeping these out of it means that cleanup logic never has to
 * know they exist.
 */
@Configuration
public class QuartzSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(QuartzSchedulerConfig.class);

    private static final String RECOVERY_GROUP = "camt-mq-recovery";
    private static final String RECOVERY_JOB_NAME = "deadLetterRecoveryJob";
    private static final String RECOVERY_TRIGGER_NAME = "deadLetterRecoveryJob-trigger";
    private static final String RETENTION_JOB_NAME = "auditRetentionJob";
    private static final String RETENTION_TRIGGER_NAME = "auditRetentionJob-trigger";

    /**
     * Builds all report job definitions and triggers from configuration.
     *
     * @param properties the scheduling configuration
     * @return container with all JobDetails and Triggers
     */
    @Bean
    public ReportJobScheduleBuilder.ReportJobSchedule reportJobSchedule(SchedulingProperties properties) {
        return ReportJobScheduleBuilder.build(properties);
    }

    @Bean
    public JobDetail deadLetterRecoveryJobDetail() {
        return JobBuilder.newJob(DeadLetterRecoveryJob.class)
                .withIdentity(RECOVERY_JOB_NAME, RECOVERY_GROUP)
                .storeDurably(true)
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger deadLetterRecoveryTrigger(
            JobDetail deadLetterRecoveryJobDetail,
            MqResilienceProperties mqResilienceProperties,
            SchedulingProperties schedulingProperties) {
        return TriggerBuilder.newTrigger()
                .forJob(deadLetterRecoveryJobDetail)
                .withIdentity(RECOVERY_TRIGGER_NAME, RECOVERY_GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(mqResilienceProperties.getRecoveryJobCron())
                        .inTimeZone(TimeZone.getTimeZone(ZoneId.of(schedulingProperties.getTimezone())))
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    @Bean
    public JobDetail auditRetentionJobDetail() {
        return JobBuilder.newJob(AuditRetentionJob.class)
                .withIdentity(RETENTION_JOB_NAME, RECOVERY_GROUP)
                .storeDurably(true)
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger auditRetentionTrigger(
            JobDetail auditRetentionJobDetail,
            AuditRetentionProperties auditRetentionProperties,
            SchedulingProperties schedulingProperties) {
        return TriggerBuilder.newTrigger()
                .forJob(auditRetentionJobDetail)
                .withIdentity(RETENTION_TRIGGER_NAME, RECOVERY_GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(auditRetentionProperties.getCron())
                        .inTimeZone(TimeZone.getTimeZone(ZoneId.of(schedulingProperties.getTimezone())))
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }

    /**
     * Registers every built job and trigger — report schedules, dead-letter recovery, and
     * audit retention — with the Quartz scheduler in one pass.
     *
     * @param schedule the built report job/trigger definitions
     * @param deadLetterRecoveryJobDetail the recovery job's definition
     * @param deadLetterRecoveryTrigger the recovery job's trigger
     * @param auditRetentionJobDetail the retention job's definition
     * @param auditRetentionTrigger the retention job's trigger
     * @return a customizer that registers the combined schedule
     */
    @Bean
    public SchedulerFactoryBeanCustomizer reportJobScheduleCustomizer(
            ReportJobScheduleBuilder.ReportJobSchedule schedule,
            JobDetail deadLetterRecoveryJobDetail,
            Trigger deadLetterRecoveryTrigger,
            JobDetail auditRetentionJobDetail,
            Trigger auditRetentionTrigger) {
        return schedulerFactoryBean -> {
            List<JobDetail> jobDetails = new ArrayList<>(schedule.jobDetails());
            jobDetails.add(deadLetterRecoveryJobDetail);
            jobDetails.add(auditRetentionJobDetail);

            List<Trigger> triggers = new ArrayList<>(schedule.triggers());
            triggers.add(deadLetterRecoveryTrigger);
            triggers.add(auditRetentionTrigger);

            schedulerFactoryBean.setJobDetails(jobDetails.toArray(new JobDetail[0]));
            schedulerFactoryBean.setTriggers(triggers.toArray(new Trigger[0]));

            log.info("Registered {} jobs and {} triggers", jobDetails.size(), triggers.size());
        };
    }
}
