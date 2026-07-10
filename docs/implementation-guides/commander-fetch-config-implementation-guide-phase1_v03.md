# Commander — Report Configuration Fetch Implementation Guide (Phase 1)

**Scope:** This guide covers Phase 1 only — the scheduled path's read/assembly logic:
how active report configurations are located, how the agreement hierarchy is walked, how
results fan out into one-or-more report request messages, and the persistence approach
used to do it. It does not cover Quartz job/trigger structure or window computation (see
the Scheduling Module guide), the writer/publish step that sends messages to IBM MQ and
writes audit rows, feature-flag check placement, or the dead-letter retry mechanism —
those are separate guides, free to choose whatever processing/orchestration mechanism
fits their own concerns (chunking, batch framework or otherwise) independent of what this
guide does on the read side.

**Out of scope, by design, not by oversight:** the On-Demand API's read path (Phase 7)
and the audit-insert batching mechanism (Phase 6) were previously drafted inline in an
earlier version of this guide. Both have been removed to keep this guide scoped to Phase
1 only. They'll be designed properly as their own guides when those phases are actually
picked up — the removed content (including the on-demand cross-check/matching design and
the JDBC batch-insert rationale) remains available in the prior full revision if needed
for reference.

**Status:** Design finalized, pending implementation.

---

## 1. Design Decisions Summary

| Decision | Choice |
|---|---|
| Agreement scope trust boundary | `ReportAgreementScope` row existence alone is the eligibility signal — no re-check of `AgreementScope.Status` or `AgreementVersion.Status` at read time |
| Zero-scope / no-payment-type-list configs | Valid, first-class case — produces a config-only message using only `ReportConfig`/`Recipient` data |
| Bundling semantics | `IsBundled=true` → one message per `ReportConfig`, merged; `IsBundled=false` → one message per account-or-alias row, flattened across all scopes/payment-types for that config. Executor uses this flag to decide one XML vs. per-account XML; no-op for config-only reports |
| Account vs. alias | Mutually exclusive per `PaymentTypeAssignment` — never both |
| Persistence approach | Plain SQL via `JdbcTemplate`/`NamedParameterJdbcTemplate`, staged per hierarchy level — no JPA (reserved for Audit/DeadLetter writes only), no stored procedure |
| `IN (...)` parameter limit mitigation | SQL Server caps queries at **2,100 parameters**; a naively-expanded `IN (:ids)` list can exceed this at levels 2→3 and 3→4 of the staged read (§3), since fan-out isn't bounded by page size. Mitigation: **table-valued parameters (TVP)** — pass ID sets as a single structured SQL Server user-defined table type parameter instead of N individual bound parameters. Requires a new UDT schema migration |
| TVP binding mechanism | `NamedParameterJdbcTemplate` has no first-class TVP support. These specific staged queries are implemented via `JdbcTemplate`/raw `PreparedStatement`, unwrapped to `SQLServerPreparedStatement`, using `setStructured(...)` — wrapped behind a `TvpParameterSource` helper so call sites don't touch SQL Server-specific JDBC types directly. These repository methods are integration-tested against SQL Server (Testcontainers), not unit-tested against a DB-agnostic abstraction |
| Partial staged-query failure mid-read | No partial persistence. The staged hierarchical read (§3) is read-only and fully completes (or throws) before any write (MQ publish, audit insert) is attempted, so a failure mid-read never leaves a partial write behind. Retry/skip policy for a failed read is owned by whatever orchestrates re-invocation — not decided in this guide |
| Caching layer for hierarchy data | **Evaluated, not adopted for this design.** Parked as a named future option (§6) rather than promoted into the active design — Commander runs clustered (Quartz JDBCJobStore), so a correctness-safe cache would need to be distributed (e.g. Redis), which is new infrastructure not currently justified by measured load |
| Scheduled reader shape | Plain repository-level keyset pagination on `ReportConfig.Id`, page size **500** — no batch-processing framework involved on the read side; owned entirely by this guide |
| MQ publish vs. commit ordering | Owned entirely by the writer/publish step — not this guide's concern at all, not even to defer a numeric decision (see §6) |
| New index required | `(ReportType, ReportFrequency, IsActive)` on `ReportConfig`, covering the scheduled path's core filter + paging sort |
| Scale assumption | ~1,000,000 `ReportConfig` rows total, evenly spread across ~6 report types × ~9 frequencies (~18,500 rows per job execution on average) |

---

## 2. Scheduled Processing — Hierarchical Read

Triggered by a Quartz job carrying `(reportType, reportFrequency)`. Commander walks the
full agreement hierarchy from `ReportConfig` down to individual accounts/aliases,
because nothing about which accounts belong to the report is known in advance — it has
to be read from the database.

*A note on division of responsibility: Commander's role throughout this guide is to
decide **which** accounts/aliases belong in **which** message(s) — it never generates
report content itself. Executor is the consumer that takes Commander's command message
and builds the actual CAMT XML from it.*

*A forward-looking note: the On-Demand path (Phase 7) is expected to reuse the staged
read design in §3 — scoped to a single recipient/config rather than a paged read across
many configs — and converge on the same intermediate shape (config header + zero-or-more
scopes → payment-type assignments → account-rows-or-single-alias) before fan-out/bundling
logic (§4) runs. That design will be worked out properly in its own guide when Phase 7 is
picked up; it's noted here only so this guide's shape isn't accidentally built in a way
that forecloses it.*

---

## 3. Read Mechanics

### 3.1 Top-Level Filter
```
SELECT ... FROM CAMT.ReportConfig
WHERE ReportType = :reportType
  AND ReportFrequency = :reportFrequency
  AND IsActive = 1
ORDER BY Id
```
`ReportFrequency` is a direct column on `ReportConfig` (not inferred via the unenforced
`CAMT.ReportTypeFrequency` table), so the job's `JobDataMap` values map straight onto
this filter with no intermediate lookup.

### 3.2 Paging
A plain repository method, keyset-paginated on `ReportConfig.Id`, **page size 500**:
```
SELECT ... FROM CAMT.ReportConfig
WHERE ReportType = :reportType
  AND ReportFrequency = :reportFrequency
  AND IsActive = 1
  AND Id > :lastSeenId
ORDER BY Id
OFFSET 0 ROWS FETCH NEXT :pageSize ROWS ONLY
```
(`OFFSET 0 ROWS` is not optional — SQL Server's offset-fetch clause requires an `OFFSET`
before `FETCH NEXT` syntactically, even when the offset itself is zero and the real
paging work is being done by the `Id > :lastSeenId` predicate instead.)

The caller drives the loop: read a page, process it, take the last `Id` seen, request the
next page, until a page comes back short of `pageSize` (or empty). No open server-side
cursor, no framework component, no persisted read-position — if the caller wants to
resume after a failure, it re-supplies `lastSeenId` itself; how (or whether) that's
tracked is entirely up to whatever calls this repository, not this guide's concern. For
example, a caller could resume from a persisted `lastSeenId` across invocations, or
simply re-run the whole read from the start each time and rely on Phase 6's audit-based
dedup to skip already-sent items — both are legitimate, and given dedup already exists,
the second is likely the simpler choice. The read layer does not prescribe either.

This deliberately carries no restart/checkpoint guarantee of its own. Keyset pagination
(`Id > :lastSeenId`) rather than `OFFSET n ROWS` is still the right choice regardless —
`OFFSET`-based paging degrades on large offsets and can skip or repeat rows if data
changes between page reads, whereas `Id > :lastSeenId` doesn't have either problem. But
whether re-processing after a failure needs to resume from an exact position at all, or
can simply re-run from the start and rely on Phase 6's audit-based dedup to skip
already-sent items, is a question for whatever orchestrates re-invocation of this
read — not something this guide needs to answer or build machinery for.

**Connection/transaction ownership.** These repository methods — the page query above and
the staged sub-reads in §3.3 — are plain read queries and manage no transaction
themselves. If a caller wants read consistency across a page's staged sub-reads, the
natural scope for that is a single short-lived, read-only transaction *per page* — not
one held open across the entire multi-page read. Holding a transaction or connection open
across pages defeats the reason pages exist in the first place (bounding resource use per
unit of work) and is not recommended; this guide assumes callers won't do that, but
doesn't enforce it, since transaction demarcation is the caller's responsibility, not
something these repository methods impose.

Page size 500 is carried forward from the original scale estimate (§9, Round 4) as a
starting hypothesis, not re-derived here — it balances round-trip overhead against
result-set/memory size and remains untested against real fan-out volume (§6).

### 3.3 Staged Batched Sub-Reads (per page from §3.2, not per whole read)
To avoid join-fanout/Cartesian row multiplication across scope × payment-type × account
levels, each hierarchy level is fetched as its own flat, indexed query, keyed off the IDs
collected from the level above:

1. `ReportAgreementScope` / `AgreementScope` `WHERE ReportConfigId IN (:configIds)`
2. `PaymentTypeAssignment` `WHERE AgreementScopeId IN (:scopeIds)`
3. `AccountAssignment` **or** `AliasAssignment` `WHERE PaymentTypeAssignmentId IN (:paymentTypeAssignmentIds)` (mutually exclusive per assignment — never both for the same row)

Results are grouped back into a tree in Java (config → scopes → payment-type
assignments → account rows or a single alias), scoped to the current page only, keeping
memory bounded regardless of total table size — bounded by *page size*, that is, not by
total table size; the actual footprint for a page is page size × fan-out depth, and
"bounded" doesn't mean "small" if fan-out per config turns out to be large. For the
expected scale (§9, Round 4) this is well within normal JVM heap, but this is exactly the
same unmeasured fan-out risk already flagged in §6 — if unusually deep fan-out is
observed in practice, page size is externalized and can be lowered as the first
mitigation.

*(See §3a for a correctness constraint on how these `IN (...)` clauses must actually
be implemented — a naive expansion has a hard failure mode at scale, not just a
performance concern. See §3b for what happens if one of these staged queries fails
partway through.)*

### 3a. `IN (...)` Parameter Limit and Mitigation
SQL Server enforces a hard cap of **2,100 parameters per query**. If
`NamedParameterJdbcTemplate` is used the naive way — binding a collection-valued named
parameter, which expands to one `?` per element — any staged query in §3.3 whose ID list
exceeds ~2,100 elements will fail outright (`The incoming request has too many
parameters` or equivalent), not degrade gracefully.

**Where this actually bites:** Level 1→2 (`WHERE ReportConfigId IN (:configIds)`) is
naturally bounded by the reader page size (500), so it's safe as-is. Levels 2→3 and 3→4
are **not** bounded by page size directly — they're bounded by fan-out, which §6 already
flags as unmeasured. If one page's 500 configs collectively resolve to, say, 3,000+
scopes (plausible, since one config can link to multiple scopes per the domain model),
the level-3 query already exceeds the limit and crashes rather than just running slowly.
This is a correctness/crash risk, not merely a performance concern.

**Decision: table-valued parameters (TVP).** Each staged query's ID list is passed as a
single structured SQL Server user-defined table type (UDT) parameter rather than N
individually bound parameters — a TVP counts as *one* parameter regardless of how many
rows it carries, so the 2,100 cap is sidestepped entirely rather than budgeted around.
Chosen over the alternatives:
- **IN-list chunking** (splitting any ID collection over ~2,000 into sub-batches, issuing
  multiple queries per level and merging in Java) — works, but adds round-trips exactly
  where the staged-query design was already trying to minimize them, and adds a second,
  independent layer of batching logic (page-level *and* IN-list-level) that has to be
  correct at every hierarchy level.
- **Temp table join** (insert the ID set into a session-scoped `#temp` table, join against
  it instead of `IN (...)`) — also sidesteps the limit, but adds insert-then-join
  round-trip overhead and more careful transaction/session lifecycle handling.

**Implementation implication — schema:** requires a new SQL Server user-defined table
type (e.g. a single-`BIGINT`-column type for passing ID sets), added as a schema
migration alongside the composite index in §3.6 (see the consolidated migration list in
§6).

**Implementation implication — binding mechanism:** TVP support in
`NamedParameterJdbcTemplate` is not first-class. In practice, the staged-query repository
methods that use a TVP are implemented via `JdbcTemplate` or a raw `PreparedStatement` —
unwrapped to the SQL Server-specific `SQLServerPreparedStatement` — and bound using
`SQLServerPreparedStatement#setStructured(...)` against a `SQLServerDataTable` built from
the ID collection. This is scoped to *these specific staged queries only*; it is not a
wholesale move away from `NamedParameterJdbcTemplate` elsewhere in the codebase. To keep
call sites from touching SQL Server-specific JDBC types directly, this is wrapped behind
a small `TvpParameterSource` helper that presents a consistent interface at the
repository layer.

**Implementation implication — testing:** because the binding relies on SQL
Server-specific JDBC APIs rather than Spring's higher-level, DB-agnostic abstractions,
these particular repository methods are integration-tested against real SQL Server
(Testcontainers), not unit-tested against a fake/abstracted `SqlParameterSource`. This is
consistent with how the rest of the read path is already tested (§5), just worth calling
out explicitly since the TVP methods have less room to be tested any other way.

**Implementation implication — portability:** binding to `SQLServerDataTable`/
`SQLServerPreparedStatement` couples this part of the repository layer to the SQL Server
JDBC driver specifically. This is an accepted tradeoff, consistent with other SQL
Server-specific choices already made elsewhere in the codebase (e.g. T-SQL-specific
`OFFSET/FETCH` paging, `WITH (UPDLOCK, ROWLOCK)` locking hints) — not treated as a new
platform-lock-in decision introduced by this guide.

### 3b. Partial Staged-Query Failure Mid-Read
What happens if, for a given page, one staged query succeeds (e.g. scopes fetched) and
the next one fails (e.g. the payment-type-assignment query throws — timeout, connection
drop, etc.)?

**Decision: no partial persistence, no bespoke rollback logic.** The staged hierarchical
read (§3.3) is a read-only sequence — nothing is written to `ReportCommandAudit` or
published to MQ until *after* the full tree for that page has been successfully
assembled. If a staged query fails partway through, nothing has been written yet, so
there is no partial-write state to roll back at the DB level. The failure simply
propagates to the caller as an exception; the caller (whatever orchestrates the read →
write flow, and however it decides to retry or skip) decides what happens next. This
guide does not define a retry/skip policy, because retrying or skipping is a decision
about the write/orchestration flow, not about this read path.

This is a correctness statement about *where* failure can leave data, not a retry-tuning
recommendation — transient-failure retry policy (e.g. whether a TVP query timeout should
be retried with backoff) is general DB-resiliency policy, not specific to this read path,
and is out of scope for this guide (§7).

### 3.4 Eligibility Trust Boundary
`ReportAgreementScope` is populated by a nightly (22:00) reconciliation job in the
Agreement Management (AM) API — newly active scopes are added, cancelled scopes are
removed. **Commander trusts this table's contents as-is**: the mere existence of a
`ReportAgreementScope` row is the signal that a scope is currently reportable. Commander
does **not** re-check `AgreementScope.Status` or `AgreementVersion.Status` at read time.

**Accepted tradeoff:** because the sync runs once nightly, there is an up-to-24-hour lag
between an agreement being cancelled in the AM system and it disappearing from
Commander's eligible set. This is documented, accepted behavior, not a defect.

### 3.5 Zero-Scope Configs
A `ReportConfig` with no linked `ReportAgreementScope` rows is a **valid** business case,
not an error: it produces a report using only `ReportConfig`/`Recipient` data, with no
payment types, accounts, or aliases. `IsBundled` does not apply in this case (nothing to
fan out over) — exactly one message is produced, but the flag is still stamped onto the
outgoing command to Executor for contract consistency; Executor uses `IsBundled` to
decide whether to write one XML or split per account, and simply has nothing to split
when the account list is empty, so the flag is a no-op here rather than something
Executor branches on specially.

This is a permanent, expected shape for at least one report type (`CAMT054D`, which
never has agreement data by design) and a temporary shape for others (see note below).

*Note: this is expected to be temporary — in the long term, every active `ReportConfig`
should have at least one linked agreement scope. The zero-scope path should remain
supported but is not the long-term steady state.*

### 3.6 Required Index
The current schema only indexes `MessageRecipientId` on `ReportConfig`. At ~1M rows, the
scheduled path's filter/sort (§3.1) requires a new composite index:
```
CREATE INDEX IX_ReportConfig_TypeFrequencyActive
    ON CAMT.ReportConfig (ReportType, ReportFrequency, IsActive)
    INCLUDE (Id, MessageRecipientId);
```
(Exact included columns to be finalized against the actual projection needed — placeholder
here reflects the columns known to be used immediately.) This must ship as a schema
migration alongside the read implementation.

### 3.7 Timeouts
Timeouts are externalized as configuration properties rather than hardcoded, and this
applies uniformly to every query this guide defines — the top-level filter/page query
(§3.1/§3.2) exactly the same as the staged queries (§3.3) and the TVP-backed queries
specifically (§3a). There's no reason for the top-level query to be treated differently
just because it isn't the one with the parameter-limit concern; all of them are network
round-trips that can hang. Transaction/commit granularity for the *write* side (how many
results from this read get published/audited before a commit) is entirely the write
step's decision — this guide's page size (a read-batching concept) has no implied
relationship to it, and this guide takes no position on what that write-side granularity
should be.

---

## 4. Fan-Out / Bundling Logic

This logic is application-layer (behind a port), not persistence logic.

| Case | Behavior |
|---|---|
| Zero linked scopes | Exactly one message, config-only content (no payment/account/alias data). `IsBundled` still stamped on the outgoing command for contract consistency, though it drives no fan-out here — Executor has nothing to split when the account list is empty. |
| `IsBundled = true` | One message per `ReportConfig`, merging every account/alias reachable across **all** linked scopes and payment-type assignments. Executor writes this as a single XML report. |
| `IsBundled = false` | One message **per account-or-alias row**, flattened across all scopes/payment-types for that config (total message count = sum of every account/alias row reachable from the config) — but each message retains its own originating `agreement_scope_id` for audit purposes; flattening affects message *count*, not per-message scope lineage. Executor writes a separate XML report per message. |

Account and alias are mutually exclusive per `PaymentTypeAssignment` — a single
assignment resolves to either a list of accounts or a single alias, never both — so
fan-out never has to reconcile the two within one assignment.

**How lineage survives flattening:** each account/alias row produced by the staged reads
(§3.3) carries its originating `AgreementScope.Id` with it as part of that row's data,
not just implicitly through query structure. When `IsBundled = false` flattens the tree
into one message per account-or-alias row, that `agreement_scope_id` travels with the row
into the outgoing message/audit entry unchanged — flattening changes how many messages
are produced, not what data each one carries. A reader of just this section, without
cross-referencing the decision log, should be able to see why lineage isn't lost.

---

## 5. Persistence Approach

**Decision: plain SQL via `JdbcTemplate` / `NamedParameterJdbcTemplate`, staged per
hierarchy level. No JPA for this read path (JPA is reserved for `ReportCommandAudit` /
`DeadLetterMessage` writes only). No stored procedure.**

Rationale:

1. **Business logic placement.** The genuinely tricky part of this read is the
   fan-out/bundling logic (§4), which is application-layer business logic, not
   persistence logic. Keeping it in Java, behind a port, keeps it code-reviewable,
   unit-testable without a database, and consistent with the hexagonal architecture used
   elsewhere in Commander/Executor. Pushing it into a stored procedure would either
   split domain rules across two languages (T-SQL + Java) or reduce the SP to a plain
   projection query anyway — in which case plain SQL achieves the same result with less
   deployment ceremony.
2. **Testability.** Repository adapters backed by parameterized SQL integration-test
   cleanly with Testcontainers and fake cleanly behind a port for use-case-level unit
   tests. Multi-result-set stored procedures called via JDBC (`CallableStatement` +
   repeated `getMoreResults()`) carry meaningfully more boilerplate for no corresponding
   benefit here, since the query isn't doing heavy set-based computation. (The TVP-backed
   staged queries specifically are an exception to the "fakes cleanly" half of this
   statement — see the testing note in §3a.)
3. **Versioning and review.** SQL living alongside the Java that consumes it stays in
   the same PR/diff/review as the logic it feeds. A stored procedure is a separately
   deployed artifact, one layer removed from the calling code, needing its own migration
   coordination.
4. **The join-fanout problem is solved by query shape, not by choice of SP vs. SQL.**
   Staged, batched, `IN (...)`-keyed queries per hierarchy level (§3.3) avoid Cartesian
   row multiplication regardless of whether they're issued as raw SQL or wrapped in a
   procedure — so this concern doesn't favor either approach.

**What would change this recommendation:** if real fan-out volumes turn out large enough
that N staged queries per page become a genuine latency bottleneck (not expected at
current estimates), a single well-tuned denormalized query — or, as a last resort, an SP —
could be revisited. No evidence of that today. A caching layer in front of the staged
reads was also considered under this same escape hatch and parked rather than adopted —
see §6.

---

## 6. Open Items (Not Blocking, Flagged for Later)

- **⚠️ Highest-priority item: real fan-out depth is unknown.** Page size (500) is a
  starting hypothesis based on `ReportConfig` row distribution alone; actual
  scope/payment-type/account counts per config (which drive real per-page result size —
  see the memory-footprint note in §3.3 — and separately, whatever write-side
  transaction/commit sizing the writer/publish step chooses) haven't been measured yet.
  This single unknown is what every other sizing assumption in this guide (page size,
  index columns, expected memory footprint) is provisionally hanging off of — it's listed
  first for that reason. Revisit this guide's page size once representative data is
  available.
- **Index column list (§3.6) is provisional** — the exact `INCLUDE` columns should be
  finalized against the final query projection once the reader/repository is
  implemented, not treated as final here. The new TVP user-defined table type (§3a)
  should ship as part of the same migration effort.
- **Consolidated pending schema migrations (Phase 1 scope):** (1) composite index
  `(ReportType, ReportFrequency, IsActive)` on `ReportConfig` (§3.6); (2) TVP
  user-defined table type for ID-set parameters (§3a). These should ship together as one
  migration effort where practical. (Additional migrations belonging to Phase 6/7 — a
  new audit column, new audit status values — are tracked in those guides, not here.)
- **Long-term zero-scope assumption.** §3.5's zero-scope case is currently expected to be
  temporary for most report types (every active config should eventually have at least
  one linked scope) — the code path should remain supported indefinitely regardless, but
  this is worth revisiting if/when the "always at least one scope" invariant is actually
  enforced upstream. Note this is **permanent, not temporary, for `CAMT054D`** specifically
  (see §3.5) — that report type never has agreement data by design.
- **MQ publish vs. DB commit ordering — entirely the writer/publish step's decision.**
  Whether publish happens inside the same transaction as the audit insert (risky — holds
  DB locks across network I/O) or after commit (safer, but needs idempotency/retry
  handling for post-commit publish failures) is not addressed here at all — this guide
  produces read results and takes no position on how they're subsequently written.
- **Caching layer for hierarchy data (evaluated, not adopted).** Since
  `ReportAgreementScope`/`PaymentTypeAssignment`/`AccountAssignment` data only changes
  once per night (§3.4), a cache populated after the nightly sync (TTL or explicit
  invalidation on sync) could in principle remove most staged queries entirely. Not
  adopted for the active design because: (1) Commander runs clustered (Quartz
  JDBCJobStore), so an in-memory cache would be per-node and incoherent across
  instances — a correctness-safe version needs a distributed cache (e.g. Redis), which is
  new infrastructure not currently justified; (2) scheduled processing already reads each
  config's hierarchy once per job run, not repeatedly within a run, so there's no clear
  redundant-read case on this path today. Named here explicitly as one of the options to
  reach for if the fan-out-volume escape hatch in §5 is ever hit, rather than left
  implicit.
- **General DB-resiliency / retry policy is out of scope for this guide.** Transient-
  failure handling for individual query timeouts (e.g. a TVP-backed staged query timing
  out) and DB connection-pool exhaustion under load are not addressed here. These are
  treated as the same category of concern already deferred to the writer/publish guide
  for MQ failure handling (§7). Whichever guide ends up owning general DB-resiliency/
  retry policy for Commander should cover query-level timeout/retry-with-backoff
  behavior; this guide intentionally does not invent that policy in isolation for just
  the read path.

---

## 7. Explicitly Out of Scope for This Guide

- Quartz job/trigger structure and window computation — see the Scheduling Module guide.
- The writer/publish step (constructing and sending the actual `ReportMessage` to IBM
  MQ) — separate guide, free to choose its own processing/orchestration mechanism
  (chunking, a batch framework, or otherwise) independent of this guide's read design.
- Feature flag check placement within the execution flow — separate guide.
- `ReportCommandAudit` / `DeadLetterMessage` write logic, including the audit-insert
  batching mechanism — separate guide (Phase 6).
- The On-Demand API's read path, request contract, rejection handling, and window-input
  mode — separate guide (Phase 7), expected to reuse this guide's staged-read design
  (§3) but not designed here.
- General DB-resiliency/retry policy — transient query-timeout retry-with-backoff and DB
  connection-pool exhaustion handling. Same category as the MQ-failure handling already
  deferred above; not owned by this guide (§6).

---

## 8. Guidance for AI-Assisted Code Generation

This section is written for whichever agent (AI or human) implements this guide. It's
not a restatement of §1–§7 — it's the layer on top: how to turn the *decisions* above
into code that's idiomatic, testable, and doesn't quietly reintroduce something this
guide deliberately rejected.

### 8.1 Guardrails — Do Not Reintroduce These

Every item below was considered and explicitly rejected somewhere in §1–§7 or the
decision log (§9). If the resulting code needs any of these, that's a sign the
requirement changed and this guide needs to be revisited first — not that the agent
should quietly add it back in because it's a familiar or "safer" default:

- **No Spring Batch, no `ItemReader`/`ItemWriter`/`Step`/`Job` anywhere in this read
  path.** Reading is a plain repository method (§3.2). If a generation prompt or partial
  codebase context nudges toward `JdbcPagingItemReader`, that's exactly the mistake §9
  Round 8 corrected — don't repeat it.
- **No JPA/Hibernate entities or repositories for this read path.** `ReportConfig` and
  the agreement hierarchy are read via `JdbcTemplate`/`NamedParameterJdbcTemplate` only
  (§5). JPA is reserved for `ReportCommandAudit`/`DeadLetterMessage` writes, which this
  guide doesn't touch at all.
- **No stored procedure.** The fan-out/bundling logic (§4) stays in Java, not T-SQL.
- **No single denormalized `LEFT JOIN` query** across the scope → payment-type →
  account/alias hierarchy. This is the exact Cartesian-row-multiplication problem the
  staged design (§3.3) exists to avoid — collapsing it back into one query defeats the
  guide's central design decision, even if it looks simpler.
- **No naive `IN (:ids)` binding via `NamedParameterJdbcTemplate`'s collection
  expansion** for the level-2→3 and level-3→4 staged queries. This isn't a style
  preference — it has a hard failure mode at scale (§3a). TVP binding is mandatory for
  those two queries specifically, even in a first draft, even against small local test
  data where the naive version would "work fine." Don't defer it as a follow-up
  optimization.
- **No caching layer** (in-memory, Caffeine, Redis, or otherwise) in front of the
  hierarchy reads. Evaluated and explicitly parked (§6) — clustering makes an in-memory
  cache incoherent, and no distributed-cache infrastructure has been justified.
- **No chunk/commit-size configuration on the read side.** Transaction/commit
  granularity is a write-step concern this guide doesn't own (§3.7). Don't invent a
  `chunkSize` property here even by analogy with `pageSize`.
- **No swallowed exceptions in the staged read.** §3b's decision is that a mid-read
  failure propagates to the caller as-is. Don't wrap staged-query calls in a try/catch
  that logs and returns a partial or empty tree — that silently produces wrong output
  (a config that looks zero-scope when it actually failed to read) instead of a clear
  failure.

### 8.2 Architecture & Package Structure

Hexagonal/ports-and-adapters, consistent with the rest of Commander (§5's rationale) and
with how existing sibling code (e.g. the `demo-quartz` reference codebase) is organized:

- **Port (interface), in the application/domain layer:** something like
  `ReportConfigHierarchyReader`, exposing the paged read (§3.2) and returning the
  assembled tree shape. This is what the fan-out/bundling logic and, later, the
  writer/publish step depend on — never the JDBC adapter directly.
- **Adapter (implementation), in the infrastructure layer:** the actual
  `JdbcTemplate`/`NamedParameterJdbcTemplate` repository code, including the TVP-specific
  binding (§3a). Keep the `SQLServerPreparedStatement`/`SQLServerDataTable` unwrapping
  contained to this layer — nothing above the adapter boundary should import SQL
  Server-specific JDBC types.
- **Fan-out/bundling (§4) as its own application-layer component**, taking the assembled
  tree as input and producing the message list as output. Pure function, no I/O — this
  is the part of the design that most benefits from being trivially unit-testable
  without a database (§5's whole rationale for keeping this out of a stored procedure),
  so don't let it leak SQL or repository types into its signature.

### 8.3 Design Patterns to Apply

- **Repository pattern** for §3.1–§3.3's reads, one method per hierarchy level plus one
  orchestrating method that runs the staged sequence and assembles the tree. Keep each
  staged query's method small and named for what it fetches (e.g.
  `findScopesByConfigIds`, `findPaymentTypeAssignmentsByScopeIds`), not generic
  (`findByIds`) — the specificity matches the guide's "flat, indexed query per level"
  framing (§3.3) and keeps SQL reviewable per-method.
- **A dedicated `TvpParameterSource`-style helper (§3a)** wrapping the
  `SQLServerDataTable`-building and `setStructured(...)` binding, so the two TVP-backed
  repository methods call one shared, tested piece of code rather than duplicating
  low-level SQL Server JDBC handling.
- **Immutable value types (Java `record`s)** for every query projection and for the
  assembled tree's nodes (config, scope, payment-type assignment, account/alias row).
  This tree is read-only by construction (§3.3's "results are grouped back into a tree")
  — there's no reason for it to be mutable, and immutability makes the fan-out logic
  (§4) easier to reason about and test.
- **Explicit typed distinction between "zero-scope config" and "config with an empty
  page of scopes due to pagination in progress."** These are different states (§3.5 vs.
  normal mid-read state) — don't collapse them into the same `null`/empty-list
  representation if the calling code needs to tell them apart. An empty `Optional` or a
  small sealed hierarchy (e.g. a `ScopeSet` that's either `NoScopes` or
  `Scopes(List<Scope>)`) communicates this better than an ambiguous empty collection.
- **Strategy-free, not Strategy-pattern, for `IsBundled` branching (§4).** The
  bundled/unbundled/zero-scope cases in §4's table are a closed set that isn't expected
  to grow — a simple `switch`/pattern-match over the three cases is more readable and
  just as testable as a `Strategy` interface with three implementations would be here.
  Reach for the interface only if a fourth case is added later, not preemptively.

### 8.4 Data Access / SQL Specifics

- Every query is parameterized — `reportType`, `reportFrequency`, all ID collections.
  No string concatenation building SQL, including for the zero-scope case (§3.5) — that
  path still goes through the same parameterized top-level query, it just short-circuits
  the staged sub-reads when the scope-ID list comes back empty.
- The TVP methods (§3a) are the only place raw `PreparedStatement`/
  `SQLServerPreparedStatement` should appear. Every other query in this guide uses
  `NamedParameterJdbcTemplate` — don't drop to raw JDBC elsewhere "for consistency" with
  the TVP methods; the guide's rationale (§3a) is that TVP is the exception, not the
  house style.
- Map `ResultSet` rows to the `record` projections (§8.3) via `RowMapper`/
  `BeanPropertyRowMapper`-style mapping kept next to the query that produces them, not a
  shared generic mapper reused across unrelated queries — each staged query's shape is
  deliberately narrow (§3.3), and the mapping code should mirror that.
- All numeric/timing constants named in this guide as configuration (page size,
  per-query timeouts — §3.2, §3.7) are `@ConfigurationProperties`-bound, not
  hardcoded or passed as magic numbers. Property keys should follow this codebase's
  existing kebab-case convention (Spring Boot does not auto-normalize this, so match the
  existing style exactly rather than defaulting to camelCase).

### 8.5 Error Handling & Logging

- Let failures from the staged read propagate as unchecked exceptions (§3b) — don't
  invent a new checked-exception hierarchy for this read path unless one already exists
  elsewhere in Commander that this should be consistent with.
- Log at the point of failure with enough context to debug without a debugger:
  `reportType`, `reportFrequency`, the page's `lastSeenId` range, and which staged query
  (level 1/2/3) failed. Don't log full ID collections at info level (could be hundreds of
  IDs from a TVP-bound query) — log counts, and put full detail behind debug/trace if
  needed.
- No PII beyond what's already routinely logged elsewhere in Commander — recipient
  identity (`RecipientType`/`RecipientValue`) is a reasonable thing to log for
  traceability given it's already a first-class domain key (per the solution document's
  domain model), but avoid logging entire assembled trees, which could be large per
  §3.3's memory-footprint note.

### 8.6 Testing Strategy

Matches the split the guide already describes (§5, §3a):

- **Unit tests, no database:** the fan-out/bundling logic (§4) — every row of that
  table (zero-scope, bundled, unbundled) should be a distinct test case, plus the
  account/alias mutual-exclusivity assumption and the `agreement_scope_id` lineage
  claim added in §4 (assert it survives flattening, don't just assert message *count*).
- **Integration tests, Testcontainers + real SQL Server:** every repository method,
  especially the two TVP-backed ones (§3a already states these can't be meaningfully
  faked). Include a test that actually exceeds ~2,100 IDs for a TVP-bound query, to prove
  the mitigation works, not just that the code compiles against a small fixture.
- **A test for the zero-scope path (§3.5)** that isn't just "empty scope list" but
  specifically "config with `IsActive=1` and zero `ReportAgreementScope` rows" end to
  end through the repository, since that's the actual DB-level condition being handled.
- Use JUnit 5 and AssertJ fluent assertions, consistent with the rest of Commander.

### 8.7 Documentation in Code

- The TVP methods (§3a) and the zero-scope handling (§3.5) are the two places in this
  guide where the "obvious" naive implementation is wrong. Put a short comment at each
  explaining *why* (parameter-limit crash risk; valid business case not a bug) so a
  future maintainer without this guide in front of them doesn't "simplify" either one
  back to the naive version.
- Reference this guide's section numbers in comments where it clarifies intent (e.g. `//
  TVP binding — see Phase 1 fetch guide §3a`), rather than re-explaining the full
  rationale inline every time.

### 8.8 When the Guide Is Silent or Ambiguous

If implementation reveals a decision this guide doesn't cover (an edge case in the
tree-assembly logic, a mapping detail, an exact index column beyond what §3.6 already
flags as provisional) — the correct move is to flag it explicitly rather than pick a
silent default, the same way every open item in §6 was surfaced rather than quietly
resolved. A one-line "guide doesn't specify X, implemented as Y, worth confirming" is
more valuable than a confident guess baked into the code with no trace of the decision
having been made.

---

## 9. Decision Log

Record of the design discussion that produced this guide, kept for future reference.
Trimmed to Phase 1 (scheduled read path) items only — on-demand-specific rounds from the
original discussion are not reproduced here.

### Round 1 — Eligibility Filtering

| # | Question | Decision |
|---|---|---|
| 1 | Should the `AgreementScope` join re-check `AgreementScope.Status`/`AgreementVersion.Status`, or is a pending-cancellation version's still-active scope in scope? | Neither is re-checked. A nightly (22:00) AM API reconciliation job maintains `ReportAgreementScope` as the trusted, pre-filtered signal — its presence alone means "currently reportable." Accepted tradeoff: up to 24h lag between an AM-side cancellation and it disappearing from Commander's eligible set. Documented, not a defect. |
| 2 | Is a `ReportConfig` with zero linked `AgreementScope` rows a no-op, or a data-integrity flag? | Valid business case — report contains only `ReportConfig`-table data, no payment types/accounts/aliases. Noted as expected to be temporary; long-term every active config should have at least one scope, but the zero-scope path remains supported. |

### Round 2 — Bundling / Fan-Out

| # | Question | Decision |
|---|---|---|
| 3 | What does `ReportConfig.IsBundled` control? | Confirmed hypothesis for `true`: one message per `ReportConfig`, merged across all linked scopes. For `false`: one message **per account or alias** (finer-grained than "per scope"). |
| 4 | Is bundling ever mixed with the `PaymentTypeAssignment` level? | Same answer as #3 — no separate payment-type-level bundling rule. |
| — | Can a single `PaymentTypeAssignment` have both accounts and an alias? | No — mutually exclusive in practice. |
| — | When `IsBundled=false`, does fan-out flatten across *all* linked scopes for the config, or stay scoped per-scope? | Flattens across all linked scopes and payment-type assignments for that config — total message count = sum of every account/alias row reachable from the config, regardless of originating scope/payment-type. Each message still retains its own `agreement_scope_id` for audit (resolves the apparent tension with `ReportCommandAudit.agreement_scope_id` being `NOT NULL` — flattening is about message *count*, not lost lineage). |
| — | For a zero-scope config, does `IsBundled` apply? | No — nothing to fan out over, so exactly one message always. The flag is still passed through on the outgoing command to Executor regardless. |
| — | Why is `IsBundled` stamped on a zero-scope/config-only message if it drives no fan-out there? | Executor uses `IsBundled` when fetching transactions for the recipient to decide whether to write one XML (bundled) or a separate XML per account (`IsBundled=false`). For a config-only report there's nothing to split either way, so the flag is a no-op on that path specifically, not something Executor branches on specially — it's stamped purely for command-contract consistency. |

### Round 3 — Persistence Approach

| # | Question | Decision |
|---|---|---|
| — | JPA, stored procedure, or plain SQL for the read path? | Plain SQL via `JdbcTemplate`/`NamedParameterJdbcTemplate`, staged per hierarchy level. No JPA (reserved for Audit/DeadLetter writes only) — Commander is read-only against `ReportConfig`/agreement tables. No stored procedure: the fan-out/bundling logic is application-layer business logic that should stay in Java behind a port, not split into T-SQL or reduced to an SP that's just a projection query anyway. Read mechanics (staged, `IN (...)`-keyed queries per level) solve the join-fanout problem regardless of SP vs. SQL, so that concern doesn't favor either approach. |
| — | Query shape: one denormalized query, staged batched queries, or JPA entity graphs? | Staged batched queries per hierarchy level (`WHERE ... IN (:parentIds)`), keeping each query flat and independently testable, and avoiding hand-written multi-row-per-parent grouping logic (e.g. `ResultSetExtractor` null-row handling for `LEFT JOIN`s) that's easy to get subtly wrong. |

### Round 4 — Scale & Batch Sizing

| # | Question | Decision |
|---|---|---|
| — | Expected `ReportConfig` row volume? | ~1,000,000 total rows. |
| — | Distribution across report types/frequencies? | Roughly even across ~6 report types × ~9 frequencies (~18,500 rows per job execution on average) — no single combination requires special-case tuning. |
| — | Reader shape given this scale? | *Superseded — see Round 8.* Original decision was `JdbcPagingItemReader` (Spring Batch); replaced with plain repository-level keyset pagination. Bulk upfront in-memory load remains ruled out at this row count regardless. |
| — | Page size? | 500, proposed as a starting point balancing round-trip overhead against result-set/memory size. (Unaffected by Round 8 — still applies to keyset pagination.) |
| — | Chunk/commit size? | *Superseded — see Round 8.* This was a write-transaction-boundary concept that shouldn't have been decided in this guide at all; removed rather than reassigned. |
| — | New index required? | Yes — `(ReportType, ReportFrequency, IsActive)` composite index on `ReportConfig`, since only `MessageRecipientId` is currently indexed and a table scan across 1M rows per job fire is otherwise unavoidable. Must ship as a schema migration alongside the read implementation. |

### Round 5 — Peer Review, Read-Path Items

A colleague's review of an earlier draft raised several items; the two below concerned
the scheduled read path specifically and were resolved from existing schema/framework
knowledge, no business input needed.

| # | Question | Decision |
|---|---|---|
| F | `ReportAgreementScope` — physical delete or soft-delete on the nightly sync? | Physical delete, confirmed by the business and consistent with the schema (`CAMT.ReportAgreementScope` has no status/`IsActive`/soft-delete column to begin with). |
| G | Does page-size (500) ≠ chunk-size (50–100) cause duplicate re-reads on restart? | *Premise superseded — see Round 8.* This question assumed a Spring Batch reader/step pairing that no longer exists in this guide's design; the checkpointing mechanism it describes doesn't apply to the plain repository pagination that replaced it. |
| B | MQ publish vs. DB commit transaction boundary | Explicitly deferred to the writer/publish guide — agreed not to resolve it here, since it's that step's concern, not the fetch/assembly layer's. |

### Round 6 — SQL Server `IN (...)` Parameter Limit

| # | Question | Decision |
|---|---|---|
| — | Does SQL Server limit the number of parameters in an `IN (...)` clause? | Confirmed — 2,100 parameters per query, hard cap. A naive `NamedParameterJdbcTemplate` collection-parameter binding expands to one bound parameter per ID, so any staged query (§3.3) whose ID list exceeds ~2,100 fails outright rather than degrading gracefully. Levels 2→3 and 3→4 of the staged read are exposed to this (unlike level 1→2, which is bounded by page size), since fan-out is unmeasured (§6). |
| — | Mitigation: IN-list chunking, table-valued parameters, or temp-table join? | **Table-valued parameters (TVP).** Sidesteps the 2,100 limit entirely (a TVP is one parameter regardless of row count), keeps each staged query a single round-trip (consistent with the existing staged-query design intent), at the cost of a new SQL Server user-defined table type as a schema migration. Chunking was rejected as adding round-trips and a second independent layer of batching logic; temp-table join was rejected as adding insert/session ceremony for no advantage over a TVP in this case. No reason found to avoid TVPs in this codebase, so no further tradeoff discussion needed. |

### Round 7 — Peer Review of TVP / Error-Handling / Caching Feedback

A written peer review raised several items relevant to the scheduled read path. Each was
independently triaged — agreed-as-is, corrected, or evaluated-and-parked — rather than
accepted wholesale.

| # | Item Raised | Outcome |
|---|---|---|
| 1 | TVP implementation complexity understated — `NamedParameterJdbcTemplate` has no first-class TVP support; `SQLServerDataTable`/`SQLServerPreparedStatement#setStructured(...)` needed; portability and unit-testing impact | **Agreed — decision unchanged, detail added.** TVP remains the chosen mitigation (§3a/§6 Round 6); the binding mechanism, a `TvpParameterSource` wrapper, and the testing-strategy implication (integration-tested via Testcontainers, not unit-tested against a DB-agnostic abstraction) are now stated explicitly in §3a. |
| 2 | Missing error handling & retry strategy — partial staged-query failure and DB connection-pool exhaustion under load flagged | **Split.** In scope, previously undefined, now resolved: partial staged-query failure mid-read (§3b — no partial persistence; failure propagates to the caller, which owns retry/skip policy — see Round 8 for how this was later decoupled from any particular framework). Out of scope for this guide: TVP timeout/retry-with-backoff and connection-pool exhaustion are general DB-resiliency/transient-failure handling — deferred to whichever guide owns that policy (§6, §7). |
| 3 | Caching strategy for hierarchy data — cache after nightly sync with TTL/invalidation, "would eliminate most staged queries" | **Evaluated, not adopted.** Commander runs clustered (Quartz JDBCJobStore); a correctness-safe cache would need to be distributed (e.g. Redis) — new infrastructure not currently justified by measured load. No evidence staged queries are a bottleneck today. Falls under the escape hatch already stated in §5 ("if real fan-out volumes turn out large enough... could be revisited"); named explicitly as an option under that hatch in §6 rather than promoted into the active design. |
| 4 | TVP abstraction wrapper (`TvpParameterSource`-style helper to present a consistent interface at call sites) | **Agreed, low-risk.** Folded into the §3a implementation-implication update alongside item 1. |
| 5 | Configuration externalization — page/chunk size already covered; TVP batch size and query timeouts were not | **Agreed.** Staged-query and TVP-query timeouts added as configurable properties alongside the existing page-size/chunk-size properties (§3.7). |

### Round 8 — Removing Spring Batch from the Read Path

| # | Question | Decision |
|---|---|---|
| — | Should this guide continue to specify `JdbcPagingItemReader` and a chunk/commit size for the scheduled read? | No. This guide's own scope statement excludes the writer/publish step, yet the reader shape and chunk sizing had quietly imported a Spring Batch framework choice and a write-transaction-boundary concept (chunk/commit size bounds MQ-publish-plus-audit-insert blast radius — a write concern) into a guide that owns reading only. Reader shape replaced with a plain repository method using keyset pagination (§3.2); chunk/commit sizing removed outright, not reassigned elsewhere in this guide. |
| — | Does removing Spring Batch lose anything this guide actually needs? | Two things rode on it: checkpointed restart, and `JobExecution`/`StepExecution` IDs for audit cross-referencing. Neither is required for this guide's own correctness — Phase 6's dedup design (audit check before send, keyed on `(configId, scopeId, windowStart, windowEnd)`) already makes reprocessing idempotent, so exact-position restart is a nice-to-have, not a requirement, and is explicitly left to whatever orchestrates re-invocation (§3.2) rather than built here. |
| — | Does this guide take a position on whether Phase 3/4 (or wherever the write/orchestration step ends up) should use Spring Batch? | No, deliberately. That choice belongs entirely to whichever guide covers that step, made on that step's own merits — this guide neither recommends nor rules out a batch-processing framework there, and doesn't need to know which way it goes. |
