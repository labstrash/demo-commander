package com.example.commander.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies message-count chunking (Phase 4 batch pipeline guide, Decision 2): a single
 * bundled config's fan-out messages correctly span/close chunks at the message-count
 * boundary, not the tree boundary.
 *
 * <p>Uses the {@code 08-seed-data.sql} B.3 multi-scope fan-in fixture (ConfigId 10000103,
 * report type {@code CAMT052BT} / frequency {@code ONE_TIME_PER_DAY}), whose single
 * {@code ReportConfig} fans out to exactly 2 unbundled messages from one tree. With
 * {@code commander.batch.commit-interval=1} that tree's fan-out must span (at least) two
 * chunks, verified via Spring Batch's own {@link StepExecution} counters — no custom
 * instrumentation needed.
 *
 * <p>Requires the local {@code docker-compose} SQL Server (with the full init chain,
 * including {@code 08-seed-data.sql} and {@code 97-schema-batch.sql}) already running.
 */
@SpringBootTest(classes = BatchIntegrationTestConfig.class)
@TestPropertySource(properties = "commander.batch.commit-interval=1")
class ChunkCommitBoundaryIntegrationTest {

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private Job reportPipelineJob;

    @Test
    void multiScopeFanInConfigSpansMultipleChunks() throws Exception {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("reportType", "CAMT052BT")
                .addString("reportFrequency", "ONE_TIME_PER_DAY")
                .addJobParameter("windowStartUtc", Instant.parse("2026-07-01T00:00:00Z"), Instant.class)
                .addJobParameter("windowEndUtc", Instant.parse("2026-07-02T00:00:00Z"), Instant.class)
                .toJobParameters();

        JobExecution jobExecution = jobOperator.start(reportPipelineJob, jobParameters);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution stepExecution =
                jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(2);
        assertThat(stepExecution.getWriteCount()).isEqualTo(2);
        assertThat(stepExecution.getCommitCount())
                .describedAs("commit-interval=1 with 2 messages from one tree must span multiple chunks")
                .isGreaterThanOrEqualTo(2);
    }
}
