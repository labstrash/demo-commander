package com.example.commander.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the report generation read layer.
 *
 * <p>Controls pagination, batching, and timeout behavior for fetching report configuration
 * and audit data from the database.
 *
 * <p>Configured via {@code commander.read} prefix in application properties.
 */
@Validated
@ConfigurationProperties(prefix = "commander.read")
public class ReadLayerProperties {

    /** Keyset-pagination page size for ReportConfig queries. */
    @Positive private int pageSize = 500;

    /** Rows accumulated before executing batch insert for audit records. */
    @Positive private int auditBatchSize = 500;

    /** Query timeout for staged hierarchy reads. */
    @Positive private int stagedQueryTimeoutSeconds = 10;

    /** Query timeout for TVP-backed staged queries. */
    @Positive private int tvpQueryTimeoutSeconds = 15;

    /** Maximum allowed time span for on-demand report requests. Defaults to 31 days. */
    @NotNull private Duration onDemandMaxWindowDuration = Duration.ofDays(31);

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getAuditBatchSize() {
        return auditBatchSize;
    }

    public void setAuditBatchSize(int auditBatchSize) {
        this.auditBatchSize = auditBatchSize;
    }

    public int getStagedQueryTimeoutSeconds() {
        return stagedQueryTimeoutSeconds;
    }

    public void setStagedQueryTimeoutSeconds(int stagedQueryTimeoutSeconds) {
        this.stagedQueryTimeoutSeconds = stagedQueryTimeoutSeconds;
    }

    public int getTvpQueryTimeoutSeconds() {
        return tvpQueryTimeoutSeconds;
    }

    public void setTvpQueryTimeoutSeconds(int tvpQueryTimeoutSeconds) {
        this.tvpQueryTimeoutSeconds = tvpQueryTimeoutSeconds;
    }

    public Duration getOnDemandMaxWindowDuration() {
        return onDemandMaxWindowDuration;
    }

    public void setOnDemandMaxWindowDuration(Duration onDemandMaxWindowDuration) {
        this.onDemandMaxWindowDuration = onDemandMaxWindowDuration;
    }
}
