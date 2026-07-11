-- =============================================================================
-- SCRIPT: 97-schema-batch.sql
-- PURPOSE: Create Spring Batch's JobRepository metadata tables (BATCH_JOB_*,
--          BATCH_STEP_*) and their identity sequences
-- EXECUTION: Run after 96-drop-batch.sql, before 98-drop-quartz.sql
--
-- NOTE: These tables live in the dbo schema, not CAMT - 00-drop-all.sql does
-- not touch them (see 96-drop-batch.sql instead, same split as Quartz's
-- 98-drop-quartz.sql / 99-schema-quartz.sql pair). Numbered 96/97, ahead of
-- Quartz's 98/99, so both pairs sit together at the end of the chain, outside
-- the 00-08 CAMT-schema range. Table/column layout is taken verbatim from
-- Spring Batch 6.0.4's own org/springframework/batch/core/schema-sqlserver.sql
-- (extracted from the resolved spring-batch-core jar), not hand-written, since
-- this is the exact DDL Spring Batch's JobRepository/JobExplorer expect.
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 97: SPRING BATCH JOB REPOSITORY';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
    BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. BATCH TABLES
    -- =========================================================================

    PRINT '>>> Creating Spring Batch tables...';

    CREATE TABLE [dbo].[BATCH_JOB_INSTANCE] (
        [JOB_INSTANCE_ID] bigint        NOT NULL PRIMARY KEY,
        [VERSION]         bigint        NULL,
        [JOB_NAME]        nvarchar(100) NOT NULL,
        [JOB_KEY]         nvarchar(32)  NOT NULL,
        CONSTRAINT [JOB_INST_UN] UNIQUE ([JOB_NAME], [JOB_KEY])
    );
    PRINT '  ✓ BATCH_JOB_INSTANCE created.';

    CREATE TABLE [dbo].[BATCH_JOB_EXECUTION] (
        [JOB_EXECUTION_ID] bigint        NOT NULL PRIMARY KEY,
        [VERSION]          bigint        NULL,
        [JOB_INSTANCE_ID]  bigint        NOT NULL,
        [CREATE_TIME]      datetime      NOT NULL,
        [START_TIME]       datetime      DEFAULT NULL,
        [END_TIME]         datetime      DEFAULT NULL,
        [STATUS]           nvarchar(10)  NULL,
        [EXIT_CODE]        nvarchar(2500) NULL,
        [EXIT_MESSAGE]     nvarchar(2500) NULL,
        [LAST_UPDATED]     datetime      NULL,
        CONSTRAINT [JOB_INST_EXEC_FK] FOREIGN KEY ([JOB_INSTANCE_ID])
            REFERENCES [dbo].[BATCH_JOB_INSTANCE] ([JOB_INSTANCE_ID])
    );
    PRINT '  ✓ BATCH_JOB_EXECUTION created.';

    CREATE TABLE [dbo].[BATCH_JOB_EXECUTION_PARAMS] (
        [JOB_EXECUTION_ID] bigint        NOT NULL,
        [PARAMETER_NAME]   nvarchar(100) NOT NULL,
        [PARAMETER_TYPE]   nvarchar(100) NOT NULL,
        [PARAMETER_VALUE]  nvarchar(2500) NULL,
        [IDENTIFYING]      char(1)       NOT NULL,
        CONSTRAINT [JOB_EXEC_PARAMS_FK] FOREIGN KEY ([JOB_EXECUTION_ID])
            REFERENCES [dbo].[BATCH_JOB_EXECUTION] ([JOB_EXECUTION_ID])
    );
    PRINT '  ✓ BATCH_JOB_EXECUTION_PARAMS created.';

    CREATE TABLE [dbo].[BATCH_STEP_EXECUTION] (
        [STEP_EXECUTION_ID]  bigint        NOT NULL PRIMARY KEY,
        [VERSION]            bigint        NOT NULL,
        [STEP_NAME]          nvarchar(100) NOT NULL,
        [JOB_EXECUTION_ID]   bigint        NOT NULL,
        [CREATE_TIME]        datetime      NOT NULL,
        [START_TIME]         datetime      DEFAULT NULL,
        [END_TIME]           datetime      DEFAULT NULL,
        [STATUS]             nvarchar(10)  NULL,
        [COMMIT_COUNT]       bigint        NULL,
        [READ_COUNT]         bigint        NULL,
        [FILTER_COUNT]       bigint        NULL,
        [WRITE_COUNT]        bigint        NULL,
        [READ_SKIP_COUNT]    bigint        NULL,
        [WRITE_SKIP_COUNT]   bigint        NULL,
        [PROCESS_SKIP_COUNT] bigint        NULL,
        [ROLLBACK_COUNT]     bigint        NULL,
        [EXIT_CODE]          nvarchar(2500) NULL,
        [EXIT_MESSAGE]       nvarchar(2500) NULL,
        [LAST_UPDATED]       datetime      NULL,
        CONSTRAINT [JOB_EXEC_STEP_FK] FOREIGN KEY ([JOB_EXECUTION_ID])
            REFERENCES [dbo].[BATCH_JOB_EXECUTION] ([JOB_EXECUTION_ID])
    );
    PRINT '  ✓ BATCH_STEP_EXECUTION created.';

    CREATE TABLE [dbo].[BATCH_STEP_EXECUTION_CONTEXT] (
        [STEP_EXECUTION_ID]  bigint         NOT NULL PRIMARY KEY,
        [SHORT_CONTEXT]      nvarchar(2500) NOT NULL,
        [SERIALIZED_CONTEXT] nvarchar(max)  NULL,
        CONSTRAINT [STEP_EXEC_CTX_FK] FOREIGN KEY ([STEP_EXECUTION_ID])
            REFERENCES [dbo].[BATCH_STEP_EXECUTION] ([STEP_EXECUTION_ID])
    );
    PRINT '  ✓ BATCH_STEP_EXECUTION_CONTEXT created.';

    CREATE TABLE [dbo].[BATCH_JOB_EXECUTION_CONTEXT] (
        [JOB_EXECUTION_ID]   bigint         NOT NULL PRIMARY KEY,
        [SHORT_CONTEXT]      nvarchar(2500) NOT NULL,
        [SERIALIZED_CONTEXT] nvarchar(max)  NULL,
        CONSTRAINT [JOB_EXEC_CTX_FK] FOREIGN KEY ([JOB_EXECUTION_ID])
            REFERENCES [dbo].[BATCH_JOB_EXECUTION] ([JOB_EXECUTION_ID])
    );
    PRINT '  ✓ BATCH_JOB_EXECUTION_CONTEXT created.';

    PRINT '✓ All Spring Batch tables created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. IDENTITY SEQUENCES
    -- =========================================================================

    PRINT '>>> Creating Spring Batch identity sequences...';

    CREATE SEQUENCE [dbo].[BATCH_STEP_EXECUTION_SEQ] START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 NO CACHE NO CYCLE;
    PRINT '  ✓ BATCH_STEP_EXECUTION_SEQ created.';

    CREATE SEQUENCE [dbo].[BATCH_JOB_EXECUTION_SEQ] START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 NO CACHE NO CYCLE;
    PRINT '  ✓ BATCH_JOB_EXECUTION_SEQ created.';

    CREATE SEQUENCE [dbo].[BATCH_JOB_INSTANCE_SEQ] START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 NO CACHE NO CYCLE;
    PRINT '  ✓ BATCH_JOB_INSTANCE_SEQ created.';

    PRINT '✓ All Spring Batch sequences created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying tables and sequences exist...';

    DECLARE @TablesExist TABLE (TableName NVARCHAR(100), TableExists BIT);

    INSERT INTO @TablesExist VALUES
        ('BATCH_JOB_INSTANCE',         CASE WHEN OBJECT_ID(N'[dbo].[BATCH_JOB_INSTANCE]', N'U')         IS NOT NULL THEN 1 ELSE 0 END),
        ('BATCH_JOB_EXECUTION',        CASE WHEN OBJECT_ID(N'[dbo].[BATCH_JOB_EXECUTION]', N'U')        IS NOT NULL THEN 1 ELSE 0 END),
        ('BATCH_JOB_EXECUTION_PARAMS', CASE WHEN OBJECT_ID(N'[dbo].[BATCH_JOB_EXECUTION_PARAMS]', N'U') IS NOT NULL THEN 1 ELSE 0 END),
        ('BATCH_STEP_EXECUTION',       CASE WHEN OBJECT_ID(N'[dbo].[BATCH_STEP_EXECUTION]', N'U')       IS NOT NULL THEN 1 ELSE 0 END),
        ('BATCH_STEP_EXECUTION_CONTEXT',CASE WHEN OBJECT_ID(N'[dbo].[BATCH_STEP_EXECUTION_CONTEXT]', N'U') IS NOT NULL THEN 1 ELSE 0 END),
        ('BATCH_JOB_EXECUTION_CONTEXT',CASE WHEN OBJECT_ID(N'[dbo].[BATCH_JOB_EXECUTION_CONTEXT]', N'U') IS NOT NULL THEN 1 ELSE 0 END),
        ('BATCH_STEP_EXECUTION_SEQ',   CASE WHEN EXISTS (SELECT 1 FROM sys.sequences WHERE name = 'BATCH_STEP_EXECUTION_SEQ' AND SCHEMA_NAME(schema_id) = 'dbo') THEN 1 ELSE 0 END),
        ('BATCH_JOB_EXECUTION_SEQ',    CASE WHEN EXISTS (SELECT 1 FROM sys.sequences WHERE name = 'BATCH_JOB_EXECUTION_SEQ' AND SCHEMA_NAME(schema_id) = 'dbo') THEN 1 ELSE 0 END),
        ('BATCH_JOB_INSTANCE_SEQ',     CASE WHEN EXISTS (SELECT 1 FROM sys.sequences WHERE name = 'BATCH_JOB_INSTANCE_SEQ' AND SCHEMA_NAME(schema_id) = 'dbo') THEN 1 ELSE 0 END);

    SELECT
        CASE WHEN TableExists = 1 THEN '  ✓ ' ELSE '  ✗ ' END + TableName AS Status
    FROM @TablesExist;

    IF EXISTS (SELECT 1 FROM @TablesExist WHERE TableExists = 0)
        RAISERROR('ERROR: One or more Spring Batch objects were not created successfully!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

    COMMIT TRANSACTION;
    PRINT '========================================';
    PRINT '✓ SCRIPT 97 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 97 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO
