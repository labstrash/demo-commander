package com.example.commander.scheduling;

import com.example.commander.domain.ReportFrequency;
import com.example.commander.domain.ReportWindow;
import com.example.commander.domain.ReportingPeriodCalculator;
import com.example.commander.service.ScheduledConfigReader;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Quartz job that executes report generation for a specific report type and frequency.
 *
 * <p>Retrieves scheduling parameters from the job and trigger data maps, calculates
 * the reporting window using {@link ReportingPeriodCalculator}, and prepares the
 * window for downstream processing (e.g., Spring Batch, MQ, audit).
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

    private final ReportingPeriodCalculator reportingPeriodCalculator;
    private final ScheduledConfigReader configReader;

    public ReportSchedulingJob(
            ReportingPeriodCalculator reportingPeriodCalculator, ScheduledConfigReader configReader) {
        this.reportingPeriodCalculator = reportingPeriodCalculator;
        this.configReader = configReader;
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

            // Phase 1: read + assemble + fan-out only (no publish/audit yet).
            ScheduledConfigReader.ReadSummary summary = configReader.readAssembleAndFanOut(
                    reportType, frequencyStr, window.windowStartUtc(), window.windowEndUtc());

            // TODO: Integrate with downstream processing:
            // - Phase 4: Spring Batch pipeline
            // - Phase 5: Message queue publication
            // - Phase 6: Audit record creation

            log.info(
                    "Job completed successfully: reportType={}, frequency={}, configsRead={}, messagesAssembled={}",
                    reportType,
                    frequency,
                    summary.treeCount(),
                    summary.messageCount());

        } catch (Exception e) {
            log.error("Job failed: reportType={}, frequency={}, fireTime={}", reportType, frequency, fireTime, e);
            throw new JobExecutionException(
                    "Report scheduling job failed for " + reportType + "/" + frequency, e, false);
        }
    }
}
