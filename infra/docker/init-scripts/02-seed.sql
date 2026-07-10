-- 02-seed.sql
USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

-- =============================================================================
-- PHASE 1 SEED DATA
--
-- Four scenarios covering the cases the Phase 1 repository/aggregator needs
-- to prove out: bundled vs unbundled, multi-account, and alias-bearing scope
-- details. Each scenario is a self-contained batch (no shared variables across
-- GO boundaries, since T-SQL locals don't survive a batch break).
--
-- ConfigId is populated using the same INSERT-then-UPDATE pattern the
-- application uses: ((Id * 7919) + 1234567) % 90000000 + 10000000.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Scenario 1 — CAMT052B / EVERY_30_MIN — bundled, 2 accounts, 1 scope
-- -----------------------------------------------------------------------------
DECLARE @Recipient1Id BIGINT, @Version1Id BIGINT, @Scope1Id BIGINT, @Pta1Id BIGINT, @Config1Id BIGINT;

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES ('BIC', 'NDEASESSXXX', 'Nordea Test Corp', GETUTCDATE(), 'SEED_SCRIPT');
SET @Recipient1Id = SCOPE_IDENTITY();

INSERT INTO CAMT.Agreement (Id, EngagementBank, EngagementId, Channel, Track, CreatedAt, CreatedBy)
VALUES ('AGR-SEED-0001', 'NDEA', 'ENG-0001', 'SWIFT', 'STANDARD', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.AgreementVersion (VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES ('AGRV-SEED-0001', 'AGR-SEED-0001', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Version1Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementScope (AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@Version1Id, 'Scenario 1 Scope', @Recipient1Id, 'CAMT052B', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Scope1Id = SCOPE_IDENTITY();

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@Scope1Id, 'ALL', GETUTCDATE(), 'SEED_SCRIPT');
SET @Pta1Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AccountAssignment (PaymentTypeAssignmentId, ClearingNumber, AccountNumber, AccountBBAN, Currency, CreatedAt, CreatedBy)
VALUES
    (@Pta1Id, '9900', '111111111', '9900111111111', 'SEK', GETUTCDATE(), 'SEED_SCRIPT'),
    (@Pta1Id, '9900', '222222222', '9900222222222', 'SEK', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.ReportConfig (ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES ('CAMT052B', 'V2', 'EVERY_30_MIN', 'Seed scenario 1 - bundled balances', @Recipient1Id, 'BBAN', 0, 0, 0, 1, GETUTCDATE(), 'SEED_SCRIPT');
SET @Config1Id = SCOPE_IDENTITY();

UPDATE CAMT.ReportConfig
SET ConfigId = ((@Config1Id * 7919) + 1234567) % 90000000 + 10000000,
    IsActive = 1
WHERE Id = @Config1Id;

INSERT INTO CAMT.ReportAgreementScope (ReportConfigId, AgreementScopeId, CreatedAt, CreatedBy)
VALUES (@Config1Id, @Scope1Id, GETUTCDATE(), 'SEED_SCRIPT');
GO

-- -----------------------------------------------------------------------------
-- Scenario 2 — CAMT052BT / EVERY_1_HOUR — unbundled, 2 accounts, 1 scope
-- -----------------------------------------------------------------------------
DECLARE @Recipient2Id BIGINT, @Version2Id BIGINT, @Scope2Id BIGINT, @Pta2Id BIGINT, @Config2Id BIGINT;

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES ('ORIGINATOR', 'SE1234567890', 'Test Originator AB', GETUTCDATE(), 'SEED_SCRIPT');
SET @Recipient2Id = SCOPE_IDENTITY();

INSERT INTO CAMT.Agreement (Id, EngagementBank, EngagementId, Channel, Track, CreatedAt, CreatedBy)
VALUES ('AGR-SEED-0002', 'TSTB', 'ENG-0002', 'SWIFT', 'STANDARD', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.AgreementVersion (VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES ('AGRV-SEED-0002', 'AGR-SEED-0002', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Version2Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementScope (AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@Version2Id, 'Scenario 2 Scope', @Recipient2Id, 'CAMT052BT', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Scope2Id = SCOPE_IDENTITY();

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@Scope2Id, 'CREDIT_TRANSFER', GETUTCDATE(), 'SEED_SCRIPT');
SET @Pta2Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AccountAssignment (PaymentTypeAssignmentId, ClearingNumber, AccountNumber, AccountBBAN, Currency, CreatedAt, CreatedBy)
VALUES
    (@Pta2Id, '9910', '333333333', '9910333333333', 'SEK', GETUTCDATE(), 'SEED_SCRIPT'),
    (@Pta2Id, '9910', '444444444', '9910444444444', 'SEK', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.ReportConfig (ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES ('CAMT052BT', 'V2', 'EVERY_1_HOUR', 'Seed scenario 2 - unbundled balances+txns', @Recipient2Id, 'BBAN', 0, 0, 0, 0, GETUTCDATE(), 'SEED_SCRIPT');
SET @Config2Id = SCOPE_IDENTITY();

UPDATE CAMT.ReportConfig
SET ConfigId = ((@Config2Id * 7919) + 1234567) % 90000000 + 10000000,
    IsActive = 1
WHERE Id = @Config2Id;

INSERT INTO CAMT.ReportAgreementScope (ReportConfigId, AgreementScopeId, CreatedAt, CreatedBy)
VALUES (@Config2Id, @Scope2Id, GETUTCDATE(), 'SEED_SCRIPT');
GO

-- -----------------------------------------------------------------------------
-- Scenario 3 — CAMT054C / FOUR_TIMES_PER_DAY — bundled, 1 account + 1 alias
-- -----------------------------------------------------------------------------
DECLARE @Recipient3Id BIGINT, @Version3Id BIGINT, @Scope3Id BIGINT, @Pta3Id BIGINT, @Config3Id BIGINT;

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES ('BIC', 'SEBSSESS', 'SEB Test Bank', GETUTCDATE(), 'SEED_SCRIPT');
SET @Recipient3Id = SCOPE_IDENTITY();

INSERT INTO CAMT.Agreement (Id, EngagementBank, EngagementId, Channel, Track, CreatedAt, CreatedBy)
VALUES ('AGR-SEED-0003', 'SEBB', 'ENG-0003', 'SWIFT', 'STANDARD', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.AgreementVersion (VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES ('AGRV-SEED-0003', 'AGR-SEED-0003', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Version3Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementScope (AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@Version3Id, 'Scenario 3 Scope', @Recipient3Id, 'CAMT054C', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Scope3Id = SCOPE_IDENTITY();

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@Scope3Id, 'DIRECT_DEBIT', GETUTCDATE(), 'SEED_SCRIPT');
SET @Pta3Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AccountAssignment (PaymentTypeAssignmentId, ClearingNumber, AccountNumber, AccountBBAN, Currency, CreatedAt, CreatedBy)
VALUES (@Pta3Id, '9920', '555555555', '9920555555555', 'SEK', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.AliasAssignment (PaymentTypeAssignmentId, AliasId, CreatedAt, CreatedBy)
VALUES (@Pta3Id, 'ALIAS-0001', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.ReportConfig (ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES ('CAMT054C', 'V2', 'FOUR_TIMES_PER_DAY', 'Seed scenario 3 - bundled credit notifications w/ alias', @Recipient3Id, 'BBAN', 0, 0, 0, 1, GETUTCDATE(), 'SEED_SCRIPT');
SET @Config3Id = SCOPE_IDENTITY();

UPDATE CAMT.ReportConfig
SET ConfigId = ((@Config3Id * 7919) + 1234567) % 90000000 + 10000000,
    IsActive = 1
WHERE Id = @Config3Id;

INSERT INTO CAMT.ReportAgreementScope (ReportConfigId, AgreementScopeId, CreatedAt, CreatedBy)
VALUES (@Config3Id, @Scope3Id, GETUTCDATE(), 'SEED_SCRIPT');
GO

-- -----------------------------------------------------------------------------
-- Scenario 4 — CAMT054D / DAILY — bundled, 1 account
-- (Also proves CAMT054D no longer has a NULL frequency — see chat history.)
-- -----------------------------------------------------------------------------
DECLARE @Recipient4Id BIGINT, @Version4Id BIGINT, @Scope4Id BIGINT, @Pta4Id BIGINT, @Config4Id BIGINT;

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES ('BIC', 'HANDSESS', 'Handelsbanken Test', GETUTCDATE(), 'SEED_SCRIPT');
SET @Recipient4Id = SCOPE_IDENTITY();

INSERT INTO CAMT.Agreement (Id, EngagementBank, EngagementId, Channel, Track, CreatedAt, CreatedBy)
VALUES ('AGR-SEED-0004', 'HAND', 'ENG-0004', 'SWIFT', 'STANDARD', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.AgreementVersion (VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES ('AGRV-SEED-0004', 'AGR-SEED-0004', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Version4Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementScope (AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@Version4Id, 'Scenario 4 Scope', @Recipient4Id, 'CAMT054D', 'ACTIVE', GETUTCDATE(), GETUTCDATE(), 'SEED_SCRIPT');
SET @Scope4Id = SCOPE_IDENTITY();

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@Scope4Id, 'ALL', GETUTCDATE(), 'SEED_SCRIPT');
SET @Pta4Id = SCOPE_IDENTITY();

INSERT INTO CAMT.AccountAssignment (PaymentTypeAssignmentId, ClearingNumber, AccountNumber, AccountBBAN, Currency, CreatedAt, CreatedBy)
VALUES (@Pta4Id, '9930', '666666666', '9930666666666', 'SEK', GETUTCDATE(), 'SEED_SCRIPT');

INSERT INTO CAMT.ReportConfig (ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES ('CAMT054D', 'V2', 'DAILY', 'Seed scenario 4 - bundled debit notifications', @Recipient4Id, 'BBAN', 0, 0, 0, 1, GETUTCDATE(), 'SEED_SCRIPT');
SET @Config4Id = SCOPE_IDENTITY();

UPDATE CAMT.ReportConfig
SET ConfigId = ((@Config4Id * 7919) + 1234567) % 90000000 + 10000000,
    IsActive = 1
WHERE Id = @Config4Id;

INSERT INTO CAMT.ReportAgreementScope (ReportConfigId, AgreementScopeId, CreatedAt, CreatedBy)
VALUES (@Config4Id, @Scope4Id, GETUTCDATE(), 'SEED_SCRIPT');
GO
