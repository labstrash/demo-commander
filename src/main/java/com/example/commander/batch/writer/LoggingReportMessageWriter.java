package com.example.commander.batch.writer;

import com.example.commander.domain.message.OutboundReportMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Logging-only writer, deliberately kept alongside real MQ delivery rather than replaced by
 * it. {@link DeliverySelectionReportMessageWriter} delegates to this writer, not
 * {@link com.example.commander.batch.config.BatchPipelineConfig} directly, so it can serve two
 * ongoing purposes: (1) a per-report-type kill switch — a type can stay on log-only delivery
 * independently of the others — and (2) a debug aid, giving payload visibility even while real
 * delivery is active. Stays typed against {@code OutboundReportMessage}, not
 * {@code PipelineReportMessage} — it only ever needs the wire payload for its debug-print
 * purpose, never the internal {@code reportConfigId}/{@code agreementScopeId} bookkeeping.
 */
@Component
public class LoggingReportMessageWriter implements ItemWriter<OutboundReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(LoggingReportMessageWriter.class);

    @Override
    public void write(Chunk<? extends OutboundReportMessage> chunk) {
        log.info("Writing chunk of {} outbound report messages", chunk.size());
        for (OutboundReportMessage message : chunk) {
            log.debug("outboundReportMessage={}", message);
        }
    }
}
