package com.example.commander.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.commander.domain.ReportFrequency;
import com.example.commander.domain.ReportWindow;
import com.example.commander.domain.ReportingPeriodCalculator;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Trigger;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.dao.CannotAcquireLockException;

/**
 * Unit tests for {@link ReportSchedulingJob}'s orchestration logic: catch-ordering around
 * {@link JobInstanceAlreadyCompleteException}, the deadlock retry loop, and the
 * {@code BatchStatus} check on the returned {@link JobExecution} - none of which the
 * integration tests exercise, since those need live SQL Server and aren't part of the
 * fast/default run.
 */
@ExtendWith(MockitoExtension.class)
class ReportSchedulingJobTest {

    @Mock
    private ReportingPeriodCalculator reportingPeriodCalculator;

    @Mock
    private JobOperator jobOperator;

    @Mock
    private Job reportPipelineJob;

    private ReportSchedulingJob job;

    @BeforeEach
    void setUp() {
        job = new ReportSchedulingJob(reportingPeriodCalculator, jobOperator, reportPipelineJob);
    }

    @Test
    void completesNormallyWhenPipelineJobCompletes() throws Exception {
        when(reportingPeriodCalculator.calculate(eq(ReportFrequency.DAILY), any(), eq(null), eq(null)))
                .thenReturn(
                        new ReportWindow(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z")));
        when(jobOperator.start(eq(reportPipelineJob), any())).thenReturn(completedExecution());

        job.execute(context("CAMT054C", "DAILY"));

        verify(jobOperator, times(1)).start(eq(reportPipelineJob), any());
    }

    @Test
    void duplicateFiringIsSwallowedNotThrown() throws Exception {
        when(reportingPeriodCalculator.calculate(eq(ReportFrequency.DAILY), any(), eq(null), eq(null)))
                .thenReturn(
                        new ReportWindow(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z")));
        when(jobOperator.start(eq(reportPipelineJob), any()))
                .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

        job.execute(context("CAMT054C", "DAILY"));

        verify(jobOperator, times(1)).start(eq(reportPipelineJob), any());
    }

    @Test
    void nonCompletedStatusIsTreatedAsAFailure() throws Exception {
        when(reportingPeriodCalculator.calculate(eq(ReportFrequency.DAILY), any(), eq(null), eq(null)))
                .thenReturn(
                        new ReportWindow(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z")));
        when(jobOperator.start(eq(reportPipelineJob), any())).thenReturn(executionWithStatus(BatchStatus.FAILED));

        assertThatThrownBy(() -> job.execute(context("CAMT054C", "DAILY")))
                .isInstanceOf(JobExecutionException.class)
                .hasMessageContaining("CAMT054C")
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessageContaining("FAILED");
    }

    @Test
    void transientFailureIsRetriedAndEventuallySucceeds() throws Exception {
        when(reportingPeriodCalculator.calculate(eq(ReportFrequency.DAILY), any(), eq(null), eq(null)))
                .thenReturn(
                        new ReportWindow(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z")));
        when(jobOperator.start(eq(reportPipelineJob), any()))
                .thenThrow(new CannotAcquireLockException("deadlocked"))
                .thenReturn(completedExecution());

        job.execute(context("CAMT054C", "DAILY"));

        verify(jobOperator, times(2)).start(eq(reportPipelineJob), any());
    }

    @Test
    void transientFailureExhaustsRetriesAndFailsTheJob() throws Exception {
        when(reportingPeriodCalculator.calculate(eq(ReportFrequency.DAILY), any(), eq(null), eq(null)))
                .thenReturn(
                        new ReportWindow(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z")));
        when(jobOperator.start(eq(reportPipelineJob), any())).thenThrow(new CannotAcquireLockException("deadlocked"));

        assertThatThrownBy(() -> job.execute(context("CAMT054C", "DAILY")))
                .isInstanceOf(JobExecutionException.class)
                .hasCauseInstanceOf(CannotAcquireLockException.class);

        // MAX_LAUNCH_ATTEMPTS is 3 - not part of the public contract, but exhausting retries
        // rather than retrying forever (or just once) is the behavior worth pinning down.
        verify(jobOperator, times(3)).start(eq(reportPipelineJob), any());
    }

    @Test
    void missingReportTypeOrFrequencyFailsFastWithoutCallingJobOperator() {
        JobExecutionContext context = context(null, "DAILY");

        assertThatThrownBy(() -> job.execute(context)).isInstanceOf(JobExecutionException.class);

        verifyNoInteractions(jobOperator);
    }

    private static JobExecutionContext context(String reportType, String reportFrequency) {
        JobDataMap jobDataMap = new JobDataMap();
        if (reportType != null) {
            jobDataMap.put(ReportSchedulingJob.KEY_REPORT_TYPE, reportType);
        }
        if (reportFrequency != null) {
            jobDataMap.put(ReportSchedulingJob.KEY_REPORT_FREQUENCY, reportFrequency);
        }

        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(jobDataMap);

        Trigger trigger = mock(Trigger.class);
        when(trigger.getJobDataMap()).thenReturn(new JobDataMap());

        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(context.getTrigger()).thenReturn(trigger);
        if (reportType != null && reportFrequency != null) {
            when(context.getScheduledFireTime()).thenReturn(Date.from(Instant.parse("2026-07-02T00:00:00Z")));
        }
        return context;
    }

    private static JobExecution completedExecution() {
        return executionWithStatus(BatchStatus.COMPLETED);
    }

    private static JobExecution executionWithStatus(BatchStatus status) {
        JobInstance jobInstance = new JobInstance(1L, "reportPipelineJob");
        JobExecution jobExecution = new JobExecution(1L, jobInstance, new JobParameters());
        jobExecution.setStatus(status);
        return jobExecution;
    }
}
