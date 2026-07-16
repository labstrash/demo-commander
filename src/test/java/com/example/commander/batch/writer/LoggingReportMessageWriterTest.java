package com.example.commander.batch.writer;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;

class LoggingReportMessageWriterTest {

    private final LoggingReportMessageWriter writer = new LoggingReportMessageWriter();

    @Test
    void writesAChunkOfMessagesWithoutThrowing() {
        OutboundReportMessage message = new OutboundReportMessage(
                12345678,
                "CAMT054C",
                "1.0",
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"),
                false,
                TriggerType.SCHEDULED,
                new RecipientRef(999L, "UNRESOLVED", "UNRESOLVED", "UNRESOLVED"),
                List.of(),
                null);

        assertThatCode(() -> writer.write(new Chunk<>(message))).doesNotThrowAnyException();
    }

    @Test
    void writesAnEmptyChunkWithoutThrowing() {
        assertThatCode(() -> writer.write(new Chunk<>())).doesNotThrowAnyException();
    }
}
