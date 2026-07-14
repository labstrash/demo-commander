package com.example.commander.batch.processor;

import com.example.commander.domain.message.OutboundReportMessage;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Pass-through processor stage — the real seam for future recipient resolution.
 *
 * <p>{@code ScheduledConfigReader.contextFor()} (now {@code ReportPipelineItemReader})
 * hardcodes an unresolved placeholder recipient; real recipient resolution belongs here
 * once that work lands, without touching reader or writer wiring.
 */
@Component
public class NoOpReportMessageProcessor implements ItemProcessor<OutboundReportMessage, OutboundReportMessage> {

    @Override
    public OutboundReportMessage process(OutboundReportMessage item) {
        return item;
    }
}
