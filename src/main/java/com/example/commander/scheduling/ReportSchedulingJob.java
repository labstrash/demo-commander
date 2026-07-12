package com.example.commander.scheduling;

import com.example.commander.domain.ReportFrequency;
import com.example.commander.domain.ReportWindow;
import com.example.commander.domain.ReportingPeriodCalculator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * Quartz job that executes report generation for a specific report type and frequency.
 *
 * <p>Retrieves scheduling parameters from the job and trigger data maps, calculates the
 * reporting window using {@link ReportingPeriodCalculator}, and launches the Spring Batch
 * {@code reportPipelineJob} synchronously via {@link JobOperator#start}, blocking until
 * that job finishes. Synchronous (not async) so Quartz's own misfire/next-fire-time
 * bookkeeping stays tied to real completion, and so {@code @DisallowConcurrentExecution}
 * keeps meaning what it says.
 *
 * <p>Uses {@code useProperties=true} in Quartz configuration, so all job data keys
 * must be strings.
 */
@Component
@DisallowConcurrentExecution
public class ReportSchedulingJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ReportSchedulingJob.class);

    /** Job data key for the report type to generate. */
    public static final String KEY_REPORT_TYPE = "reportType";

    /** Job data key for the frequency label. */
    public static final String KEY_REPORT_FREQUENCY = "reportFrequency";

    /** Job data key for window interval in minutes (Pattern A, non-DAILY). */
    public static final String KEY_WINDOW_INTERVAL_MINUTES = "windowIntervalMinutes";

    /** Job data key for boundary times string (Pattern B). */
    public static final String KEY_BOUNDARIES = "boundaries";

    /** Job data key for window sequence number (0-based). */
    public static final String KEY_WINDOW_SEQUENCE = "windowSequence";

    // BATCH_JOB_INSTANCE is a shared table across every report type/frequency combination,
    // so concurrent firings (several triggers landing in the same second - routine under
    // the local dev profile's collapsed-to-every-minute schedules, and possible in
    // production too) can hit a genuine SQL Server deadlock on the INSERT. SQL Server's own
    // error text says it plainly: "chosen as the deadlock victim... Rerun the transaction."
    // This only covers deadlocks during JobInstance/JobExecution creation, before any step
    // runs - that's the sole point where a transient failure is thrown directly out of
    // start() rather than being absorbed into a normally-returned FAILED JobExecution (a
    // mid-step failure never reaches this catch; it comes back via getStatus() == FAILED,
    // handled by the status check below instead). Safe to retry from scratch: SQL Server
    // rolls back the deadlock victim's transaction, so nothing was committed yet.
    private static final int MAX_LAUNCH_ATTEMPTS = 3;
    private static final Duration LAUNCH_RETRY_BACKOFF = Duration.ofMillis(250);

    private final ReportingPeriodCalculator reportingPeriodCalculator;
    private final JobOperator jobOperator;

    // Fully qualified: this class already implements org.quartz.Job, so the Spring Batch
    // Job type (also just named "Job") can't be imported unqualified without an
    // ambiguous-import compile error.
    private final org.springframework.batch.core.job.Job reportPipelineJob;

    public ReportSchedulingJob(
            ReportingPeriodCalculator reportingPeriodCalculator,
            JobOperator jobOperator,
            org.springframework.batch.core.job.Job reportPipelineJob) {
        this.reportingPeriodCalculator = reportingPeriodCalculator;
        this.jobOperator = jobOperator;
        this.reportPipelineJob = reportPipelineJob;
    }

    /**
     * Executes the report scheduling job.
     *
     * <p>Retrieves job parameters from the Quartz context, calculates the reporting
     * window, and delegates the actual report generation to downstream components.
     *
     * @param context Quartz job execution context containing job and trigger data
     * @throws JobExecutionException if required parameters are missing or window
     *         calculation fails
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap jobData = context.getJobDetail().getJobDataMap();
        JobDataMap triggerData = context.getTrigger().getJobDataMap();

        String reportType = jobData.getString(KEY_REPORT_TYPE);
        String frequencyStr = jobData.getString(KEY_REPORT_FREQUENCY);

        if (reportType == null || frequencyStr == null) {
            throw new JobExecutionException(
                    "Missing required job parameters: reportType=" + reportType + ", reportFrequency=" + frequencyStr);
        }

        ReportFrequency frequency = ReportFrequency.fromConfig(frequencyStr);
        Instant fireTime = context.getScheduledFireTime().toInstant();

        String sequenceStr = triggerData.getString(KEY_WINDOW_SEQUENCE);
        Integer windowSequence = sequenceStr != null ? Integer.valueOf(sequenceStr) : null;

        String boundariesCsv = jobData.getString(KEY_BOUNDARIES);
        List<LocalTime> boundaries =
                boundariesCsv != null ? ReportJobScheduleBuilder.parseBoundaries(boundariesCsv) : null;

        try {
            ReportWindow window = reportingPeriodCalculator.calculate(frequency, fireTime, windowSequence, boundaries);

            log.info(
                    "Job executed: reportType={}, frequency={}, fireTime={}, windowStart={}, windowEnd={}",
                    reportType,
                    frequency,
                    fireTime,
                    window.windowStartUtc(),
                    window.windowEndUtc());

            // Window boundaries are already unique per firing, so JobParameters double as a
            // free double-fire guard: Spring Batch refuses to create a new JobInstance for
            // parameters that already completed successfully.
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("reportType", reportType)
                    .addString("reportFrequency", frequencyStr)
                    .addJobParameter("windowStartUtc", window.windowStartUtc(), Instant.class)
                    .addJobParameter("windowEndUtc", window.windowEndUtc(), Instant.class)
                    .toJobParameters();

            JobExecution jobExecution = startWithDeadlockRetry(jobParameters);

            // JobOperator.start() is synchronous but only throws for launch-time problems
            // (already-running, already-complete, invalid params). A genuine step failure
            // doesn't propagate out of start() at all - it comes back as a normally-returned
            // JobExecution with BatchStatus.FAILED, so that has to be checked explicitly or
            // Quartz sees no exception and treats a failed pipeline run as a successful firing.
            if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
                throw new IllegalStateException("reportPipelineJob did not complete successfully for " + reportType
                        + "/" + frequencyStr + ": status=" + jobExecution.getStatus());
            }

            log.info(
                    "Job completed successfully: reportType={}, frequency={}, status={}",
                    reportType,
                    frequency,
                    jobExecution.getStatus());

            // TODO: Integrate with downstream processing:
            // - Phase 5: Message queue publication
            // - Phase 6: Audit record creation

        } catch (JobInstanceAlreadyCompleteException e) {
            // Expected/benign: the same (reportType, reportFrequency, window) already ran to
            // completion - most likely a Quartz misfire double-trigger. Must be caught before
            // the general Exception catch below, or a correctly-skipped duplicate firing would
            // be logged/alerted on as a real job failure.
            log.info(
                    "Skipping duplicate firing: reportType={}, frequency={}, fireTime={} already completed",
                    reportType,
                    frequency,
                    fireTime);
        } catch (Exception e) {
            log.error("Job failed: reportType={}, frequency={}, fireTime={}", reportType, frequency, fireTime, e);
            throw new JobExecutionException(
                    "Report scheduling job failed for " + reportType + "/" + frequency, e, false);
        }
    }

    /**
     * Launches {@code reportPipelineJob}, retrying a bounded number of times on a transient
     * data-access failure (e.g. a SQL Server deadlock on {@code BATCH_JOB_INSTANCE}). Does
     * not retry {@link JobInstanceAlreadyCompleteException} or any other checked exception
     * from {@link JobOperator#start} - those propagate immediately, unchanged.
     */
    private JobExecution startWithDeadlockRetry(JobParameters jobParameters) throws Exception {
        TransientDataAccessException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_LAUNCH_ATTEMPTS; attempt++) {
            try {
                return jobOperator.start(reportPipelineJob, jobParameters);
            } catch (TransientDataAccessException e) {
                lastFailure = e;
                log.warn(
                        "Transient failure launching reportPipelineJob (attempt {}/{}): {}",
                        attempt,
                        MAX_LAUNCH_ATTEMPTS,
                        e.getMessage());
                if (attempt < MAX_LAUNCH_ATTEMPTS) {
                    sleepBeforeRetry();
                }
            }
        }
        throw lastFailure;
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(LAUNCH_RETRY_BACKOFF.toMillis());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
