-- =============================================================================
-- SCRIPT: 04-schema-scope.sql
-- PURPOSE: Create scope and assignment tables (Scope, PaymentType, Accounts, Aliases)
-- EXECUTION: Run after 03-schema-agreement.sql
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 04: SCOPE & ASSIGNMENTS';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. AGREEMENT SCOPE TABLE
    -- =========================================================================

    PRINT '>>> Creating AgreementScope table...';

CREATE TABLE CAMT.AgreementScope
(
    Id                 BIGINT NOT NULL IDENTITY(1,1),
    AgreementVersionId BIGINT NOT NULL,
    Name               NVARCHAR(80) NULL,
    MessageRecipientId BIGINT NOT NULL,
    ReportType         NVARCHAR(40) NOT NULL,
    Status             NVARCHAR(15) NOT NULL,
    CreatedAt          DATETIME2 NOT NULL,
    ActivatedAt        DATETIME2 NULL,
    CancelledAt        DATETIME2 NULL,
    CreatedBy          NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_AgreementScope PRIMARY KEY (Id),
    CONSTRAINT FK_AgreementScope_Version
        FOREIGN KEY (AgreementVersionId) REFERENCES CAMT.AgreementVersion(Id),
    CONSTRAINT FK_AgreementScope_Recipient
        FOREIGN KEY (MessageRecipientId) REFERENCES CAMT.Recipient(Id),
    CONSTRAINT FK_AgreementScope_ReportType
        FOREIGN KEY (ReportType) REFERENCES CAMT.ReportType(Code),
    CONSTRAINT UX_AgreementScope_Unique
        UNIQUE (AgreementVersionId, MessageRecipientId, ReportType)
);

PRINT '✓ CAMT.AgreementScope created successfully.';
    PRINT '';

    PRINT '>>> Creating AgreementScope indexes...';

CREATE INDEX IX_AgreementScope_Version ON CAMT.AgreementScope (AgreementVersionId);
PRINT '  ✓ IX_AgreementScope_Version created.';

CREATE INDEX IX_AgreementScope_Recipient ON CAMT.AgreementScope (MessageRecipientId);
PRINT '  ✓ IX_AgreementScope_Recipient created.';

    PRINT '✓ All AgreementScope indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. PAYMENT TYPE ASSIGNMENT TABLE
    -- =========================================================================

    PRINT '>>> Creating PaymentTypeAssignment table...';

CREATE TABLE CAMT.PaymentTypeAssignment
(
    Id               BIGINT NOT NULL IDENTITY(1,1),
    AgreementScopeId BIGINT NOT NULL,
    PaymentType      NVARCHAR(40) NOT NULL,
    CreatedAt        DATETIME2 NOT NULL,
    CreatedBy        NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_PaymentTypeAssignment PRIMARY KEY (Id),
    CONSTRAINT FK_PaymentTypeAssignment_Scope
        FOREIGN KEY (AgreementScopeId) REFERENCES CAMT.AgreementScope(Id),
    CONSTRAINT FK_PaymentTypeAssignment_PaymentType
        FOREIGN KEY (PaymentType) REFERENCES CAMT.PaymentType(Code),
    CONSTRAINT UX_PaymentTypeAssignment_Unique
        UNIQUE (AgreementScopeId, PaymentType)
);

PRINT '✓ CAMT.PaymentTypeAssignment created successfully.';
    PRINT '';

    PRINT '>>> Creating PaymentTypeAssignment indexes...';

CREATE INDEX IX_PaymentTypeAssignment_Scope
    ON CAMT.PaymentTypeAssignment (AgreementScopeId);
PRINT '  ✓ IX_PaymentTypeAssignment_Scope created.';

    PRINT '✓ All PaymentTypeAssignment indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. ACCOUNT ASSIGNMENT TABLE
    -- =========================================================================

    PRINT '>>> Creating AccountAssignment table...';

CREATE TABLE CAMT.AccountAssignment
(
    Id                      BIGINT NOT NULL IDENTITY(1,1),
    PaymentTypeAssignmentId BIGINT NOT NULL,
    ClearingNumber          NVARCHAR(5) NOT NULL,
    AccountNumber           NVARCHAR(9) NOT NULL,
    AccountBBAN             NVARCHAR(15) NOT NULL,
    Currency                NVARCHAR(3) NOT NULL,
    CreatedAt               DATETIME2 NOT NULL,
    CreatedBy               NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_AccountAssignment PRIMARY KEY (Id),
    CONSTRAINT FK_AccountAssignment_PaymentType
        FOREIGN KEY (PaymentTypeAssignmentId) REFERENCES CAMT.PaymentTypeAssignment(Id),
    CONSTRAINT UX_AccountAssignment_Unique
        UNIQUE (PaymentTypeAssignmentId, AccountNumber)
);

PRINT '✓ CAMT.AccountAssignment created successfully.';
    PRINT '';

    PRINT '>>> Creating AccountAssignment indexes...';

CREATE INDEX IX_AccountAssignment_PaymentType
    ON CAMT.AccountAssignment (PaymentTypeAssignmentId);
PRINT '  ✓ IX_AccountAssignment_PaymentType created.';

    PRINT '✓ All AccountAssignment indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 4. ALIAS ASSIGNMENT TABLE
    -- =========================================================================

    PRINT '>>> Creating AliasAssignment table...';

CREATE TABLE CAMT.AliasAssignment
(
    Id                      BIGINT NOT NULL IDENTITY(1,1),
    PaymentTypeAssignmentId BIGINT NOT NULL,
    AliasId                 NVARCHAR(15) NOT NULL,
    CreatedAt               DATETIME2 NOT NULL,
    CreatedBy               NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_AliasAssignment PRIMARY KEY (Id),
    CONSTRAINT FK_AliasAssignment_PaymentType
        FOREIGN KEY (PaymentTypeAssignmentId) REFERENCES CAMT.PaymentTypeAssignment(Id),
    CONSTRAINT UX_AliasAssignment_Unique
        UNIQUE (PaymentTypeAssignmentId, AliasId)
);

PRINT '✓ CAMT.AliasAssignment created successfully.';
    PRINT '';

    PRINT '>>> Creating AliasAssignment indexes...';

CREATE INDEX IX_AliasAssignment_PaymentType
    ON CAMT.AliasAssignment (PaymentTypeAssignmentId);
PRINT '  ✓ IX_AliasAssignment_PaymentType created.';

    PRINT '✓ All AliasAssignment indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 5. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying tables exist...';

    DECLARE @TablesExist TABLE (TableName NVARCHAR(100), TableExists BIT);

INSERT INTO @TablesExist VALUES
                             ('CAMT.AgreementScope', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.AgreementScope')) THEN 1 ELSE 0 END),
                             ('CAMT.PaymentTypeAssignment', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.PaymentTypeAssignment')) THEN 1 ELSE 0 END),
                             ('CAMT.AccountAssignment', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.AccountAssignment')) THEN 1 ELSE 0 END),
                             ('CAMT.AliasAssignment', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.AliasAssignment')) THEN 1 ELSE 0 END);

SELECT
    CASE WHEN TableExists = 1 THEN '  ✓ ' ELSE '  ✗ ' END + TableName AS Status
FROM @TablesExist;

IF EXISTS (SELECT 1 FROM @TablesExist WHERE TableExists = 0)
        RAISERROR('ERROR: One or more tables were not created successfully!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

    -- Verify foreign key references
    PRINT '>>> Validation: Checking foreign key constraints...';

    DECLARE @FKCheck TABLE (ConstraintName NVARCHAR(100), TableName NVARCHAR(100), Status VARCHAR(20));

INSERT INTO @FKCheck
SELECT
    fk.name AS ConstraintName,
    OBJECT_NAME(fk.parent_object_id) AS TableName,
    CASE WHEN fk.is_disabled = 1 THEN 'DISABLED' ELSE 'ENABLED' END AS Status
FROM sys.foreign_keys fk
WHERE OBJECT_SCHEMA_NAME(fk.parent_object_id) = 'CAMT';

SELECT
    '  ' + Status + ' - ' + ConstraintName + ' (' + TableName + ')' AS FK_Status
FROM @FKCheck
ORDER BY TableName;

IF EXISTS (SELECT 1 FROM @FKCheck WHERE Status = 'DISABLED')
        PRINT '⚠ WARNING: Some foreign keys are disabled!';
ELSE
        PRINT '✓ All foreign keys are enabled.';

    PRINT '✓ Validation complete.';
    PRINT '';

COMMIT TRANSACTION;
PRINT '========================================';
    PRINT '✓ SCRIPT 04 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 04 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO