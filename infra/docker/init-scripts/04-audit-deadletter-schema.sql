USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

-- =============================================================================
-- DEAD LETTER TABLE - For messages that failed MQ delivery
-- =============================================================================

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.DeadLetterMessage'))
CREATE TABLE CAMT.DeadLetterMessage (
                                        id BIGINT NOT NULL IDENTITY(1,1),
                                        message_id NVARCHAR(100) NOT NULL,
                                        report_config_id BIGINT NOT NULL,
                                        agreement_scope_id BIGINT NOT NULL,
                                        report_type NVARCHAR(40) NOT NULL,
                                        message_payload NVARCHAR(MAX) NOT NULL,
                                        target_queue NVARCHAR(100) NOT NULL,
                                        retry_count INT NOT NULL DEFAULT 0,
                                        max_retries INT NOT NULL DEFAULT 5,
                                        last_error NVARCHAR(MAX) NULL,
                                        status NVARCHAR(20) NOT NULL DEFAULT 'PENDING_RETRY',
                                        created_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
                                        updated_at DATETIME2 NULL,
                                        next_retry_at DATETIME2 NULL,
                                        CONSTRAINT PK_DeadLetterMessage PRIMARY KEY (id)
);

CREATE INDEX IX_DeadLetterMessage_StatusNextRetry
    ON CAMT.DeadLetterMessage (status, next_retry_at)
    INCLUDE (message_id, report_type, target_queue);
GO

-- =============================================================================
-- AUDIT TABLE - For tracking all ReportCommand messages sent
-- =============================================================================

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit'))
CREATE TABLE CAMT.ReportCommandAudit (
                                         id BIGINT NOT NULL IDENTITY(1,1),
                                         message_id NVARCHAR(100) NOT NULL,
                                         correlation_id NVARCHAR(200) NOT NULL,
                                         report_config_id BIGINT NOT NULL,
                                         config_id NVARCHAR(50) NOT NULL,
                                         agreement_scope_id BIGINT NOT NULL,
                                         report_type NVARCHAR(40) NOT NULL,
                                         report_version NVARCHAR(3) NOT NULL,
                                         report_frequency NVARCHAR(35) NOT NULL,
                                         trigger_type NVARCHAR(20) NOT NULL,
                                         window_start_utc DATETIME2 NOT NULL,
                                         window_end_utc DATETIME2 NOT NULL,
                                         is_bundled BIT NOT NULL,
                                         account_count INT NOT NULL,
                                         payment_type_count INT NOT NULL,
                                         recipient_type NVARCHAR(20) NOT NULL,
                                         recipient_value NVARCHAR(100) NOT NULL,
                                         mq_queue_name NVARCHAR(100) NOT NULL,
                                         mq_message_id NVARCHAR(100) NULL,
                                         status NVARCHAR(20) NOT NULL,
                                         error_message NVARCHAR(MAX) NULL,
                                         sent_at DATETIME2 NOT NULL,
                                         retry_count INT NOT NULL DEFAULT 0,
                                         job_execution_id BIGINT NULL,
                                         step_execution_id BIGINT NULL,
                                         CONSTRAINT PK_ReportCommandAudit PRIMARY KEY (id)
);

CREATE INDEX IX_ReportCommandAudit_MessageId ON CAMT.ReportCommandAudit (message_id);
CREATE INDEX IX_ReportCommandAudit_ConfigId ON CAMT.ReportCommandAudit (config_id);
CREATE INDEX IX_ReportCommandAudit_ReportType ON CAMT.ReportCommandAudit (report_type);
CREATE INDEX IX_ReportCommandAudit_SentAt ON CAMT.ReportCommandAudit (sent_at DESC);
CREATE INDEX IX_ReportCommandAudit_Status ON CAMT.ReportCommandAudit (status);
CREATE INDEX IX_ReportCommandAudit_JobExecution ON CAMT.ReportCommandAudit (job_execution_id);
GO