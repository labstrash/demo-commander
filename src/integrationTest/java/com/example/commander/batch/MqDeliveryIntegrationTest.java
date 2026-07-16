package com.example.commander.batch;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.jms.ConnectionFactory;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;

/**
 * Verifies a message sent through the real writer lands on the correct MQ queue with the
 * expected payload content.
 *
 * <p>Uses the {@code 08-seed-data.sql} happy-path fixture (ConfigId 10000001, report type
 * {@code CAMT054C} / frequency {@code FOUR_TIMES_PER_DAY}) with a window not used by any
 * other integration test, so {@code JobParameters} identity doesn't collide.
 *
 * <p>Requires the local {@code docker-compose} SQL Server and IBM MQ containers (with the
 * full init chain, including {@code 08-seed-data.sql}, {@code 97-schema-batch.sql}, and
 * {@code config.mqsc}) already running.
 */
@SpringBootTest(classes = BatchIntegrationTestConfig.class)
class MqDeliveryIntegrationTest {

    private static final String TARGET_QUEUE = "CAMT.054C.QUEUE";

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private Job reportPipelineJob;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void messageSentThroughRealWriterLandsOnTheConfiguredQueue() throws Exception {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("reportType", "CAMT054C")
                .addString("reportFrequency", "FOUR_TIMES_PER_DAY")
                .addJobParameter("windowStartUtc", Instant.parse("2026-07-16T00:00:00Z"), Instant.class)
                .addJobParameter("windowEndUtc", Instant.parse("2026-07-16T06:00:00Z"), Instant.class)
                .toJobParameters();

        JobExecution jobExecution = jobOperator.start(reportPipelineJob, jobParameters);
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(5000);

        String received = (String) receiver.receiveAndConvert(TARGET_QUEUE);

        assertThat(received).isNotNull();
        assertThat(received).contains("\"configId\":10000001");
        assertThat(received).contains("\"reportType\":\"CAMT054C\"");
    }
}
