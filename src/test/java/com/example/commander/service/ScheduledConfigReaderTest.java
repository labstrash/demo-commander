package com.example.commander.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commander.config.ReadLayerProperties;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.message.MessageAssemblyContext;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.TriggerType;
import com.example.commander.repository.ConfigurationReadRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ScheduledConfigReader}.
 *
 * <p>Covers the keyset-pagination driving loop: paging until a page comes back short of
 * the configured page size (or empty), advancing {@code lastSeenId} to the last row's ID
 * seen on each page, running fan-out per page rather than after accumulating the whole
 * job's trees (so memory stays bounded by page size on the assembly side too — see the
 * class Javadoc), and building the fan-out context with the placeholder recipient/trigger
 * described in {@code contextFor}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledConfigReaderTest {

    private static final String REPORT_TYPE = "CAMT054C";
    private static final String REPORT_FREQUENCY = "ONE_TIME_PER_DAY";
    private static final Instant WINDOW_START = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-02T00:00:00Z");

    @Mock
    private ConfigurationReadRepository repository;

    @Mock
    private FanOutAssemblyService fanOutAssemblyService;

    private ScheduledConfigReader reader;

    @BeforeEach
    void setUp() {
        ReadLayerProperties properties = new ReadLayerProperties();
        properties.setPageSize(2);
        reader = new ScheduledConfigReader(repository, fanOutAssemblyService, properties);
    }

    @Test
    void emptyFirstPageProducesZeroCountsAndNeverAssemblesTrees() {
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 2)).thenReturn(List.of());

        ScheduledConfigReader.ReadSummary summary =
                reader.readAssembleAndFanOut(REPORT_TYPE, REPORT_FREQUENCY, WINDOW_START, WINDOW_END);

        assertThat(summary.pageCount()).isZero();
        assertThat(summary.treeCount()).isZero();
        assertThat(summary.messageCount()).isZero();
        verify(repository, never()).assembleTrees(any());
        verify(fanOutAssemblyService, never()).assemble(any(), any());
    }

    @Test
    void singlePageShortOfPageSizeStopsAfterOnePage() {
        ReportConfigRow config1 = config(1L);
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 2)).thenReturn(List.of(config1));

        ReportConfigTree tree1 = new ReportConfigTree(config1, List.of());
        when(repository.assembleTrees(List.of(config1))).thenReturn(List.of(tree1));
        when(fanOutAssemblyService.assemble(eq(tree1), any())).thenReturn(List.of(message(), message()));

        ScheduledConfigReader.ReadSummary summary =
                reader.readAssembleAndFanOut(REPORT_TYPE, REPORT_FREQUENCY, WINDOW_START, WINDOW_END);

        assertThat(summary.pageCount()).isEqualTo(1);
        assertThat(summary.treeCount()).isEqualTo(1);
        assertThat(summary.messageCount()).isEqualTo(2);
        verify(repository, times(1)).findConfigPage(any(), any(), anyLong(), eq(2));
    }

    @Test
    void fullPageFollowedByShortPageAdvancesLastSeenIdAndAggregatesAcrossPages() {
        ReportConfigRow config1 = config(1L);
        ReportConfigRow config2 = config(2L);
        ReportConfigRow config3 = config(3L);

        // Page 1: full page of 2 -> loop continues, using config2.id() as the next lastSeenId.
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 2)).thenReturn(List.of(config1, config2));
        // Page 2: short page of 1 -> loop stops.
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 2L, 2)).thenReturn(List.of(config3));

        ReportConfigTree tree1 = new ReportConfigTree(config1, List.of());
        ReportConfigTree tree2 = new ReportConfigTree(config2, List.of());
        ReportConfigTree tree3 = new ReportConfigTree(config3, List.of());
        when(repository.assembleTrees(List.of(config1, config2))).thenReturn(List.of(tree1, tree2));
        when(repository.assembleTrees(List.of(config3))).thenReturn(List.of(tree3));

        when(fanOutAssemblyService.assemble(eq(tree1), any())).thenReturn(List.of(message()));
        when(fanOutAssemblyService.assemble(eq(tree2), any())).thenReturn(List.of(message(), message()));
        when(fanOutAssemblyService.assemble(eq(tree3), any())).thenReturn(List.of(message()));

        ScheduledConfigReader.ReadSummary summary =
                reader.readAssembleAndFanOut(REPORT_TYPE, REPORT_FREQUENCY, WINDOW_START, WINDOW_END);

        assertThat(summary.pageCount()).isEqualTo(2);
        assertThat(summary.treeCount()).isEqualTo(3);
        assertThat(summary.messageCount()).isEqualTo(4);

        verify(repository).findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 2);
        verify(repository).findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 2L, 2);
        verify(repository, times(1)).assembleTrees(List.of(config1, config2));
        verify(repository, times(1)).assembleTrees(List.of(config3));
    }

    @Test
    void emptyPageAfterAFullPageStopsWithoutAssemblingAnEmptyPage() {
        ReportConfigRow config1 = config(1L);
        ReportConfigRow config2 = config(2L);

        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 2)).thenReturn(List.of(config1, config2));
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 2L, 2)).thenReturn(List.of());

        ReportConfigTree tree1 = new ReportConfigTree(config1, List.of());
        ReportConfigTree tree2 = new ReportConfigTree(config2, List.of());
        when(repository.assembleTrees(List.of(config1, config2))).thenReturn(List.of(tree1, tree2));
        when(fanOutAssemblyService.assemble(any(), any())).thenReturn(List.of(message()));

        ScheduledConfigReader.ReadSummary summary =
                reader.readAssembleAndFanOut(REPORT_TYPE, REPORT_FREQUENCY, WINDOW_START, WINDOW_END);

        assertThat(summary.pageCount()).isEqualTo(1);
        assertThat(summary.treeCount()).isEqualTo(2);
        assertThat(summary.messageCount()).isEqualTo(2);
        verify(repository, times(1)).assembleTrees(any());
    }

    @Test
    void fanOutContextCarriesWindowAndPlaceholderRecipientPerTree() {
        ReportConfigRow config1 = config(1L);
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 2)).thenReturn(List.of(config1));

        ReportConfigTree tree1 = new ReportConfigTree(config1, List.of());
        when(repository.assembleTrees(List.of(config1))).thenReturn(List.of(tree1));
        when(fanOutAssemblyService.assemble(eq(tree1), any())).thenReturn(List.of());

        reader.readAssembleAndFanOut(REPORT_TYPE, REPORT_FREQUENCY, WINDOW_START, WINDOW_END);

        ArgumentCaptor<MessageAssemblyContext> contextCaptor = ArgumentCaptor.forClass(MessageAssemblyContext.class);
        verify(fanOutAssemblyService).assemble(eq(tree1), contextCaptor.capture());

        MessageAssemblyContext context = contextCaptor.getValue();
        assertThat(context.windowStartUtc()).isEqualTo(WINDOW_START);
        assertThat(context.windowEndUtc()).isEqualTo(WINDOW_END);
        assertThat(context.reportVersion()).isEqualTo(config1.reportVersion());
        assertThat(context.triggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(context.requestorName()).isNull();
        // Recipient type/value/name aren't resolved at this layer yet (see contextFor Javadoc).
        assertThat(context.recipient().id()).isEqualTo(config1.messageRecipientId());
        assertThat(context.recipient().type()).isEqualTo("UNRESOLVED");
    }

    private static ReportConfigRow config(long id) {
        return new ReportConfigRow(
                id,
                10_000_000 + (int) id,
                REPORT_TYPE,
                "1.0",
                REPORT_FREQUENCY,
                "desc",
                999L,
                "IBAN",
                true,
                false,
                false,
                true);
    }

    private static OutboundReportMessage message() {
        return new OutboundReportMessage(
                1L,
                12345678,
                0L,
                REPORT_TYPE,
                "1.0",
                REPORT_FREQUENCY,
                WINDOW_START,
                WINDOW_END,
                true,
                TriggerType.SCHEDULED,
                null,
                List.of(),
                List.of(),
                0,
                null);
    }
}
