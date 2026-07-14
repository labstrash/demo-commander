package com.example.commander.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the double-fire guard (batch pipeline implementation guide, Decision 5 / §7 /
 * §10): launching the same {@code JobParameters} twice must not create a second
 * {@code JobInstance}/{@code JobExecution}, and the second {@code JobOperator.start()} call
 * must throw {@link JobInstanceAlreadyCompleteException} rather than silently no-op or
 * corrupt state.
 *
 * <p>Treated as load-bearing per the guide's §7 operational caution: this exercises the
 * exact upstream subsystem (Spring Batch's {@code JobInstance} identity/signature handling)
 * flagged as new and worth verifying directly rather than assumed correct by design.
 *
 * <p>Uses the {@code 08-seed-data.sql} happy-path fixture (ConfigId 10000001, report type
 * {@code CAMT054C} / frequency {@code FOUR_TIMES_PER_DAY}) with a fixed window, so the same
 * {@code JobParameters} are reproducible across both launches in this test.
 *
 * <p>Requires the local {@code docker-compose} SQL Server (with the full init chain,
 * including {@code 08-seed-data.sql} and {@code 97-schema-batch.sql}) already running.
 */
@SpringBootTest(classes = BatchIntegrationTestConfig.class)
class DoubleFireGuardIntegrationTest {

    private static final String JOB_NAME = "reportPipelineJob";

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private Job reportPipelineJob;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void secondLaunchWithIdenticalParametersIsRejectedNotDuplicated() throws Exception {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("reportType", "CAMT054C")
                .addString("reportFrequency", "FOUR_TIMES_PER_DAY")
                .addJobParameter("windowStartUtc", Instant.parse("2026-08-01T00:00:00Z"), Instant.class)
                .addJobParameter("windowEndUtc", Instant.parse("2026-08-01T06:00:00Z"), Instant.class)
                .toJobParameters();

        int instanceCountBefore = jobRepository.findJobInstances(JOB_NAME).size();

        JobExecution firstExecution = jobOperator.start(reportPipelineJob, jobParameters);
        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(jobRepository.findJobInstances(JOB_NAME).size()).isEqualTo(instanceCountBefore + 1);

        assertThatThrownBy(() -> jobOperator.start(reportPipelineJob, jobParameters))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);

        assertThat(jobRepository.findJobInstances(JOB_NAME).size())
                .describedAs("the rejected duplicate launch must not create a second JobInstance")
                .isEqualTo(instanceCountBefore + 1);
    }
}
