package com.example.commander.batch.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commander.config.ReadLayerProperties;
import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeNode;
import com.example.commander.domain.config.PaymentTypeAssignmentNode;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.repository.ConfigurationReadRepository;
import com.example.commander.service.FanOutAssemblyService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Unit tests for {@link ReportPipelineItemReader}'s paging, drain-order checkpoint, and
 * zero-fan-out eager-fold behavior — the reader logic ported from {@code
 * ScheduledConfigReader}, plus its {@code ItemStream} checkpoint semantics.
 *
 * <p>{@link FanOutAssemblyService} is real (pure Java, no DB dependency); only the
 * repository is mocked, matching the DB-adjacent-but-not-DB-hitting pattern used
 * elsewhere in this test suite.
 */
@ExtendWith(MockitoExtension.class)
class ReportPipelineItemReaderTest {

    private static final String REPORT_TYPE = "CAMT054C";
    private static final String REPORT_FREQUENCY = "DAILY";
    private static final Instant WINDOW_START = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-02T00:00:00Z");

    @Mock
    private ConfigurationReadRepository repository;

    private final FanOutAssemblyService fanOutAssemblyService = new FanOutAssemblyService();

    private ReportPipelineItemReader newReader(int pageSize) {
        ReadLayerProperties properties = new ReadLayerProperties();
        properties.setPageSize(pageSize);
        return new ReportPipelineItemReader(
                repository, fanOutAssemblyService, properties, REPORT_TYPE, REPORT_FREQUENCY, WINDOW_START, WINDOW_END);
    }

    @Test
    void pagesUntilShortPageAndReturnsNullAtEnd() {
        ReportPipelineItemReader reader = newReader(2);
        reader.open(new ExecutionContext());

        ReportConfigRow row1 = zeroScopeConfig(1L);
        ReportConfigRow row2 = zeroScopeConfig(2L);
        ReportConfigRow row3 = zeroScopeConfig(3L);

        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 2)).thenReturn(List.of(row1, row2));
        when(repository.assembleTrees(List.of(row1, row2)))
                .thenReturn(List.of(zeroScopeTree(row1), zeroScopeTree(row2)));
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 2L, 2)).thenReturn(List.of(row3));
        when(repository.assembleTrees(List.of(row3))).thenReturn(List.of(zeroScopeTree(row3)));

        assertThat(reader.read().payload().configId()).isEqualTo(10000001);
        assertThat(reader.read().payload().configId()).isEqualTo(10000002);
        assertThat(reader.read().payload().configId()).isEqualTo(10000003);
        assertThat(reader.read()).isNull();

        ExecutionContext checkpoint = new ExecutionContext();
        reader.update(checkpoint);
        assertThat(checkpoint.getLong("reportPipeline.lastSeenId")).isEqualTo(3L);
    }

    @Test
    void checkpointOnlyAdvancesOnceATreeIsFullyDrained() {
        ReportPipelineItemReader reader = newReader(1);

        ReportConfigRow row = unbundledConfig(10L);
        ReportConfigTree tree = unbundledTreeWithTwoAccounts(row);

        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 1)).thenReturn(List.of(row));
        when(repository.assembleTrees(List.of(row))).thenReturn(List.of(tree));

        reader.read(); // first of the tree's two messages

        ExecutionContext midDrain = new ExecutionContext();
        reader.update(midDrain);
        assertThat(midDrain.getLong("reportPipeline.lastSeenId"))
                .describedAs("checkpoint must not advance while the tree still has unread buffered items")
                .isEqualTo(0L);

        reader.read(); // second and last message - tree is now fully drained

        ExecutionContext fullyDrained = new ExecutionContext();
        reader.update(fullyDrained);
        assertThat(fullyDrained.getLong("reportPipeline.lastSeenId")).isEqualTo(10L);
    }

    @Test
    void leadingRunOfZeroFanOutTreesAdvancesEagerlyWithoutBeingRead() {
        // pageSize 4 > the 3 rows returned, so this is a short page - noMorePages is set on
        // the first fetch and no second page fetch ever happens.
        ReportPipelineItemReader reader = newReader(4);

        ReportConfigRow zero1 = zeroFanOutConfig(10L);
        ReportConfigRow zero2 = zeroFanOutConfig(20L);
        ReportConfigRow real = zeroScopeConfig(30L);

        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 4)).thenReturn(List.of(zero1, zero2, real));
        when(repository.assembleTrees(List.of(zero1, zero2, real)))
                .thenReturn(List.of(zeroFanOutTree(zero1), zeroFanOutTree(zero2), zeroScopeTree(real)));

        PipelineReportMessage message = reader.read();

        assertThat(message.payload().configId()).isEqualTo(10000030);

        ExecutionContext checkpoint = new ExecutionContext();
        reader.update(checkpoint);
        assertThat(checkpoint.getLong("reportPipeline.lastSeenId"))
                .describedAs("both zero-fan-out trees, plus the just-drained real tree, must be folded in")
                .isEqualTo(30L);
    }

    @Test
    void midPageRunOfZeroFanOutTreesFoldsInAfterPrecedingTreeDrains() {
        // pageSize 4 > the 3 rows returned, so this is a short page - noMorePages is set on
        // the first fetch and no second page fetch ever happens.
        ReportPipelineItemReader reader = newReader(4);

        ReportConfigRow first = zeroScopeConfig(10L);
        ReportConfigRow zero = zeroFanOutConfig(20L);
        ReportConfigRow last = zeroScopeConfig(30L);

        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 4)).thenReturn(List.of(first, zero, last));
        when(repository.assembleTrees(List.of(first, zero, last)))
                .thenReturn(List.of(zeroScopeTree(first), zeroFanOutTree(zero), zeroScopeTree(last)));

        assertThat(reader.read().payload().configId()).isEqualTo(10000010);

        ExecutionContext afterFirstRead = new ExecutionContext();
        reader.update(afterFirstRead);
        assertThat(afterFirstRead.getLong("reportPipeline.lastSeenId"))
                .describedAs("the zero-fan-out tree right after the drained one must fold in immediately")
                .isEqualTo(20L);

        assertThat(reader.read().payload().configId()).isEqualTo(10000030);
    }

    @Test
    void restartResumesFromPersistedCheckpointWithoutReReadingDrainedPages() {
        ReportPipelineItemReader firstReader = newReader(1);
        ReportConfigRow row = zeroScopeConfig(5L);

        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 0L, 1)).thenReturn(List.of(row));
        when(repository.assembleTrees(List.of(row))).thenReturn(List.of(zeroScopeTree(row)));

        firstReader.open(new ExecutionContext());
        firstReader.read();

        ExecutionContext persisted = new ExecutionContext();
        firstReader.update(persisted);
        assertThat(persisted.getLong("reportPipeline.lastSeenId")).isEqualTo(5L);

        ReportPipelineItemReader restartedReader = newReader(1);
        when(repository.findConfigPage(REPORT_TYPE, REPORT_FREQUENCY, 5L, 1)).thenReturn(List.of());

        restartedReader.open(persisted);
        assertThat(restartedReader.read()).isNull();

        verify(repository).findConfigPage(eq(REPORT_TYPE), eq(REPORT_FREQUENCY), eq(5L), eq(1));
    }

    private static ReportConfigRow zeroScopeConfig(long id) {
        return new ReportConfigRow(
                id,
                (int) (10000000 + id),
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

    private static ReportConfigRow zeroFanOutConfig(long id) {
        return unbundledConfig(id);
    }

    private static ReportConfigRow unbundledConfig(long id) {
        return new ReportConfigRow(
                id,
                (int) (10000000 + id),
                REPORT_TYPE,
                "1.0",
                REPORT_FREQUENCY,
                "desc",
                999L,
                "IBAN",
                true,
                false,
                false,
                false);
    }

    private static ReportConfigTree zeroScopeTree(ReportConfigRow row) {
        return new ReportConfigTree(row, List.of());
    }

    /**
     * A tree with a non-empty scopes list (so it doesn't hit the zero-scope short-circuit,
     * which always yields exactly one config-only message) whose sole scope has no payment
     * type assignments at all — the unbundled fan-out path then has nothing to iterate over
     * and genuinely produces zero messages.
     */
    private static ReportConfigTree zeroFanOutTree(ReportConfigRow row) {
        AgreementScopeNode emptyScope = new AgreementScopeNode(900L + row.id(), row.id(), "Empty Scope", List.of());
        return new ReportConfigTree(row, List.of(emptyScope));
    }

    private static ReportConfigTree unbundledTreeWithTwoAccounts(ReportConfigRow row) {
        AccountAssignmentRow account1 = new AccountAssignmentRow(1L, 1L, "1234", "5678901", null, "SEK");
        AccountAssignmentRow account2 = new AccountAssignmentRow(2L, 1L, "1235", "5678902", null, "SEK");
        PaymentTypeAssignmentNode assignment =
                new PaymentTypeAssignmentNode(1L, 101L, "SWISH", List.of(account1, account2), List.of());
        AgreementScopeNode scope = new AgreementScopeNode(101L, row.id(), "Scope A", List.of(assignment));
        return new ReportConfigTree(row, List.of(scope));
    }
}
