package com.example.commander.batch.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.commander.domain.config.RecipientRow;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import com.example.commander.repository.ReportConfigLookupRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecipientResolvingReportMessageProcessorTest {

    @Mock
    private ReportConfigLookupRepository repository;

    private RecipientResolvingReportMessageProcessor processor;

    @Test
    void resolvesRecipientAndRebuildsMessageWithEveryOtherFieldUnchanged() throws Exception {
        processor = new RecipientResolvingReportMessageProcessor(repository);
        RecipientRow recipient = new RecipientRow(999L, "BIC", "SOMEBIC", "Some Recipient");
        when(repository.findRecipientById(999L)).thenReturn(Optional.of(recipient));

        PipelineReportMessage item = placeholderMessage();

        PipelineReportMessage result = processor.process(item);

        assertThat(result).isNotNull();
        assertThat(result.payload().recipient()).isEqualTo(new RecipientRef(999L, "BIC", "SOMEBIC", "Some Recipient"));
        assertThat(result.reportConfigId()).isEqualTo(item.reportConfigId());
        assertThat(result.agreementScopeId()).isEqualTo(item.agreementScopeId());
        // messageId is non-deterministic — must be threaded forward unchanged, never regenerated
        assertThat(result.payload().messageId()).isEqualTo(item.payload().messageId());
        assertThat(result.payload().correlationId()).isEqualTo(item.payload().correlationId());
        assertThat(result.payload().configId()).isEqualTo(item.payload().configId());
        assertThat(result.payload().reportType()).isEqualTo(item.payload().reportType());
        assertThat(result.payload().paymentTypeAllocations())
                .isEqualTo(item.payload().paymentTypeAllocations());
    }

    @Test
    void filtersMessageWhenRecipientIsUnresolvable() throws Exception {
        processor = new RecipientResolvingReportMessageProcessor(repository);
        when(repository.findRecipientById(999L)).thenReturn(Optional.empty());

        PipelineReportMessage result = processor.process(placeholderMessage());

        assertThat(result).isNull();
    }

    private static PipelineReportMessage placeholderMessage() {
        OutboundReportMessage payload = new OutboundReportMessage(
                12345678,
                "CAMT054C",
                "1.0",
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"),
                true,
                TriggerType.SCHEDULED,
                new RecipientRef(999L, "UNRESOLVED", "UNRESOLVED", "UNRESOLVED"),
                List.of(),
                null,
                "corr-id",
                "FIKASE054C123450Q9Z6XZHPAH5R0000");
        return new PipelineReportMessage(payload, 1L, null);
    }
}
