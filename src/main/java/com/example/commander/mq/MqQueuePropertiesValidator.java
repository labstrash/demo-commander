package com.example.commander.mq;

import com.example.commander.config.SchedulingProperties;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails startup if any report type the scheduler can fire lacks a configured MQ queue.
 *
 * <p>Better to fail at boot than discover a missing {@code commander.mq.queues} mapping on
 * the first send.
 */
@Component
public class MqQueuePropertiesValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MqQueuePropertiesValidator.class);

    private final MqProperties mqProperties;
    private final SchedulingProperties schedulingProperties;

    public MqQueuePropertiesValidator(MqProperties mqProperties, SchedulingProperties schedulingProperties) {
        this.mqProperties = mqProperties;
        this.schedulingProperties = schedulingProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> schedulableReportTypes = new TreeSet<>();
        for (SchedulingProperties.Schedule schedule : schedulingProperties.getSchedules()) {
            schedulableReportTypes.addAll(schedule.getReportTypes());
        }

        Set<String> missing = new TreeSet<>();
        for (String reportType : schedulableReportTypes) {
            if (!mqProperties.getQueues().containsKey(reportType)) {
                missing.add(reportType);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing commander.mq.queues entry for schedulable report type(s): " + missing);
        }

        log.info(
                "MQ queue mapping validated: {} schedulable report type(s), all mapped", schedulableReportTypes.size());
    }
}
