-- =============================================================================
-- SCRIPT: 03-schema-agreement.sql
-- PURPOSE: Create core agreement structure (Recipient, Agreement, Contact, Version)
-- EXECUTION: Run after 02-schema-sequence.sql
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 03: AGREEMENT CORE STRUCTURE';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. RECIPIENT TABLE
    -- =========================================================================

    PRINT '>>> Creating Recipient table...';

CREATE TABLE CAMT.Recipient
(
    Id        BIGINT NOT NULL IDENTITY(1,1),
    Type      NVARCHAR(20) NOT NULL,
    Value     NVARCHAR(100) NOT NULL,
    Name      NVARCHAR(100) NOT NULL,
    CreatedAt DATETIME2 NOT NULL,
    CreatedBy NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_Recipient PRIMARY KEY (Id),
    CONSTRAINT UX_Recipient_TypeValue UNIQUE (Type, Value)
);

PRINT '✓ CAMT.Recipient created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. AGREEMENT TABLE
    -- =========================================================================

    PRINT '>>> Creating Agreement table...';

CREATE TABLE CAMT.Agreement
(
    Id             NVARCHAR(50) NOT NULL,
    Name           NVARCHAR(35) NULL,
    EngagementBank NVARCHAR(5) NOT NULL,
    EngagementId   NVARCHAR(15) NOT NULL,
    Channel        NVARCHAR(100) NOT NULL,
    Track          NVARCHAR(15) NOT NULL,
    StartDate      DATETIME2 NULL,
    CancelledAt    DATETIME2 NULL,
    CreatedAt      DATETIME2 NOT NULL,
    UpdatedAt      DATETIME2 NULL,
    CreatedBy      NVARCHAR(20) NOT NULL,
    UpdatedBy      NVARCHAR(20) NULL,
    CONSTRAINT PK_Agreement PRIMARY KEY (Id)
);

PRINT '✓ CAMT.Agreement created successfully.';
    PRINT '';

    PRINT '>>> Creating Agreement indexes...';

    -- Filtered unique index: name uniqueness only applies to non-cancelled agreements
CREATE UNIQUE INDEX UX_Agreement_EngagementBankEngagementIdName
    ON CAMT.Agreement (EngagementBank, EngagementId, Name)
    WHERE Name IS NOT NULL AND CancelledAt IS NULL;
PRINT '  ✓ UX_Agreement_EngagementBankEngagementIdName created.';

CREATE INDEX IX_Agreement_EngagementBankEngagementId
    ON CAMT.Agreement (EngagementBank, EngagementId);
PRINT '  ✓ IX_Agreement_EngagementBankEngagementId created.';

    PRINT '✓ All Agreement indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. AGREEMENT CONTACT TABLE
    -- =========================================================================

    PRINT '>>> Creating AgreementContact table...';

CREATE TABLE CAMT.AgreementContact
(
    Id           BIGINT NOT NULL IDENTITY(1,1),
    AgreementId  NVARCHAR(50) NOT NULL,
    ContactName  NVARCHAR(40) NOT NULL,
    ContactEmail NVARCHAR(50) NOT NULL,
    ContactPhone NVARCHAR(20) NOT NULL,
    CreatedAt    DATETIME2 NOT NULL,
    CreatedBy    NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_AgreementContact PRIMARY KEY (Id),
    CONSTRAINT FK_AgreementContact_Agreement
        FOREIGN KEY (AgreementId) REFERENCES CAMT.Agreement(Id)
);

PRINT '✓ CAMT.AgreementContact created successfully.';
    PRINT '';

    PRINT '>>> Creating AgreementContact indexes...';

CREATE INDEX IX_AgreementContact_Agreement
    ON CAMT.AgreementContact (AgreementId);
PRINT '  ✓ IX_AgreementContact_Agreement created.';

    PRINT '✓ All AgreementContact indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 4. AGREEMENT VERSION TABLE
    -- =========================================================================

    PRINT '>>> Creating AgreementVersion table...';

CREATE TABLE CAMT.AgreementVersion
(
    Id              BIGINT NOT NULL IDENTITY(1,1),
    VersionId       NVARCHAR(50) NOT NULL,
    AgreementId     NVARCHAR(50) NOT NULL,
    Status          NVARCHAR(25) NOT NULL,
    PricingOrderRef NVARCHAR(35) NULL,
    CreatedAt       DATETIME2 NOT NULL,
    ActivatedAt     DATETIME2 NULL,
    SupersededAt    DATETIME2 NULL,
    CancelledAt     DATETIME2 NULL,
    CreatedBy       NVARCHAR(20) NOT NULL,
    UpdatedAt       DATETIME2 NULL,
    UpdatedBy       NVARCHAR(20) NULL,
    Version         BIGINT NOT NULL CONSTRAINT DF_AgreementVersion_Version DEFAULT 0,
    CONSTRAINT PK_AgreementVersion PRIMARY KEY (Id),
    CONSTRAINT UQ_AgreementVersion_VersionId UNIQUE (VersionId),
    CONSTRAINT FK_AgreementVersion_Agreement
        FOREIGN KEY (AgreementId) REFERENCES CAMT.Agreement(Id)
);

PRINT '✓ CAMT.AgreementVersion created successfully.';
    PRINT '';

    PRINT '>>> Creating AgreementVersion indexes...';

    -- Filtered unique index: only one ACTIVE version per agreement
CREATE UNIQUE INDEX UX_AgreementVersion_Active
    ON CAMT.AgreementVersion (AgreementId)
    WHERE Status = 'ACTIVE';
PRINT '  ✓ UX_AgreementVersion_Active created.';

    -- Filtered unique index: only one pending version per agreement
CREATE UNIQUE INDEX UX_AgreementVersion_OnePending
    ON CAMT.AgreementVersion (AgreementId)
    WHERE Status IN ('PENDING_ACTIVATION', 'PENDING_CHANGE', 'PENDING_CANCELLATION');
PRINT '  ✓ UX_AgreementVersion_OnePending created.';

CREATE INDEX IX_AgreementVersion_Agreement
    ON CAMT.AgreementVersion (AgreementId, Status)
    INCLUDE (VersionId, PricingOrderRef, CreatedAt, ActivatedAt, SupersededAt, CancelledAt, CreatedBy, UpdatedAt, UpdatedBy);
PRINT '  ✓ IX_AgreementVersion_Agreement created.';

    PRINT '✓ All AgreementVersion indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 5. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying tables exist...';

    DECLARE @TablesExist TABLE (TableName NVARCHAR(100), TableExists BIT);

INSERT INTO @TablesExist VALUES
                             ('CAMT.Recipient', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.Recipient')) THEN 1 ELSE 0 END),
                             ('CAMT.Agreement', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.Agreement')) THEN 1 ELSE 0 END),
                             ('CAMT.AgreementContact', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.AgreementContact')) THEN 1 ELSE 0 END),
                             ('CAMT.AgreementVersion', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.AgreementVersion')) THEN 1 ELSE 0 END);

SELECT
    CASE WHEN TableExists = 1 THEN '  ✓ ' ELSE '  ✗ ' END + TableName AS Status
FROM @TablesExist;

IF EXISTS (SELECT 1 FROM @TablesExist WHERE TableExists = 0)
        RAISERROR('ERROR: One or more tables were not created successfully!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

COMMIT TRANSACTION;
PRINT '========================================';
    PRINT '✓ SCRIPT 03 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 03 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO