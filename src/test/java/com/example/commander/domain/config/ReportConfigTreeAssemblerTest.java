package com.example.commander.domain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReportConfigTreeAssembler}.
 *
 * <p>Covers the tree-assembly edge cases: zero-scope configs, multi-scope fan-in,
 * dangling payment-type assignments (neither accounts nor aliases), the account/alias
 * mutual-exclusivity invariant, and correct grouping isolation across multiple configs
 * read on the same page.
 */
class ReportConfigTreeAssemblerTest {

    @Test
    void zeroScopeConfigProducesTreeWithEmptyScopeList() {
        ReportConfigRow config = config(1L, "CAMT054D");

        List<ReportConfigTree> trees =
                ReportConfigTreeAssembler.assemble(List.of(config), List.of(), List.of(), List.of(), List.of());

        assertThat(trees).hasSize(1);
        assertThat(trees.getFirst().isZeroScope()).isTrue();
        assertThat(trees.getFirst().scopes()).isEmpty();
        assertThat(trees.getFirst().config()).isEqualTo(config);
    }

    @Test
    void emptyConfigListProducesEmptyTreeList() {
        List<ReportConfigTree> trees =
                ReportConfigTreeAssembler.assemble(List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(trees).isEmpty();
    }

    @Test
    void multiScopeFanInAssemblesFullHierarchyForSingleConfig() {
        ReportConfigRow config = config(1L, "CAMT054C");

        AgreementScopeRow scopeA = new AgreementScopeRow(101L, 1L, "Scope A");
        AgreementScopeRow scopeB = new AgreementScopeRow(102L, 1L, "Scope B");

        PaymentTypeAssignmentRow assignmentA1 = new PaymentTypeAssignmentRow(201L, 101L, "SWISH");
        PaymentTypeAssignmentRow assignmentB1 = new PaymentTypeAssignmentRow(202L, 102L, "BG");

        AccountAssignmentRow account1 = new AccountAssignmentRow(301L, 201L, "1234", "5678901", null, "SEK");
        AccountAssignmentRow account2 = new AccountAssignmentRow(302L, 201L, "1234", "5678902", null, "SEK");
        AliasAssignmentRow alias1 = new AliasAssignmentRow(401L, 202L, "ALIAS-1");

        List<ReportConfigTree> trees = ReportConfigTreeAssembler.assemble(
                List.of(config),
                List.of(scopeA, scopeB),
                List.of(assignmentA1, assignmentB1),
                List.of(account1, account2),
                List.of(alias1));

        assertThat(trees).hasSize(1);
        ReportConfigTree tree = trees.getFirst();
        assertThat(tree.isZeroScope()).isFalse();
        assertThat(tree.scopes()).hasSize(2);

        AgreementScopeNode resolvedScopeA =
                tree.scopes().stream().filter(s -> s.id() == 101L).findFirst().orElseThrow();
        assertThat(resolvedScopeA.paymentTypeAssignments()).hasSize(1);
        PaymentTypeAssignmentNode resolvedAssignmentA1 =
                resolvedScopeA.paymentTypeAssignments().getFirst();
        assertThat(resolvedAssignmentA1.isAccountAssignment()).isTrue();
        assertThat(resolvedAssignmentA1.accounts()).containsExactly(account1, account2);
        assertThat(resolvedAssignmentA1.aliases()).isEmpty();

        AgreementScopeNode resolvedScopeB =
                tree.scopes().stream().filter(s -> s.id() == 102L).findFirst().orElseThrow();
        PaymentTypeAssignmentNode resolvedAssignmentB1 =
                resolvedScopeB.paymentTypeAssignments().getFirst();
        assertThat(resolvedAssignmentB1.isAliasAssignment()).isTrue();
        assertThat(resolvedAssignmentB1.aliases()).containsExactly(alias1);
        assertThat(resolvedAssignmentB1.accounts()).isEmpty();
    }

    @Test
    void danglingPaymentTypeAssignmentWithNoAccountsOrAliasesIsIncludedAsEmptyNode() {
        ReportConfigRow config = config(1L, "CAMT054C");
        AgreementScopeRow scope = new AgreementScopeRow(101L, 1L, "Scope A");
        PaymentTypeAssignmentRow danglingAssignment = new PaymentTypeAssignmentRow(201L, 101L, "SWISH");

        List<ReportConfigTree> trees = ReportConfigTreeAssembler.assemble(
                List.of(config), List.of(scope), List.of(danglingAssignment), List.of(), List.of());

        assertThat(trees).hasSize(1);
        List<PaymentTypeAssignmentNode> assignments =
                trees.getFirst().scopes().getFirst().paymentTypeAssignments();
        assertThat(assignments).hasSize(1);
        assertThat(assignments.getFirst().isEmpty()).isTrue();
        assertThat(assignments.getFirst().isAccountAssignment()).isFalse();
        assertThat(assignments.getFirst().isAliasAssignment()).isFalse();
    }

    @Test
    void scopeWithNoPaymentTypeAssignmentsProducesEmptyAssignmentList() {
        ReportConfigRow config = config(1L, "CAMT054C");
        AgreementScopeRow scope = new AgreementScopeRow(101L, 1L, "Scope A");

        List<ReportConfigTree> trees =
                ReportConfigTreeAssembler.assemble(List.of(config), List.of(scope), List.of(), List.of(), List.of());

        assertThat(trees.getFirst().scopes()).hasSize(1);
        assertThat(trees.getFirst().scopes().getFirst().paymentTypeAssignments())
                .isEmpty();
    }

    @Test
    void invariantViolatingAssignmentWithBothAccountsAndAliasesThrows() {
        ReportConfigRow config = config(1L, "CAMT054C");
        AgreementScopeRow scope = new AgreementScopeRow(101L, 1L, "Scope A");
        PaymentTypeAssignmentRow assignment = new PaymentTypeAssignmentRow(201L, 101L, "SWISH");
        AccountAssignmentRow account = new AccountAssignmentRow(301L, 201L, "1234", "5678901", null, "SEK");
        AliasAssignmentRow alias = new AliasAssignmentRow(401L, 201L, "ALIAS-1");

        assertThatThrownBy(() -> ReportConfigTreeAssembler.assemble(
                        List.of(config), List.of(scope), List.of(assignment), List.of(account), List.of(alias)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutual-exclusivity");
    }

    @Test
    void rowsAreGroupedIndependentlyPerConfigOnTheSamePage() {
        ReportConfigRow config1 = config(1L, "CAMT054C");
        ReportConfigRow config2 = config(2L, "CAMT054C");

        AgreementScopeRow scopeForConfig1 = new AgreementScopeRow(101L, 1L, "Scope A");
        AgreementScopeRow scopeForConfig2 = new AgreementScopeRow(102L, 2L, "Scope B");

        PaymentTypeAssignmentRow assignmentForScope1 = new PaymentTypeAssignmentRow(201L, 101L, "SWISH");
        PaymentTypeAssignmentRow assignmentForScope2 = new PaymentTypeAssignmentRow(202L, 102L, "BG");

        AccountAssignmentRow accountForAssignment1 =
                new AccountAssignmentRow(301L, 201L, "1234", "5678901", null, "SEK");
        AliasAssignmentRow aliasForAssignment2 = new AliasAssignmentRow(401L, 202L, "ALIAS-1");

        List<ReportConfigTree> trees = ReportConfigTreeAssembler.assemble(
                List.of(config1, config2),
                List.of(scopeForConfig1, scopeForConfig2),
                List.of(assignmentForScope1, assignmentForScope2),
                List.of(accountForAssignment1),
                List.of(aliasForAssignment2));

        assertThat(trees).hasSize(2);

        ReportConfigTree tree1 =
                trees.stream().filter(t -> t.config().id() == 1L).findFirst().orElseThrow();
        ReportConfigTree tree2 =
                trees.stream().filter(t -> t.config().id() == 2L).findFirst().orElseThrow();

        assertThat(tree1.scopes()).hasSize(1);
        assertThat(tree1.scopes().getFirst().paymentTypeAssignments().getFirst().accounts())
                .containsExactly(accountForAssignment1);
        assertThat(tree1.scopes().getFirst().paymentTypeAssignments().getFirst().aliases())
                .isEmpty();

        assertThat(tree2.scopes()).hasSize(1);
        assertThat(tree2.scopes().getFirst().paymentTypeAssignments().getFirst().aliases())
                .containsExactly(aliasForAssignment2);
        assertThat(tree2.scopes().getFirst().paymentTypeAssignments().getFirst().accounts())
                .isEmpty();
    }

    @Test
    void configOrderInResultMatchesInputConfigOrder() {
        ReportConfigRow config1 = config(1L, "CAMT054C");
        ReportConfigRow config2 = config(2L, "CAMT054C");
        ReportConfigRow config3 = config(3L, "CAMT054C");

        List<ReportConfigTree> trees = ReportConfigTreeAssembler.assemble(
                List.of(config1, config2, config3), List.of(), List.of(), List.of(), List.of());

        assertThat(trees).extracting(t -> t.config().id()).containsExactly(1L, 2L, 3L);
    }

    private static ReportConfigRow config(long id, String reportType) {
        return new ReportConfigRow(
                id,
                10_000_000 + (int) id,
                reportType,
                "1.0",
                "ONE_TIME_PER_DAY",
                "desc",
                999L,
                "IBAN",
                true,
                false,
                false,
                true);
    }
}
