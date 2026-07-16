package com.example.commander.mq;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the MQ resilience tier — in-process retry, the circuit breaker, and the
 * dead-letter recovery job.
 *
 * <p>Configured via {@code commander.mq.resilience} prefix in application properties.
 *
 * <p>Retry attempt count/backoff and the breaker's threshold/cool-down are placeholder
 * defaults — sizing them properly needs real outage/recovery-time data, not available at
 * implementation time (same category as the batch pipeline's chunk-size tuning).
 */
@Validated
@ConfigurationProperties(prefix = "commander.mq.resilience")
public class MqResilienceProperties {

    /** Maximum attempts per send (including the first), for transient failures only. */
    @Positive private int retryMaxAttempts = 3;

    /** Fixed backoff between retry attempts, in milliseconds. */
    @Positive private long retryBackoffMs = 500;

    /** Consecutive transient-exhausted failures before the circuit breaker opens. */
    @Positive private int breakerFailureThreshold = 5;

    /** How long the breaker stays open before letting a probe call through. */
    @Positive private long breakerCoolDownSeconds = 30;

    /** Recovery attempts allowed before a dead-letter row is marked terminally failed. */
    @Positive private int deadLetterMaxRetries = 5;

    /** Backoff applied to any report type not listed in {@link #deadLetterRetryBackoffTiers}. */
    @Valid private Backoff deadLetterRetryBackoffDefault = new Backoff(60, 3600);

    /**
     * Groups of report types sharing a dead-letter recovery backoff — e.g. high-frequency
     * report types recover faster than once-daily ones. Mirrors {@code
     * SchedulingProperties.schedules[N].report-types}'s existing report-type-grouping shape
     * rather than a flat per-type map, since types cluster into a handful of priority tiers
     * more naturally than each needing a bespoke value.
     */
    @Valid private List<BackoffTier> deadLetterRetryBackoffTiers = new ArrayList<>();

    /** Cron expression for the dead-letter recovery job's polling cadence — global across every report type. */
    @NotBlank private String recoveryJobCron = "0 */5 * * * ?";

    private Map<String, Backoff> backoffByReportType;

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getBreakerFailureThreshold() {
        return breakerFailureThreshold;
    }

    public void setBreakerFailureThreshold(int breakerFailureThreshold) {
        this.breakerFailureThreshold = breakerFailureThreshold;
    }

    public long getBreakerCoolDownSeconds() {
        return breakerCoolDownSeconds;
    }

    public void setBreakerCoolDownSeconds(long breakerCoolDownSeconds) {
        this.breakerCoolDownSeconds = breakerCoolDownSeconds;
    }

    public int getDeadLetterMaxRetries() {
        return deadLetterMaxRetries;
    }

    public void setDeadLetterMaxRetries(int deadLetterMaxRetries) {
        this.deadLetterMaxRetries = deadLetterMaxRetries;
    }

    public Backoff getDeadLetterRetryBackoffDefault() {
        return deadLetterRetryBackoffDefault;
    }

    public void setDeadLetterRetryBackoffDefault(Backoff deadLetterRetryBackoffDefault) {
        this.deadLetterRetryBackoffDefault = deadLetterRetryBackoffDefault;
    }

    public List<BackoffTier> getDeadLetterRetryBackoffTiers() {
        return deadLetterRetryBackoffTiers;
    }

    public void setDeadLetterRetryBackoffTiers(List<BackoffTier> deadLetterRetryBackoffTiers) {
        this.deadLetterRetryBackoffTiers = deadLetterRetryBackoffTiers;
    }

    public String getRecoveryJobCron() {
        return recoveryJobCron;
    }

    public void setRecoveryJobCron(String recoveryJobCron) {
        this.recoveryJobCron = recoveryJobCron;
    }

    /**
     * Flattens {@link #deadLetterRetryBackoffTiers} into a report-type lookup map, failing
     * fast if a report type appears in more than one tier — the same discipline {@code
     * SchedulingProperties.validate()} applies to duplicate {@code (report-type, frequency)}
     * pairs. Spring calls this once, at bean initialization, so a misconfigured tier fails
     * the app at startup rather than on first use; {@link #getDeadLetterRetryBackoff}
     * additionally computes it lazily on first call for instances built outside a Spring
     * container (e.g. directly in a test), so nothing besides Spring needs to remember to
     * call this explicitly.
     *
     * @throws IllegalStateException if a report type appears in more than one tier
     */
    @PostConstruct
    void flattenAndValidateTiers() {
        Map<String, Backoff> flattened = new HashMap<>();
        Map<String, Integer> tierIndexByReportType = new HashMap<>();

        for (int tierIndex = 0; tierIndex < deadLetterRetryBackoffTiers.size(); tierIndex++) {
            BackoffTier tier = deadLetterRetryBackoffTiers.get(tierIndex);
            Backoff backoff = new Backoff(tier.getBaseSeconds(), tier.getMaxSeconds());

            for (String reportType : tier.getReportTypes()) {
                Integer existingTierIndex = tierIndexByReportType.putIfAbsent(reportType, tierIndex);
                if (existingTierIndex != null) {
                    throw new IllegalStateException("Report type " + reportType
                            + " appears in more than one commander.mq.resilience.dead-letter-retry-backoff-tiers"
                            + " entry (tier " + existingTierIndex + " and tier " + tierIndex + ")");
                }
                flattened.put(reportType, backoff);
            }
        }

        this.backoffByReportType = flattened;
    }

    /**
     * Returns the dead-letter recovery backoff for the given report type — its tier's
     * backoff if it's listed in one, {@link #deadLetterRetryBackoffDefault} otherwise.
     *
     * @param reportType the report type
     * @return the effective backoff
     */
    public Backoff getDeadLetterRetryBackoff(String reportType) {
        if (backoffByReportType == null) {
            flattenAndValidateTiers();
        }
        return backoffByReportType.getOrDefault(reportType, deadLetterRetryBackoffDefault);
    }

    /** Base/ceiling backoff pair for dead-letter recovery retries. */
    public static class Backoff {

        @Positive private long baseSeconds;

        @Positive private long maxSeconds;

        public Backoff() {}

        public Backoff(long baseSeconds, long maxSeconds) {
            this.baseSeconds = baseSeconds;
            this.maxSeconds = maxSeconds;
        }

        public long getBaseSeconds() {
            return baseSeconds;
        }

        public void setBaseSeconds(long baseSeconds) {
            this.baseSeconds = baseSeconds;
        }

        public long getMaxSeconds() {
            return maxSeconds;
        }

        public void setMaxSeconds(long maxSeconds) {
            this.maxSeconds = maxSeconds;
        }
    }

    /** One dead-letter retry backoff, shared by every report type listed in {@link #reportTypes}. */
    public static class BackoffTier {

        @Positive private long baseSeconds;

        @Positive private long maxSeconds;

        @NotEmpty private List<String> reportTypes = new ArrayList<>();

        public long getBaseSeconds() {
            return baseSeconds;
        }

        public void setBaseSeconds(long baseSeconds) {
            this.baseSeconds = baseSeconds;
        }

        public long getMaxSeconds() {
            return maxSeconds;
        }

        public void setMaxSeconds(long maxSeconds) {
            this.maxSeconds = maxSeconds;
        }

        public List<String> getReportTypes() {
            return reportTypes;
        }

        public void setReportTypes(List<String> reportTypes) {
            this.reportTypes = reportTypes;
        }
    }
}
