package com.example.commander.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commander.domain.deadletter.DeadLetterMessageRow;
import com.example.commander.mq.MqResilienceProperties;
import com.example.commander.mq.ResilientMqSender;
import com.example.commander.mq.SendOutcome;
import com.example.commander.repository.DeadLetterMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeadLetterRecoveryJobTest {

    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

    @Mock
    private DeadLetterMessageRepository repository;

    @Mock
    private ResilientMqSender sender;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MqResilienceProperties properties = properties();

    private DeadLetterRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new DeadLetterRecoveryJob(repository, sender, properties, clock);
    }

    @Test
    void doesNothingWhenNoRowsAreDue() throws Exception {
        when(repository.findDueForRetry(anyInt())).thenReturn(List.of());

        job.execute(null);

        verify(repository, never()).delete(any(Long.class));
        verify(repository, never()).markRetryScheduled(any(Long.class), any(Integer.class), any(), any());
        verify(repository, never()).markFailed(any(Long.class), any(Integer.class), any());
    }

    @Test
    void successfulResendDeletesTheRow() throws Exception {
        DeadLetterMessageRow row = row(1L, 0, 5);
        when(repository.findDueForRetry(anyInt())).thenReturn(List.of(row));
        when(sender.send(row.targetQueue(), row.messagePayload())).thenReturn(SendOutcome.success());

        job.execute(null);

        verify(repository).delete(1L);
    }

    @Test
    void failureShortOfMaxRetriesSchedulesTheNextAttempt() throws Exception {
        DeadLetterMessageRow row = row(1L, 0, 5);
        when(repository.findDueForRetry(anyInt())).thenReturn(List.of(row));
        when(sender.send(row.targetQueue(), row.messagePayload()))
                .thenReturn(SendOutcome.transientExhausted(new RuntimeException("still down")));

        job.execute(null);

        Instant expectedNextRetryAt = NOW.plusSeconds(
                properties.getDeadLetterRetryBackoff(row.reportType()).getBaseSeconds());
        verify(repository).markRetryScheduled(eq(1L), eq(1), eq(expectedNextRetryAt), eq("still down"));
        verify(repository, never()).markFailed(any(Long.class), any(Integer.class), any());
    }

    @Test
    void reportTypesInDifferentTiersGetDifferentBackoff() throws Exception {
        DeadLetterMessageRow fastRow = row(1L, 0, 5, "CAMT052B");
        DeadLetterMessageRow slowRow = row(2L, 0, 5, "CAMT053E");
        when(repository.findDueForRetry(anyInt())).thenReturn(List.of(fastRow, slowRow));
        when(sender.send(fastRow.targetQueue(), fastRow.messagePayload()))
                .thenReturn(SendOutcome.transientExhausted(new RuntimeException("still down")));
        when(sender.send(slowRow.targetQueue(), slowRow.messagePayload()))
                .thenReturn(SendOutcome.transientExhausted(new RuntimeException("still down")));

        job.execute(null);

        Instant expectedFastNextRetryAt = NOW.plusSeconds(15);
        Instant expectedSlowNextRetryAt = NOW.plusSeconds(120);
        verify(repository).markRetryScheduled(eq(1L), eq(1), eq(expectedFastNextRetryAt), any());
        verify(repository).markRetryScheduled(eq(2L), eq(1), eq(expectedSlowNextRetryAt), any());
    }

    @Test
    void failureAtMaxRetriesMarksTheRowTerminallyFailed() throws Exception {
        DeadLetterMessageRow row = row(1L, 4, 5); // one more failed attempt reaches maxRetries=5
        when(repository.findDueForRetry(anyInt())).thenReturn(List.of(row));
        when(sender.send(row.targetQueue(), row.messagePayload()))
                .thenReturn(SendOutcome.permanent(new RuntimeException("still broken")));

        job.execute(null);

        verify(repository).markFailed(1L, 5, "still broken");
        verify(repository, never()).markRetryScheduled(any(Long.class), any(Integer.class), any(), any());
    }

    private static DeadLetterMessageRow row(long id, int retryCount, int maxRetries) {
        return row(id, retryCount, maxRetries, "CAMT054C");
    }

    private static DeadLetterMessageRow row(long id, int retryCount, int maxRetries, String reportType) {
        return new DeadLetterMessageRow(
                id,
                "MSG-" + id,
                1L,
                null,
                reportType,
                "{}",
                "QUEUE-" + id,
                retryCount,
                maxRetries,
                null,
                "PENDING_RETRY",
                NOW,
                null,
                NOW);
    }

    private static MqResilienceProperties properties() {
        MqResilienceProperties properties = new MqResilienceProperties();
        properties.setDeadLetterRetryBackoffDefault(new MqResilienceProperties.Backoff(60, 3600));

        MqResilienceProperties.BackoffTier fastTier = new MqResilienceProperties.BackoffTier();
        fastTier.setBaseSeconds(15);
        fastTier.setMaxSeconds(300);
        fastTier.setReportTypes(List.of("CAMT052B"));

        MqResilienceProperties.BackoffTier slowTier = new MqResilienceProperties.BackoffTier();
        slowTier.setBaseSeconds(120);
        slowTier.setMaxSeconds(7200);
        slowTier.setReportTypes(List.of("CAMT053E"));

        properties.setDeadLetterRetryBackoffTiers(List.of(fastTier, slowTier));
        return properties;
    }
}
