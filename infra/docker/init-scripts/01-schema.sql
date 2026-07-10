-- Create a sample database
CREATE DATABASE REPORTDB;
GO

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

-- =============================================================================
-- Corporate Reporting Agreement — SQL Server DDL
-- Schema: CAMT
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.schemas
WHERE name = 'CAMT')
    EXEC('CREATE SCHEMA CAMT');
GO

-- =============================================================================
-- REFERENCE TABLES
--
-- AgreementVersionStatus, AgreementScopeStatus, MessageRecipientType, and
-- ReportVersion are intentionally absent. Valid values for those columns are
-- enforced by the Java enum types at the application level; the database stores
-- the string representation without a FK constraint.
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.ReportType'))
CREATE TABLE CAMT.ReportType
(
    Code NVARCHAR(40) NOT NULL,
    Description NVARCHAR(100) NOT NULL,
    CONSTRAINT PK_ReportType PRIMARY KEY (Code)
);
GO

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.PaymentType'))
CREATE TABLE CAMT.PaymentType
(
    Code NVARCHAR(40) NOT NULL,
    Description NVARCHAR(100) NOT NULL,
    CONSTRAINT PK_PaymentType PRIMARY KEY (Code)
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.ReportFrequency'))
CREATE TABLE CAMT.ReportFrequency
(
    Code NVARCHAR(35) NOT NULL,
    Description NVARCHAR(100) NOT NULL,
    CONSTRAINT PK_ReportFrequency PRIMARY KEY (Code)
);
GO

-- Maps which frequencies are valid for each report type.
-- Every report type has exactly one applicable frequency (ReportConfig.ReportFrequency
-- is NOT NULL). NOTE: this table is currently unpopulated and not referenced by any FK
-- from ReportConfig — it exists as a documentation/validation aid for a future check,
-- not an enforced constraint today. Pre-existing gap, not introduced by this change.
IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.ReportTypeFrequency'))
CREATE TABLE CAMT.ReportTypeFrequency
(
    ReportTypeCode NVARCHAR(40) NOT NULL,
    FrequencyCode  NVARCHAR(35) NOT NULL,
    CONSTRAINT PK_ReportTypeFrequency PRIMARY KEY (ReportTypeCode, FrequencyCode),
    CONSTRAINT FK_ReportTypeFrequency_ReportType
        FOREIGN KEY (ReportTypeCode) REFERENCES CAMT.ReportType(Code),
    CONSTRAINT FK_ReportTypeFrequency_ReportFrequency
        FOREIGN KEY (FrequencyCode)  REFERENCES CAMT.ReportFrequency(Code)
);
GO

-- =============================================================================
-- SEED DATA — REFERENCE TABLES
-- =============================================================================

MERGE CAMT.ReportType AS target
USING (VALUES
    ('CAMT052B',  'CAMT052 — Balances only'),
    ('CAMT052BT', 'CAMT052 — Balances and transactions'),
    ('CAMT053S',  'CAMT053 — Standard'),
    ('CAMT053E',  'CAMT053 — Extended'),
    ('CAMT054D',  'CAMT054 — Debit notifications'),
    ('CAMT054C',  'CAMT054 — Credit notifications')
) AS source (Code, Description)
ON target.Code = source.Code
WHEN NOT MATCHED THEN INSERT (Code, Description) VALUES (source.Code, source.Description);
GO

-- FIX: Frequency codes now match ReportFrequencyConstants.java exactly.
-- EVERY_2_HOURS and EVERY_4_HOURS (with trailing S) are the canonical forms.
MERGE CAMT.ReportFrequency AS target
USING (VALUES
    ('SNAPSHOT',           'Snapshot report - no time window'),
    ('EVERY_30_MIN',       'Every 30 minutes'),
    ('EVERY_1_HOUR',       'Every 1 hour'),
    ('EVERY_2_HOURS',      'Every 2 hours'),   -- was EVERY_2_HOUR (no trailing S) — fixed
    ('EVERY_4_HOURS',      'Every 4 hours'),   -- was EVERY_4_HOUR (no trailing S) — fixed
    ('DAILY',              'Daily report'),
    ('ONE_TIME_PER_DAY',   '1 time per day'),
    ('FOUR_TIMES_PER_DAY', '4 times per day'),
    ('EIGHT_TIMES_PER_DAY','8 times per day')
) AS source (Code, Description)
ON target.Code = source.Code
WHEN NOT MATCHED THEN INSERT (Code, Description) VALUES (source.Code, source.Description);
GO

MERGE CAMT.PaymentType AS target
USING (VALUES
    ('ALL', N'Default payment type — used for non-CAMT054 and CAMT054_DEBIT callers'),
    ('CREDIT_TRANSFER', 'Credit transfer'),
    ('DIRECT_DEBIT', 'Direct debit'),
    ('INSTANT_PAYMENT', 'Instant payment')
) AS source (Code, Description)
ON target.Code = source.Code
WHEN NOT MATCHED THEN INSERT (Code, Description) VALUES (source.Code, source.Description);
GO

-- =============================================================================
-- AGREEMENT ID SEQUENCE TABLE (per engagement)
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.AgreementSequence'))
CREATE TABLE CAMT.AgreementSequence
(
    EngagementId NVARCHAR(15) NOT NULL,
    NextVal      BIGINT NOT NULL,
    CONSTRAINT PK_AgreementSequence PRIMARY KEY (EngagementId)
);
GO

-- =============================================================================
-- AGREEMENT ID STORED PROCEDURE
-- Atomically increments (or initialises) the per-engagement sequence counter.
-- WITH (UPDLOCK, SERIALIZABLE) prevents the first-insert race condition under concurrency.
-- =============================================================================

IF OBJECT_ID('CAMT.GetNextAgreementSequence', 'P') IS NOT NULL
DROP PROCEDURE CAMT.GetNextAgreementSequence;
GO

CREATE PROCEDURE CAMT.GetNextAgreementSequence
    @EngagementId NVARCHAR(15),
    @Next         BIGINT OUTPUT
AS
BEGIN
    SET NOCOUNT ON;

UPDATE CAMT.AgreementSequence WITH (UPDLOCK, SERIALIZABLE)
SET @Next = NextVal = NextVal + 1
WHERE EngagementId = @EngagementId;

IF @@ROWCOUNT = 0
BEGIN
INSERT INTO CAMT.AgreementSequence
(EngagementId, NextVal)
VALUES
    (@EngagementId, 1);
SET @Next = 1;
END
END
GO

-- =============================================================================
-- MESSAGE RECIPIENT
-- Shared lookup table for report delivery endpoints (e.g. SFTP, API targets).
-- Find-or-create: application resolves an existing row by (Type, Value)
-- before inserting a new one.
-- Type values (ORIGINATOR, BIC) are validated by the MessageRecipientType Java enum;
-- no FK constraint is required.
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.Recipient'))
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
GO

-- =============================================================================
-- AGREEMENT
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.Agreement'))
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
GO

-- Filtered unique index: name uniqueness only applies to non-cancelled agreements.
-- Allows a name to be reused after an agreement is cancelled, and permits multiple
-- unnamed (NULL) agreements for the same engagement bank/engagement ID pair.
DROP INDEX IF EXISTS UX_Agreement_EngagementBankEngagementIdName ON CAMT.Agreement;
CREATE UNIQUE INDEX UX_Agreement_EngagementBankEngagementIdName
    ON CAMT.Agreement (EngagementBank, EngagementId, Name)
    WHERE Name IS NOT NULL AND CancelledAt IS NULL;
GO

DROP INDEX IF EXISTS IX_Agreement_EngagementBankEngagementId ON CAMT.Agreement;
CREATE INDEX IX_Agreement_EngagementBankEngagementId ON CAMT.Agreement (EngagementBank, EngagementId);
GO

-- =============================================================================
-- AGREEMENT CONTACT
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.AgreementContact'))
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
GO

DROP INDEX IF EXISTS IX_AgreementContact_Agreement ON CAMT.AgreementContact;
CREATE INDEX IX_AgreementContact_Agreement ON CAMT.AgreementContact (AgreementId);
GO

-- =============================================================================
-- AGREEMENT VERSION
--
-- Status column stores Java AgreementVersionStatus enum names:
--   PENDING_ACTIVATION, PENDING_CHANGE, PENDING_CANCELLATION, ACTIVE, REPLACED, CANCELLED, EXPIRED
-- NVARCHAR(25) accommodates the longest value (PENDING_CANCELLATION = 20 chars).
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.AgreementVersion'))
CREATE TABLE CAMT.AgreementVersion
(
    Id              BIGINT NOT NULL IDENTITY(1,1),
    -- UUID generated by the domain model at construction time; never null in practice.
    VersionId       NVARCHAR(50) NOT NULL,
    AgreementId     NVARCHAR(50) NOT NULL,
    Status          NVARCHAR(25) NOT NULL,
    PricingOrderRef NVARCHAR(20) NULL,
    CreatedAt       DATETIME2 NOT NULL,
    ActivatedAt     DATETIME2 NULL,
    SupersededAt    DATETIME2 NULL,
    CancelledAt     DATETIME2 NULL,
    CreatedBy       NVARCHAR(20) NOT NULL,
    UpdatedAt       DATETIME2 NULL,
    UpdatedBy       NVARCHAR(20) NULL,
    -- Optimistic locking counter; incremented by JPA @Version on every write.
    Version         BIGINT NOT NULL CONSTRAINT DF_AgreementVersion_Version DEFAULT 0,
    CONSTRAINT PK_AgreementVersion PRIMARY KEY (Id),
    CONSTRAINT UQ_AgreementVersion_VersionId UNIQUE (VersionId),
    CONSTRAINT FK_AgreementVersion_Agreement
        FOREIGN KEY (AgreementId) REFERENCES CAMT.Agreement(Id)
);
GO

-- Filtered unique index: only one ACTIVE version per agreement at any time.
DROP INDEX IF EXISTS UX_AgreementVersion_Active ON CAMT.AgreementVersion;
CREATE UNIQUE INDEX UX_AgreementVersion_Active
    ON CAMT.AgreementVersion (AgreementId)
    WHERE Status = 'ACTIVE';
GO

-- Filtered unique index: only one pending version per agreement at any time.
DROP INDEX IF EXISTS UX_AgreementVersion_OnePending ON CAMT.AgreementVersion;
CREATE UNIQUE INDEX UX_AgreementVersion_OnePending
    ON CAMT.AgreementVersion (AgreementId)
    WHERE Status IN ('PENDING_ACTIVATION', 'PENDING_CHANGE', 'PENDING_CANCELLATION');
GO

DROP INDEX IF EXISTS IX_AgreementVersion_Agreement ON CAMT.AgreementVersion;
CREATE INDEX IX_AgreementVersion_Agreement
    ON CAMT.AgreementVersion (AgreementId, Status)
    INCLUDE (VersionId, PricingOrderRef, CreatedAt, ActivatedAt, SupersededAt, CancelledAt, CreatedBy, UpdatedAt, UpdatedBy);
GO

-- =============================================================================
-- AGREEMENT SCOPE
--
-- Status column stores Java AgreementScopeStatus enum names: PENDING, ACTIVE, CANCELLED.
-- ReportType column stores Java ReportType.dbCode() values and is FK-constrained to
-- CAMT.ReportType (the ReportType reference table is retained as it drives frequency rules).
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.AgreementScope'))
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
GO

DROP INDEX IF EXISTS IX_AgreementScope_Version ON CAMT.AgreementScope;
DROP INDEX IF EXISTS IX_AgreementScope_Recipient ON CAMT.AgreementScope;
CREATE INDEX IX_AgreementScope_Version   ON CAMT.AgreementScope (AgreementVersionId);
CREATE INDEX IX_AgreementScope_Recipient ON CAMT.AgreementScope (MessageRecipientId);
GO

-- =============================================================================
-- PAYMENT TYPE ASSIGNMENT
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.PaymentTypeAssignment'))
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
GO

DROP INDEX IF EXISTS IX_PaymentTypeAssignment_Scope ON CAMT.PaymentTypeAssignment;
CREATE INDEX IX_PaymentTypeAssignment_Scope ON CAMT.PaymentTypeAssignment (AgreementScopeId);
GO

-- =============================================================================
-- ACCOUNT ASSIGNMENT
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.AccountAssignment'))
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
GO

DROP INDEX IF EXISTS IX_AccountAssignment_PaymentType ON CAMT.AccountAssignment;
CREATE INDEX IX_AccountAssignment_PaymentType ON CAMT.AccountAssignment (PaymentTypeAssignmentId);
GO

-- =============================================================================
-- ALIAS ASSIGNMENT
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.AliasAssignment'))
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
GO

DROP INDEX IF EXISTS IX_AliasAssignment_PaymentType ON CAMT.AliasAssignment;
CREATE INDEX IX_AliasAssignment_PaymentType ON CAMT.AliasAssignment (PaymentTypeAssignmentId);
GO

-- =============================================================================
-- PHASE B — REPORT GENERATION & DELIVERY
--
-- ReportVersion column stores values validated by Java code (e.g. V2, V3, V4);
-- no FK constraint is required.
-- =============================================================================

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.ReportConfig'))
CREATE TABLE CAMT.ReportConfig
(
    Id                   BIGINT NOT NULL IDENTITY(1,1),
    -- Set to NULL on INSERT; application computes ((Id * 7919) + 1234567) % 90000000 + 10000000
    -- and UPDATEs in the same transaction. This always yields an 8-digit integer in [10000000, 99999999].
    -- Stored as INT (not VARCHAR) — the audit table stores it as NVARCHAR(50) for forward compatibility.
    ConfigId             INT NULL,
    ReportType           NVARCHAR(40) NOT NULL,
    ReportVersion        NVARCHAR(3) NOT NULL,
    -- Every report type now has an assigned frequency (no frequency-less types).
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
    -- FIX: Enforce that any ACTIVE config always has a ConfigId set.
    -- The INSERT+UPDATE pattern means ConfigId may be NULL transiently, but
    -- a committed active row must never have a NULL ConfigId.
    CONSTRAINT CK_ReportConfig_ActiveHasConfigId
        CHECK (IsActive = 0 OR ConfigId IS NOT NULL)
);
GO

DROP INDEX IF EXISTS IX_ReportConfig_MessageRecipientId ON CAMT.ReportConfig;
CREATE INDEX IX_ReportConfig_MessageRecipientId ON CAMT.ReportConfig (MessageRecipientId);
GO

-- Filtered unique index: allows multiple NULLs during in-flight INSERT+UPDATE transactions,
-- while enforcing uniqueness once ConfigId is committed.
DROP INDEX IF EXISTS UX_ReportConfig_ConfigId ON CAMT.ReportConfig;
CREATE UNIQUE INDEX UX_ReportConfig_ConfigId ON CAMT.ReportConfig (ConfigId) WHERE ConfigId IS NOT NULL;
GO

IF NOT EXISTS (SELECT 1
FROM sys.tables
WHERE object_id = OBJECT_ID('CAMT.ReportAgreementScope'))
CREATE TABLE CAMT.ReportAgreementScope
(
    Id               BIGINT NOT NULL IDENTITY(1,1),
    ReportConfigId   BIGINT NOT NULL,
    AgreementScopeId BIGINT NOT NULL,
    CreatedAt        DATETIME2 NOT NULL,
    CreatedBy        NVARCHAR(20) NOT NULL,
    CONSTRAINT PK_ReportAgreementScope PRIMARY KEY (Id),
    CONSTRAINT UX_ReportAgreementScope_Unique UNIQUE (ReportConfigId, AgreementScopeId),
    CONSTRAINT FK_ReportAgreementScope_ReportConfig   FOREIGN KEY (ReportConfigId)   REFERENCES CAMT.ReportConfig(Id),
    CONSTRAINT FK_ReportAgreementScope_AgreementScope FOREIGN KEY (AgreementScopeId) REFERENCES CAMT.AgreementScope(Id)
);
GO

DROP INDEX IF EXISTS IX_ReportAgreementScope_Scope ON CAMT.ReportAgreementScope;
CREATE INDEX IX_ReportAgreementScope_Scope ON CAMT.ReportAgreementScope (AgreementScopeId);
GO