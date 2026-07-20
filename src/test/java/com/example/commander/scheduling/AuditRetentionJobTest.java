package com.example.commander.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commander.audit.AuditRetentionProperties;
import com.example.commander.repository.ReportCommandAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock
    private ReportCommandAuditRepository repository;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private AuditRetentionProperties properties;
    private AuditRetentionJob job;

    @BeforeEach
    void setUp() {
        properties = new AuditRetentionProperties();
        properties.setRetentionDays(90);
        properties.setBatchSize(1000);
        job = new AuditRetentionJob(repository, properties, clock);
    }

    @Test
    void doesNothingFurtherWhenNothingIsDue() {
        when(repository.deleteOlderThan(any(), eq(1000))).thenReturn(0);

        job.execute(null);

        verify(repository, times(1)).deleteOlderThan(any(), eq(1000));
    }

    @Test
    void usesRetentionDaysToComputeTheCutoff() {
        when(repository.deleteOlderThan(any(), eq(1000))).thenReturn(0);

        job.execute(null);

        Instant expectedCutoff = NOW.minusSeconds(90L * 24 * 3600);
        verify(repository).deleteOlderThan(expectedCutoff, 1000);
    }

    @Test
    void loopsUntilABatchReturnsFewerRowsThanTheBatchSize() {
        when(repository.deleteOlderThan(any(), eq(1000))).thenReturn(1000, 1000, 400);

        job.execute(null);

        verify(repository, times(3)).deleteOlderThan(any(), eq(1000));
    }

    @Test
    void stopsAfterOneCallWhenTheFirstBatchIsAlreadyBelowTheLimit() {
        when(repository.deleteOlderThan(any(), eq(1000))).thenReturn(37);

        job.execute(null);

        verify(repository, times(1)).deleteOlderThan(any(), eq(1000));
    }
}
