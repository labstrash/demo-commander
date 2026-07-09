package com.example.commander.config;

import com.example.commander.scheduling.ReportJobScheduleBuilder;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Quartz scheduler with report generation jobs and triggers.
 *
 * <p>Builds job and trigger definitions from {@link SchedulingProperties}
 * and registers them with Spring Boot's auto-configured {@code SchedulerFactoryBean}.
 *
 * <p>Uses {@link SchedulerFactoryBeanCustomizer} for idiomatic Spring Boot integration,
 * ensuring jobs are registered before the scheduler starts.
 */
@Configuration
public class QuartzSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(QuartzSchedulerConfig.class);

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

    /**
     * Registers built jobs and triggers with the Quartz scheduler.
     *
     * <p>Sets the JobDetails and Triggers on the scheduler factory bean
     * for automatic registration during scheduler initialization.
     *
     * @param schedule the built job and trigger definitions
     * @return a customizer that registers the schedule
     */
    @Bean
    public SchedulerFactoryBeanCustomizer reportJobScheduleCustomizer(
            ReportJobScheduleBuilder.ReportJobSchedule schedule) {
        return schedulerFactoryBean -> {
            schedulerFactoryBean.setJobDetails(schedule.jobDetails().toArray(new JobDetail[0]));
            schedulerFactoryBean.setTriggers(schedule.triggers().toArray(new Trigger[0]));

            log.info(
                    "Registered {} jobs and {} triggers",
                    schedule.jobDetails().size(),
                    schedule.triggers().size());
        };
    }
}
