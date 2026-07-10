-- 05-configuration-read-migrations.sql
-- Schema changes required by the "Report Configuration Fetch & Assembly" implementation
-- guide (v07). Idempotent: safe to run multiple times.
--
-- Consolidated pending migrations per guide §7:
--   1. Composite index (ReportType, ReportFrequency, IsActive) on ReportConfig (§3.6)
--   2. TVP user-defined table type for id-set parameters (§3.3a)
--   3. requestor_name NVARCHAR(200) NULL on ReportCommandAudit (§4.2)
--   4. Two new ReportCommandAudit.status values, enforced in Java only, no CHECK
--      constraint change needed (see AuditStatus.java) — REJECTED_CONFIG_NOT_ELIGIBLE,
--      REJECTED_INVALID_WINDOW (§4.1, §4.2)
--
-- PLUS one change beyond what the guide explicitly calls out, flagged for sign-off:
--   5. Relaxing report_config_id / config_id / agreement_scope_id / report_frequency /
--      mq_queue_name to NULL on ReportCommandAudit.
--
--      Why: the guide requires rejection audit rows for cases where no ReportConfig was
--      ever found (§4.1 step 3, "recipient not found" / "config not found" outcomes) and
--      for messages not attributable to exactly one AgreementScope (the zero-scope and
--      bundled fan-out cases, §3.5/§5) -- neither case can populate all of the
--      currently-NOT-NULL columns above. The guide does not itself resolve this; this
--      migration is a pragmatic implementation decision, not something drawn directly
--      from the design doc. Please confirm before this ships.

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

-- =============================================================================
-- 1. Composite index for the scheduled path's top-level filter/sort (§3.1, §3.6)
-- =============================================================================
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'IX_ReportConfig_TypeFrequencyActive'
      AND object_id = OBJECT_ID('CAMT.ReportConfig')
)
CREATE INDEX IX_ReportConfig_TypeFrequencyActive
    ON CAMT.ReportConfig (ReportType, ReportFrequency, IsActive)
    INCLUDE (Id, MessageRecipientId);
GO
-- NOTE (§7): the INCLUDE column list above is provisional, per the guide, pending the
-- final query projection. Current projection (ConfigurationRowMappers.REPORT_CONFIG)
-- selects every ReportConfig column, so INCLUDE here only benefits MessageRecipientId
-- and Id specifically; revisit if the top-level query's SELECT list changes.

-- =============================================================================
-- 2. TVP user-defined table type for id-set parameters (§3.3a)
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM sys.types WHERE is_table_type = 1 AND name = 'BigIntIdList')
CREATE TYPE dbo.BigIntIdList AS TABLE (Id BIGINT NOT NULL PRIMARY KEY);
GO
-- Must match TvpParameterSource.BIGINT_ID_LIST_TYPE ("dbo.BigIntIdList") exactly.

-- =============================================================================
-- 3. requestor_name column (§4.2)
-- =============================================================================
IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit') AND name = 'requestor_name'
)
ALTER TABLE CAMT.ReportCommandAudit ADD requestor_name NVARCHAR(200) NULL;
GO

-- =============================================================================
-- 5. Nullability relaxation for rejection-audit rows (see header note above)
-- =============================================================================
IF EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit') AND name = 'report_config_id' AND is_nullable = 0
)
ALTER TABLE CAMT.ReportCommandAudit ALTER COLUMN report_config_id BIGINT NULL;
GO

IF EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit') AND name = 'config_id' AND is_nullable = 0
)
ALTER TABLE CAMT.ReportCommandAudit ALTER COLUMN config_id NVARCHAR(50) NULL;
GO

IF EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit') AND name = 'agreement_scope_id' AND is_nullable = 0
)
ALTER TABLE CAMT.ReportCommandAudit ALTER COLUMN agreement_scope_id BIGINT NULL;
GO

IF EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit') AND name = 'report_frequency' AND is_nullable = 0
)
ALTER TABLE CAMT.ReportCommandAudit ALTER COLUMN report_frequency NVARCHAR(35) NULL;
GO

IF EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('CAMT.ReportCommandAudit') AND name = 'mq_queue_name' AND is_nullable = 0
)
ALTER TABLE CAMT.ReportCommandAudit ALTER COLUMN mq_queue_name NVARCHAR(100) NULL;
GO
