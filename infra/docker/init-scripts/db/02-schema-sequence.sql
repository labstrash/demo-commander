-- =============================================================================
-- SCRIPT: 02-schema-sequence.sql
-- PURPOSE: Create sequence infrastructure for agreement ID generation
-- EXECUTION: Run after 01-schema-reference.sql
-- =============================================================================

USE [REPORTDB];
GO

SET QUOTED_IDENTIFIER ON;
GO

PRINT '========================================';
PRINT 'SCRIPT 02: SEQUENCE INFRASTRUCTURE';
PRINT 'START TIME: ' + CAST(GETDATE() AS VARCHAR);
PRINT '========================================';
PRINT '';

BEGIN TRY
BEGIN TRANSACTION;

    -- =========================================================================
    -- 1. AGREEMENT SEQUENCE TABLE
    -- =========================================================================

    PRINT '>>> Creating AgreementSequence table...';

CREATE TABLE CAMT.AgreementSequence
(
    EngagementId NVARCHAR(15) NOT NULL,
    NextVal      BIGINT NOT NULL,
    CONSTRAINT PK_AgreementSequence PRIMARY KEY (EngagementId)
);

PRINT '✓ CAMT.AgreementSequence created successfully.';
    PRINT '';

    -- =========================================================================
    -- 2. GET NEXT SEQUENCE STORED PROCEDURE
    -- =========================================================================

    PRINT '>>> Creating GetNextAgreementSequence stored procedure...';

    -- Drop if exists (shouldn't exist after schema drop, but safe)
    IF OBJECT_ID('CAMT.GetNextAgreementSequence', 'P') IS NOT NULL
DROP PROCEDURE CAMT.GetNextAgreementSequence;

EXEC('
    CREATE PROCEDURE CAMT.GetNextAgreementSequence
        @EngagementId NVARCHAR(15),
        @Next         BIGINT OUTPUT
    AS
    BEGIN
        SET NOCOUNT ON;

        -- Atomically increment the sequence with locks to prevent race conditions
        UPDATE CAMT.AgreementSequence WITH (UPDLOCK, SERIALIZABLE)
        SET @Next = NextVal = NextVal + 1
        WHERE EngagementId = @EngagementId;

        -- If no row exists, insert with initial value 1
        IF @@ROWCOUNT = 0
        BEGIN
            INSERT INTO CAMT.AgreementSequence (EngagementId, NextVal)
            VALUES (@EngagementId, 1);
            SET @Next = 1;
        END
    END
    ');

    PRINT '✓ CAMT.GetNextAgreementSequence stored procedure created successfully.';
    PRINT '';

    -- =========================================================================
    -- 3. VALIDATION
    -- =========================================================================

    PRINT '>>> Validation: Verifying objects exist...';

    IF EXISTS (SELECT 1 FROM sys.tables WHERE object_id = OBJECT_ID('CAMT.AgreementSequence'))
        PRINT '  ✓ CAMT.AgreementSequence table exists.';
ELSE
        RAISERROR('  ✗ CAMT.AgreementSequence table NOT found!', 16, 1);

    IF EXISTS (SELECT 1 FROM sys.objects WHERE object_id = OBJECT_ID('CAMT.GetNextAgreementSequence') AND type = 'P')
        PRINT '  ✓ CAMT.GetNextAgreementSequence procedure exists.';
ELSE
        RAISERROR('  ✗ CAMT.GetNextAgreementSequence procedure NOT found!', 16, 1);

    PRINT '✓ Validation complete.';
    PRINT '';

    -- Test the sequence
    PRINT '>>> Testing sequence generation...';
    DECLARE @TestId NVARCHAR(15) = 'TEST_ENGAGEMENT';
    DECLARE @TestNext BIGINT;

EXEC CAMT.GetNextAgreementSequence @TestId, @TestNext OUTPUT;
    PRINT '  - First sequence value for TEST_ENGAGEMENT: ' + CAST(@TestNext AS VARCHAR);

EXEC CAMT.GetNextAgreementSequence @TestId, @TestNext OUTPUT;
    PRINT '  - Second sequence value for TEST_ENGAGEMENT: ' + CAST(@TestNext AS VARCHAR);

    -- Clean up test data
DELETE FROM CAMT.AgreementSequence WHERE EngagementId = @TestId;
PRINT '  - Test data cleaned up.';
    PRINT '✓ Sequence test passed.';
    PRINT '';

COMMIT TRANSACTION;
PRINT '========================================';
    PRINT '✓ SCRIPT 02 COMPLETED SUCCESSFULLY';
    PRINT 'END TIME: ' + CAST(GETDATE() AS VARCHAR);
    PRINT '========================================';

END TRY
BEGIN CATCH
IF @@TRANCOUNT > 0
        ROLLBACK TRANSACTION;

    PRINT '';
    PRINT '========================================';
    PRINT '✗ SCRIPT 02 FAILED!';
    PRINT 'ERROR: ' + ERROR_MESSAGE();
    PRINT 'ERROR NUMBER: ' + CAST(ERROR_NUMBER() AS VARCHAR);
    PRINT 'ERROR LINE: ' + CAST(ERROR_LINE() AS VARCHAR);
    PRINT '========================================';

    THROW;
END CATCH
GO