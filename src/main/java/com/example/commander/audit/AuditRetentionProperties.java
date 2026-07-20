package com.example.commander.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for {@code CAMT.ReportCommandAudit} retention.
 *
 * <p>Configured via {@code commander.audit.retention} prefix in application properties.
 *
 * <p>90-day default matches the solution document's stated retention policy. Cadence and
 * batch size are placeholder defaults, same category as the MQ resilience tier's retry/
 * backoff defaults — sizing them against real row-volume data is future tuning, not a
 * design question this phase resolves.
 */
@Validated
@ConfigurationProperties(prefix = "commander.audit.retention")
public class AuditRetentionProperties {

    /** Rows with {@code sent_at} older than this many days are eligible for deletion. */
    @Positive private int retentionDays = 90;

    /** Maximum rows deleted per {@code deleteOlderThan} call — bounds a single DB round-trip/lock. */
    @Positive private int batchSize = 1000;

    /** Cron expression for the retention job's polling cadence. */
    @NotBlank private String cron = "0 0 3 * * ?";

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }
}
