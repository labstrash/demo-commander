package com.example.commander.batch;

import com.example.commander.batch.config.BatchPipelineProperties;
import com.example.commander.config.ReadLayerProperties;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal Spring context for batch pipeline integration tests — deliberately not the full
 * {@code DemoCommanderApplication} context.
 *
 * <p>Loading the whole app would also start {@code QuartzSchedulerConfig}'s scheduler via
 * {@code OrphanedTriggerCleanupRunner}, firing real cron/boundary triggers during the test.
 * {@link QuartzAutoConfiguration} is excluded and only the packages the pipeline actually
 * needs are component-scanned, so no Quartz beans (scheduler, job factory, dedicated
 * datasource) are created at all.
 *
 * <p>Everything else (the app's main {@code DataSource}, {@code PlatformTransactionManager},
 * and Spring Batch's {@code JobRepository}/{@code JobOperator}) comes from normal Spring Boot
 * autoconfiguration, resolved against {@code src/main/resources/application.properties} on
 * this source set's classpath — the same {@code localhost:1433} instance this repo's
 * {@code docker-compose} brings up for local dev, which must already be running and
 * initialized (through {@code 97-schema-batch.sql}) before these tests run.
 */
@Configuration
@EnableAutoConfiguration(exclude = QuartzAutoConfiguration.class)
@EnableConfigurationProperties({ReadLayerProperties.class, BatchPipelineProperties.class})
@ComponentScan(
        basePackages = {
            "com.example.commander.batch",
            "com.example.commander.repository",
            "com.example.commander.service"
        })
public class BatchIntegrationTestConfig {}
