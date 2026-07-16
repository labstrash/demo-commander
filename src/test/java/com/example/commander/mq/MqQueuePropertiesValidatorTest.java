package com.example.commander.mq;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.example.commander.config.SchedulingProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class MqQueuePropertiesValidatorTest {

    @Test
    void failsStartupWhenASchedulableReportTypeHasNoQueueMapping() {
        SchedulingProperties scheduling = schedulingWithReportTypes("CAMT054C", "CAMT052B");
        MqProperties mqProperties = new MqProperties();
        mqProperties.setQueues(Map.of("CAMT054C", "CAMT.054C.QUEUE"));

        MqQueuePropertiesValidator validator = new MqQueuePropertiesValidator(mqProperties, scheduling);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAMT052B");
    }

    @Test
    void passesWhenEveryScheduledReportTypeHasAQueueMapping() {
        SchedulingProperties scheduling = schedulingWithReportTypes("CAMT054C", "CAMT052B");
        MqProperties mqProperties = new MqProperties();
        mqProperties.setQueues(Map.of("CAMT054C", "CAMT.054C.QUEUE", "CAMT052B", "CAMT.052B.QUEUE"));

        MqQueuePropertiesValidator validator = new MqQueuePropertiesValidator(mqProperties, scheduling);

        assertThatCode(() -> validator.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }

    private static SchedulingProperties schedulingWithReportTypes(String... reportTypes) {
        SchedulingProperties.Schedule schedule = new SchedulingProperties.Schedule();
        schedule.setFrequency("DAILY");
        schedule.setCron("0 0 6 ? * TUE-SAT");
        schedule.setReportTypes(List.of(reportTypes));

        SchedulingProperties properties = new SchedulingProperties();
        properties.setSchedules(List.of(schedule));
        return properties;
    }
}
