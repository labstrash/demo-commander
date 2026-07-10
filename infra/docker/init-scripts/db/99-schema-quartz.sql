-- =============================================================================
-- SCRIPT: 99-schema-quartz.sql
-- PURPOSE: Create Quartz JDBC job store tables, foreign keys, and indexes
-- EXECUTION: Run last, after all CAMT-schema scripts. Numbered 99 (rather
-- than sequentially) since Quartz is unrelated to the CAMT schema — this
-- leaves room to add more CAMT-related scripts without renumbering.
--
-- NOTE: These tables live in the dbo schema, not CAMT — 00-drop-all.sql
-- must drop them explicitly since they aren't covered by the CAMT-schema
-- cleanup. Table/column layout matches the standard Quartz SQL Server schema.
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 99: QUARTZ JOB STORE';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
    BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. QUARTZ TABLES
    -- =========================================================================

    PRINT '>>> Creating Quartz tables...';

    CREATE TABLE [dbo].[QRTZ_JOB_DETAILS] (
        [SCHED_NAME]        nvarchar(120) NOT NULL,
        [JOB_NAME]          nvarchar(150) NOT NULL,
        [JOB_GROUP]         nvarchar(150) NOT NULL,
        [DESCRIPTION]       nvarchar(250) NULL,
        [JOB_CLASS_NAME]    nvarchar(250) NOT NULL,
        [IS_DURABLE]        bit           NOT NULL,
        [IS_NONCONCURRENT]  bit           NOT NULL,
        [IS_UPDATE_DATA]    bit           NOT NULL,
        [REQUESTS_RECOVERY] bit           NOT NULL,
        [JOB_DATA]          varbinary(max) NULL,
        CONSTRAINT [PK_QRTZ_JOB_DETAILS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [JOB_NAME], [JOB_GROUP])
    );
    PRINT '  ✓ QRTZ_JOB_DETAILS created.';

    CREATE TABLE [dbo].[QRTZ_TRIGGERS] (
        [SCHED_NAME]     nvarchar(120) NOT NULL,
        [TRIGGER_NAME]   nvarchar(150) NOT NULL,
        [TRIGGER_GROUP]  nvarchar(150) NOT NULL,
        [JOB_NAME]       nvarchar(150) NOT NULL,
        [JOB_GROUP]      nvarchar(150) NOT NULL,
        [DESCRIPTION]    nvarchar(250) NULL,
        [NEXT_FIRE_TIME] bigint        NULL,
        [PREV_FIRE_TIME] bigint        NULL,
        [PRIORITY]       int           NULL,
        [TRIGGER_STATE]  nvarchar(16)  NOT NULL,
        [TRIGGER_TYPE]   nvarchar(8)   NOT NULL,
        [START_TIME]     bigint        NOT NULL,
        [END_TIME]       bigint        NULL,
        [CALENDAR_NAME]  nvarchar(200) NULL,
        [MISFIRE_INSTR]  int           NULL,
        [JOB_DATA]       varbinary(max) NULL,
        CONSTRAINT [PK_QRTZ_TRIGGERS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
    );
    PRINT '  ✓ QRTZ_TRIGGERS created.';

    CREATE TABLE [dbo].[QRTZ_SIMPLE_TRIGGERS] (
        [SCHED_NAME]      nvarchar(120) NOT NULL,
        [TRIGGER_NAME]    nvarchar(150) NOT NULL,
        [TRIGGER_GROUP]   nvarchar(150) NOT NULL,
        [REPEAT_COUNT]    int           NOT NULL,
        [REPEAT_INTERVAL] bigint        NOT NULL,
        [TIMES_TRIGGERED] int           NOT NULL,
        CONSTRAINT [PK_QRTZ_SIMPLE_TRIGGERS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
    );
    PRINT '  ✓ QRTZ_SIMPLE_TRIGGERS created.';

    CREATE TABLE [dbo].[QRTZ_CRON_TRIGGERS] (
        [SCHED_NAME]      nvarchar(120) NOT NULL,
        [TRIGGER_NAME]    nvarchar(150) NOT NULL,
        [TRIGGER_GROUP]   nvarchar(150) NOT NULL,
        [CRON_EXPRESSION] nvarchar(120) NOT NULL,
        [TIME_ZONE_ID]    nvarchar(80)  NULL,
        CONSTRAINT [PK_QRTZ_CRON_TRIGGERS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
    );
    PRINT '  ✓ QRTZ_CRON_TRIGGERS created.';

    CREATE TABLE [dbo].[QRTZ_SIMPROP_TRIGGERS] (
        [SCHED_NAME]    nvarchar(120)  NOT NULL,
        [TRIGGER_NAME]  nvarchar(150)  NOT NULL,
        [TRIGGER_GROUP] nvarchar(150)  NOT NULL,
        [STR_PROP_1]    nvarchar(512)  NULL,
        [STR_PROP_2]    nvarchar(512)  NULL,
        [STR_PROP_3]    nvarchar(512)  NULL,
        [INT_PROP_1]    int            NULL,
        [INT_PROP_2]    int            NULL,
        [LONG_PROP_1]   bigint         NULL,
        [LONG_PROP_2]   bigint         NULL,
        [DEC_PROP_1]    numeric(13, 4) NULL,
        [DEC_PROP_2]    numeric(13, 4) NULL,
        [BOOL_PROP_1]   bit            NULL,
        [BOOL_PROP_2]   bit            NULL,
        CONSTRAINT [PK_QRTZ_SIMPROP_TRIGGERS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
    );
    PRINT '  ✓ QRTZ_SIMPROP_TRIGGERS created.';

    CREATE TABLE [dbo].[QRTZ_BLOB_TRIGGERS] (
        [SCHED_NAME]    nvarchar(120)  NOT NULL,
        [TRIGGER_NAME]  nvarchar(150)  NOT NULL,
        [TRIGGER_GROUP] nvarchar(150)  NOT NULL,
        [BLOB_DATA]     varbinary(max) NULL,
        CONSTRAINT [PK_QRTZ_BLOB_TRIGGERS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
    );
    PRINT '  ✓ QRTZ_BLOB_TRIGGERS created.';

    CREATE TABLE [dbo].[QRTZ_CALENDARS] (
        [SCHED_NAME]    nvarchar(120)  NOT NULL,
        [CALENDAR_NAME] nvarchar(200)  NOT NULL,
        [CALENDAR]      varbinary(max) NOT NULL,
        CONSTRAINT [PK_QRTZ_CALENDARS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [CALENDAR_NAME])
    );
    PRINT '  ✓ QRTZ_CALENDARS created.';

    CREATE TABLE [dbo].[QRTZ_PAUSED_TRIGGER_GRPS] (
        [SCHED_NAME]    nvarchar(120) NOT NULL,
        [TRIGGER_GROUP] nvarchar(150) NOT NULL,
        CONSTRAINT [PK_QRTZ_PAUSED_TRIGGER_GRPS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [TRIGGER_GROUP])
    );
    PRINT '  ✓ QRTZ_PAUSED_TRIGGER_GRPS created.';

    CREATE TABLE [dbo].[QRTZ_FIRED_TRIGGERS] (
        [SCHED_NAME]        nvarchar(120) NOT NULL,
        [ENTRY_ID]          nvarchar(140) NOT NULL,
        [TRIGGER_NAME]      nvarchar(150) NOT NULL,
        [TRIGGER_GROUP]     nvarchar(150) NOT NULL,
        [INSTANCE_NAME]     nvarchar(200) NOT NULL,
        [FIRED_TIME]        bigint        NOT NULL,
        [SCHED_TIME]        bigint        NOT NULL,
        [PRIORITY]          int           NOT NULL,
        [STATE]             nvarchar(16)  NOT NULL,
        [JOB_NAME]          nvarchar(150) NULL,
        [JOB_GROUP]         nvarchar(150) NULL,
        [IS_NONCONCURRENT]  bit           NOT NULL,
        [REQUESTS_RECOVERY] bit           NULL,
        CONSTRAINT [PK_QRTZ_FIRED_TRIGGERS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [ENTRY_ID])
    );
    PRINT '  ✓ QRTZ_FIRED_TRIGGERS created.';

    CREATE TABLE [dbo].[QRTZ_SCHEDULER_STATE] (
        [SCHED_NAME]        nvarchar(120) NOT NULL,
        [INSTANCE_NAME]     nvarchar(200) NOT NULL,
        [LAST_CHECKIN_TIME] bigint        NOT NULL,
        [CHECKIN_INTERVAL]  bigint        NOT NULL,
        CONSTRAINT [PK_QRTZ_SCHEDULER_STATE] PRIMARY KEY CLUSTERED ([SCHED_NAME], [INSTANCE_NAME])
    );
    PRINT '  ✓ QRTZ_SCHEDULER_STATE created.';

    CREATE TABLE [dbo].[QRTZ_LOCKS] (
        [SCHED_NAME] nvarchar(120) NOT NULL,
        [LOCK_NAME]  nvarchar(40)  NOT NULL,
        CONSTRAINT [PK_QRTZ_LOCKS] PRIMARY KEY CLUSTERED ([SCHED_NAME], [LOCK_NAME])
    );
    PRINT '  ✓ QRTZ_LOCKS created.';

    PRINT '✓ All Quartz tables created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. FOREIGN KEY CONSTRAINTS
    -- =========================================================================

    PRINT '>>> Creating Quartz foreign key constraints...';

    ALTER TABLE [dbo].[QRTZ_TRIGGERS]
        ADD CONSTRAINT [FK_QRTZ_TRIGGERS_JOB_DETAILS] FOREIGN KEY ([SCHED_NAME], [JOB_NAME], [JOB_GROUP])
        REFERENCES [dbo].[QRTZ_JOB_DETAILS] ([SCHED_NAME], [JOB_NAME], [JOB_GROUP]);
    PRINT '  ✓ FK_QRTZ_TRIGGERS_JOB_DETAILS created.';

    ALTER TABLE [dbo].[QRTZ_SIMPLE_TRIGGERS]
        ADD CONSTRAINT [FK_QRTZ_SIMPLE_TRIGGERS] FOREIGN KEY ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
        REFERENCES [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP]);
    PRINT '  ✓ FK_QRTZ_SIMPLE_TRIGGERS created.';

    ALTER TABLE [dbo].[QRTZ_CRON_TRIGGERS]
        ADD CONSTRAINT [FK_QRTZ_CRON_TRIGGERS] FOREIGN KEY ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
        REFERENCES [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP]);
    PRINT '  ✓ FK_QRTZ_CRON_TRIGGERS created.';

    ALTER TABLE [dbo].[QRTZ_SIMPROP_TRIGGERS]
        ADD CONSTRAINT [FK_QRTZ_SIMPROP_TRIGGERS] FOREIGN KEY ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
        REFERENCES [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP]);
    PRINT '  ✓ FK_QRTZ_SIMPROP_TRIGGERS created.';

    ALTER TABLE [dbo].[QRTZ_BLOB_TRIGGERS]
        ADD CONSTRAINT [FK_QRTZ_BLOB_TRIGGERS] FOREIGN KEY ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP])
        REFERENCES [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP]);
    PRINT '  ✓ FK_QRTZ_BLOB_TRIGGERS created.';

    PRINT '✓ All Quartz foreign key constraints created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. INDEXES
    -- =========================================================================

    PRINT '>>> Creating Quartz indexes...';

    CREATE INDEX [IDX_QRTZ_J_REQ_RECOVERY] ON [dbo].[QRTZ_JOB_DETAILS] ([SCHED_NAME], [REQUESTS_RECOVERY]);
    PRINT '  ✓ IDX_QRTZ_J_REQ_RECOVERY created.';

    CREATE INDEX [IDX_QRTZ_J_GRP] ON [dbo].[QRTZ_JOB_DETAILS] ([SCHED_NAME], [JOB_GROUP]);
    PRINT '  ✓ IDX_QRTZ_J_GRP created.';

    CREATE INDEX [IDX_QRTZ_T_J] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [JOB_NAME], [JOB_GROUP]);
    PRINT '  ✓ IDX_QRTZ_T_J created.';

    CREATE INDEX [IDX_QRTZ_T_JG] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [JOB_GROUP]);
    PRINT '  ✓ IDX_QRTZ_T_JG created.';

    CREATE INDEX [IDX_QRTZ_T_C] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [CALENDAR_NAME]);
    PRINT '  ✓ IDX_QRTZ_T_C created.';

    CREATE INDEX [IDX_QRTZ_T_G] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_GROUP]);
    PRINT '  ✓ IDX_QRTZ_T_G created.';

    CREATE INDEX [IDX_QRTZ_T_STATE] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_STATE]);
    PRINT '  ✓ IDX_QRTZ_T_STATE created.';

    CREATE INDEX [IDX_QRTZ_T_N_STATE] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP], [TRIGGER_STATE]);
    PRINT '  ✓ IDX_QRTZ_T_N_STATE created.';

    CREATE INDEX [IDX_QRTZ_T_N_G_STATE] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_GROUP], [TRIGGER_STATE]);
    PRINT '  ✓ IDX_QRTZ_T_N_G_STATE created.';

    CREATE INDEX [IDX_QRTZ_T_NEXT_FIRE_TIME] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [NEXT_FIRE_TIME]);
    PRINT '  ✓ IDX_QRTZ_T_NEXT_FIRE_TIME created.';

    CREATE INDEX [IDX_QRTZ_T_NFT_ST] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [TRIGGER_STATE], [NEXT_FIRE_TIME]);
    PRINT '  ✓ IDX_QRTZ_T_NFT_ST created.';

    CREATE INDEX [IDX_QRTZ_T_NFT_MISFIRE] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [MISFIRE_INSTR], [NEXT_FIRE_TIME]);
    PRINT '  ✓ IDX_QRTZ_T_NFT_MISFIRE created.';

    CREATE INDEX [IDX_QRTZ_T_NFT_ST_MISFIRE] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [MISFIRE_INSTR], [NEXT_FIRE_TIME], [TRIGGER_STATE]);
    PRINT '  ✓ IDX_QRTZ_T_NFT_ST_MISFIRE created.';

    CREATE INDEX [IDX_QRTZ_T_NFT_ST_MISFIRE_GRP] ON [dbo].[QRTZ_TRIGGERS] ([SCHED_NAME], [MISFIRE_INSTR], [NEXT_FIRE_TIME], [TRIGGER_GROUP], [TRIGGER_STATE]);
    PRINT '  ✓ IDX_QRTZ_T_NFT_ST_MISFIRE_GRP created.';

    CREATE INDEX [IDX_QRTZ_FT_TRIG_INST_NAME] ON [dbo].[QRTZ_FIRED_TRIGGERS] ([SCHED_NAME], [INSTANCE_NAME]);
    PRINT '  ✓ IDX_QRTZ_FT_TRIG_INST_NAME created.';

    CREATE INDEX [IDX_QRTZ_FT_INST_JOB_REQ_RCVRY] ON [dbo].[QRTZ_FIRED_TRIGGERS] ([SCHED_NAME], [INSTANCE_NAME], [REQUESTS_RECOVERY]);
    PRINT '  ✓ IDX_QRTZ_FT_INST_JOB_REQ_RCVRY created.';

    CREATE INDEX [IDX_QRTZ_FT_J_G] ON [dbo].[QRTZ_FIRED_TRIGGERS] ([SCHED_NAME], [JOB_NAME], [JOB_GROUP]);
    PRINT '  ✓ IDX_QRTZ_FT_J_G created.';

    CREATE INDEX [IDX_QRTZ_FT_JG] ON [dbo].[QRTZ_FIRED_TRIGGERS] ([SCHED_NAME], [JOB_GROUP]);
    PRINT '  ✓ IDX_QRTZ_FT_JG created.';

    CREATE INDEX [IDX_QRTZ_FT_T_G] ON [dbo].[QRTZ_FIRED_TRIGGERS] ([SCHED_NAME], [TRIGGER_NAME], [TRIGGER_GROUP]);
    PRINT '  ✓ IDX_QRTZ_FT_T_G created.';

    CREATE INDEX [IDX_QRTZ_FT_TG] ON [dbo].[QRTZ_FIRED_TRIGGERS] ([SCHED_NAME], [TRIGGER_GROUP]);
    PRINT '  ✓ IDX_QRTZ_FT_TG created.';

    PRINT '✓ All Quartz indexes created successfully.';
    PRINT '';

    -- =========================================================================
    -- 4. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying tables exist...';

    DECLARE @TablesExist TABLE (TableName NVARCHAR(100), TableExists BIT);

    INSERT INTO @TablesExist VALUES
        ('QRTZ_JOB_DETAILS',        CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_JOB_DETAILS]', N'U')        IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_TRIGGERS',           CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_TRIGGERS]', N'U')           IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_SIMPLE_TRIGGERS',    CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_SIMPLE_TRIGGERS]', N'U')    IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_CRON_TRIGGERS',      CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_CRON_TRIGGERS]', N'U')      IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_SIMPROP_TRIGGERS',   CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_SIMPROP_TRIGGERS]', N'U')   IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_BLOB_TRIGGERS',      CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_BLOB_TRIGGERS]', N'U')      IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_CALENDARS',          CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_CALENDARS]', N'U')          IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_PAUSED_TRIGGER_GRPS',CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_PAUSED_TRIGGER_GRPS]', N'U') IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_FIRED_TRIGGERS',     CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_FIRED_TRIGGERS]', N'U')     IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_SCHEDULER_STATE',    CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_SCHEDULER_STATE]', N'U')    IS NOT NULL THEN 1 ELSE 0 END),
        ('QRTZ_LOCKS',              CASE WHEN OBJECT_ID(N'[dbo].[QRTZ_LOCKS]', N'U')              IS NOT NULL THEN 1 ELSE 0 END);

    SELECT
        CASE WHEN TableExists = 1 THEN '  ✓ ' ELSE '  ✗ ' END + TableName AS Status
    FROM @TablesExist;

    IF EXISTS (SELECT 1 FROM @TablesExist WHERE TableExists = 0)
        RAISERROR('ERROR: One or more Quartz tables were not created successfully!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

    COMMIT TRANSACTION;
    PRINT '========================================';
    PRINT '✓ SCRIPT 99 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';
    PRINT '';
    PRINT '========================================';
    PRINT 'ALL SCRIPTS COMPLETED SUCCESSFULLY!';
    PRINT 'CAMT SCHEMA IS NOW FULLY DEPLOYED.';
    PRINT '========================================';

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 99 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO
