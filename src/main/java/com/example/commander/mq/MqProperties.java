package com.example.commander.mq;

import jakarta.validation.constraints.NotEmpty;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MQ delivery configuration properties.
 *
 * <p>Configured via {@code commander.mq} prefix in application properties.
 *
 * <ul>
 *   <li>{@code commander.mq.queues.<reportType>} — target MQ queue name for that report
 *       type. Validated eagerly at startup by {@link MqQueuePropertiesValidator} against every
 *       report type the scheduler can fire.
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "commander.mq")
public class MqProperties {

    @NotEmpty private Map<String, String> queues = new HashMap<>();

    public Map<String, String> getQueues() {
        return queues;
    }

    public void setQueues(Map<String, String> queues) {
        this.queues = queues;
    }

    /**
     * Returns the target queue name for the given report type.
     *
     * @param reportType the report type
     * @return the configured queue name
     * @throws IllegalStateException if no queue is configured for this report type — should
     *     never happen past startup, since {@link MqQueuePropertiesValidator} already checked
     *     every schedulable report type
     */
    public String queueFor(String reportType) {
        String queue = queues.get(reportType);
        if (queue == null) {
            throw new IllegalStateException("No commander.mq.queues entry configured for reportType=" + reportType);
        }
        return queue;
    }
}
