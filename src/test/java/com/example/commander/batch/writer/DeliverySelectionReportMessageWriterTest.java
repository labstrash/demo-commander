package com.example.commander.batch.writer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;

import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

@ExtendWith(MockitoExtension.class)
class DeliverySelectionReportMessageWriterTest {

    @Mock
    private LoggingReportMessageWriter loggingWriter;

    @Mock
    private MqReportMessageWriter mqWriter;

    @Test
    void writesToBothWritersUnconditionallyLoggingFirst() throws Exception {
        DeliverySelectionReportMessageWriter writer = new DeliverySelectionReportMessageWriter(loggingWriter, mqWriter);

        writer.write(new Chunk<>(message()));

        InOrder order = inOrder(loggingWriter, mqWriter);
        order.verify(loggingWriter).write(any());
        order.verify(mqWriter).write(any());
    }

    private static PipelineReportMessage message() {
        OutboundReportMessage payload = new OutboundReportMessage(
                12345678,
                "CAMT054C",
                "1.0",
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"),
                true,
                TriggerType.SCHEDULED,
                new RecipientRef(999L, "BIC", "SOMEBIC", "Some Recipient"),
                List.of(),
                null,
                "corr-id",
                "FIKASE054C123450Q9Z6XZHPAH5R0000");
        return new PipelineReportMessage(payload, 1L, null);
    }
}
