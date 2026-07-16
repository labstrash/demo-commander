package com.example.commander.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeNode;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentNode;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.message.MessageAssemblyContext;
import com.example.commander.domain.message.PaymentTypeAllocation;
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FanOutAssemblyServiceTest {

    private final FanOutAssemblyService service = new FanOutAssemblyService();

    @Test
    void zeroScopeProducesSingleConfigOnlyMessage() {
        ReportConfigTree tree = new ReportConfigTree(config(true), List.of());

        List<PipelineReportMessage> messages = service.assemble(tree, context());

        assertThat(messages).hasSize(1);
        PipelineReportMessage message = messages.get(0);
        assertThat(message.payload().isConfigOnly()).isTrue();
        // IsBundled is still stamped through even though it drives no fan-out here
        assertThat(message.payload().isBundled()).isTrue();
        assertThat(message.agreementScopeId()).isNull();
        assertThat(message.reportConfigId()).isEqualTo(1L);
    }

    @Test
    void bundledMergesAllAccountsAndAliasesAcrossScopesIntoOneMessage() {
        PaymentTypeAssignmentNode scope1Assignment =
                new PaymentTypeAssignmentNode(1L, 101L, "SWISH", List.of(account(1L, 1L)), List.of());
        PaymentTypeAssignmentNode scope2Assignment =
                new PaymentTypeAssignmentNode(2L, 102L, "BG", List.of(), List.of(alias(2L, 2L)));

        ReportConfigTree tree = new ReportConfigTree(
                config(true),
                List.of(
                        new AgreementScopeNode(101L, 1L, "Scope A", List.of(scope1Assignment)),
                        new AgreementScopeNode(102L, 1L, "Scope B", List.of(scope2Assignment))));

        List<PipelineReportMessage> messages = service.assemble(tree, context());

        assertThat(messages).hasSize(1);
        PipelineReportMessage message = messages.get(0);
        assertThat(message.payload().paymentTypeCount()).isEqualTo(2);
        assertThat(message.payload().accountCount()).isEqualTo(1);
        assertThat(message.agreementScopeId()).isNull();

        PaymentTypeAllocation swishAllocation = allocationFor(message, "SWISH");
        assertThat(swishAllocation.accounts()).hasSize(1);
        assertThat(swishAllocation.aliases()).isEmpty();

        PaymentTypeAllocation bgAllocation = allocationFor(message, "BG");
        assertThat(bgAllocation.aliases()).hasSize(1);
        assertThat(bgAllocation.accounts()).isEmpty();
    }

    @Test
    void bundledMergesSamePaymentTypeAcrossScopesIntoOneAllocation() {
        PaymentTypeAssignmentNode scope1Assignment =
                new PaymentTypeAssignmentNode(1L, 101L, "SWISH", List.of(account(1L, 1L)), List.of());
        PaymentTypeAssignmentNode scope2Assignment =
                new PaymentTypeAssignmentNode(2L, 102L, "SWISH", List.of(account(2L, 2L)), List.of());

        ReportConfigTree tree = new ReportConfigTree(
                config(true),
                List.of(
                        new AgreementScopeNode(101L, 1L, "Scope A", List.of(scope1Assignment)),
                        new AgreementScopeNode(102L, 1L, "Scope B", List.of(scope2Assignment))));

        List<PipelineReportMessage> messages = service.assemble(tree, context());

        assertThat(messages).hasSize(1);
        PipelineReportMessage message = messages.get(0);
        // both scopes' SWISH assignments merge into a single allocation, not two
        assertThat(message.payload().paymentTypeCount()).isEqualTo(1);
        assertThat(allocationFor(message, "SWISH").accounts()).hasSize(2);
    }

    @Test
    void unbundledProducesOneMessagePerAccountOrAliasRow() {
        PaymentTypeAssignmentNode scope1Assignment =
                new PaymentTypeAssignmentNode(1L, 101L, "SWISH", List.of(account(1L, 1L), account(2L, 1L)), List.of());
        PaymentTypeAssignmentNode scope2Assignment =
                new PaymentTypeAssignmentNode(2L, 102L, "BG", List.of(), List.of(alias(3L, 2L)));

        ReportConfigTree tree = new ReportConfigTree(
                config(false),
                List.of(
                        new AgreementScopeNode(101L, 1L, "Scope A", List.of(scope1Assignment)),
                        new AgreementScopeNode(102L, 1L, "Scope B", List.of(scope2Assignment))));

        List<PipelineReportMessage> messages = service.assemble(tree, context());

        // total message count = sum of every account/alias row
        assertThat(messages).hasSize(3);
        assertThat(messages).allMatch(m -> m.payload().paymentTypeCount() == 1);

        // lineage: each message's agreementScopeId matches the scope it actually came from
        assertThat(messages)
                .filteredOn(FanOutAssemblyServiceTest::hasAccounts)
                .allMatch(m -> m.agreementScopeId() == 101L);
        assertThat(messages)
                .filteredOn(FanOutAssemblyServiceTest::hasAliases)
                .allMatch(m -> m.agreementScopeId() == 102L);

        // each unbundled message's single allocation still carries the originating payment type
        assertThat(messages).filteredOn(FanOutAssemblyServiceTest::hasAccounts).allMatch(m -> m.payload()
                .paymentTypeAllocations()
                .get(0)
                .paymentType()
                .equals("SWISH"));
        assertThat(messages).filteredOn(FanOutAssemblyServiceTest::hasAliases).allMatch(m -> m.payload()
                .paymentTypeAllocations()
                .get(0)
                .paymentType()
                .equals("BG"));

        // same-scope collision fix: two unbundled messages from scope 101 (different accounts)
        // must not derive the same correlationId
        Set<String> correlationIdsFromScope101 = messages.stream()
                .filter(FanOutAssemblyServiceTest::hasAccounts)
                .map(m -> m.payload().correlationId())
                .collect(Collectors.toSet());
        assertThat(correlationIdsFromScope101).hasSize(2);
    }

    @Test
    void danglingAssignmentWithNeitherAccountsNorAliasesIsSkippedNotCounted() {
        PaymentTypeAssignmentNode danglingAssignment = new PaymentTypeAssignmentNode(1L, 101L, "SWISH", null, null);

        ReportConfigTree tree = new ReportConfigTree(
                config(true), List.of(new AgreementScopeNode(101L, 1L, "Scope A", List.of(danglingAssignment))));

        List<PipelineReportMessage> messages = service.assemble(tree, context());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).payload().paymentTypeCount()).isZero();
    }

    @Test
    void paymentTypeAssignmentRejectsBothAccountsAndAliases() {
        assertThatThrownBy(() -> new PaymentTypeAssignmentNode(
                        1L, 101L, "SWISH", List.of(account(1L, 1L)), List.of(alias(2L, 1L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutual-exclusivity");
    }

    @Test
    void correlationIdIsDeterministicAcrossSeparateAssembleCalls() {
        ReportConfigTree tree = new ReportConfigTree(config(true), List.of());

        String first = service.assemble(tree, context()).get(0).payload().correlationId();
        String second = service.assemble(tree, context()).get(0).payload().correlationId();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void messageIdIsNonNullAndWithinLengthBudgetForAnOrdinaryReportType() {
        ReportConfigTree tree = new ReportConfigTree(config(true), List.of());

        String messageId = service.assemble(tree, context()).get(0).payload().messageId();

        assertThat(messageId).isNotBlank().hasSizeLessThan(35).startsWith("FIKASE");
    }

    @Test
    void messageIdGenerationFailsLoudlyWhenTheGeneratedIdWouldReachTheLengthCeiling() {
        // CAMT052BT's report-type-derived segment is 4 characters, one more than every other
        // configured report type (3) — pushes the generated ID to exactly 35 characters,
        // right at (not under) ReportMessageIdGenerator's own documented ceiling.
        ReportConfigRow longSuffixConfig = new ReportConfigRow(
                1L, 12345678, "CAMT052BT", "1.0", "EVERY_30_MIN", "desc", 999L, "IBAN", true, false, false, true);
        ReportConfigTree tree = new ReportConfigTree(longSuffixConfig, List.of());

        assertThatThrownBy(() -> service.assemble(tree, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAMT052BT");
    }

    private static ReportConfigRow config(boolean bundled) {
        return new ReportConfigRow(
                1L, 12345678, "CAMT054C", "1.0", "ONE_TIME_PER_DAY", "desc", 999L, "IBAN", true, false, false, bundled);
    }

    private static AccountAssignmentRow account(long id, long assignmentId) {
        return new AccountAssignmentRow(id, assignmentId, "1234", "5678901" + id, null, "SEK");
    }

    private static AliasAssignmentRow alias(long id, long assignmentId) {
        return new AliasAssignmentRow(id, assignmentId, "ALIAS-" + id);
    }

    private static PaymentTypeAllocation allocationFor(PipelineReportMessage message, String paymentType) {
        return message.payload().paymentTypeAllocations().stream()
                .filter(allocation -> allocation.paymentType().equals(paymentType))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no allocation for payment type " + paymentType));
    }

    private static boolean hasAccounts(PipelineReportMessage message) {
        return message.payload().paymentTypeAllocations().stream()
                .anyMatch(allocation -> !allocation.accounts().isEmpty());
    }

    private static boolean hasAliases(PipelineReportMessage message) {
        return message.payload().paymentTypeAllocations().stream()
                .anyMatch(allocation -> !allocation.aliases().isEmpty());
    }

    private static MessageAssemblyContext context() {
        return new MessageAssemblyContext(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"),
                "1.0",
                TriggerType.SCHEDULED,
                new RecipientRef(999L, "BIC", "SOMEBIC", "Some Recipient"),
                null);
    }
}
