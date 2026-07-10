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
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FanOutAssemblyServiceTest {

    private final FanOutAssemblyService service = new FanOutAssemblyService();

    @Test
    void zeroScopeProducesSingleConfigOnlyMessage() {
        ReportConfigTree tree = new ReportConfigTree(config(true), List.of());

        List<OutboundReportMessage> messages = service.assemble(tree, context());

        assertThat(messages).hasSize(1);
        OutboundReportMessage message = messages.get(0);
        assertThat(message.isConfigOnly()).isTrue();
        assertThat(message.agreementScopeId()).isEqualTo(FanOutAssemblyService.NO_SINGLE_SCOPE_SENTINEL);
        // IsBundled is still stamped through even though it drives no fan-out here
        assertThat(message.isBundled()).isTrue();
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

        List<OutboundReportMessage> messages = service.assemble(tree, context());

        assertThat(messages).hasSize(1);
        OutboundReportMessage message = messages.get(0);
        assertThat(message.accounts()).hasSize(1);
        assertThat(message.aliases()).hasSize(1);
        assertThat(message.paymentTypeCount()).isEqualTo(2);
        assertThat(message.agreementScopeId()).isEqualTo(FanOutAssemblyService.NO_SINGLE_SCOPE_SENTINEL);
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

        List<OutboundReportMessage> messages = service.assemble(tree, context());

        // total message count = sum of every account/alias row
        assertThat(messages).hasSize(3);
        assertThat(messages).allMatch(m -> m.paymentTypeCount() == 1);

        // lineage: each message's agreementScopeId matches the scope it actually came from,
        // not just message count ("how lineage survives flattening")
        assertThat(messages).filteredOn(m -> !m.accounts().isEmpty()).allMatch(m -> m.agreementScopeId() == 101L);
        assertThat(messages).filteredOn(m -> !m.aliases().isEmpty()).allMatch(m -> m.agreementScopeId() == 102L);
    }

    @Test
    void danglingAssignmentWithNeitherAccountsNorAliasesIsSkippedNotCounted() {
        PaymentTypeAssignmentNode danglingAssignment = new PaymentTypeAssignmentNode(1L, 101L, "SWISH", null, null);

        ReportConfigTree tree = new ReportConfigTree(
                config(true), List.of(new AgreementScopeNode(101L, 1L, "Scope A", List.of(danglingAssignment))));

        List<OutboundReportMessage> messages = service.assemble(tree, context());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).paymentTypeCount()).isZero();
    }

    @Test
    void paymentTypeAssignmentRejectsBothAccountsAndAliases() {
        assertThatThrownBy(() -> new PaymentTypeAssignmentNode(
                        1L, 101L, "SWISH", List.of(account(1L, 1L)), List.of(alias(2L, 1L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutual-exclusivity");
    }

    private static ReportConfigRow config(boolean bundled) {
        return new ReportConfigRow(
                1L, 12345678, "CAMT054C", "1.0", "ONE_TIME_PER_DAY", "desc", 999L, "IBAN", true, false, false, bundled);
    }

    private static AccountAssignmentRow account(long id, long assignmentId) {
        return new AccountAssignmentRow(id, assignmentId, "1234", "5678901", null, "SEK");
    }

    private static AliasAssignmentRow alias(long id, long assignmentId) {
        return new AliasAssignmentRow(id, assignmentId, "ALIAS-" + id);
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
