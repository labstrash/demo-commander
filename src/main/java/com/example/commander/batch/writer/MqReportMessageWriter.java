package com.example.commander.batch.writer;

import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.mq.MqProperties;
import com.example.commander.mq.MqResilienceProperties;
import com.example.commander.mq.ResilientMqSender;
import com.example.commander.mq.SendOutcome;
import com.example.commander.repository.DeadLetterMessageRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends each pipeline message's wire payload to the report type's configured MQ queue via
 * {@link ResilientMqSender}.
 *
 * <p>A message {@link ResilientMqSender} couldn't deliver (retries exhausted, permanent
 * failure, or the circuit breaker open) is written to {@code CAMT.DeadLetterMessage} instead
 * of failing this writer — the whole point of the dead-letter tier is that one message's
 * delivery trouble doesn't cascade into a failed batch job. Only a genuine failure to write
 * the dead-letter row itself (a DB problem) still propagates, same as any other unhandled
 * failure in the pipeline today.
 *
 * <p>Payload serialization happens here, before the resilient send — not inside {@link
 * ResilientMqSender}, which only ever handles an already-serialized string (the recovery job
 * resends a stored one, with nothing to serialize). A serialization failure on this flat,
 * JSR-310-aware record payload is realistically never expected; unlike a send failure, there
 * would be no valid payload to persist in {@code message_payload} for it, so it isn't
 * caught/dead-lettered here — it propagates and fails the job like any other unexpected,
 * programmer-error-class failure.
 *
 * <p>{@code @StepScope}: resolves {@code reportType} from {@code JobParameters} and the
 * target queue once per job execution — a firing only ever processes one report type, so
 * there's nothing to re-resolve mid-step.
 */
@Component
@StepScope
public class MqReportMessageWriter implements ItemWriter<PipelineReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(MqReportMessageWriter.class);

    private final ObjectMapper objectMapper;
    private final ResilientMqSender sender;
    private final DeadLetterMessageRepository deadLetterRepository;
    private final Clock clock;
    private final String reportType;
    private final String targetQueue;
    private final int deadLetterMaxRetries;

    public MqReportMessageWriter(
            ObjectMapper objectMapper,
            ResilientMqSender sender,
            DeadLetterMessageRepository deadLetterRepository,
            Clock clock,
            MqProperties mqProperties,
            MqResilienceProperties mqResilienceProperties,
            @Value("#{jobParameters['reportType']}") String reportType) {
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.deadLetterRepository = deadLetterRepository;
        this.clock = clock;
        this.reportType = reportType;
        this.targetQueue = mqProperties.queueFor(reportType);
        this.deadLetterMaxRetries = mqResilienceProperties.getDeadLetterMaxRetries();
    }

    @Override
    public void write(Chunk<? extends PipelineReportMessage> chunk) throws Exception {
        int sent = 0;
        for (PipelineReportMessage item : chunk) {
            String json = objectMapper.writeValueAsString(item.payload());
            SendOutcome outcome = sender.send(targetQueue, json);

            if (outcome.isFailure()) {
                deadLetter(item, json, outcome);
            } else {
                sent++;
            }
        }
        log.info("Sent {}/{} message(s) to queue={}", sent, chunk.size(), targetQueue);
    }

    private void deadLetter(PipelineReportMessage item, String json, SendOutcome outcome) {
        log.warn(
                "Dead-lettering messageId={} (outcome={}) for queue={}",
                item.payload().messageId(),
                outcome.type(),
                targetQueue,
                outcome.cause());
        deadLetterRepository.insert(
                item.payload().messageId(),
                item.reportConfigId(),
                item.agreementScopeId(),
                reportType,
                json,
                targetQueue,
                deadLetterMaxRetries,
                clock.instant());
    }
}
