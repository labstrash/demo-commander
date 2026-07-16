package com.example.commander.batch.writer;

import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.mq.MqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends each pipeline message's wire payload to the report type's configured MQ queue.
 *
 * <p>Only {@link PipelineReportMessage#payload()} is ever serialized to MQ —
 * {@code reportConfigId}/{@code agreementScopeId} are internal-only bookkeeping for a later
 * dead-letter stage, not part of the wire payload.
 *
 * <p>Deliberately no retry/circuit-breaker/dead-letter handling here. A failed send throws,
 * the chunk rolls back, the job fails, same as every other unhandled failure in the pipeline
 * today.
 *
 * <p>{@code @StepScope}: resolves {@code reportType} from {@code JobParameters} and the
 * target queue once per job execution — a firing only ever processes one report type, so
 * there's nothing to re-resolve mid-step.
 */
@Component
@StepScope
public class MqReportMessageWriter implements ItemWriter<PipelineReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(MqReportMessageWriter.class);

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;
    private final String targetQueue;

    public MqReportMessageWriter(
            JmsTemplate jmsTemplate,
            ObjectMapper objectMapper,
            MqProperties mqProperties,
            @Value("#{jobParameters['reportType']}") String reportType) {
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
        this.targetQueue = mqProperties.queueFor(reportType);
    }

    @Override
    public void write(Chunk<? extends PipelineReportMessage> chunk) throws Exception {
        for (PipelineReportMessage item : chunk) {
            String json = objectMapper.writeValueAsString(item.payload());
            jmsTemplate.convertAndSend(targetQueue, json);
        }
        log.info("Sent {} message(s) to queue={}", chunk.size(), targetQueue);
    }
}
