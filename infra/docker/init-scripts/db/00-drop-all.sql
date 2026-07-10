-- =============================================================================
-- SCRIPT: 00-drop-all.sql
-- PURPOSE: Complete cleanup of CAMT schema - drops all objects and the schema itself
-- EXECUTION: Run this first to start with a clean slate
-- =============================================================================
--
-- NOTE: SQL Server DROP SCHEMA does NOT support CASCADE.
-- This script explicitly drops all objects in the correct dependency order.
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 00: DROP ALL CAMT OBJECTS';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';
PRINT 'WARNING: This will permanently delete ALL objects in the CAMT schema.';
PRINT 'Ensure you have a backup before proceeding.';
PRINT '';

BEGIN TRY
BEGIN TRANSACTION;

    -- Check if CAMT schema exists
    IF EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'CAMT')
BEGIN
        PRINT '>>> CAMT schema exists. Dropping all objects in dependency order...';
        PRINT '';

        -- =====================================================================
        -- 1. DROP FOREIGN KEY CONSTRAINTS (child tables first)
        -- =====================================================================
        PRINT '>>> Step 1: Dropping foreign key constraints...';

        -- Drop all foreign keys in CAMT schema
        DECLARE @DropFKSQL NVARCHAR(MAX) = '';

SELECT @DropFKSQL = @DropFKSQL +
                    'ALTER TABLE [' + SCHEMA_NAME(fk.schema_id) + '].[' + OBJECT_NAME(fk.parent_object_id) + '] ' +
                    'DROP CONSTRAINT [' + fk.name + '];' + CHAR(13)
FROM sys.foreign_keys fk
WHERE SCHEMA_NAME(fk.schema_id) = 'CAMT'
ORDER BY OBJECT_NAME(fk.parent_object_id);

IF LEN(@DropFKSQL) > 0
BEGIN
EXEC sp_executesql @DropFKSQL;
            PRINT '  ✓ All foreign key constraints dropped.';
END
ELSE
BEGIN
            PRINT '  ✓ No foreign key constraints found.';
END
        PRINT '';

        -- =====================================================================
        -- 2. DROP TABLES (in reverse dependency order - child tables first)
        -- =====================================================================
        PRINT '>>> Step 2: Dropping tables...';

        -- Define drop order (child tables first, parent tables last)
        DECLARE @DropTableSQL NVARCHAR(MAX) = '';

        -- Build dynamic DROP TABLE statements in correct order
SELECT @DropTableSQL = @DropTableSQL +
                       'DROP TABLE [' + SCHEMA_NAME(schema_id) + '].[' + name + '];' + CHAR(13)
FROM sys.tables
WHERE SCHEMA_NAME(schema_id) = 'CAMT'
ORDER BY
    -- Order by dependency (child tables first)
    CASE name
    -- Leaf tables (no dependents)
    WHEN 'AliasAssignment' THEN 1
    WHEN 'AccountAssignment' THEN 2
    WHEN 'PaymentTypeAssignment' THEN 3
    -- Scope level
    WHEN 'AgreementScope' THEN 4
    WHEN 'ReportAgreementScope' THEN 5
    -- Report config
    WHEN 'ReportConfig' THEN 6
    -- Agreement version and contacts
    WHEN 'AgreementVersion' THEN 7
    WHEN 'AgreementContact' THEN 8
    -- Agreement parent
    WHEN 'Agreement' THEN 9
    -- Recipient (referenced by multiple tables)
    WHEN 'Recipient' THEN 10
    -- Sequence table
    WHEN 'AgreementSequence' THEN 11
    -- Reference tables (no dependencies)
    WHEN 'ReportFrequency' THEN 12
    WHEN 'PaymentType' THEN 13
    WHEN 'ReportType' THEN 14
    ELSE 99
END;

        IF LEN(@DropTableSQL) > 0
BEGIN
EXEC sp_executesql @DropTableSQL;
            PRINT '  ✓ All tables dropped.';
END
ELSE
BEGIN
            PRINT '  ✓ No tables found.';
END
        PRINT '';

        -- =====================================================================
        -- 3. DROP STORED PROCEDURES
        -- =====================================================================
        PRINT '>>> Step 3: Dropping stored procedures...';

        DECLARE @DropProcSQL NVARCHAR(MAX) = '';

SELECT @DropProcSQL = @DropProcSQL +
                      'DROP PROCEDURE [' + SCHEMA_NAME(schema_id) + '].[' + name + '];' + CHAR(13)
FROM sys.procedures
WHERE SCHEMA_NAME(schema_id) = 'CAMT';

IF LEN(@DropProcSQL) > 0
BEGIN
EXEC sp_executesql @DropProcSQL;
            PRINT '  ✓ All stored procedures dropped.';
END
ELSE
BEGIN
            PRINT '  ✓ No stored procedures found.';
END
        PRINT '';

        -- =====================================================================
        -- 4. DROP VIEWS (if any exist)
        -- =====================================================================
        PRINT '>>> Step 4: Dropping views...';

        DECLARE @DropViewSQL NVARCHAR(MAX) = '';

SELECT @DropViewSQL = @DropViewSQL +
                      'DROP VIEW [' + SCHEMA_NAME(schema_id) + '].[' + name + '];' + CHAR(13)
FROM sys.views
WHERE SCHEMA_NAME(schema_id) = 'CAMT';

IF LEN(@DropViewSQL) > 0
BEGIN
EXEC sp_executesql @DropViewSQL;
            PRINT '  ✓ All views dropped.';
END
ELSE
BEGIN
            PRINT '  ✓ No views found.';
END
        PRINT '';

        -- =====================================================================
        -- 5. DROP USER-DEFINED FUNCTIONS (if any exist)
        -- =====================================================================
        PRINT '>>> Step 5: Dropping user-defined functions...';

        DECLARE @DropFunctionSQL NVARCHAR(MAX) = '';

SELECT @DropFunctionSQL = @DropFunctionSQL +
                          'DROP FUNCTION [' + SCHEMA_NAME(schema_id) + '].[' + name + '];' + CHAR(13)
FROM sys.objects
WHERE SCHEMA_NAME(schema_id) = 'CAMT'
  AND type IN ('FN', 'IF', 'TF'); -- Scalar, inline table, multi-statement table functions

IF LEN(@DropFunctionSQL) > 0
BEGIN
EXEC sp_executesql @DropFunctionSQL;
            PRINT '  ✓ All user-defined functions dropped.';
END
ELSE
BEGIN
            PRINT '  ✓ No user-defined functions found.';
END
        PRINT '';

        -- =====================================================================
        -- 6. DROP THE SCHEMA
        -- =====================================================================
        PRINT '>>> Step 6: Dropping CAMT schema...';

        -- Verify schema is empty before dropping
        DECLARE @ObjectCount INT;

SELECT @ObjectCount = COUNT(*)
FROM sys.objects
WHERE SCHEMA_NAME(schema_id) = 'CAMT';

IF @ObjectCount = 0
BEGIN
DROP SCHEMA CAMT;
PRINT '  ✓ CAMT schema dropped successfully.';
END
ELSE
BEGIN
            PRINT '  ⚠ WARNING: ' + CAST(@ObjectCount AS VARCHAR) + ' objects remain in CAMT schema.';
            PRINT '  Listing remaining objects:';

SELECT
    '    - ' + TYPE_DESC COLLATE DATABASE_DEFAULT + ': ' + NAME AS RemainingObject
FROM sys.objects
WHERE SCHEMA_NAME(schema_id) = 'CAMT'
ORDER BY TYPE_DESC, NAME;

RAISERROR('Cannot drop schema - objects remain. Manual cleanup required.', 16, 1);
END
        PRINT '';

        -- =====================================================================
        -- 7. VERIFICATION
        -- =====================================================================
        PRINT '>>> Verification: Confirming schema is dropped...';

        IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'CAMT')
BEGIN
            PRINT '  ✓ CAMT schema is completely removed.';
END
ELSE
BEGIN
            RAISERROR('ERROR: CAMT schema still exists after drop attempt!', 16, 1);
END
        PRINT '';

END
ELSE
BEGIN
        PRINT '>>> CAMT schema does not exist - nothing to drop.';
        PRINT '';
END

        -- =====================================================================
        -- 8. DROP dbo.BigIntIdList TVP TYPE
        --
        -- Lives in the dbo schema, not CAMT, so it isn't covered by the
        -- CAMT-schema cleanup above. Table types cannot be ALTERed, so a
        -- clean re-deploy must drop and recreate it.
        -- =====================================================================
        PRINT '>>> Step 8: Dropping dbo.BigIntIdList table type...';

        IF EXISTS (SELECT 1 FROM sys.types WHERE is_table_type = 1 AND name = 'BigIntIdList' AND SCHEMA_NAME(schema_id) = 'dbo')
BEGIN
DROP TYPE dbo.BigIntIdList;
PRINT '  ✓ dbo.BigIntIdList dropped successfully.';
END
ELSE
BEGIN
            PRINT '  ✓ dbo.BigIntIdList does not exist - nothing to drop.';
END
        PRINT '';

COMMIT TRANSACTION;
PRINT '========================================';
    PRINT '✓ SCRIPT 00 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 00 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT 'ERROR SEVERITY: ' + CAST(ERROR_SEVERITY() AS VARCHAR);
    PRINT 'ERROR STATE: ' + CAST(ERROR_STATE() AS VARCHAR);
    PRINT '========================================';
    PRINT '';
    PRINT 'Common causes:';
    PRINT '  - Foreign key constraints referencing tables outside CAMT schema';
    PRINT '  - Objects in other schemas referencing CAMT objects';
    PRINT '  - Open transactions or connections using CAMT objects';
    PRINT '';
    PRINT 'To resolve:';
    PRINT '  1. Check for dependencies using:';
    PRINT '     SELECT * FROM sys.sql_expression_dependencies WHERE referenced_schema_name = ''CAMT'';';
    PRINT '  2. Drop any external dependencies manually';
    PRINT '  3. Ensure no other connections are using the schema';
    PRINT '';

    THROW;
END CATCH
GO