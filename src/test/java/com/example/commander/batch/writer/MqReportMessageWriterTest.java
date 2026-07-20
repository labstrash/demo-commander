package com.example.commander.batch.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commander.domain.audit.ReportCommandAuditEntry;
import com.example.commander.domain.audit.ReportCommandAuditStatus;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import com.example.commander.mq.MqProperties;
import com.example.commander.mq.MqResilienceProperties;
import com.example.commander.mq.ResilientMqSender;
import com.example.commander.mq.SendOutcome;
import com.example.commander.repository.DeadLetterMessageRepository;
import com.example.commander.repository.ReportCommandAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MqReportMessageWriterTest {

    private static final String TARGET_QUEUE = "CAMT.054C.QUEUE";
    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ResilientMqSender sender;

    @Mock
    private DeadLetterMessageRepository deadLetterRepository;

    @Mock
    private ReportCommandAuditRepository auditRepository;

    private MqReportMessageWriter writer;

    private MqReportMessageWriter newWriter() {
        MqProperties mqProperties = new MqProperties();
        mqProperties.setQueues(Map.of("CAMT054C", TARGET_QUEUE));
        MqResilienceProperties resilienceProperties = new MqResilienceProperties();
        resilienceProperties.setDeadLetterMaxRetries(5);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new MqReportMessageWriter(
                objectMapper,
                sender,
                deadLetterRepository,
                auditRepository,
                clock,
                mqProperties,
                resilienceProperties,
                "CAMT054C",
                "DAILY",
                111L,
                222L);
    }

    @ParameterizedTest
    @MethodSource("failureOutcomes")
    void deadLettersOnAnyFailureOutcomeAndReturnsNormally(SendOutcome outcome) throws Exception {
        writer = newWriter();
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"json\":true}");
        when(sender.send(eq(TARGET_QUEUE), anyString())).thenReturn(outcome);
        PipelineReportMessage item = message();

        assertThatCode(() -> writer.write(new Chunk<>(item))).doesNotThrowAnyException();

        verify(deadLetterRepository)
                .insert(
                        eq(item.payload().messageId()),
                        eq(item.reportConfigId()),
                        eq(item.agreementScopeId()),
                        eq("CAMT054C"),
                        eq("{\"json\":true}"),
                        eq(TARGET_QUEUE),
                        eq(5),
                        eq(NOW));
    }

    @ParameterizedTest
    @MethodSource("failureOutcomes")
    void writesAFailedAuditRowOnAnyFailureOutcome(SendOutcome outcome) throws Exception {
        writer = newWriter();
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"json\":true}");
        when(sender.send(eq(TARGET_QUEUE), anyString())).thenReturn(outcome);
        PipelineReportMessage item = message();

        writer.write(new Chunk<>(item));

        ReportCommandAuditEntry entry = capturedAuditEntry();
        assertThat(entry.status()).isEqualTo(ReportCommandAuditStatus.FAILED);
        assertThat(entry.mqMessageId()).isNull();
        assertThat(entry.retryCount()).isZero();
        assertThat(entry.jobExecutionId()).isEqualTo(111L);
        assertThat(entry.stepExecutionId()).isEqualTo(222L);
        assertThat(entry.reportFrequency()).isEqualTo("DAILY");
    }

    @Test
    void successfulSendNeverWritesADeadLetterRow() throws Exception {
        writer = newWriter();
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"json\":true}");
        when(sender.send(eq(TARGET_QUEUE), anyString())).thenReturn(SendOutcome.success("jms-msg-id"));

        writer.write(new Chunk<>(message()));

        verify(deadLetterRepository, never()).insert(any(), anyLong(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void successfulSendWritesASentAuditRowWithTheRealJmsMessageId() throws Exception {
        writer = newWriter();
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"json\":true}");
        when(sender.send(eq(TARGET_QUEUE), anyString())).thenReturn(SendOutcome.success("jms-msg-id"));

        writer.write(new Chunk<>(message()));

        ReportCommandAuditEntry entry = capturedAuditEntry();
        assertThat(entry.status()).isEqualTo(ReportCommandAuditStatus.SENT);
        assertThat(entry.mqMessageId()).isEqualTo("jms-msg-id");
    }

    @Test
    void existingSentRowForCorrelationIdSkipsTheSendEntirely() throws Exception {
        writer = newWriter();
        when(auditRepository.existsSent("corr-id")).thenReturn(true);

        writer.write(new Chunk<>(message()));

        verify(sender, never()).send(any(), any());
        verify(deadLetterRepository, never()).insert(any(), anyLong(), any(), any(), any(), any(), anyInt(), any());
        ReportCommandAuditEntry entry = capturedAuditEntry();
        assertThat(entry.status()).isEqualTo(ReportCommandAuditStatus.SKIPPED_DUPLICATE);
    }

    private ReportCommandAuditEntry capturedAuditEntry() {
        ArgumentCaptor<ReportCommandAuditEntry> captor = ArgumentCaptor.forClass(ReportCommandAuditEntry.class);
        verify(auditRepository).insert(captor.capture());
        return captor.getValue();
    }

    private static Stream<SendOutcome> failureOutcomes() {
        return Stream.of(
                SendOutcome.breakerOpen(),
                SendOutcome.transientExhausted(new RuntimeException("boom")),
                SendOutcome.permanent(new RuntimeException("boom")));
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
