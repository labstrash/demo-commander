package com.example.commander.domain.audit;

import com.example.commander.domain.message.TriggerType;
import java.time.Instant;
import java.util.Objects;

/**
 * One row to insert into {@code CAMT.ReportCommandAudit} — one send attempt.
 *
 * <p>Built by whichever of the two call sites just observed a {@code
 * ResilientMqSender.send()} outcome: {@code MqReportMessageWriter} (primary send, has
 * {@code StepExecution}/{@code JobParameters} context) or {@code DeadLetterRecoveryJob}
 * (recovery resend, has neither — {@link #jobExecutionId()}/{@link #stepExecutionId()} stay
 * {@code null} for these rows, same as {@code report_frequency}).
 *
 * <p>A builder, not a 24-argument constructor — this many fields, several nullable, is
 * exactly the case where positional construction invites transcription errors at the two
 * call sites building one of these.
 */
public record ReportCommandAuditEntry(
        String messageId,
        String correlationId,
        Long reportConfigId,
        String configId,
        Long agreementScopeId,
        String reportType,
        String reportVersion,
        String reportFrequency,
        TriggerType triggerType,
        Instant windowStartUtc,
        Instant windowEndUtc,
        boolean isBundled,
        int accountCount,
        int paymentTypeCount,
        String recipientType,
        String recipientValue,
        String mqQueueName,
        String mqMessageId,
        ReportCommandAuditStatus status,
        String errorMessage,
        Instant sentAt,
        int retryCount,
        Long jobExecutionId,
        Long stepExecutionId,
        String requestorName) {

    public ReportCommandAuditEntry {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(reportType, "reportType");
        Objects.requireNonNull(reportVersion, "reportVersion");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(windowStartUtc, "windowStartUtc");
        Objects.requireNonNull(windowEndUtc, "windowEndUtc");
        Objects.requireNonNull(recipientType, "recipientType");
        Objects.requireNonNull(recipientValue, "recipientValue");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sentAt, "sentAt");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder — see the class Javadoc for why one exists here. */
    public static final class Builder {

        private String messageId;
        private String correlationId;
        private Long reportConfigId;
        private String configId;
        private Long agreementScopeId;
        private String reportType;
        private String reportVersion;
        private String reportFrequency;
        private TriggerType triggerType;
        private Instant windowStartUtc;
        private Instant windowEndUtc;
        private boolean isBundled;
        private int accountCount;
        private int paymentTypeCount;
        private String recipientType;
        private String recipientValue;
        private String mqQueueName;
        private String mqMessageId;
        private ReportCommandAuditStatus status;
        private String errorMessage;
        private Instant sentAt;
        private int retryCount;
        private Long jobExecutionId;
        private Long stepExecutionId;
        private String requestorName;

        private Builder() {}

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder reportConfigId(Long reportConfigId) {
            this.reportConfigId = reportConfigId;
            return this;
        }

        public Builder configId(String configId) {
            this.configId = configId;
            return this;
        }

        public Builder agreementScopeId(Long agreementScopeId) {
            this.agreementScopeId = agreementScopeId;
            return this;
        }

        public Builder reportType(String reportType) {
            this.reportType = reportType;
            return this;
        }

        public Builder reportVersion(String reportVersion) {
            this.reportVersion = reportVersion;
            return this;
        }

        public Builder reportFrequency(String reportFrequency) {
            this.reportFrequency = reportFrequency;
            return this;
        }

        public Builder triggerType(TriggerType triggerType) {
            this.triggerType = triggerType;
            return this;
        }

        public Builder windowStartUtc(Instant windowStartUtc) {
            this.windowStartUtc = windowStartUtc;
            return this;
        }

        public Builder windowEndUtc(Instant windowEndUtc) {
            this.windowEndUtc = windowEndUtc;
            return this;
        }

        public Builder isBundled(boolean isBundled) {
            this.isBundled = isBundled;
            return this;
        }

        public Builder accountCount(int accountCount) {
            this.accountCount = accountCount;
            return this;
        }

        public Builder paymentTypeCount(int paymentTypeCount) {
            this.paymentTypeCount = paymentTypeCount;
            return this;
        }

        public Builder recipientType(String recipientType) {
            this.recipientType = recipientType;
            return this;
        }

        public Builder recipientValue(String recipientValue) {
            this.recipientValue = recipientValue;
            return this;
        }

        public Builder mqQueueName(String mqQueueName) {
            this.mqQueueName = mqQueueName;
            return this;
        }

        public Builder mqMessageId(String mqMessageId) {
            this.mqMessageId = mqMessageId;
            return this;
        }

        public Builder status(ReportCommandAuditStatus status) {
            this.status = status;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder sentAt(Instant sentAt) {
            this.sentAt = sentAt;
            return this;
        }

        public Builder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public Builder jobExecutionId(Long jobExecutionId) {
            this.jobExecutionId = jobExecutionId;
            return this;
        }

        public Builder stepExecutionId(Long stepExecutionId) {
            this.stepExecutionId = stepExecutionId;
            return this;
        }

        public Builder requestorName(String requestorName) {
            this.requestorName = requestorName;
            return this;
        }

        public ReportCommandAuditEntry build() {
            return new ReportCommandAuditEntry(
                    messageId,
                    correlationId,
                    reportConfigId,
                    configId,
                    agreementScopeId,
                    reportType,
                    reportVersion,
                    reportFrequency,
                    triggerType,
                    windowStartUtc,
                    windowEndUtc,
                    isBundled,
                    accountCount,
                    paymentTypeCount,
                    recipientType,
                    recipientValue,
                    mqQueueName,
                    mqMessageId,
                    status,
                    errorMessage,
                    sentAt,
                    retryCount,
                    jobExecutionId,
                    stepExecutionId,
                    requestorName);
        }
    }
}
