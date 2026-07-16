package com.example.commander.service;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeNode;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentNode;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.message.AccountAllocation;
import com.example.commander.domain.message.AliasAllocation;
import com.example.commander.domain.message.MessageAssemblyContext;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.PaymentTypeAllocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Assembles report configuration trees into outbound messages.
 *
 * <p>Transforms a {@link ReportConfigTree} into one or more {@link OutboundReportMessage}s
 * based on the configuration's bundling rules and scope assignments.
 *
 * <p>Three assembly strategies:
 * <ol>
 *   <li><b>Zero scopes:</b> Single config-only message with no account/alias data</li>
 *   <li><b>Bundled ({@code isBundled = true}):</b> One message per config, merging all
 *       accounts and aliases across all scopes and payment types</li>
 *   <li><b>Unbundled ({@code isBundled = false}):</b> One message per account or alias row</li>
 * </ol>
 *
 * <p>This service is pure Java with no database dependencies, making it easily testable
 * without a database or Spring context.
 */
@Component
public class FanOutAssemblyService {

    /**
     * Assembles a configuration tree into outbound messages.
     *
     * @param tree the assembled report configuration tree
     * @param context the message assembly context
     * @return list of outbound messages (one or more based on bundling rules)
     * @throws NullPointerException if tree or context is null
     */
    public List<OutboundReportMessage> assemble(ReportConfigTree tree, MessageAssemblyContext context) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(context, "context");

        if (tree.isZeroScope()) {
            return List.of(configOnlyMessage(tree, context));
        }

        return tree.config().isBundled() ? List.of(bundledMessage(tree, context)) : unbundledMessages(tree, context);
    }

    private OutboundReportMessage configOnlyMessage(ReportConfigTree tree, MessageAssemblyContext context) {
        return newMessage(tree, context, List.of());
    }

    private OutboundReportMessage bundledMessage(ReportConfigTree tree, MessageAssemblyContext context) {
        // LinkedHashMap: preserves first-seen payment type order across scopes for stable output.
        Map<String, List<AccountAllocation>> accountsByType = new LinkedHashMap<>();
        Map<String, List<AliasAllocation>> aliasesByType = new LinkedHashMap<>();

        for (AgreementScopeNode scope : tree.scopes()) {
            for (PaymentTypeAssignmentNode assignment : scope.paymentTypeAssignments()) {
                if (assignment.isAccountAssignment()) {
                    accountsByType
                            .computeIfAbsent(assignment.paymentType(), key -> new ArrayList<>())
                            .addAll(toAccountAllocations(assignment.accounts()));
                } else if (assignment.isAliasAssignment()) {
                    aliasesByType
                            .computeIfAbsent(assignment.paymentType(), key -> new ArrayList<>())
                            .addAll(toAliasAllocations(assignment.aliases()));
                }
            }
        }

        Set<String> paymentTypes = new LinkedHashSet<>(accountsByType.keySet());
        paymentTypes.addAll(aliasesByType.keySet());

        List<PaymentTypeAllocation> allocations = paymentTypes.stream()
                .map(paymentType -> new PaymentTypeAllocation(
                        paymentType,
                        accountsByType.getOrDefault(paymentType, List.of()),
                        aliasesByType.getOrDefault(paymentType, List.of())))
                .toList();

        return newMessage(tree, context, allocations);
    }

    private List<OutboundReportMessage> unbundledMessages(ReportConfigTree tree, MessageAssemblyContext context) {
        List<OutboundReportMessage> messages = new ArrayList<>();

        for (AgreementScopeNode scope : tree.scopes()) {
            for (PaymentTypeAssignmentNode assignment : scope.paymentTypeAssignments()) {
                for (AccountAssignmentRow account : assignment.accounts()) {
                    PaymentTypeAllocation allocation = new PaymentTypeAllocation(
                            assignment.paymentType(), toAccountAllocations(List.of(account)), List.of());
                    messages.add(newMessage(tree, context, List.of(allocation)));
                }
                for (AliasAssignmentRow alias : assignment.aliases()) {
                    PaymentTypeAllocation allocation = new PaymentTypeAllocation(
                            assignment.paymentType(), List.of(), toAliasAllocations(List.of(alias)));
                    messages.add(newMessage(tree, context, List.of(allocation)));
                }
            }
        }

        return messages;
    }

    private static List<AccountAllocation> toAccountAllocations(List<AccountAssignmentRow> accounts) {
        return accounts.stream()
                .map(account -> new AccountAllocation(
                        account.clearingNumber(), account.accountNumber(), account.accountBban(), account.currency()))
                .toList();
    }

    private static List<AliasAllocation> toAliasAllocations(List<AliasAssignmentRow> aliases) {
        return aliases.stream()
                .map(alias -> new AliasAllocation(alias.aliasId()))
                .toList();
    }

    private OutboundReportMessage newMessage(
            ReportConfigTree tree, MessageAssemblyContext context, List<PaymentTypeAllocation> paymentTypeAllocations) {

        ReportConfigRow config = tree.config();
        return new OutboundReportMessage(
                config.configId(),
                config.reportType(),
                context.reportVersion(),
                context.windowStartUtc(),
                context.windowEndUtc(),
                config.isBundled(),
                context.triggerType(),
                context.recipient(),
                paymentTypeAllocations,
                context.requestorName());
    }
}
