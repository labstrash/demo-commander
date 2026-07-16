-- =============================================================================
-- SCRIPT: 08-seed-data.sql
-- PURPOSE: Seed demo/dev data - happy-path scenarios plus the fetch-config
--          read-path edge cases (zero-scope config, dangling PaymentType-
--          Assignment, multi-scope fan-in).
-- EXECUTION: Run after 07-schema-fetch-config.sql
--
-- No idempotency guards (no IF NOT EXISTS) - consistent with the rest of this
-- directory's "clean deployment" approach: 00-drop-all.sql always runs first,
-- so every script here always starts from an empty CAMT schema.
--
-- VersionId values use NEWID() rather than hardcoded GUIDs, since the only
-- requirement is that they're unique (UQ_AgreementVersion_VersionId).
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 08: SEED DATA';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
BEGIN TRANSACTION;

    DECLARE @RecipientId1 BIGINT, @RecipientId2 BIGINT;
    DECLARE @AgreementVersionId1 BIGINT, @AgreementVersionId2 BIGINT;
    DECLARE @AgreementScopeId1 BIGINT, @AgreementScopeId2 BIGINT;
    DECLARE @PaymentTypeAssignmentId1 BIGINT, @PaymentTypeAssignmentId2 BIGINT;
    DECLARE @ReportConfigId1 BIGINT, @ReportConfigId2 BIGINT;

    DECLARE @EdgeRecipientId1 BIGINT, @EdgeRecipientId2 BIGINT, @EdgeRecipientId3 BIGINT;
    DECLARE @EdgeVersionId2 BIGINT, @EdgeVersionId3A BIGINT, @EdgeVersionId3B BIGINT;
    DECLARE @EdgeScopeId2 BIGINT, @EdgeScopeId3A BIGINT, @EdgeScopeId3B BIGINT;
    DECLARE @EdgePtaId3A BIGINT, @EdgePtaId3B BIGINT;
    DECLARE @EdgeConfigId1 BIGINT, @EdgeConfigId2 BIGINT, @EdgeConfigId3 BIGINT;

    -- =========================================================================
    -- SECTION A: HAPPY-PATH SCENARIOS
    -- Two ordinary agreements, each with one scope, one payment-type
    -- assignment, and one funded account - the everyday case the fetch/
    -- assembly path spends most of its time on.
    -- =========================================================================

    PRINT '>>> Section A: Happy-path scenarios';
    PRINT '';

    -- -------------------------------------------------------------------
    -- A.1 Agreement sequence (pre-seeds the next-value counter so
    --     GetNextAgreementSequence has a starting point for these
    --     engagement IDs)
    -- -------------------------------------------------------------------
    PRINT '  - Seeding AgreementSequence...';

INSERT INTO CAMT.AgreementSequence (EngagementId, NextVal)
VALUES
    (N'067696104012', 1),
    (N'065561959304', 1);

PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into AgreementSequence.';

    -- -------------------------------------------------------------------
    -- A.2 Recipients
    -- -------------------------------------------------------------------
    PRINT '  - Seeding Recipients...';

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES (N'ORIGINATOR', N'3937231530REP0001', N'Team Nirvana A', SYSDATETIME(), N'seed');
SET @RecipientId1 = SCOPE_IDENTITY();

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES (N'ORIGINATOR', N'767951290ORI0240', N'Team Nirvana B', SYSDATETIME(), N'seed');
SET @RecipientId2 = SCOPE_IDENTITY();

    PRINT '  ✓ 2 rows inserted into Recipient.';

    -- -------------------------------------------------------------------
    -- A.3 Agreements
    -- -------------------------------------------------------------------
    PRINT '  - Seeding Agreements...';

INSERT INTO CAMT.Agreement
(Id, Name, EngagementBank, EngagementId, Channel, Track, StartDate, CreatedAt, CreatedBy)
VALUES
    (N'393723153REP0001', N'Agreement One', N'8999', N'067696104012', N'Customer Portal', N'NOTIFICATION', SYSDATETIME(), SYSDATETIME(), N'seed'),
    (N'767951290REP0001', N'Agreement Two', N'8999', N'065561959304', N'Customer Portal', N'STANDARD',     SYSDATETIME(), SYSDATETIME(), N'seed');

PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into Agreement.';

    -- -------------------------------------------------------------------
    -- A.4 Agreement contact (Agreement Two only, mirroring real data
    --     where not every agreement has a contact on file)
    -- -------------------------------------------------------------------
    PRINT '  - Seeding AgreementContact...';

INSERT INTO CAMT.AgreementContact
(AgreementId, ContactName, ContactEmail, ContactPhone, CreatedAt, CreatedBy)
VALUES
    (N'767951290REP0001', N'Contact TNB', N'nirvana@example.com', N'07987654321', SYSDATETIME(), N'seed');

PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into AgreementContact.';

    -- -------------------------------------------------------------------
    -- A.5 Agreement versions
    -- -------------------------------------------------------------------
    PRINT '  - Seeding AgreementVersion...';

INSERT INTO CAMT.AgreementVersion
(VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy, Version)
VALUES (CAST(NEWID() AS NVARCHAR(50)), N'393723153REP0001', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed', 0);
SET @AgreementVersionId1 = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementVersion
(VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy, Version)
VALUES (CAST(NEWID() AS NVARCHAR(50)), N'767951290REP0001', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed', 0);
SET @AgreementVersionId2 = SCOPE_IDENTITY();

    PRINT '  ✓ 2 rows inserted into AgreementVersion.';

    -- -------------------------------------------------------------------
    -- A.6 Agreement scopes
    -- -------------------------------------------------------------------
    PRINT '  - Seeding AgreementScope...';

INSERT INTO CAMT.AgreementScope
(AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@AgreementVersionId1, N'Scope One', @RecipientId1, N'CAMT054C', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed');
SET @AgreementScopeId1 = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementScope
(AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@AgreementVersionId2, N'Scope Two', @RecipientId2, N'CAMT052B', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed');
SET @AgreementScopeId2 = SCOPE_IDENTITY();

    PRINT '  ✓ 2 rows inserted into AgreementScope.';

    -- -------------------------------------------------------------------
    -- A.7 Payment type assignments
    -- -------------------------------------------------------------------
    PRINT '  - Seeding PaymentTypeAssignment...';

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@AgreementScopeId1, N'CREDIT_TRANSFER', SYSDATETIME(), N'seed');
SET @PaymentTypeAssignmentId1 = SCOPE_IDENTITY();

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@AgreementScopeId2, N'ALL', SYSDATETIME(), N'seed');
SET @PaymentTypeAssignmentId2 = SCOPE_IDENTITY();

    PRINT '  ✓ 2 rows inserted into PaymentTypeAssignment.';

    -- -------------------------------------------------------------------
    -- A.8 Account assignments
    -- -------------------------------------------------------------------
    PRINT '  - Seeding AccountAssignment...';

INSERT INTO CAMT.AccountAssignment
(PaymentTypeAssignmentId, ClearingNumber, AccountNumber, AccountBBAN, Currency, CreatedAt, CreatedBy)
VALUES
    (@PaymentTypeAssignmentId1, N'89011', N'504669699', N'89011504669699', N'SEK', SYSDATETIME(), N'seed'),
    (@PaymentTypeAssignmentId2, N'83279', N'503797518', N'83279503797518', N'SEK', SYSDATETIME(), N'seed');

PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into AccountAssignment.';

    -- -------------------------------------------------------------------
    -- A.9 Report configs
    -- -------------------------------------------------------------------
    PRINT '  - Seeding ReportConfig...';

INSERT INTO CAMT.ReportConfig
(ConfigId, ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES (10000001, N'CAMT054C', N'V02', N'FOUR_TIMES_PER_DAY', N'Report Config One', @RecipientId1, N'IBAN', 1, 0, 1, 0, SYSDATETIME(), N'seed');
SET @ReportConfigId1 = SCOPE_IDENTITY();

INSERT INTO CAMT.ReportConfig
(ConfigId, ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES (10000002, N'CAMT052B', N'V02', N'EVERY_30_MIN', N'Report Config Two', @RecipientId2, N'BBAN', 1, 1, 0, 1, SYSDATETIME(), N'seed');
SET @ReportConfigId2 = SCOPE_IDENTITY();

    PRINT '  ✓ 2 rows inserted into ReportConfig.';

    -- -------------------------------------------------------------------
    -- A.10 Report/agreement-scope links
    -- -------------------------------------------------------------------
    PRINT '  - Seeding ReportAgreementScope...';

INSERT INTO CAMT.ReportAgreementScope (ReportConfigId, AgreementScopeId, CreatedAt, CreatedBy)
VALUES
    (@ReportConfigId1, @AgreementScopeId1, SYSDATETIME(), N'seed'),
    (@ReportConfigId2, @AgreementScopeId2, SYSDATETIME(), N'seed');

PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into ReportAgreementScope.';
    PRINT '✓ Section A complete.';
    PRINT '';

    -- =========================================================================
    -- SECTION B: EDGE-CASE SCENARIOS
    -- Exercises the three tree-assembly edge cases the fetch/assembly read
    -- path has to handle correctly (see ReportConfigTreeAssemblerTest):
    --   B.1 - zero-scope config      (ReportConfig with no linked scopes)
    --   B.2 - dangling PTA           (PaymentTypeAssignment with neither
    --                                 accounts nor aliases underneath it)
    --   B.3 - multi-scope fan-in     (one ReportConfig, two AgreementScope
    --                                 rows from two different agreements)
    -- =========================================================================

    PRINT '>>> Section B: Edge-case scenarios';
    PRINT '';

    -- -------------------------------------------------------------------
    -- B.1 Zero-scope config: a ReportConfig with no ReportAgreementScope
    --     rows at all. No Agreement/Scope/PTA needed - the whole point is
    --     that nothing links to this config.
    -- -------------------------------------------------------------------
    PRINT '  - B.1: Zero-scope config...';

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES (N'ORIGINATOR', N'EDGE-ZERO-0001', N'Edge Case - Zero Scope Ltd', SYSDATETIME(), N'seed');
SET @EdgeRecipientId1 = SCOPE_IDENTITY();

INSERT INTO CAMT.ReportConfig
(ConfigId, ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES (10000101, N'CAMT054D', N'V02', N'DAILY', N'Edge case - zero-scope config (no linked scope)', @EdgeRecipientId1, N'BBAN', 1, 0, 1, 1, SYSDATETIME(), N'seed');
SET @EdgeConfigId1 = SCOPE_IDENTITY();

    PRINT '  ✓ ReportConfig ' + CAST(@EdgeConfigId1 AS VARCHAR) + ' seeded with zero linked scopes.';
    PRINT '';

    -- -------------------------------------------------------------------
    -- B.2 Dangling PTA: a PaymentTypeAssignment with no AccountAssignment
    --     and no AliasAssignment rows underneath it.
    -- -------------------------------------------------------------------
    PRINT '  - B.2: Dangling payment-type assignment...';

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES (N'ORIGINATOR', N'EDGE-DANGLING-01', N'Edge Case - Dangling PTA AB', SYSDATETIME(), N'seed');
SET @EdgeRecipientId2 = SCOPE_IDENTITY();

INSERT INTO CAMT.Agreement
(Id, Name, EngagementBank, EngagementId, Channel, Track, StartDate, CreatedAt, CreatedBy)
VALUES (N'AGR-EDGE-DANGLING-0001', N'Edge Agreement - Dangling PTA', N'EDGE', N'EDGE-DANG-01', N'Customer Portal', N'STANDARD', SYSDATETIME(), SYSDATETIME(), N'seed');

INSERT INTO CAMT.AgreementVersion
(VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy, Version)
VALUES (CAST(NEWID() AS NVARCHAR(50)), N'AGR-EDGE-DANGLING-0001', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed', 0);
SET @EdgeVersionId2 = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementScope
(AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@EdgeVersionId2, N'Edge Scope - Dangling PTA', @EdgeRecipientId2, N'CAMT053S', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed');
SET @EdgeScopeId2 = SCOPE_IDENTITY();

    -- Deliberately no AccountAssignment / AliasAssignment rows for this PTA.
INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@EdgeScopeId2, N'INSTANT_PAYMENT', SYSDATETIME(), N'seed');

INSERT INTO CAMT.ReportConfig
(ConfigId, ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES (10000102, N'CAMT053S', N'V02', N'EVERY_2_HOURS', N'Edge case - dangling PTA (no accounts or aliases)', @EdgeRecipientId2, N'IBAN', 1, 0, 1, 0, SYSDATETIME(), N'seed');
SET @EdgeConfigId2 = SCOPE_IDENTITY();

INSERT INTO CAMT.ReportAgreementScope (ReportConfigId, AgreementScopeId, CreatedAt, CreatedBy)
VALUES (@EdgeConfigId2, @EdgeScopeId2, SYSDATETIME(), N'seed');

PRINT '  ✓ ReportConfig ' + CAST(@EdgeConfigId2 AS VARCHAR) + ' seeded with a PTA that has neither accounts nor aliases.';
    PRINT '';

    -- -------------------------------------------------------------------
    -- B.3 Multi-scope fan-in: one recipient/report-type pair (one
    --     ReportConfig) reached via scopes on two separate agreements.
    -- -------------------------------------------------------------------
    PRINT '  - B.3: Multi-scope fan-in...';

INSERT INTO CAMT.Recipient (Type, Value, Name, CreatedAt, CreatedBy)
VALUES (N'ORIGINATOR', N'EDGE-FANIN-0001', N'Edge Case - Multi-Scope Fan-In Corp', SYSDATETIME(), N'seed');
SET @EdgeRecipientId3 = SCOPE_IDENTITY();

INSERT INTO CAMT.Agreement
(Id, Name, EngagementBank, EngagementId, Channel, Track, StartDate, CreatedAt, CreatedBy)
VALUES
    (N'AGR-EDGE-FANIN-A', N'Edge Agreement - Fan-In A', N'EDGE', N'EDGE-FANIN-A', N'Customer Portal', N'STANDARD', SYSDATETIME(), SYSDATETIME(), N'seed'),
    (N'AGR-EDGE-FANIN-B', N'Edge Agreement - Fan-In B', N'EDGE', N'EDGE-FANIN-B', N'Partner API',     N'STANDARD', SYSDATETIME(), SYSDATETIME(), N'seed');

INSERT INTO CAMT.AgreementVersion
(VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy, Version)
VALUES (CAST(NEWID() AS NVARCHAR(50)), N'AGR-EDGE-FANIN-A', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed', 0);
SET @EdgeVersionId3A = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementVersion
(VersionId, AgreementId, Status, CreatedAt, ActivatedAt, CreatedBy, Version)
VALUES (CAST(NEWID() AS NVARCHAR(50)), N'AGR-EDGE-FANIN-B', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed', 0);
SET @EdgeVersionId3B = SCOPE_IDENTITY();

    -- Same MessageRecipientId + ReportType on both scopes is what makes
    -- these fan into a single ReportConfig via ReportAgreementScope below.
INSERT INTO CAMT.AgreementScope
(AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@EdgeVersionId3A, N'Edge Scope - Fan-In A', @EdgeRecipientId3, N'CAMT053E', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed');
SET @EdgeScopeId3A = SCOPE_IDENTITY();

INSERT INTO CAMT.AgreementScope
(AgreementVersionId, Name, MessageRecipientId, ReportType, Status, CreatedAt, ActivatedAt, CreatedBy)
VALUES (@EdgeVersionId3B, N'Edge Scope - Fan-In B', @EdgeRecipientId3, N'CAMT053E', N'ACTIVE', SYSDATETIME(), SYSDATETIME(), N'seed');
SET @EdgeScopeId3B = SCOPE_IDENTITY();

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@EdgeScopeId3A, N'DIRECT_DEBIT', SYSDATETIME(), N'seed');
SET @EdgePtaId3A = SCOPE_IDENTITY();

INSERT INTO CAMT.PaymentTypeAssignment (AgreementScopeId, PaymentType, CreatedAt, CreatedBy)
VALUES (@EdgeScopeId3B, N'CREDIT_TRANSFER', SYSDATETIME(), N'seed');
SET @EdgePtaId3B = SCOPE_IDENTITY();

INSERT INTO CAMT.AccountAssignment
(PaymentTypeAssignmentId, ClearingNumber, AccountNumber, AccountBBAN, Currency, CreatedAt, CreatedBy)
VALUES
    (@EdgePtaId3A, N'90001', N'100000001', N'90001100000001', N'SEK', SYSDATETIME(), N'seed'),
    (@EdgePtaId3B, N'90002', N'200000002', N'90002200000002', N'SEK', SYSDATETIME(), N'seed');

INSERT INTO CAMT.ReportConfig
(ConfigId, ReportType, ReportVersion, ReportFrequency, Description, MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled, CreatedAt, CreatedBy)
VALUES (10000103, N'CAMT053E', N'V02', N'ONE_TIME_PER_DAY', N'Edge case - multi-scope fan-in (2 agreements, 1 recipient/type)', @EdgeRecipientId3, N'BBAN', 1, 0, 0, 0, SYSDATETIME(), N'seed');
SET @EdgeConfigId3 = SCOPE_IDENTITY();

INSERT INTO CAMT.ReportAgreementScope (ReportConfigId, AgreementScopeId, CreatedAt, CreatedBy)
VALUES
    (@EdgeConfigId3, @EdgeScopeId3A, SYSDATETIME(), N'seed'),
    (@EdgeConfigId3, @EdgeScopeId3B, SYSDATETIME(), N'seed');

PRINT '  ✓ ReportConfig ' + CAST(@EdgeConfigId3 AS VARCHAR) + ' seeded with 2 AgreementScope rows from 2 separate agreements.';
    PRINT '';
    PRINT '✓ Section B complete.';
    PRINT '';

    -- =========================================================================
    -- VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying row counts...';

SELECT 'Recipient' AS TableName, COUNT(*) AS [RowCount] FROM CAMT.Recipient
UNION ALL SELECT 'Agreement', COUNT(*) FROM CAMT.Agreement
UNION ALL SELECT 'AgreementContact', COUNT(*) FROM CAMT.AgreementContact
UNION ALL SELECT 'AgreementVersion', COUNT(*) FROM CAMT.AgreementVersion
UNION ALL SELECT 'AgreementScope', COUNT(*) FROM CAMT.AgreementScope
UNION ALL SELECT 'PaymentTypeAssignment', COUNT(*) FROM CAMT.PaymentTypeAssignment
UNION ALL SELECT 'AccountAssignment', COUNT(*) FROM CAMT.AccountAssignment
UNION ALL SELECT 'ReportConfig', COUNT(*) FROM CAMT.ReportConfig
UNION ALL SELECT 'ReportAgreementScope', COUNT(*) FROM CAMT.ReportAgreementScope;

IF NOT EXISTS (
        SELECT 1 FROM CAMT.ReportConfig rc
        WHERE rc.Id = @EdgeConfigId1
          AND NOT EXISTS (SELECT 1 FROM CAMT.ReportAgreementScope ras WHERE ras.ReportConfigId = rc.Id)
    )
        RAISERROR('ERROR: expected zero-scope edge-case config to have no ReportAgreementScope rows!', 16, 1);

    IF (SELECT COUNT(*) FROM CAMT.ReportAgreementScope WHERE ReportConfigId = @EdgeConfigId3) <> 2
        RAISERROR('ERROR: expected multi-scope fan-in edge-case config to have exactly 2 ReportAgreementScope rows!', 16, 1);

PRINT '✓ Validation complete.';
    PRINT '';

COMMIT TRANSACTION;
PRINT '========================================';
    PRINT '✓ SCRIPT 08 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 08 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO