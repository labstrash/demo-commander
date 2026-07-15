package com.example.commander.batch.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoOpReportMessageProcessorTest {

    private final NoOpReportMessageProcessor processor = new NoOpReportMessageProcessor();

    @Test
    void returnsTheSameItemUnchanged() throws Exception {
        OutboundReportMessage message = new OutboundReportMessage(
                1L,
                12345678,
                0L,
                "CAMT054C",
                "1.0",
                "DAILY",
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"),
                false,
                TriggerType.SCHEDULED,
                new RecipientRef(999L, "UNRESOLVED", "UNRESOLVED", "UNRESOLVED"),
                List.of(),
                null);

        assertThat(processor.process(message)).isSameAs(message);
    }
}
