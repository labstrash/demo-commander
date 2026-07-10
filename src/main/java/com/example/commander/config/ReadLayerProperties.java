package com.example.commander.config;

import jakarta.validation.constraints.Positive;
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

    /** Query timeout for staged hierarchy reads. */
    @Positive private int stagedQueryTimeoutSeconds = 10;

    /** Query timeout for TVP-backed staged queries. */
    @Positive private int tvpQueryTimeoutSeconds = 15;

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
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
}
