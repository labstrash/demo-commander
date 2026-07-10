package com.example.commander.service;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeNode;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentNode;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.message.MessageAssemblyContext;
import com.example.commander.domain.message.OutboundReportMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        return newMessage(tree, context, NO_SINGLE_SCOPE_SENTINEL, List.of(), List.of(), 0);
    }

    private OutboundReportMessage bundledMessage(ReportConfigTree tree, MessageAssemblyContext context) {
        List<AccountAssignmentRow> mergedAccounts = new ArrayList<>();
        List<AliasAssignmentRow> mergedAliases = new ArrayList<>();
        int paymentTypeCount = 0;

        for (AgreementScopeNode scope : tree.scopes()) {
            for (PaymentTypeAssignmentNode assignment : scope.paymentTypeAssignments()) {
                if (assignment.isEmpty()) {
                    continue;
                }
                paymentTypeCount++;
                mergedAccounts.addAll(assignment.accounts());
                mergedAliases.addAll(assignment.aliases());
            }
        }

        return newMessage(tree, context, NO_SINGLE_SCOPE_SENTINEL, mergedAccounts, mergedAliases, paymentTypeCount);
    }

    private List<OutboundReportMessage> unbundledMessages(ReportConfigTree tree, MessageAssemblyContext context) {
        List<OutboundReportMessage> messages = new ArrayList<>();

        for (AgreementScopeNode scope : tree.scopes()) {
            for (PaymentTypeAssignmentNode assignment : scope.paymentTypeAssignments()) {
                for (AccountAssignmentRow account : assignment.accounts()) {
                    messages.add(newMessage(tree, context, scope.id(), List.of(account), List.of(), 1));
                }
                for (AliasAssignmentRow alias : assignment.aliases()) {
                    messages.add(newMessage(tree, context, scope.id(), List.of(), List.of(alias), 1));
                }
            }
        }

        return messages;
    }

    private OutboundReportMessage newMessage(
            ReportConfigTree tree,
            MessageAssemblyContext context,
            long agreementScopeId,
            List<AccountAssignmentRow> accounts,
            List<AliasAssignmentRow> aliases,
            int paymentTypeCount) {

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
                accounts,
                aliases,
                paymentTypeCount,
                context.requestorName());
    }
}
