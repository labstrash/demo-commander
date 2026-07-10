-- =============================================================================
-- SCRIPT: 05-schema-report.sql
-- PURPOSE: Create report configuration tables (ReportConfig, ReportAgreementScope)
-- EXECUTION: Run after 04-schema-scope.sql
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 05: REPORT CONFIGURATION';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. REPORT CONFIG TABLE
    -- =========================================================================

    PRINT '>>> Creating ReportConfig table...';

CREATE TABLE CAMT.ReportConfig
(
    Id                   BIGINT NOT NULL IDENTITY(1,1),
    ConfigId             INT NULL,
    ReportType           NVARCHAR(40) NOT NULL,
    ReportVersion        NVARCHAR(3) NOT NULL,
    ReportFrequency      NVARCHAR(35) NOT NULL,
    Description          NVARCHAR(80) NOT NULL,
    MessageRecipientId   BIGINT NOT NULL,
    AccountFormat        NVARCHAR(4) NOT NULL,
    IsActive             BIT NOT NULL CONSTRAINT DF_ReportConfig_IsActive             DEFAULT 0,
    IsPaginated          BIT NOT NULL CONSTRAINT DF_ReportConfig_IsPaginated          DEFAULT 0,
    IsEmptyReportAllowed BIT NOT NULL CONSTRAINT DF_ReportConfig_IsEmptyReportAllowed DEFAULT 0,
    IsBundled            BIT NOT NULL CONSTRAINT DF_ReportConfig_IsBundled            DEFAULT 0,
    CreatedAt            DATETIME2 NOT NULL,
    CreatedBy            NVARCHAR(20) NOT NULL,
    UpdatedAt            DATETIME2 NULL,
    UpdatedBy            NVARCHAR(20) NULL,
    CONSTRAINT PK_ReportConfig PRIMARY KEY (Id),
    CONSTRAINT FK_ReportConfig_ReportType      FOREIGN KEY (ReportType)      REFERENCES CAMT.ReportType(Code),
    CONSTRAINT FK_ReportConfig_ReportFrequency FOREIGN KEY (ReportFrequency) REFERENCES CAMT.ReportFrequency(Code),
    CONSTRAINT FK_ReportConfig_Recipient       FOREIGN KEY (MessageRecipientId) REFERENCES CAMT.Recipient(Id),
    CONSTRAINT UX_ReportConfig_RecipientReportType UNIQUE (MessageRecipientId, ReportType),
    -- Enforce that any ACTIVE config always has a ConfigId set
    CONSTRAINT CK_ReportConfig_ActiveHasConfigId
        CHECK (IsActive = 0 OR ConfigId IS NOT NULL)
);

PRINT '✓ CAMT.ReportConfig created successfully.';
    PRINT '';

    PRINT '>>> Creating ReportConfig indexes...';

CREATE INDEX IX_ReportConfig_MessageRecipientId
    ON CAMT.ReportConfig (MessageRecipientId);
PRINT '  ✓ IX_ReportConfig_MessageRecipientId created.';

    -- Filtered unique index: allows multiple NULLs during in-flight transactions
CREATE UNIQUE INDEX UX_ReportConfig_ConfigId
    ON CAMT.ReportConfig (ConfigId)
    WHERE ConfigId IS NOT NULL;
PRINT '  ✓ UX_ReportConfig_ConfigId created.';

    -- Composite index for the scheduled path's top-level filter/sort.
    -- INCLUDE list is provisional pending the final query projection; current
    -- projection (ConfigurationRowMappers.REPORT_CONFIG) selects every
    -- ReportConfig column, so INCLUDE here only benefits Id and
    -- MessageRecipientId specifically — revisit if that SELECT list changes.
CREATE INDEX IX_ReportConfig_TypeFrequencyActive
    ON CAMT.ReportConfig (ReportType, ReportFrequency, IsActive)
    INCLUDE (Id, MessageRecipientId);
PRINT '  ✓ IX_ReportConfig_TypeFrequencyActive created.';

    PRINT '✓ All ReportConfig indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. REPORT AGREEMENT SCOPE TABLE
    -- =========================================================================

    PRINT '>>> Creating ReportAgreementScope table...';

CREATE TABLE CAMT.ReportAgreementScope
(
    Id               BIGINT NOT NULL IDENTITY(1,1),
    ReportConfigId   BIGINT NOT NULL,
    AgreementScopeId BIGINT NOT NULL,
    CreatedAt        DATETIME2 NOT NULL,
    CreatedBy        NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_ReportAgreementScope PRIMARY KEY (Id),
    CONSTRAINT UX_ReportAgreementScope_Unique UNIQUE (ReportConfigId, AgreementScopeId),
    CONSTRAINT FK_ReportAgreementScope_ReportConfig
        FOREIGN KEY (ReportConfigId)   REFERENCES CAMT.ReportConfig(Id),
    CONSTRAINT FK_ReportAgreementScope_AgreementScope
        FOREIGN KEY (AgreementScopeId) REFERENCES CAMT.AgreementScope(Id)
);

PRINT '✓ CAMT.ReportAgreementScope created successfully.';
    PRINT '';

    PRINT '>>> Creating ReportAgreementScope indexes...';

CREATE INDEX IX_ReportAgreementScope_Scope
    ON CAMT.ReportAgreementScope (AgreementScopeId);
PRINT '  ✓ IX_ReportAgreementScope_Scope created.';

    PRINT '✓ All ReportAgreementScope indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying tables exist...';

    DECLARE @TablesExist TABLE (TableName NVARCHAR(100), TableExists BIT);

INSERT INTO @TablesExist VALUES
                             ('CAMT.ReportConfig', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.ReportConfig')) THEN 1 ELSE 0 END),
                             ('CAMT.ReportAgreementScope', CASE WHEN EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.ReportAgreementScope')) THEN 1 ELSE 0 END);

SELECT
    CASE WHEN TableExists = 1 THEN '  ✓ ' ELSE '  ✗ ' END + TableName AS Status
FROM @TablesExist;

IF EXISTS (SELECT 1 FROM @TablesExist WHERE TableExists = 0)
        RAISERROR('ERROR: One or more tables were not created successfully!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

    -- =========================================================================
    -- 4. FINAL SUMMARY - ALL TABLES
    -- =========================================================================

    PRINT '>>> Final Summary: All tables in CAMT schema';
    PRINT '========================================';

SELECT
    '  ' + OBJECT_NAME(object_id) AS TableName,
    CASE
        WHEN type_desc = 'USER_TABLE' THEN '✓ Table'
        ELSE type_desc
        END AS ObjectType
FROM sys.objects
WHERE OBJECT_SCHEMA_NAME(object_id) = 'CAMT'
  AND type_desc = 'USER_TABLE'
ORDER BY OBJECT_NAME(object_id);

-- Get total table count
DECLARE @TotalTables INT;
SELECT @TotalTables = COUNT(*)
FROM sys.objects
WHERE OBJECT_SCHEMA_NAME(object_id) = 'CAMT'
  AND type_desc = 'USER_TABLE';

PRINT '========================================';
    PRINT '  Total tables in CAMT schema: ' + CAST(@TotalTables AS VARCHAR);
    PRINT '========================================';
    PRINT '';

    -- =========================================================================
    -- 5. FOREIGN KEY VALIDATION
    -- =========================================================================

    PRINT '>>> Foreign Key Constraint Summary:';

SELECT
    '  ' + fk.name AS ConstraintName,
    OBJECT_NAME(fk.parent_object_id) AS TableName,
    OBJECT_NAME(fk.referenced_object_id) AS ReferencedTable,
    CASE WHEN fk.is_disabled = 1 THEN 'DISABLED' ELSE 'ENABLED' END AS Status
FROM sys.foreign_keys fk
WHERE OBJECT_SCHEMA_NAME(fk.parent_object_id) = 'CAMT'
ORDER BY OBJECT_NAME(fk.parent_object_id);

PRINT '';

    -- Check for any disabled FKs
    IF EXISTS (
        SELECT 1
        FROM sys.foreign_keys fk
        WHERE OBJECT_SCHEMA_NAME(fk.parent_object_id) = 'CAMT'
            AND fk.is_disabled = 1
    )
BEGIN
        PRINT '⚠ WARNING: Some foreign keys are disabled!';
        PRINT '  Please investigate and enable them if needed.';
END
ELSE
BEGIN
        PRINT '✓ All foreign keys are enabled.';
END

    PRINT '';

COMMIT TRANSACTION;
PRINT '========================================';
    PRINT '✓ SCRIPT 05 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 05 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO