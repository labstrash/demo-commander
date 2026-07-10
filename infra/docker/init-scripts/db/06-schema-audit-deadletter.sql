-- =============================================================================
-- SCRIPT: 06-schema-audit-deadletter.sql
-- PURPOSE: Create dead-letter and report-command audit tables
-- EXECUTION: Run after 05-schema-report.sql
--
-- ReportCommandAudit is created directly in its target shape rather than via
-- CREATE + ALTER, since this is a clean/fresh deployment, not a migration of
-- an existing table:
--   - requestor_name NVARCHAR(200) NULL is present from creation.
--   - report_config_id, config_id, agreement_scope_id, report_frequency, and
--     mq_queue_name are nullable from creation, to support rejection-audit
--     rows where no ReportConfig/AgreementScope could be resolved (e.g.
--     recipient-not-found or config-not-found outcomes, or messages not
--     attributable to exactly one AgreementScope). Carried forward from the
--     original migration's sign-off note — confirm this is still wanted.
--   - status is NVARCHAR(30), not NVARCHAR(20): the Java-only status values
--     REJECTED_CONFIG_NOT_ELIGIBLE (28 chars) and REJECTED_INVALID_WINDOW
--     (23 chars) do not fit in NVARCHAR(20).
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 06: AUDIT & DEAD LETTER';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
    BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. DEAD LETTER MESSAGE TABLE
    -- =========================================================================

    PRINT '>>> Creating DeadLetterMessage table...';

    CREATE TABLE CAMT.DeadLetterMessage (
        id                  BIGINT NOT NULL IDENTITY(1,1),
        message_id          NVARCHAR(100) NOT NULL,
        report_config_id    BIGINT NOT NULL,
        agreement_scope_id  BIGINT NOT NULL,
        report_type         NVARCHAR(40) NOT NULL,
        message_payload     NVARCHAR(MAX) NOT NULL,
        target_queue        NVARCHAR(100) NOT NULL,
        retry_count         INT NOT NULL DEFAULT 0,
        max_retries         INT NOT NULL DEFAULT 5,
        last_error          NVARCHAR(MAX) NULL,
        status              NVARCHAR(20) NOT NULL DEFAULT 'PENDING_RETRY',
        created_at          DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
        updated_at          DATETIME2 NULL,
        next_retry_at       DATETIME2 NULL,
        CONSTRAINT PK_DeadLetterMessage PRIMARY KEY (id)
    );

    PRINT '✓ CAMT.DeadLetterMessage created successfully.';
    PRINT '';

    PRINT '>>> Creating DeadLetterMessage indexes...';

    CREATE INDEX IX_DeadLetterMessage_StatusNextRetry
        ON CAMT.DeadLetterMessage (status, next_retry_at)
        INCLUDE (message_id, report_type, target_queue);
    PRINT '  ✓ IX_DeadLetterMessage_StatusNextRetry created.';

    PRINT '✓ All DeadLetterMessage indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. REPORT COMMAND AUDIT TABLE
    -- =========================================================================

    PRINT '>>> Creating ReportCommandAudit table...';

    CREATE TABLE CAMT.ReportCommandAudit (
        id                  BIGINT NOT NULL IDENTITY(1,1),
        message_id          NVARCHAR(100) NOT NULL,
        correlation_id      NVARCHAR(200) NOT NULL,
        report_config_id    BIGINT NULL,
        config_id           NVARCHAR(50) NULL,
        agreement_scope_id  BIGINT NULL,
        report_type         NVARCHAR(40) NOT NULL,
        report_version      NVARCHAR(3) NOT NULL,
        report_frequency    NVARCHAR(35) NULL,
        trigger_type        NVARCHAR(20) NOT NULL,
        window_start_utc    DATETIME2 NOT NULL,
        window_end_utc      DATETIME2 NOT NULL,
        is_bundled          BIT NOT NULL,
        account_count       INT NOT NULL,
        payment_type_count  INT NOT NULL,
        recipient_type      NVARCHAR(20) NOT NULL,
        recipient_value     NVARCHAR(100) NOT NULL,
        mq_queue_name       NVARCHAR(100) NULL,
        mq_message_id       NVARCHAR(100) NULL,
        status              NVARCHAR(30) NOT NULL,
        error_message       NVARCHAR(MAX) NULL,
        sent_at             DATETIME2 NOT NULL,
        retry_count         INT NOT NULL DEFAULT 0,
        job_execution_id    BIGINT NULL,
        step_execution_id   BIGINT NULL,
        requestor_name      NVARCHAR(200) NULL,
        CONSTRAINT PK_ReportCommandAudit PRIMARY KEY (id)
    );

    PRINT '✓ CAMT.ReportCommandAudit created successfully.';
    PRINT '';

    PRINT '>>> Creating ReportCommandAudit indexes...';

    CREATE INDEX IX_ReportCommandAudit_MessageId    ON CAMT.ReportCommandAudit (message_id);
    PRINT '  ✓ IX_ReportCommandAudit_MessageId created.';

    CREATE INDEX IX_ReportCommandAudit_ConfigId     ON CAMT.ReportCommandAudit (config_id);
    PRINT '  ✓ IX_ReportCommandAudit_ConfigId created.';

    CREATE INDEX IX_ReportCommandAudit_ReportType   ON CAMT.ReportCommandAudit (report_type);
    PRINT '  ✓ IX_ReportCommandAudit_ReportType created.';

    CREATE INDEX IX_ReportCommandAudit_SentAt       ON CAMT.ReportCommandAudit (sent_at DESC);
    PRINT '  ✓ IX_ReportCommandAudit_SentAt created.';

    CREATE INDEX IX_ReportCommandAudit_Status       ON CAMT.ReportCommandAudit (status);
    PRINT '  ✓ IX_ReportCommandAudit_Status created.';

    CREATE INDEX IX_ReportCommandAudit_JobExecution ON CAMT.ReportCommandAudit (job_execution_id);
    PRINT '  ✓ IX_ReportCommandAudit_JobExecution created.';

    PRINT '✓ All ReportCommandAudit indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying tables exist...';

    DECLARE @TablesExist TABLE (TableName NVARCHAR(100), TableExists BIT);

    INSERT INTO @TablesExist VALUES
        ('CAMT.DeadLetterMessage', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.DeadLetterMessage')) THEN 1 ELSE 0 END),
        ('CAMT.ReportCommandAudit', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit')) THEN 1 ELSE 0 END);

    SELECT
        CASE WHEN TableExists = 1 THEN '  ✓ ' ELSE '  ✗ ' END + TableName AS Status
    FROM @TablesExist;

    IF EXISTS (SELECT 1 FROM @TablesExist WHERE TableExists = 0)
        RAISERROR('ERROR: One or more tables were not created successfully!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

    COMMIT TRANSACTION;
    PRINT '========================================';
    PRINT '✓ SCRIPT 06 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 06 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO
