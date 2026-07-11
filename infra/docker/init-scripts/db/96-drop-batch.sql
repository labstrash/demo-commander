-- =============================================================================
-- SCRIPT: 96-drop-batch.sql
-- PURPOSE: Complete cleanup of Spring Batch (BATCH_*) job repository objects
-- EXECUTION: Run before 97-schema-batch.sql
--
-- Spring Batch tables/sequences live in the dbo schema, not CAMT, so they are
-- not touched by 00-drop-all.sql. Kept as its own drop/create pair (96/97),
-- numbered ahead of Quartz's 98-drop-quartz.sql / 99-schema-quartz.sql pair
-- (same reasoning: unrelated to the CAMT schema, dropped/created outside
-- 00-drop-all.sql). Scoped strictly to dbo objects named 'BATCH_%' so this
-- can never touch unrelated dbo objects.
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 96: DROP SPRING BATCH OBJECTS';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
    BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. DROP FOREIGN KEY CONSTRAINTS
    -- =========================================================================
    PRINT '>>> Step 1: Dropping Spring Batch foreign key constraints...';

    DECLARE @DropFKSQL NVARCHAR(MAX) = '';

SELECT @DropFKSQL = @DropFKSQL +
                    'ALTER TABLE [dbo].[' + OBJECT_NAME(fk.parent_object_id) + '] ' +
                    'DROP CONSTRAINT [' + fk.name + '];' + CHAR(13)
FROM sys.foreign_keys fk
WHERE SCHEMA_NAME(fk.schema_id) = 'dbo'
  AND OBJECT_NAME(fk.parent_object_id) LIKE 'BATCH[_]%';

IF LEN(@DropFKSQL) > 0
BEGIN
EXEC sp_executesql @DropFKSQL;
        PRINT '  ✓ All Spring Batch foreign key constraints dropped.';
END
ELSE
BEGIN
        PRINT '  ✓ No Spring Batch foreign key constraints found.';
END
    PRINT '';

    -- =========================================================================
    -- 2. DROP TABLES
    -- =========================================================================
    PRINT '>>> Step 2: Dropping Spring Batch tables...';

    DECLARE @DropTableSQL NVARCHAR(MAX) = '';

SELECT @DropTableSQL = @DropTableSQL +
                       'DROP TABLE [dbo].[' + name + '];' + CHAR(13)
FROM sys.tables
WHERE SCHEMA_NAME(schema_id) = 'dbo'
  AND name LIKE 'BATCH[_]%';

IF LEN(@DropTableSQL) > 0
BEGIN
EXEC sp_executesql @DropTableSQL;
        PRINT '  ✓ All Spring Batch tables dropped.';
END
ELSE
BEGIN
        PRINT '  ✓ No Spring Batch tables found.';
END
    PRINT '';

    -- =========================================================================
    -- 3. DROP SEQUENCES
    -- =========================================================================
    PRINT '>>> Step 3: Dropping Spring Batch sequences...';

    DECLARE @DropSequenceSQL NVARCHAR(MAX) = '';

SELECT @DropSequenceSQL = @DropSequenceSQL +
                          'DROP SEQUENCE [dbo].[' + name + '];' + CHAR(13)
FROM sys.sequences
WHERE SCHEMA_NAME(schema_id) = 'dbo'
  AND name LIKE 'BATCH[_]%';

IF LEN(@DropSequenceSQL) > 0
BEGIN
EXEC sp_executesql @DropSequenceSQL;
        PRINT '  ✓ All Spring Batch sequences dropped.';
END
ELSE
BEGIN
        PRINT '  ✓ No Spring Batch sequences found.';
END
    PRINT '';

    -- =========================================================================
    -- 4. VERIFICATION
    -- =========================================================================
    PRINT '>>> Verification: Confirming no Spring Batch objects remain...';

    DECLARE @RemainingCount INT;
SELECT @RemainingCount =
    (SELECT COUNT(*) FROM sys.tables WHERE SCHEMA_NAME(schema_id) = 'dbo' AND name LIKE 'BATCH[_]%') +
    (SELECT COUNT(*) FROM sys.sequences WHERE SCHEMA_NAME(schema_id) = 'dbo' AND name LIKE 'BATCH[_]%');

IF @RemainingCount = 0
BEGIN
        PRINT '  ✓ No Spring Batch objects remain.';
END
ELSE
BEGIN
        RAISERROR('ERROR: Spring Batch objects still exist after drop attempt!', 16, 1);
END
    PRINT '';

    COMMIT TRANSACTION;
    PRINT '========================================';
    PRINT '✓ SCRIPT 96 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 96 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO
