package com.example.commander.batch.processor;

import com.example.commander.domain.config.RecipientRow;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.repository.ReportConfigLookupRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Resolves the real recipient for each pipeline message, replacing the reader's
 * {@code UNRESOLVED} placeholder (see {@code ReportPipelineItemReader.contextFor()}).
 *
 * <p>The placeholder {@link RecipientRef} stashes {@code config.messageRecipientId()} in its
 * {@code id} field — this processor reads it back out, looks up the real recipient, and
 * rebuilds the message with every other field (including {@code correlationId}/{@code
 * messageId}, which must never be regenerated — see {@code FanOutAssemblyService}) copied
 * forward unchanged.
 *
 * <p>An unresolvable recipient ID filters the message out of the chunk entirely (return
 * {@code null}), per {@code ItemProcessor}'s contract — the filter event is logged with
 * enough detail (config ID, recipient ID) to investigate.
 */
@Component
public class RecipientResolvingReportMessageProcessor
        implements ItemProcessor<PipelineReportMessage, PipelineReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolvingReportMessageProcessor.class);

    private final ReportConfigLookupRepository repository;

    public RecipientResolvingReportMessageProcessor(ReportConfigLookupRepository repository) {
        this.repository = repository;
    }

    @Override
    public PipelineReportMessage process(PipelineReportMessage item) {
        OutboundReportMessage payload = item.payload();
        long recipientId = payload.recipient().id();

        Optional<RecipientRow> recipient = repository.findRecipientById(recipientId);
        if (recipient.isEmpty()) {
            log.warn(
                    "Filtering message: unresolvable recipient. reportConfigId={}, configId={}, recipientId={}",
                    item.reportConfigId(),
                    payload.configId(),
                    recipientId);
            return null;
        }

        RecipientRow row = recipient.get();
        OutboundReportMessage resolved = new OutboundReportMessage(
                payload.configId(),
                payload.reportType(),
                payload.reportVersion(),
                payload.windowStartUtc(),
                payload.windowEndUtc(),
                payload.isBundled(),
                payload.triggerType(),
                new RecipientRef(row.id(), row.type(), row.value(), row.name()),
                payload.paymentTypeAllocations(),
                payload.requestorName(),
                payload.correlationId(),
                payload.messageId());

        return new PipelineReportMessage(resolved, item.reportConfigId(), item.agreementScopeId());
    }
}
