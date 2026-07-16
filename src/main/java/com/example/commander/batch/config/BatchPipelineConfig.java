package com.example.commander.batch.config;

import com.example.commander.batch.processor.RecipientResolvingReportMessageProcessor;
import com.example.commander.batch.reader.ReportPipelineItemReader;
import com.example.commander.batch.writer.DeliverySelectionReportMessageWriter;
import com.example.commander.domain.message.PipelineReportMessage;
import java.time.Instant;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.batch.autoconfigure.BatchConversionServiceCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the report pipeline's single generic {@link Job} and its one chunk-oriented
 * {@link Step}. {@code reportType}/{@code reportFrequency}/window are runtime
 * {@code JobParameters} (bound into {@link ReportPipelineItemReader} via {@code @StepScope}),
 * not compile-time wiring — one bean covers every report type/frequency combination.
 */
@Configuration
public class BatchPipelineConfig {

    /**
     * Spring Batch 6's JDBC {@code JobRepository} ships {@code String} converters for
     * {@code Date}/{@code LocalDate}/{@code LocalTime}/{@code LocalDateTime}/
     * {@code OffsetDateTime}/{@code ZonedDateTime} but not {@code Instant} — without this,
     * persisting a typed {@code JobParameter<Instant>} (windowStartUtc/windowEndUtc) throws
     * {@code ConverterNotFoundException} the moment a job is launched. ISO-8601 round-trips
     * losslessly through {@link Instant#toString()} / {@link Instant#parse}.
     */
    @Bean
    public BatchConversionServiceCustomizer instantJobParameterConversionServiceCustomizer() {
        return (ConfigurableConversionService conversionService) -> {
            conversionService.addConverter(Instant.class, String.class, Instant::toString);
            conversionService.addConverter(String.class, Instant.class, Instant::parse);
        };
    }

    @Bean
    public Job reportPipelineJob(JobRepository jobRepository, Step reportPipelineStep) {
        return new JobBuilder("reportPipelineJob", jobRepository)
                .start(reportPipelineStep)
                .build();
    }

    @Bean
    public Step reportPipelineStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReportPipelineItemReader reader,
            RecipientResolvingReportMessageProcessor processor,
            DeliverySelectionReportMessageWriter writer,
            BatchPipelineProperties properties) {
        return new StepBuilder("reportPipelineStep", jobRepository)
                .<PipelineReportMessage, PipelineReportMessage>chunk(properties.getCommitInterval())
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }
}
