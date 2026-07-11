package com.example.commander.batch.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the Spring Batch report pipeline.
 *
 * <p>Configured via {@code commander.batch} prefix in application properties.
 */
@Validated
@ConfigurationProperties(prefix = "commander.batch")
public class BatchPipelineProperties {

    /**
     * Chunk size — message count per chunk/commit, not tree or page count. Independent
     * tuning knob from {@code commander.read.page-size}: page size bounds read-side query
     * round-trips, this bounds write-side transaction blast radius.
     */
    @Positive private int commitInterval = 200;

    public int getCommitInterval() {
        return commitInterval;
    }

    public void setCommitInterval(int commitInterval) {
        this.commitInterval = commitInterval;
    }
}
