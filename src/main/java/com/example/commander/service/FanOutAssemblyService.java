package com.example.commander.service;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeNode;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentNode;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
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
     * Sentinel value for agreement scope ID when a message is not attributable to a single scope.
     *
     * <p>Used for zero-scope and bundled messages where accounts/aliases come from
     * multiple scopes or no scopes at all.
     */
    public static final long NO_SINGLE_SCOPE_SENTINEL = 0L;

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
        return newMessage(tree, context, NO_SINGLE_SCOPE_SENTINEL, List.of());
    }

    private OutboundReportMessage bundledMessage(ReportConfigTree tree, MessageAssemblyContext context) {
        // LinkedHashMap: preserves first-seen payment type order across scopes for stable output.
        Map<String, List<AccountAssignmentRow>> accountsByType = new LinkedHashMap<>();
        Map<String, List<AliasAssignmentRow>> aliasesByType = new LinkedHashMap<>();

        for (AgreementScopeNode scope : tree.scopes()) {
            for (PaymentTypeAssignmentNode assignment : scope.paymentTypeAssignments()) {
                if (assignment.isAccountAssignment()) {
                    accountsByType
                            .computeIfAbsent(assignment.paymentType(), key -> new ArrayList<>())
                            .addAll(assignment.accounts());
                } else if (assignment.isAliasAssignment()) {
                    aliasesByType
                            .computeIfAbsent(assignment.paymentType(), key -> new ArrayList<>())
                            .addAll(assignment.aliases());
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

        return newMessage(tree, context, NO_SINGLE_SCOPE_SENTINEL, allocations);
    }

    private List<OutboundReportMessage> unbundledMessages(ReportConfigTree tree, MessageAssemblyContext context) {
        List<OutboundReportMessage> messages = new ArrayList<>();

        for (AgreementScopeNode scope : tree.scopes()) {
            for (PaymentTypeAssignmentNode assignment : scope.paymentTypeAssignments()) {
                for (AccountAssignmentRow account : assignment.accounts()) {
                    PaymentTypeAllocation allocation =
                            new PaymentTypeAllocation(assignment.paymentType(), List.of(account), List.of());
                    messages.add(newMessage(tree, context, scope.id(), List.of(allocation)));
                }
                for (AliasAssignmentRow alias : assignment.aliases()) {
                    PaymentTypeAllocation allocation =
                            new PaymentTypeAllocation(assignment.paymentType(), List.of(), List.of(alias));
                    messages.add(newMessage(tree, context, scope.id(), List.of(allocation)));
                }
            }
        }

        return messages;
    }

    private OutboundReportMessage newMessage(
            ReportConfigTree tree,
            MessageAssemblyContext context,
            long agreementScopeId,
            List<PaymentTypeAllocation> paymentTypeAllocations) {

        ReportConfigRow config = tree.config();
        return new OutboundReportMessage(
                config.id(),
                config.configId(),
                agreementScopeId,
                config.reportType(),
                context.reportVersion(),
                config.reportFrequency(),
                context.windowStartUtc(),
                context.windowEndUtc(),
                config.isBundled(),
                context.triggerType(),
                context.recipient(),
                paymentTypeAllocations,
                context.requestorName());
    }
}
