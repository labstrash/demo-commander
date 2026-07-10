-- =============================================================================
-- SCRIPT: 07-schema-fetch-config.sql
-- PURPOSE: TVP type for id-set parameters, supporting the "Report
--          Configuration Fetch & Assembly" feature.
-- EXECUTION: Run after 06-schema-audit-deadletter.sql
--
-- NOTE: dbo.BigIntIdList lives in the dbo schema, not CAMT — 00-drop-all.sql
-- must drop it explicitly since it isn't covered by the CAMT-schema cleanup.
-- The related composite index lives in 05-schema-report.sql instead,
-- alongside CAMT.ReportConfig's other indexes.
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 07: FETCH & ASSEMBLY SUPPORT';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
    BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. TVP USER-DEFINED TABLE TYPE FOR ID-SET PARAMETERS
    -- =========================================================================

    PRINT '>>> Creating BigIntIdList table type...';

    -- Must match TvpParameterSource.BIGINT_ID_LIST_TYPE ("dbo.BigIntIdList") exactly.
    CREATE TYPE dbo.BigIntIdList AS TABLE (Id BIGINT NOT NULL PRIMARY KEY);

    PRINT '✓ dbo.BigIntIdList created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying objects exist...';

    IF EXISTS (SELECT 1 FROM sys.types WHERE is_table_type = 1 AND name = 'BigIntIdList')
        PRINT '  ✓ dbo.BigIntIdList exists.';
    ELSE
        RAISERROR('  ✗ dbo.BigIntIdList NOT found!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

    COMMIT TRANSACTION;
    PRINT '========================================';
    PRINT '✓ SCRIPT 07 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 07 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO
