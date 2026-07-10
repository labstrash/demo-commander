-- =============================================================================
-- SCRIPT: 98-drop-quartz.sql
-- PURPOSE: Complete cleanup of Quartz (QRTZ_*) job store objects
-- EXECUTION: Run before 99-schema-quartz.sql
--
-- Quartz tables live in the dbo schema, not CAMT, so they are not touched by
-- 00-drop-all.sql. Kept as its own drop/create pair (98/99) rather than
-- folded into 00-drop-all.sql, since Quartz is unrelated to the CAMT schema.
-- Scoped strictly to dbo tables named 'QRTZ_%' so this can never touch
-- unrelated dbo objects.
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 98: DROP QUARTZ OBJECTS';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
    BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. DROP FOREIGN KEY CONSTRAINTS
    -- =========================================================================
    PRINT '>>> Step 1: Dropping Quartz foreign key constraints...';

    DECLARE @DropFKSQL NVARCHAR(MAX) = '';

SELECT @DropFKSQL = @DropFKSQL +
                    'ALTER TABLE [dbo].[' + OBJECT_NAME(fk.parent_object_id) + '] ' +
                    'DROP CONSTRAINT [' + fk.name + '];' + CHAR(13)
FROM sys.foreign_keys fk
WHERE SCHEMA_NAME(fk.schema_id) = 'dbo'
  AND OBJECT_NAME(fk.parent_object_id) LIKE 'QRTZ[_]%';

IF LEN(@DropFKSQL) > 0
BEGIN
EXEC sp_executesql @DropFKSQL;
        PRINT '  ✓ All Quartz foreign key constraints dropped.';
END
ELSE
BEGIN
        PRINT '  ✓ No Quartz foreign key constraints found.';
END
    PRINT '';

    -- =========================================================================
    -- 2. DROP TABLES
    -- =========================================================================
    PRINT '>>> Step 2: Dropping Quartz tables...';

    DECLARE @DropTableSQL NVARCHAR(MAX) = '';

SELECT @DropTableSQL = @DropTableSQL +
                       'DROP TABLE [dbo].[' + name + '];' + CHAR(13)
FROM sys.tables
WHERE SCHEMA_NAME(schema_id) = 'dbo'
  AND name LIKE 'QRTZ[_]%';

IF LEN(@DropTableSQL) > 0
BEGIN
EXEC sp_executesql @DropTableSQL;
        PRINT '  ✓ All Quartz tables dropped.';
END
ELSE
BEGIN
        PRINT '  ✓ No Quartz tables found.';
END
    PRINT '';

    -- =========================================================================
    -- 3. VERIFICATION
    -- =========================================================================
    PRINT '>>> Verification: Confirming no Quartz objects remain...';

    DECLARE @RemainingCount INT;
SELECT @RemainingCount = COUNT(*)
FROM sys.tables
WHERE SCHEMA_NAME(schema_id) = 'dbo'
  AND name LIKE 'QRTZ[_]%';

IF @RemainingCount = 0
BEGIN
        PRINT '  ✓ No Quartz tables remain.';
END
ELSE
BEGIN
        RAISERROR('ERROR: Quartz tables still exist after drop attempt!', 16, 1);
END
    PRINT '';

    COMMIT TRANSACTION;
    PRINT '========================================';
    PRINT '✓ SCRIPT 98 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 98 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO
