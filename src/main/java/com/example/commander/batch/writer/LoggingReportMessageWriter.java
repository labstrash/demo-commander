package com.example.commander.batch.writer;

import com.example.commander.domain.message.OutboundReportMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Logging-only writer stub — Phase 5 (MQ) / Phase 6 (audit) replace this bean, not the
 * interface or step configuration. Step definition and chunk/transaction wiring are final
 * as of Phase 4.
 */
@Component
public class LoggingReportMessageWriter implements ItemWriter<OutboundReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(LoggingReportMessageWriter.class);

    @Override
    public void write(Chunk<? extends OutboundReportMessage> chunk) {
        log.info("Writing chunk of {} outbound report messages", chunk.size());
        for (OutboundReportMessage message : chunk) {
            log.debug(
                    "reportConfigId={}, configId={}, agreementScopeId={}, reportType={}, accounts={}, aliases={}",
                    message.reportConfigId(),
                    message.configId(),
                    message.agreementScopeId(),
                    message.reportType(),
                    message.accounts().size(),
                    message.aliases().size());
        }
    }
}
