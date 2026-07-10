-- =============================================================================
-- SCRIPT: 01-schema-reference.sql
-- PURPOSE: Create CAMT schema and reference/lookup tables with seed data
-- EXECUTION: Run after 00-drop-all.sql
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 01: REFERENCE TABLES';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. CREATE SCHEMA
    -- =========================================================================
    PRINT '>>> Creating CAMT schema...';
EXEC('CREATE SCHEMA CAMT');
    PRINT '✓ CAMT schema created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. CREATE REFERENCE TABLES
    -- =========================================================================

    PRINT '>>> Creating reference tables...';

    -- 2.1 ReportType
    PRINT '  - Creating CAMT.ReportType...';
CREATE TABLE CAMT.ReportType
(
    Code NVARCHAR(40) NOT NULL,
    Description NVARCHAR(100) NOT NULL,
    CONSTRAINT PK_ReportType PRIMARY KEY (Code)
);
PRINT '  ✓ CAMT.ReportType created.';

    -- 2.2 PaymentType
    PRINT '  - Creating CAMT.PaymentType...';
CREATE TABLE CAMT.PaymentType
(
    Code NVARCHAR(40) NOT NULL,
    Description NVARCHAR(100) NOT NULL,
    CONSTRAINT PK_PaymentType PRIMARY KEY (Code)
);
PRINT '  ✓ CAMT.PaymentType created.';

    -- 2.3 ReportFrequency
    PRINT '  - Creating CAMT.ReportFrequency...';
CREATE TABLE CAMT.ReportFrequency
(
    Code NVARCHAR(35) NOT NULL,
    Description NVARCHAR(100) NOT NULL,
    CONSTRAINT PK_ReportFrequency PRIMARY KEY (Code)
);
PRINT '  ✓ CAMT.ReportFrequency created.';

    PRINT '✓ All reference tables created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. SEED DATA
    -- =========================================================================

    PRINT '>>> Inserting seed data...';

    -- 3.1 ReportType Seed
    PRINT '  - Inserting ReportType data...';
INSERT INTO CAMT.ReportType (Code, Description)
VALUES
    ('CAMT052B',  'CAMT052 — Balances only'),
    ('CAMT052BT', 'CAMT052 — Balances and transactions'),
    ('CAMT053S',  'CAMT053 — Standard'),
    ('CAMT053E',  'CAMT053 — Extended'),
    ('CAMT054D',  'CAMT054 — Debit notifications'),
    ('CAMT054C',  'CAMT054 — Credit notifications');
PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into ReportType.';

    -- 3.2 ReportFrequency Seed
    PRINT '  - Inserting ReportFrequency data...';
INSERT INTO CAMT.ReportFrequency (Code, Description)
VALUES
    ('SNAPSHOT',           'Snapshot report - no time window'),
    ('EVERY_30_MIN',       'Every 30 minutes'),
    ('EVERY_1_HOUR',       'Every 1 hour'),
    ('EVERY_2_HOURS',      'Every 2 hours'),
    ('EVERY_4_HOURS',      'Every 4 hours'),
    ('DAILY',              'Daily report'),
    ('ONE_TIME_PER_DAY',   '1 time per day'),
    ('FOUR_TIMES_PER_DAY', '4 times per day'),
    ('EIGHT_TIMES_PER_DAY','8 times per day');
PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into ReportFrequency.';

    -- 3.3 PaymentType Seed
    PRINT '  - Inserting PaymentType data...';
INSERT INTO CAMT.PaymentType (Code, Description)
VALUES
    ('ALL', N'Default payment type — used for non-CAMT054 and CAMT054_DEBIT callers'),
    ('CREDIT_TRANSFER', 'Credit transfer'),
    ('DIRECT_DEBIT', 'Direct debit'),
    ('INSTANT_PAYMENT', 'Instant payment');
PRINT '  ✓ ' + CAST(@@ROWCOUNT AS VARCHAR) + ' rows inserted into PaymentType.';

    PRINT '✓ All seed data inserted successfully.';
    PRINT '';

    -- =========================================================================
    -- 4. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying row counts...';

    DECLARE @ReportTypeCount INT = (SELECT COUNT(*) FROM CAMT.ReportType);
    DECLARE @ReportFrequencyCount INT = (SELECT COUNT(*) FROM CAMT.ReportFrequency);
    DECLARE @PaymentTypeCount INT = (SELECT COUNT(*) FROM CAMT.PaymentType);

    PRINT '  - ReportType:       ' + CAST(@ReportTypeCount AS VARCHAR) + ' rows';
    PRINT '  - ReportFrequency:  ' + CAST(@ReportFrequencyCount AS VARCHAR) + ' rows';
    PRINT '  - PaymentType:      ' + CAST(@PaymentTypeCount AS VARCHAR) + ' rows';
    PRINT '✓ Validation complete.';
    PRINT '';

COMMIT TRANSACTION;
PRINT '========================================';
    PRINT '✓ SCRIPT 01 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 01 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO