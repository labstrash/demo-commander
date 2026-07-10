package com.example.commander.domain.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Assembles flat database rows into a hierarchical report configuration tree.
 *
 * <p>Transforms the result of staged sub-reads into a structured tree:
 * ReportConfig → AgreementScopes → PaymentTypeAssignments → Accounts/Aliases.
 *
 * <p>This assembler is pure Java with no database dependencies, making it
 * easily testable without a database or Spring context.
 *
 * <p>The assembly process:
 * <ol>
 *   <li>Group rows by their parent IDs (accounts → assignments, assignments → scopes, scopes → configs)</li>
 *   <li>Build the tree bottom-up: configs → scopes → assignments → account/alias rows</li>
 *   <li>Each PaymentTypeAssignmentNode enforces the accounts/aliases mutual exclusivity</li>
 * </ol>
 */
public final class ReportConfigTreeAssembler {

    private ReportConfigTreeAssembler() {}

    /**
     * Assembles flat row lists into a list of complete configuration trees.
     *
     * @param configs report configuration rows
     * @param scopes agreement scope rows
     * @param assignments payment type assignment rows
     * @param accounts account assignment rows
     * @param aliases alias assignment rows
     * @return list of fully assembled report configuration trees
     */
    public static List<ReportConfigTree> assemble(
            List<ReportConfigRow> configs,
            List<AgreementScopeRow> scopes,
            List<PaymentTypeAssignmentRow> assignments,
            List<AccountAssignmentRow> accounts,
            List<AliasAssignmentRow> aliases) {

        // Group accounts and aliases by their parent assignment ID
        Map<Long, List<AccountAssignmentRow>> accountsByAssignmentId =
                accounts.stream().collect(Collectors.groupingBy(AccountAssignmentRow::paymentTypeAssignmentId));

        Map<Long, List<AliasAssignmentRow>> aliasesByAssignmentId =
                aliases.stream().collect(Collectors.groupingBy(AliasAssignmentRow::paymentTypeAssignmentId));

        // Group assignments and scopes by their parent ID
        Map<Long, List<PaymentTypeAssignmentRow>> assignmentsByScopeId =
                assignments.stream().collect(Collectors.groupingBy(PaymentTypeAssignmentRow::agreementScopeId));

        Map<Long, List<AgreementScopeRow>> scopesByConfigId =
                scopes.stream().collect(Collectors.groupingBy(AgreementScopeRow::reportConfigId));

        // Build the tree for each configuration
        List<ReportConfigTree> trees = new ArrayList<>(configs.size());
        for (ReportConfigRow config : configs) {
            List<AgreementScopeRow> configScopes = scopesByConfigId.getOrDefault(config.id(), List.of());
            List<AgreementScopeNode> scopeNodes = new ArrayList<>(configScopes.size());

            for (AgreementScopeRow scope : configScopes) {
                List<PaymentTypeAssignmentRow> scopeAssignments =
                        assignmentsByScopeId.getOrDefault(scope.id(), List.of());
                List<PaymentTypeAssignmentNode> assignmentNodes = new ArrayList<>(scopeAssignments.size());

                for (PaymentTypeAssignmentRow assignment : scopeAssignments) {
                    assignmentNodes.add(new PaymentTypeAssignmentNode(
                            assignment.id(),
                            assignment.agreementScopeId(),
                            assignment.paymentType(),
                            accountsByAssignmentId.getOrDefault(assignment.id(), List.of()),
                            aliasesByAssignmentId.getOrDefault(assignment.id(), List.of())));
                }

                scopeNodes.add(
                        new AgreementScopeNode(scope.id(), scope.reportConfigId(), scope.name(), assignmentNodes));
            }

            trees.add(new ReportConfigTree(config, scopeNodes));
        }

        return trees;
    }
}
