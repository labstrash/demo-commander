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
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.domain.message.ReportMessageIdGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Assembles report configuration trees into outbound messages.
 *
 * <p>Transforms a {@link ReportConfigTree} into one or more {@link PipelineReportMessage}s
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
     * {@code ReportMessageIdGenerator}'s own documented ceiling ("must be less than 35
     * characters"). Enforced here, not inside the generator, since the generator is owned
     * elsewhere. Note: {@code reportType}s whose 6th character onward is longer than 3
     * characters (e.g. a 9-character type where every other type is 8) push the generated ID
     * to exactly 35 — right at, not under, this ceiling — and will trip this check.
     */
    private static final int MAX_MESSAGE_ID_LENGTH = 35;

    /**
     * Assembles a configuration tree into outbound messages.
     *
     * @param tree the assembled report configuration tree
     * @param context the message assembly context
     * @return list of pipeline messages (one or more based on bundling rules)
     * @throws NullPointerException if tree or context is null
     */
    public List<PipelineReportMessage> assemble(ReportConfigTree tree, MessageAssemblyContext context) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(context, "context");

        if (tree.isZeroScope()) {
            return List.of(configOnlyMessage(tree, context));
        }

        return tree.config().isBundled() ? List.of(bundledMessage(tree, context)) : unbundledMessages(tree, context);
    }

    private PipelineReportMessage configOnlyMessage(ReportConfigTree tree, MessageAssemblyContext context) {
        return newMessage(tree, context, null, List.of());
    }

    private PipelineReportMessage bundledMessage(ReportConfigTree tree, MessageAssemblyContext context) {
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

        return newMessage(tree, context, null, allocations);
    }

    private List<PipelineReportMessage> unbundledMessages(ReportConfigTree tree, MessageAssemblyContext context) {
        List<PipelineReportMessage> messages = new ArrayList<>();

        for (AgreementScopeNode scope : tree.scopes()) {
            for (PaymentTypeAssignmentNode assignment : scope.paymentTypeAssignments()) {
                for (AccountAssignmentRow account : assignment.accounts()) {
                    PaymentTypeAllocation allocation = new PaymentTypeAllocation(
                            assignment.paymentType(), toAccountAllocations(List.of(account)), List.of());
                    messages.add(newMessage(tree, context, scope.id(), List.of(allocation)));
                }
                for (AliasAssignmentRow alias : assignment.aliases()) {
                    PaymentTypeAllocation allocation = new PaymentTypeAllocation(
                            assignment.paymentType(), List.of(), toAliasAllocations(List.of(alias)));
                    messages.add(newMessage(tree, context, scope.id(), List.of(allocation)));
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

    private PipelineReportMessage newMessage(
            ReportConfigTree tree,
            MessageAssemblyContext context,
            Long agreementScopeId,
            List<PaymentTypeAllocation> paymentTypeAllocations) {

        ReportConfigRow config = tree.config();
        long reportConfigId = config.id();

        String correlationId = correlationId(
                reportConfigId,
                agreementScopeId,
                context.windowStartUtc(),
                context.windowEndUtc(),
                paymentTypeAllocations);
        String messageId = messageId(config.configId(), config.reportType());

        OutboundReportMessage payload = new OutboundReportMessage(
                config.configId(),
                config.reportType(),
                context.reportVersion(),
                context.windowStartUtc(),
                context.windowEndUtc(),
                config.isBundled(),
                context.triggerType(),
                context.recipient(),
                paymentTypeAllocations,
                context.requestorName(),
                correlationId,
                messageId);

        return new PipelineReportMessage(payload, reportConfigId, agreementScopeId);
    }

    /**
     * Deterministic, reproducible from content — no call-once-and-thread-through discipline
     * required, unlike {@link #messageId}. Bundled/config-only messages are already uniquely
     * identified by {@code (reportConfigId, windowStartUtc, windowEndUtc)} alone — there's
     * exactly one such message per config per firing. Unbundled messages additionally fold in
     * the single allocation's identifying account/alias value, since a scope can contribute
     * several unbundled messages and {@code agreementScopeId} alone would collide between them.
     */
    private static String correlationId(
            long reportConfigId,
            Long agreementScopeId,
            Instant windowStartUtc,
            Instant windowEndUtc,
            List<PaymentTypeAllocation> paymentTypeAllocations) {

        String basis;
        if (agreementScopeId == null) {
            basis = reportConfigId + "|" + windowStartUtc + "|" + windowEndUtc;
        } else {
            PaymentTypeAllocation allocation = paymentTypeAllocations.get(0);
            String allocationKey = allocation.accounts().isEmpty()
                    ? allocation.aliases().get(0).aliasId()
                    : allocation.accounts().get(0).accountNumber();
            basis = reportConfigId + "|" + agreementScopeId + "|" + windowStartUtc + "|" + windowEndUtc + "|"
                    + allocationKey;
        }
        return UUID.nameUUIDFromBytes(basis.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Non-deterministic — {@link ReportMessageIdGenerator} mints a new value every call, so
     * this must only ever be invoked once per message, here at assembly time, and threaded
     * forward unchanged through every later transformation (recipient-resolution processor
     * rebuild, retry, dead-letter row) rather than regenerated.
     */
    private static String messageId(int configId, String reportType) {
        String messageId = ReportMessageIdGenerator.generateReportMessageId(configId, reportType);
        if (messageId.length() >= MAX_MESSAGE_ID_LENGTH) {
            throw new IllegalStateException("ReportMessageIdGenerator produced a messageId of length "
                    + messageId.length() + " (must be < " + MAX_MESSAGE_ID_LENGTH + ") for reportType=" + reportType
                    + ", configId=" + configId + " — messageId=" + messageId);
        }
        return messageId;
    }
}
