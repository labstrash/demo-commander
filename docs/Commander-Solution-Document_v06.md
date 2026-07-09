# Commander — Solution Document (v6)

**Report Command Generator for CAMT Reporting**

---

## 0. How This Document Is Structured

Starting with this revision, this document is deliberately kept **high-level**: purpose,
system context, domain model, guiding principles, and the phase-by-phase roadmap (goal,
scope, exit criterion). It intentionally does **not** contain SQL, pseudo-code, class
names, or mechanism-level decisions (e.g. stored procedure vs. staged reads, TVP binding,
Quartz trigger structure).

That detail now lives in **per-phase implementation guides**, written as each phase is
analysed, designed, and implemented in depth. Those guides are the source of truth for
*how* a phase works; this document is the source of truth for *what* each phase is for
and *how the phases fit together*. Where a guide's decisions differ in detail from an
earlier version of this document, the guide wins — this document should be updated to
match, not the other way around.

| Phase | Implementation guide |
|---|---|
| 1 — Configuration Read Layer | *Commander — Report Configuration Fetch & Assembly Implementation Guide* (currently v07). Also covers the read-mechanics half of Phase 7 (On-Demand), since both paths share the same staged-read design. |
| 0, 2–6, 8–9 | Not yet written as standalone guides |
| 7 — On-Demand API | Read-mechanics covered by the Fetch & Assembly guide above; window-input-mode decision (§7) and the rest of the on-demand surface not yet guide-documented |

**Code lives at:** `github.com/labstrash/commander` (`main` branch is current as of this
revision). The repo is the source of truth for exact implementation; this document and
the implementation guides are the design references, updated by hand.

---

## 1. Purpose & Scope

This document describes Commander's architecture and phased build plan at a conceptual
level. Commander's job is narrow and specific: read active reporting configurations,
work out *what* report is due and *for what time window*, and publish a command message
to IBM MQ telling a downstream system to produce it.

Commander does not generate reports itself. That responsibility belongs to a separate
consumer application — referred to here as **Executor** — which listens on the report
queues, builds the actual CAMT XML, and delivers it to the recipient. Executor is
mentioned only where it clarifies a contract (the shape of `ReportMessage`, the queues it
listens on); its internal design is out of scope for this document.

---

## 2. System Context

```
                  ┌────────────────────┐
                  │   SQL Server        │
                  │   (REPORTDB / CAMT) │
                  └─────────▲──────────┘
                            │ read configs
                            │
   ┌──────────┐      ┌──────┴───────┐        ┌────────────────┐
   │  Quartz  │─────▶│  COMMANDER   │───────▶│   IBM MQ        │
   │  Triggers│ fire │ (this app)   │ command│  REPORT.* queues │
   │ (x6, one │      └──────┬───────┘        └────────▲────────┘
   │  per     │             │ audit / dead-letter        │ consume
   │  report  │      ┌──────▼───────┐               ┌────┴─────┐
   │  type)   │      │ SQL Server    │               │ EXECUTOR │
   └──────────┘      │ Audit/DLQ tbl │               │ (separate│
                      └──────────────┘               │  service)│
                                                       └──────────┘
```

Commander sits between the **agreement/configuration data** (owned and maintained by a
separate **Agreement Management API** application) and **Executor** (which does the
actual report building). Commander's only outputs are: messages on MQ, audit rows, and
dead-letter rows.

### Upstream: Agreement Management API

Agreement onboarding and lifecycle management is owned entirely by a separate
application, not by Commander:

- All writes to `Agreement`, `AgreementVersion`, `AgreementScope`,
  `PaymentTypeAssignment`, `AccountAssignment`, and `AliasAssignment` happen through the
  Agreement Management API.
- A **nightly job (22:00)** reconciles the current set of active agreements into
  `ReportAgreementScope`: newly active scopes get a row added, cancelled scopes have
  their row removed.
- **Implication for Commander:** `ReportAgreementScope` is the trusted, pre-filtered
  signal of "this agreement scope is currently reportable." Whether and how Commander
  re-checks status at read time is a Phase 1 read-mechanics decision — see the
  implementation guide.
- **Known tradeoff:** because the sync runs once nightly, there is an up-to-24-hour lag
  between an agreement being cancelled in the Agreement Management system and it
  disappearing from Commander's eligible set. This is treated as an accepted, documented
  behaviour, not a defect.

---

## 3. Domain Model Recap

```
Agreement
 └─ AgreementVersion (one ACTIVE at a time, one PENDING at a time)
     └─ AgreementScope (per Recipient + ReportType, status PENDING/ACTIVE/CANCELLED)
         └─ PaymentTypeAssignment (per PaymentType)
             ├─ AccountAssignment (account/BBAN/currency/clearing number)
             └─ AliasAssignment (optional)

ReportConfig (independent top-level entity, references a Recipient directly)
 └─ ReportAgreementScope (link table, maintained by the nightly sync — see §2)
     └─ AgreementScope
```

- **`ReportConfig` is the unit of scheduling.** It carries `ReportType`, `ReportVersion`,
  `ReportFrequency`, `IsBundled`, `IsActive`, `AccountFormat`, `IsPaginated`,
  `IsEmptyReportAllowed` — the last three are read and passed through for Executor's use,
  not acted on by Commander itself.
- **Six report types are in scope:** `CAMT052B`, `CAMT052BT`, `CAMT053S`, `CAMT053E`,
  `CAMT054C`, `CAMT054D`. Each is independently schedulable (§5, Phase 3).
- **`ReportFrequency` is `NOT NULL`.** Every report type has exactly one assigned
  frequency, including `CAMT054D` (`DAILY`). Supported frequencies fall into three
  distinct shapes, each computed differently (§5, Phase 2):
  - **Interval-based:** `EVERY_30_MIN`, `EVERY_1_HOUR`, `EVERY_2_HOURS`, `EVERY_4_HOURS`
    — window is a fixed real-time duration ending at fire time.
  - **Calendar-day-based:** `DAILY` — window is midnight-to-midnight wall-clock time
    (genuinely 23h/25h across a DST transition, not a fixed 24h duration).
  - **Window-time-of-day-based:** `ONE_TIME_PER_DAY`, `FOUR_TIMES_PER_DAY`,
    `EIGHT_TIMES_PER_DAY` — window boundaries come from configured clock times, not a
    calendar or interval rule.
  - **`SNAPSHOT`** — a point-in-time window (start = end = fire time).
- **`ConfigId` is a derived, application-computed identifier**, written back to the row
  by the Agreement Management side. Commander only ever *reads* this value.
- **A recipient is identified by `(RecipientType, RecipientValue)`** — `RecipientType`
  is `ORIGINATOR` or `BIC`. `Recipient.Name` is informational only (passed through for
  display in the outgoing message), never used as a match key.
- **`(ReportType, ReportVersion, RecipientType, RecipientValue)` uniquely identifies a
  `ReportConfig`** — relied on directly by the on-demand lookup in Phase 7.
- **Account vs. alias is mutually exclusive** per `PaymentTypeAssignment` — never both on
  the same assignment.

---

## 4. Guiding Principles

1. **Commander is read-only against configuration tables.** It never writes to
   `ReportConfig`, `Agreement*`, or any table in the agreement hierarchy. Its only writes
   are to `ReportCommandAudit` and `DeadLetterMessage`.
2. **All timestamps cross application boundaries as UTC.** Business-time reasoning
   (which window, which trigger) happens in `Europe/Stockholm`; everything that leaves
   the JVM (MQ payload, audit rows) is UTC.
3. **A command is idempotent-by-intent, not idempotent-by-accident.** Deterministic
   correlation plus an audit-table dedup check before send — both designed in, not
   retrofitted.
4. **Failure is expected, not exceptional.** Every external call (DB read, MQ send)
   assumes transient failure is normal and has a defined recovery path.
5. **Build in layers, prove each layer before stacking the next.**
6. **Window derivation is one piece of logic, used by every trigger path.** Whether a
   report is triggered by Quartz or by an on-demand API call, the *same* frequency-aware
   window calculation should apply wherever possible, so scheduled and on-demand reports
   for the same type are never shaped inconsistently. (See Phase 7 — this is currently
   only partially settled.)

---

## 5. Phased Build Plan

### Phase 0 — Foundations

**Goal:** a running Spring Boot skeleton that can talk to SQL Server and resolve
configuration, with nothing "smart" happening yet.

- Project skeleton with database connectivity and externalized configuration.
- Schema readiness is surfaced without blocking application startup, given that
  container-orchestration restarts don't fix a missing table.
- A feature-flag seam in place from the start, so later phases (bundling changes,
  on-demand rollout, new report types) can stage behind it.
- A local development stack (SQL Server + IBM MQ) that mirrors production shape.

**Exit criterion:** application starts, connects to the database, and connectivity is
provable via a simple check.

---

### Phase 1 — Configuration Read Layer

**Goal:** given "report type X, frequency Y, right now," return the full, correctly-scoped
set of configurations that need a report.

- Read-only access to the agreement hierarchy, walked from `ReportConfig` down to
  individual accounts/aliases.
- A defined trust boundary for what counts as "active" and "eligible," consistent with
  the nightly sync described in §2.
- Correctness under scale — the read approach must hold up as the configuration table
  and its hierarchy grow, not just at seed-data volume.

**Exit criterion:** given a report type and frequency, a correctly-scoped, correctly-
nested set of configurations is returned, testable independent of scheduling or
messaging.

**Detailed design:** see the Fetch & Assembly implementation guide (§0).

---

### Phase 2 — Time Window & Bundling

**Goal:** work out the exact reporting window for a given firing, and how the matched
accounts/aliases fan out into one or more report messages.

- Pure, database-independent logic — deterministic given a frequency and a fire time.
- Window derivation across all three frequency shapes described in §3 (interval-based,
  calendar-day-based, window-time-of-day-based) plus `SNAPSHOT`, correct across
  daylight-saving transitions.
- Bundling decides whether a scope produces one merged message or one message per
  account/alias.

**Exit criterion:** given a fixed configuration and a fixed fire time, the module
deterministically produces the correct messages and windows, fully testable without a
database, MQ broker, or scheduler.

---

### Phase 3 — Scheduling Integration (Quartz)

**Goal:** wire Phases 1 and 2 to an actual clock, reliably and cluster-safely.

- One schedule per report type (§3's six report types — and, where a report type has
  more than one frequency, per report-type/frequency pair), so an issue with one report
  type's timing can't affect another's.
- Trigger schedule externalized to configuration rather than hardcoded.
- Scheduling must be safe to run across multiple Commander instances without double-firing.

**Exit criterion:** jobs fire independently on their configured schedules, each pulling
only its own report type/frequency's configurations and producing the right messages —
observable in logs, no MQ dependency yet.

---

### Phase 4 — Batch Pipeline (Spring Batch)

**Goal:** replace "do everything inline in the scheduled job" with a chunked,
transactional, restartable pipeline.

- One pipeline per report type, reading configurations, computing windows/bundling, and
  handing off to the writer stage.
- A bounded transaction "blast radius" per chunk, rather than one giant transaction per
  firing.
- MQ delivery and database writes treated as separate resource managers, not coupled
  into a single distributed transaction.

**Exit criterion:** the same end-to-end flow as Phase 3, but chunked, restartable, and
instrumented with identifiers that Phase 6's audit rows will reference.

---

### Phase 5 — MQ Integration & Resilience

**Goal:** actually deliver messages to Executor, with tiered failure handling.

- Queue names resolved via configuration, not hardcoded.
- An in-process retry tier for transient failures, backed by a dead-letter tier for
  failures that exhaust retry.
- A clear split between permanent errors (no point retrying) and transient errors (worth
  retrying), decided in one place.
- Fail-fast behaviour under a full MQ outage, rather than burning through retries
  per-message.

**Exit criterion:** Executor (or a stub consumer) receives correctly formed messages on
the right queues under normal conditions; a simulated MQ outage correctly populates the
dead-letter store and recovers once MQ is back.

---

### Phase 6 — Audit & Deduplication

**Goal:** every send attempt is traceable, and the same report window is never sent
twice.

- One audit row per message attempt, capturing enough identifiers to trace a message
  end-to-end.
- A deduplication check before send, so the same report/window combination is never sent
  twice even under retries or restarts.
- A deterministic correlation identifier, so audit rows and Executor-side logs can be
  cross-referenced without extra lookups.
- A 90-day retention policy for audit data, enforced independently of the main
  pipeline.

**Exit criterion:** every send attempt is traceable end-to-end, and duplicate sends are
structurally prevented, not just discouraged by convention.

---

### Phase 7 — On-Demand API

**Goal:** allow a report to be triggered outside the schedule, reusing the components
built in Phases 1–6 rather than duplicating logic.

- A lookup shape keyed by recipient identity, rather than by "which frequency is due
  right now" — a genuinely different query shape from the scheduled path, not just a
  parameter swap.
- The same downstream pipeline (window/bundling → writer → audit) as the scheduled path,
  so scheduled and on-demand reports are never shaped inconsistently once past the
  lookup step.
- **Open — window input mode:** whether the caller supplies a reference date (Commander
  derives the window the same way the scheduler would) or an explicit window override
  (bypassing derivation), or both. See §7 Open Questions.

**Exit criterion:** a manual on-demand trigger produces an equivalent message/audit
footprint to a scheduled one, distinguishable only by trigger type.

**Detailed design:** read-mechanics (lookup, eligibility checks, rejection handling, fan-
out) are covered by the Fetch & Assembly implementation guide (§0). The window-input-mode
decision and the rest of the on-demand API surface (auth, request contract beyond the
read path) are not yet guide-documented.

---

### Phase 8 — Observability & Operations

**Goal:** make Commander's behaviour visible without reading code or querying tables by
hand.

- Metrics segmented per report type, given the six-independent-jobs structure.
- Structured logging carrying correlation and execution identifiers, so a single
  report's journey can be traced end to end.
- Health checks for each external dependency (database, MQ, scheduler cluster) exposed
  separately.
- An alerting path for conditions that need a human, distinct from transient issues that
  self-resolve.

**Exit criterion:** during a simulated MQ or DB outage, dashboards/alerts surface the
right signal within the alerting SLA, and a sample message can be traced end-to-end via
correlation ID alone.

---

### Phase 9 — Hardening & Load Validation

**Goal:** confidence in correct behaviour at realistic configuration volumes and under
failure injection, before go-live.

- Volume testing against a production-representative seeded dataset, validating earlier
  phases' sizing assumptions (chunking, connection pooling, etc.).
- Failure injection across every layer — MQ outage, DB connection drop, scheduler
  misfire, duplicate trigger fire — each mapped back to the phase responsible for
  handling it.
- Clustering validation: multiple Commander instances running against the same
  scheduling store, confirming no double-sends.

**Exit criterion:** a sign-off checklist covering each failure mode above, with observed
behaviour matching designed behaviour.

---

## 6. Executor — Brief Context

- **Contract:** Executor's only input is the report command message on `REPORT.<TYPE>`
  queues. Commander must never assume anything about *how* Executor builds the report —
  only that the message contains everything Executor needs (window, recipient, scope
  details, bundling flag) to do so without querying the configuration database itself.
- **Retry queue ownership:** `REPORT.<TYPE>.RETRY` queues belong conceptually to
  Executor's side of the handshake (Executor failing to *process* a received message)
  and are distinct from Commander's own send-side retry/dead-letter mechanism (Phase 5),
  which protects against Commander *failing to deliver* the message in the first place.

---

## 7. Open Questions / Decisions Needed

1. **On-demand window input mode** — reference-date-only, explicit-override-only, or
   both with a default; and if both, whether the override mode needs additional
   authorization. *(Partially addressed for the MVP scope in the Fetch & Assembly
   implementation guide — see §0 — but the general reference-date mode and the
   both-modes question remain open beyond MVP.)*
2. **Force-reissue semantics** — should on-demand ever be allowed to target an inactive
   `ReportConfig`, and if so, under what authorization?
3. **Dead-letter alerting thresholds** — what backlog size/age triggers a page versus a
   dashboard-only signal?
4. **On-demand API authentication** — depends on the organization's existing
   gateway/auth standard, not decided here.
5. **Multi-instance Commander sizing** — expected number of clustered instances in
   production, affecting scheduler lock contention and connection pool sizing in Phase 9.

---

## 8. Cross-Cutting Concerns

| Concern | Approach |
|---|---|
| Feature flags | In place from Phase 0 onward; stages rollout of bundling changes, on-demand API, new report types |
| Configuration | All schedule/queue/retry values externalized; no magic numbers in code |
| Security | DB and MQ credentials via standard secrets management (org-specific, not designed here); on-demand API auth is an open item (§7.4) |
| Testing strategy | Phases 1–2 are pure-logic/unit-testable without infrastructure. Phase 3's orchestration logic is unit-testable too — only the live scheduler/job-store wiring needs a real or containerized SQL Server. Phase 4+ needs integration tests against real or containerized SQL Server + scheduler + MQ |

---

## 9. Phase Summary Table

| Phase | Name | Depends On | Primary Output |
|---|---|---|---|
| 0 | Foundations | — | Running skeleton, DB connectivity, feature flag seam |
| 1 | Configuration Read Layer | 0 | Correctly nested config graph per (type, frequency) |
| 2 | Time Window & Bundling | 1 | Pure-logic window calc + message fan-out |
| 3 | Scheduling (Quartz) | 1, 2 | Config-driven per-(report type, frequency) jobs |
| 4 | Batch Pipeline (Spring Batch) | 3 | Chunked, restartable, transactional processing per report type |
| 5 | MQ Integration & Resilience | 4 | Real delivery to Executor, retry + dead-letter |
| 6 | Audit & Deduplication | 4, 5 | Full traceability, no duplicate sends |
| 7 | On-Demand API | 1–6 | Manual trigger path reusing the scheduled pipeline; window mode TBD |
| 8 | Observability | 4–6 | Metrics (per report type), structured logs, health checks, alerts |
| 9 | Hardening & Load Validation | all | Go-live confidence under volume + failure injection |

---

## 10. Revision History

This document's own history (v1 → v5) is preserved in `Commander-Solution-Document_v05.md`.
Starting at v6, revisions to this document should be limited to changes at the
purpose/context/domain-model/phase-roadmap level. Changes at the mechanism/decision
level belong in the relevant phase's implementation guide instead, with this document
updated only to keep phase descriptions and the guide-pointer table (§0) accurate.
