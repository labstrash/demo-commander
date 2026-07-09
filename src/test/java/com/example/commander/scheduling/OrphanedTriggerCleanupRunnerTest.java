package com.example.commander.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.commander.scheduling.ReportJobScheduleBuilder.ReportJobSchedule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class OrphanedTriggerCleanupRunnerTest {

    @Mock
    private Scheduler scheduler;

    @Mock
    private ReportJobSchedule expectedSchedule;

    @Mock
    private Trigger trigger1;

    @Mock
    private Trigger trigger2;

    @Mock
    private ApplicationArguments applicationArguments;

    private OrphanedTriggerCleanupRunner runner;

    @BeforeEach
    void setUp() {
        runner = new OrphanedTriggerCleanupRunner(scheduler, expectedSchedule);
    }

    @Test
    void shouldDoNothingWhenNoOrphanedTriggers() throws Exception {
        TriggerKey key1 = new TriggerKey("trigger1", "camt-scheduling");
        TriggerKey key2 = new TriggerKey("trigger2", "camt-scheduling");

        when(expectedSchedule.triggers()).thenReturn(List.of(trigger1, trigger2));
        when(trigger1.getKey()).thenReturn(key1);
        when(trigger2.getKey()).thenReturn(key2);
        when(scheduler.getTriggerKeys(any())).thenReturn(Set.of(key1, key2));

        runner.run(applicationArguments);

        verify(scheduler, never()).unscheduleJob(any());
    }

    @Test
    void shouldRemoveOrphanedTriggers() throws Exception {
        TriggerKey expectedKey1 = new TriggerKey("expected1", "camt-scheduling");
        TriggerKey orphanedKey = new TriggerKey("orphaned", "camt-scheduling");

        when(expectedSchedule.triggers()).thenReturn(List.of(trigger1));
        when(trigger1.getKey()).thenReturn(expectedKey1);
        when(scheduler.getTriggerKeys(any())).thenReturn(Set.of(expectedKey1, orphanedKey));
        when(scheduler.unscheduleJob(orphanedKey)).thenReturn(true);

        runner.run(applicationArguments);

        verify(scheduler).unscheduleJob(orphanedKey);
    }

    @Test
    void shouldHandleMultipleOrphanedTriggers() throws Exception {
        TriggerKey expectedKey = new TriggerKey("expected", "camt-scheduling");
        TriggerKey orphanedKey1 = new TriggerKey("orphaned1", "camt-scheduling");
        TriggerKey orphanedKey2 = new TriggerKey("orphaned2", "camt-scheduling");

        when(expectedSchedule.triggers()).thenReturn(List.of(trigger1));
        when(trigger1.getKey()).thenReturn(expectedKey);
        when(scheduler.getTriggerKeys(any())).thenReturn(Set.of(expectedKey, orphanedKey1, orphanedKey2));
        when(scheduler.unscheduleJob(orphanedKey1)).thenReturn(true);
        when(scheduler.unscheduleJob(orphanedKey2)).thenReturn(true);

        runner.run(applicationArguments);

        verify(scheduler).unscheduleJob(orphanedKey1);
        verify(scheduler).unscheduleJob(orphanedKey2);
    }

    @Test
    void shouldHandleOrphanedTriggerAlreadyRemoved() throws Exception {
        TriggerKey expectedKey = new TriggerKey("expected", "camt-scheduling");
        TriggerKey orphanedKey = new TriggerKey("orphaned", "camt-scheduling");

        when(expectedSchedule.triggers()).thenReturn(List.of(trigger1));
        when(trigger1.getKey()).thenReturn(expectedKey);
        when(scheduler.getTriggerKeys(any())).thenReturn(Set.of(expectedKey, orphanedKey));
        when(scheduler.unscheduleJob(orphanedKey)).thenReturn(false); // Already removed

        runner.run(applicationArguments);

        verify(scheduler).unscheduleJob(orphanedKey);
        // Should not throw
    }

    @Test
    void shouldContinueOnSchedulerException() throws Exception {
        TriggerKey expectedKey = new TriggerKey("expected", "camt-scheduling");
        TriggerKey orphanedKey1 = new TriggerKey("orphaned1", "camt-scheduling");
        TriggerKey orphanedKey2 = new TriggerKey("orphaned2", "camt-scheduling");

        when(expectedSchedule.triggers()).thenReturn(List.of(trigger1));
        when(trigger1.getKey()).thenReturn(expectedKey);
        when(scheduler.getTriggerKeys(any())).thenReturn(Set.of(expectedKey, orphanedKey1, orphanedKey2));
        when(scheduler.unscheduleJob(orphanedKey1)).thenThrow(new org.quartz.SchedulerException("Test exception"));
        when(scheduler.unscheduleJob(orphanedKey2)).thenReturn(true);

        runner.run(applicationArguments);

        verify(scheduler).unscheduleJob(orphanedKey1);
        verify(scheduler).unscheduleJob(orphanedKey2);
        // Should not throw
    }
}
