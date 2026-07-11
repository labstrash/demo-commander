# CAMT Schema - Clean Production Deployment Scripts

## Overview
This set of SQL scripts performs a **clean deployment** of the CAMT (Cash Management) reporting schema for the Corporate Banking Reporting Agreement System. These scripts are designed for production deployment where all existing CAMT objects should be completely removed and recreated from scratch.

## Prerequisites
- **SQL Server** (2016 or later recommended)
- **Database**: `REPORTDB` must already exist
- **Permissions**:
  - `CREATE SCHEMA` and `DROP SCHEMA` permissions in `REPORTDB`
  - `CREATE TABLE`, `CREATE PROCEDURE` permissions
  - `ALTER` permissions on the database
- **Backup**: Ensure you have a full backup of the `REPORTDB` database before running these scripts

## ⚠️ WARNING
**These scripts will permanently delete ALL existing objects in the CAMT schema including:**
- All tables and their data
- All stored procedures
- All indexes and constraints
- The CAMT schema itself

**Do NOT run these scripts if:**
- You have production data in the CAMT schema
- Other applications depend on existing CAMT objects
- You haven't taken a backup

## Script Execution Order
Execute the scripts in the following order:

| Order | Script File | Purpose |
|-------|-------------|---------|
| 1 | `00-drop-all.sql` | **CLEANUP**: Drops the entire CAMT schema and all its objects |
| 2 | `01-schema-reference.sql` | Creates CAMT schema, reference tables (ReportType, PaymentType, ReportFrequency) and loads seed data |
| 3 | `02-schema-sequence.sql` | Creates AgreementSequence table and GetNextAgreementSequence stored procedure |
| 4 | `03-schema-agreement.sql` | Creates core agreement tables: Recipient, Agreement, AgreementContact, AgreementVersion |
| 5 | `04-schema-scope.sql` | Creates scope and assignment tables: AgreementScope, PaymentTypeAssignment, AccountAssignment, AliasAssignment |
| 6 | `05-schema-report.sql` | Creates report configuration tables: ReportConfig, ReportAgreementScope |
| 7 | `06-schema-audit-deadletter.sql` | Creates DeadLetterMessage and ReportCommandAudit tables |
| 8 | `07-schema-fetch-config.sql` | Creates the `dbo.BigIntIdList` table type for id-set query parameters |
| 9 | `08-seed-data.sql` | Seeds demo/dev data: 2 happy-path scenarios plus 3 fetch-config read-path edge cases (zero-scope config, dangling PTA, multi-scope fan-in) |
| 10 | `98-drop-quartz.sql` | **CLEANUP**: Drops all Quartz (`QRTZ_*`) job store objects |
| 11 | `99-schema-quartz.sql` | Creates the Quartz JDBC job store tables, foreign keys, and indexes |

`98`/`99` are numbered out of sequence deliberately: Quartz is unrelated to the CAMT schema, so keeping its drop/create pair at the end leaves room to add more CAMT-related scripts (08, 09, ...) without renumbering.

## How to Run

### Option 1: SQL Server Management Studio (SSMS)
1. Open SSMS and connect to your database server
2. Select the `REPORTDB` database in the query window dropdown
3. Open each script file in order (00 through 07, then 98, then 99)
4. Execute each script completely before moving to the next

### Option 2: SQLCMD
```bash
sqlcmd -S <server_name> -d REPORTDB -i 00-drop-all.sql
sqlcmd -S <server_name> -d REPORTDB -i 01-schema-reference.sql
sqlcmd -S <server_name> -d REPORTDB -i 02-schema-sequence.sql
sqlcmd -S <server_name> -d REPORTDB -i 03-schema-agreement.sql
sqlcmd -S <server_name> -d REPORTDB -i 04-schema-scope.sql
sqlcmd -S <server_name> -d REPORTDB -i 05-schema-report.sql
sqlcmd -S <server_name> -d REPORTDB -i 06-schema-audit-deadletter.sql
sqlcmd -S <server_name> -d REPORTDB -i 07-schema-fetch-config.sql
sqlcmd -S <server_name> -d REPORTDB -i 08-seed-data.sql
sqlcmd -S <server_name> -d REPORTDB -i 98-drop-quartz.sql
sqlcmd -S <server_name> -d REPORTDB -i 99-schema-quartz.sql
```

### Option 3: PowerShell (Windows)
```powershell
$server = "your_server_name"
$database = "REPORTDB"
$scripts = @("00-drop-all.sql", "01-schema-reference.sql", "02-schema-sequence.sql", "03-schema-agreement.sql", "04-schema-scope.sql", "05-schema-report.sql", "06-schema-audit-deadletter.sql", "07-schema-fetch-config.sql", "08-seed-data.sql", "98-drop-quartz.sql", "99-schema-quartz.sql")

foreach ($script in $scripts) {
    Write-Host "Executing $script..."
    sqlcmd -S $server -d $database -i $script
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Script $script failed!"
        break
    }
    Write-Host "Completed $script successfully."
}
```

### Option 4: Linux/macOS (sqlcmd)
```bash
sqlcmd -S <server_name> -d REPORTDB -i 00-drop-all.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 01-schema-reference.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 02-schema-sequence.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 03-schema-agreement.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 04-schema-scope.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 05-schema-report.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 06-schema-audit-deadletter.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 07-schema-fetch-config.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 08-seed-data.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 98-drop-quartz.sql && \
sqlcmd -S <server_name> -d REPORTDB -i 99-schema-quartz.sql
```

## What Each Script Does

### 00-drop-all.sql
- **Action**: SQL Server's `DROP SCHEMA` has no CASCADE option, so this script explicitly drops all foreign keys, tables, procedures, views, and functions in the `CAMT` schema (in dependency order) before dropping the schema itself. Also drops the `dbo.BigIntIdList` table type, which lives outside `CAMT`.
- **Result**: All CAMT objects and the `dbo.BigIntIdList` type are completely removed
- **Validation**: Confirms schema and type no longer exist

### 01-schema-reference.sql
- **Creates**:
  - `CAMT` schema
  - `ReportType` reference table
  - `PaymentType` reference table
  - `ReportFrequency` reference table
- **Inserts**: Seed data for all three reference tables
- **Validates**: Row counts in reference tables

### 02-schema-sequence.sql
- **Creates**:
  - `AgreementSequence` table (per-engagement ID generation)
  - `GetNextAgreementSequence` stored procedure (with UPDLOCK/SERIALIZABLE for concurrency)
- **Tests**: Sequence generation with sample engagement ID
- **Validates**: Both objects exist and work correctly

### 03-schema-agreement.sql
- **Creates**:
  - `Recipient` (shared delivery endpoint lookup)
  - `Agreement` (master agreement records)
  - `AgreementContact` (contact information)
  - `AgreementVersion` (agreement lifecycle management)
- **Indexes**: Filtered unique indexes for active/pending versions
- **Validates**: All tables exist

### 04-schema-scope.sql
- **Creates**:
  - `AgreementScope` (defines what and where to report)
  - `PaymentTypeAssignment` (payment types covered)
  - `AccountAssignment` (bank account details)
  - `AliasAssignment` (alternative identifiers)
- **Indexes**: All necessary indexes for performance
- **Validates**: Tables exist and foreign keys are enabled

### 05-schema-report.sql
- **Creates**:
  - `ReportConfig` (report generation configuration)
  - `ReportAgreementScope` (mapping configs to scopes)
- **Constraints**: Check constraint ensuring active configs have ConfigId
- **Indexes**: Filtered unique index for ConfigId; composite index (ReportType, ReportFrequency, IsActive) for the scheduled fetch path
- **Validates**: All tables and foreign keys
- **Summary**: Displays complete list of all tables in CAMT schema

### 06-schema-audit-deadletter.sql
- **Creates**:
  - `DeadLetterMessage` (messages that failed MQ delivery)
  - `ReportCommandAudit` (audit trail of all ReportCommand messages sent, including rejection-audit rows with no resolved config/scope)
- **Indexes**: Retry lookup index on DeadLetterMessage; message/config/type/status/sent-at/job-execution indexes on ReportCommandAudit
- **Validates**: Both tables exist

### 07-schema-fetch-config.sql
- **Creates**: `dbo.BigIntIdList` — a table-valued parameter (TVP) type used to pass id-sets into fetch queries. Must match `TvpParameterSource.BIGINT_ID_LIST_TYPE` exactly.
- **Note**: Lives in the `dbo` schema, not `CAMT`.
- **Validates**: Type exists

### 08-seed-data.sql
- **Section A (happy path)**: 2 ordinary agreements, each with one scope, one payment-type assignment, and one funded account — the everyday case the fetch/assembly path spends most of its time on.
- **Section B (edge cases)**: exercises the three tree-assembly edge cases the fetch/assembly read path has to handle correctly (mirrors `ReportConfigTreeAssemblerTest`):
  - **B.1 zero-scope config** — a `ReportConfig` with no `ReportAgreementScope` rows at all
  - **B.2 dangling PTA** — a `PaymentTypeAssignment` with neither accounts nor aliases underneath it
  - **B.3 multi-scope fan-in** — one `ReportConfig` reached via scopes on two separate agreements
- **No idempotency guards**: consistent with the rest of this directory, since `00-drop-all.sql` always runs first
- **Validates**: row counts across all seeded tables; asserts the zero-scope config really has zero scopes and the fan-in config really has exactly two

### 98-drop-quartz.sql
- **Action**: Drops all Quartz (`QRTZ_*`) foreign keys and tables from the `dbo` schema. Scoped strictly to tables named `QRTZ_%`, so it can never touch unrelated `dbo` objects.
- **Result**: All Quartz job store objects are completely removed
- **Validation**: Confirms no `QRTZ_*` tables remain

### 99-schema-quartz.sql
- **Creates**: The 11 Quartz JDBC job store tables (`QRTZ_JOB_DETAILS`, `QRTZ_TRIGGERS`, `QRTZ_SIMPLE_TRIGGERS`, `QRTZ_CRON_TRIGGERS`, `QRTZ_SIMPROP_TRIGGERS`, `QRTZ_BLOB_TRIGGERS`, `QRTZ_CALENDARS`, `QRTZ_PAUSED_TRIGGER_GRPS`, `QRTZ_FIRED_TRIGGERS`, `QRTZ_SCHEDULER_STATE`, `QRTZ_LOCKS`), their foreign keys, and their indexes.
- **Note**: Lives in the `dbo` schema, not `CAMT`. Table/column layout matches the standard Quartz SQL Server schema.
- **Validates**: All tables exist

## Schema Overview

### Complete List of Tables

| # | Table | Purpose |
|---|-------|---------|
| 1 | `ReportType` | Reference: Types of CAMT reports |
| 2 | `PaymentType` | Reference: Payment methods |
| 3 | `ReportFrequency` | Reference: Report generation frequencies |
| 4 | `Recipient` | Shared lookup for delivery endpoints |
| 5 | `Agreement` | Master agreement record |
| 6 | `AgreementContact` | Contact information for agreement |
| 7 | `AgreementVersion` | Agreement lifecycle versions |
| 8 | `AgreementScope` | Report configuration per recipient/type |
| 9 | `PaymentTypeAssignment` | Payment types covered by scope |
| 10 | `AccountAssignment` | Bank accounts linked to payment types |
| 11 | `AliasAssignment` | Alternative identifiers |
| 12 | `ReportConfig` | Report generation configuration |
| 13 | `ReportAgreementScope` | Many-to-many mapping between configs and scopes |
| 14 | `DeadLetterMessage` | Messages that failed MQ delivery |
| 15 | `ReportCommandAudit` | Audit trail of all ReportCommand messages sent |

Additionally, outside the `CAMT` schema, in `dbo`:
- `BigIntIdList` — a table-valued parameter (TVP) type, not a table
- The 11 Quartz `QRTZ_*` job store tables (see `99-schema-quartz.sql`)

### Removed Tables
The following table from the original script has been **removed** as it was unused:
- `ReportTypeFrequency` (documentation only, no FK constraints)

## Error Handling
Each script includes:
- **Transaction wrapping**: All operations in a single transaction
- **TRY/CATCH blocks**: Explicit error handling with rollback
- **Print logging**: Detailed progress messages and timestamps
- **Validation queries**: Verify each step was successful

## Recovery/Rollback
If any script fails:
1. The transaction will automatically rollback
2. Fix the error based on the error message
3. Re-run the failed script (it will continue from where it left off)

If you need to start completely over:
1. Ensure no data has been committed (check transaction status)
2. Run `00-drop-all.sql` and `98-drop-quartz.sql` again to clean up (CAMT and Quartz objects respectively)
3. Start from Script 01

## Post-Deployment Verification

After all scripts complete successfully:

### 1. Check table count
Should have 15 tables in CAMT schema:
```sql
SELECT COUNT(*) FROM sys.tables WHERE SCHEMA_NAME(schema_id) = 'CAMT';
```

Should also have 11 Quartz tables in `dbo`:
```sql
SELECT COUNT(*) FROM sys.tables WHERE SCHEMA_NAME(schema_id) = 'dbo' AND name LIKE 'QRTZ[_]%';
```

### 2. Verify seed data counts
```sql
SELECT 'ReportType' AS TableName, COUNT(*) AS RowCount FROM CAMT.ReportType
UNION ALL
SELECT 'PaymentType', COUNT(*) FROM CAMT.PaymentType
UNION ALL
SELECT 'ReportFrequency', COUNT(*) FROM CAMT.ReportFrequency;
```

Expected results:
- ReportType: 6 rows
- PaymentType: 4 rows
- ReportFrequency: 9 rows

### 3. Check foreign keys are enabled
```sql
SELECT
    OBJECT_NAME(parent_object_id) AS TableName,
    CASE WHEN is_disabled = 1 THEN 'DISABLED' ELSE 'ENABLED' END AS Status
FROM sys.foreign_keys
WHERE SCHEMA_NAME(schema_id) = 'CAMT'
ORDER BY TableName;
```

### 4. List all objects in CAMT schema
```sql
SELECT
    TYPE_DESC AS ObjectType,
    NAME AS ObjectName
FROM sys.objects
WHERE SCHEMA_NAME(schema_id) = 'CAMT'
ORDER BY TYPE_DESC, NAME;
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| "Cannot drop schema" | Ensure no other sessions are using CAMT objects. Check for open connections. |
| "Permission denied" | Use a login with db_owner or schema modification permissions |
| "Database not found" | Verify `REPORTDB` exists before running scripts |
| "Transaction count mismatch" | Run `COMMIT` or `ROLLBACK` to clear transaction state |
| "Foreign key constraint failed" | Check that referenced tables exist and have data (reference tables should have seed data) |
| "Object already exists" | You may have skipped the drop script. Run `00-drop-all.sql` first. |
| "The current transaction cannot be committed" | Check for errors in the script and ensure all operations completed successfully |

## Important Notes

### ConfigId Generation
The `ReportConfig` table uses a two-step pattern for ConfigId generation:
1. Insert with `ConfigId = NULL`
2. Application computes: `((Id * 7919) + 1234567) % 90000000 + 10000000`
3. Update with computed 8-digit value in same transaction

The `CK_ReportConfig_ActiveHasConfigId` constraint ensures active configs always have a ConfigId.

### Sequence Generation
The `GetNextAgreementSequence` stored procedure uses `UPDLOCK` and `SERIALIZABLE` hints to prevent race conditions when multiple connections request sequences simultaneously.

### Optimistic Locking
The `AgreementVersion` table includes a `Version` column (default 0) for JPA `@Version` optimistic locking.

### Status Values
These columns are validated by Java enums at the application level:
- `AgreementVersion.Status`: PENDING_ACTIVATION, PENDING_CHANGE, PENDING_CANCELLATION, ACTIVE, REPLACED, CANCELLED, EXPIRED
- `AgreementScope.Status`: PENDING, ACTIVE, CANCELLED
- `Recipient.Type`: ORIGINATOR, BIC
- `ReportCommandAudit.status`: `NVARCHAR(30)` — widened from the original `NVARCHAR(20)` to fit longer Java-only status values (e.g. `REJECTED_CONFIG_NOT_ELIGIBLE`, `REJECTED_INVALID_WINDOW`)

### Nullable Audit Columns
`ReportCommandAudit.report_config_id`, `config_id`, `agreement_scope_id`, `report_frequency`, and `mq_queue_name` are nullable, to support rejection-audit rows where no `ReportConfig`/`AgreementScope` could be resolved (e.g. recipient-not-found, config-not-found, or messages not attributable to exactly one `AgreementScope`).

## File Structure
```
db/
├── 00-drop-all.sql
├── 01-schema-reference.sql
├── 02-schema-sequence.sql
├── 03-schema-agreement.sql
├── 04-schema-scope.sql
├── 05-schema-report.sql
├── 06-schema-audit-deadletter.sql
├── 07-schema-fetch-config.sql
├── 08-seed-data.sql
├── 98-drop-quartz.sql
├── 99-schema-quartz.sql
└── README.md
```

## Support
For issues with these scripts, contact your database administrator or development team.

## Version History
- **2026-07-11 (later)**: Added `08-seed-data.sql`, wired into the automated `compose.yaml` init chain
  - Retires the old top-level `infra/docker/init-scripts/02-seed.sql`, which had been orphaned from the init chain during the `db/` restructure
  - Section A: 2 happy-path scenarios (same data as the old seed script)
  - Section B: 3 fetch-config edge cases — zero-scope config, dangling PTA, multi-scope fan-in — mirroring `ReportConfigTreeAssemblerTest`
- **2026-07-11**: Added audit/dead-letter tables, fetch-config support, and Quartz job store
  - Added `06-schema-audit-deadletter.sql` (DeadLetterMessage, ReportCommandAudit)
  - Added `07-schema-fetch-config.sql` (dbo.BigIntIdList TVP type)
  - Added composite index `IX_ReportConfig_TypeFrequencyActive` to `05-schema-report.sql`
  - Added `98-drop-quartz.sql` / `99-schema-quartz.sql` (Quartz JDBC job store, numbered out of sequence since it's unrelated to the CAMT schema)
  - `00-drop-all.sql` now also drops `dbo.BigIntIdList`
- **2026-07-10**: Initial clean deployment scripts
  - Removed `ReportTypeFrequency` table (unused)
  - Removed all idempotency checks (`IF NOT EXISTS`)
  - Replaced `MERGE` statements with `INSERT` for seed data
  - Added comprehensive logging and transaction handling
  - Organized into 6 logical groups with execution order
  - Added `00-drop-all.sql` for complete cleanup