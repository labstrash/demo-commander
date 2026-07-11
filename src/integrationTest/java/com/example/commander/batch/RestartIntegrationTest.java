package com.example.commander.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commander.batch.processor.NoOpReportMessageProcessor;
import com.example.commander.batch.reader.ReportPipelineItemReader;
import com.example.commander.domain.message.OutboundReportMessage;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Verifies a job interrupted mid-run can be relaunched (Spring Batch's restart semantics,
 * against the real {@code BATCH_*} schema) rather than being stuck FAILED forever.
 *
 * <p>Uses a dedicated {@code restartTestJob}/{@code restartTestStep} pair — not the
 * production {@code reportPipelineJob} bean — wired with a real {@link ItemWriter} that
 * fails on its first invocation and succeeds after, simulating a crash mid-chunk.
 * {@code commander.batch.commit-interval=1} plus the {@code 08-seed-data.sql} B.3
 * multi-scope fan-in fixture (2 messages from one tree) means the first chunk (message 1)
 * fails and rolls back before ever being committed, and relaunching with identical
 * {@code JobParameters} must resume and complete, writing both messages.
 *
 * <p>This proves the end-to-end restart wiring (FAILED → relaunch → COMPLETED, checkpoint
 * round-tripped through the real {@code BATCH_STEP_EXECUTION_CONTEXT} table) works against
 * real infrastructure. Skip-already-drained-tree checkpoint logic itself — the part that
 * decides which trees are safe to skip on restart — is exercised in isolation, against a
 * multi-tree page a single seeded config can't provide, by
 * {@code ReportPipelineItemReaderTest#restartResumesFromPersistedCheckpointWithoutReReadingDrainedPages}.
 *
 * <p>Requires the local {@code docker-compose} SQL Server (with the full init chain,
 * including {@code 08-seed-data.sql} and {@code 97-schema-batch.sql}) already running.
 */
@SpringBootTest(classes = {BatchIntegrationTestConfig.class, RestartIntegrationTest.RestartTestStepConfig.class})
class RestartIntegrationTest {

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    @Qualifier("restartTestJob") private Job restartTestJob;

    @Test
    void relaunchAfterMidChunkFailureResumesAndCompletes() throws Exception {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("reportType", "CAMT052BT")
                .addString("reportFrequency", "ONE_TIME_PER_DAY")
                .addJobParameter("windowStartUtc", Instant.parse("2026-09-01T00:00:00Z"), Instant.class)
                .addJobParameter("windowEndUtc", Instant.parse("2026-09-02T00:00:00Z"), Instant.class)
                .toJobParameters();

        JobExecution failedExecution = jobOperator.start(restartTestJob, jobParameters);
        assertThat(failedExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedExecution.getStepExecutions().iterator().next().getWriteCount())
                .describedAs("the failing first chunk must not have committed anything")
                .isZero();

        JobExecution restartedExecution = jobOperator.start(restartTestJob, jobParameters);
        assertThat(restartedExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restartedExecution.getStepExecutions().iterator().next().getWriteCount())
                .isEqualTo(2);
    }

    /** A writer that fails its first call, then behaves normally on every call after. */
    static class FlakyItemWriter implements ItemWriter<OutboundReportMessage> {

        private final AtomicBoolean hasFailedOnce = new AtomicBoolean(false);

        @Override
        public void write(Chunk<? extends OutboundReportMessage> chunk) {
            if (hasFailedOnce.compareAndSet(false, true)) {
                throw new RuntimeException("Simulated failure on first chunk");
            }
        }
    }

    @TestConfiguration
    static class RestartTestStepConfig {

        @Bean
        FlakyItemWriter flakyItemWriter() {
            return new FlakyItemWriter();
        }

        @Bean
        Step restartTestStep(
                JobRepository jobRepository,
                PlatformTransactionManager transactionManager,
                ReportPipelineItemReader reader,
                NoOpReportMessageProcessor processor,
                FlakyItemWriter flakyItemWriter) {
            return new StepBuilder("restartTestStep", jobRepository)
                    .<OutboundReportMessage, OutboundReportMessage>chunk(1)
                    .transactionManager(transactionManager)
                    .reader(reader)
                    .processor(processor)
                    .writer(flakyItemWriter)
                    .build();
        }

        @Bean
        Job restartTestJob(JobRepository jobRepository, Step restartTestStep) {
            return new JobBuilder("restartTestJob", jobRepository)
                    .start(restartTestStep)
                    .build();
        }
    }
}
