# Commander — Audit & Deduplication Implementation Guide (Phase 6)

**Scope:** Every attempt to send an `OutboundReportMessage` gets a durable, traceable
`CAMT.ReportCommandAudit` row, and the same logical report/window is structurally
prevented from ever being recorded as successfully sent twice. Built directly against
the `phase5-message-delivery` branch as implemented — every hook point below is a real
method in real code, not a hypothetical seam.

**Two items reopen already-merged or already-reviewed artifacts — both approved and
applied:**
1. A filtered unique index on `CAMT.ReportCommandAudit` (Decision 3). Applied to
   `06-schema-audit-deadletter.sql`.
2. A small API change to `ResilientMqSender`/`SendOutcome` capturing the real MQ
   provider message ID (Decision 6), reopening Phase 5 Part B code already merged.
   Applied, including the three call sites (two tests, one production) that referenced
   the old `SendOutcome.success()` signature.

**Status:** Implemented. New: `ReportCommandAuditStatus`, `ReportCommandAuditEntry`
(builder), `ReportCommandAuditRepository`/`JdbcReportCommandAuditRepository`,
`AuditRetentionProperties`, `AuditRetentionJob`. Modified: `MqReportMessageWriter`
(dedup pre-check + audit rows on every branch), `DeadLetterRecoveryJob` (same, plus
`correlationId` extraction via payload deserialization), `QuartzSchedulerConfig`
(retention job wiring), `DemoCommanderApplication` (properties registration),
`application.properties`. Not independently verified by compiling — Maven Central isn't
reachable from the environment this was written in; treat as needing a real build/test
pass before merging, not as pre-verified.

---

## 1. Design Decisions Summary

| Decision | Choice |
|---|---|
| Where audit rows get written | Directly inside `MqReportMessageWriter.write()` and `DeadLetterRecoveryJob.recover()` — the only two places `ResilientMqSender.send()` is called, and the only two places an outcome is actually observed. No new decorator layer |
| Row lifecycle | One row per attempt, inserted **after** the outcome is known — not a pre-send `PENDING` row. Matches the schema's own shape (`sent_at NOT NULL`, no separate `created_at`) rather than forcing a lifecycle it wasn't built for |
| Status vocabulary | `SENT`, `FAILED`, `SKIPPED_DUPLICATE` for this phase's rows. `REJECTED_CONFIG_NOT_ELIGIBLE`/`REJECTED_INVALID_WINDOW` are pre-reserved names in the schema comment for Phase 7's validation flow — untouched here |
| Dedup mechanism | A pre-send `SELECT` for an existing `SENT` row (cheap, avoids most wasted sends) backed by a **filtered unique index** `(correlation_id) WHERE status = 'SENT'` as the real structural guarantee — DB-enforced, not just convention. **Applied** to `06-schema-audit-deadletter.sql` |
| `correlationId` for recovery-job rows | Deserialize `DeadLetterMessageRow.messagePayload()` (reusing the existing `ObjectMapper`) rather than adding a `correlation_id` column to `CAMT.DeadLetterMessage` |
| `job_execution_id`/`step_execution_id` | Injected `StepExecution` (`org.springframework.batch.core.step.StepExecution` — moved packages in Spring Batch 6.0, verified against the migration guide) into `MqReportMessageWriter`'s existing `@StepScope`. `null` for `DeadLetterRecoveryJob` rows — it's a plain Quartz job, no Spring Batch context, and both columns are already nullable |
| `mq_message_id` | The MQ provider's own assigned message ID, not a duplicate of `message_id` — required `ResilientMqSender`/`SendOutcome` to capture it, since `JmsTemplate.convertAndSend()` didn't expose it. **Applied**: switched to `jmsTemplate.send(queue, MessageCreator)` and reads `Message.getJMSMessageID()` post-send |
| `config_id`, `report_frequency` | `config_id` stores `String.valueOf(configId)` (schema is `NVARCHAR(50)`, domain value is `int`). `report_frequency` sourced from `JobParameters` (`@Value("#{jobParameters['reportFrequency']}")`, same pattern as `reportType` already in `MqReportMessageWriter`) — `null` for recovery-job rows, no `JobParameters` context there either |
| Nullable columns' "confirm still wanted" note | Confirmed still wanted — reserved for Phase 7's rejection-audit rows. Phase 6 itself never writes a row with `report_config_id`/`config_id`/`agreement_scope_id` null; every row here comes from a fully-assembled message |
| Recipient-resolution filter | Stays out of Phase 6's audit scope. `RecipientResolvingReportMessageProcessor` filtering an unresolvable recipient never reaches `ResilientMqSender`, so it's not a "send attempt" by this guide's own definition — stays a `WARN` log, as it is today. Not retrofitted |
| Retention policy | A separate Quartz job, same bounded-per-run pattern as `DeadLetterRecoveryJob` (`MAX_ROWS_PER_RUN`-style cap), deleting rows past 90 days keyed off `sent_at` — already indexed (`IX_ReportCommandAudit_SentAt`) |

---

## 2. Decision 1 — Hook Points: Inside the Two Existing Call Sites, No New Layer

**Problem:** Where does audit-row writing actually happen?

**Decision:** Directly inside `MqReportMessageWriter.write()`'s existing per-item loop,
right where `outcome` is already known (today: `sent++` or `deadLetter(item, json,
outcome)`) — and directly inside `DeadLetterRecoveryJob.recover()`, right where its own
`outcome` is known (today: `repository.delete(...)`, `markRetryScheduled(...)`, or
`markFailed(...)`). These are the *only* two places `ResilientMqSender.send()` is
called — confirmed by reading `ResilientMqSender` itself, which reports outcomes to its
caller but never records anything durable on its own.

**Why not a new decorator (e.g. wrapping `ResilientMqSender` itself):** `ResilientMqSender`
is deliberately the one shared send path both callers go through (its own class comment:
"the one send path both the primary writer and the dead-letter recovery job go
through"). Auditing needs different context at each call site — `MqReportMessageWriter`
has `StepExecution`/`JobParameters`; `DeadLetterRecoveryJob` has neither, but has
`row.retryCount()` and needs to extract `correlationId` from stored payload. Pushing
audit-writing into `ResilientMqSender` would mean threading caller-specific context
through a class whose entire purpose is being caller-agnostic — the two call sites are
where this belongs.

| | Pros | Cons |
|---|---|---|
| Write directly at the two call sites | No new abstraction; each site already has exactly the context (item, outcome, row) audit-writing needs; doesn't compromise `ResilientMqSender`'s caller-agnostic design | Two call sites to keep consistent instead of one — mitigated by both going through a shared `ReportCommandAuditRepository`/status-mapping helper, not duplicated logic |
| Decorator around `ResilientMqSender` (rejected) | One place instead of two | Forces caller-specific context (`StepExecution`, `JobParameters`, dead-letter row fields) into a class specifically designed not to need any of that |

---

## 3. Decision 2 — Row Lifecycle: Post-Hoc Insert, Not Pre-Send `PENDING`

**Problem:** Should an audit row be inserted *before* the send attempt (as a `PENDING`
row, later updated) or *after* the outcome is known?

**Decision:** After. One `INSERT` per attempt, once `outcome` is known — never an
`UPDATE`. This isn't the only reasonable design, but it's the one that fits the schema as
it already exists: `sent_at DATETIME2 NOT NULL` with no separate `created_at`/
`attempted_at` column means the table has no natural place to record "an attempt is in
flight but not yet resolved." A pre-send `PENDING` row would need a schema change to give
it somewhere to record its start time distinctly from its resolution time — a third edit
to a migration already touched twice. Post-hoc insert needs none.

**What `sent_at` means for a `FAILED` row:** the timestamp the attempt was made and
resolved — not literally "when it was sent," despite the column name. Worth stating
explicitly rather than leaving it to be inferred, since the name is genuinely misleading
for anything but a `SENT` row.

| | Pros | Cons |
|---|---|---|
| Post-hoc insert only | No schema change for this decision specifically; matches what the table was actually built for | No durable record of an attempt that's *currently* in flight — acceptable here, since nothing downstream needs to observe "in progress" state; the chunk/poll loop is synchronous either way |
| Pre-send `PENDING` row (rejected) | A durable in-flight marker, useful if something needed to observe stuck-in-progress attempts | Needs a schema change to add a start-time column distinct from `sent_at`; nothing in this phase's requirements needs the in-flight visibility badly enough to justify a third schema touch |

---

## 4. Decision 3 — Dedup: Pre-Check Plus a Filtered Unique Index

**Problem:** The exit criterion demands duplicate sends be "structurally prevented, not
just discouraged by convention." No constraint exists anywhere on `ReportCommandAudit`
today. A plain `UNIQUE` constraint on `correlation_id` doesn't work — the whole point of
this table is multiple rows per `correlation_id` (one per attempt, including retries and
recovery resends); a flat unique constraint would reject the second attempt's row
outright, breaking the audit trail itself.

**Decision, two layers, doing different jobs:**

1. **Pre-check (optimization, not the guarantee):** before calling `sender.send()` in
   both `MqReportMessageWriter` and `DeadLetterRecoveryJob`, query for an existing row
   with this `correlationId` and `status = 'SENT'`. If found, skip the send entirely,
   write a `SKIPPED_DUPLICATE` row (still traceable — an attempt that was correctly
   skipped is still worth a record, not silence), and stop. This is what handles the
   realistic case — a restart or an overlapping recovery poll re-attempting something
   already delivered — cheaply, without wasting an MQ round-trip.
2. **Filtered unique index (the actual structural guarantee):**
   `CREATE UNIQUE INDEX UX_ReportCommandAudit_CorrelationId_Sent ON
   CAMT.ReportCommandAudit(correlation_id) WHERE status = 'SENT'` — a standard SQL
   Server feature (filtered indexes, available since SQL Server 2008), enforcing
   uniqueness only among rows matching the predicate. Many `FAILED`/`SKIPPED_DUPLICATE`
   rows can share a `correlation_id`; at most one `SENT` row ever can. If two attempts
   somehow both reach the point of inserting a `SENT` row (the pre-check's race window —
   see below), the second `INSERT` fails on the constraint, and that failure is caught
   and handled as "someone else already recorded success," not a hard error.

**Being honest about what this does and doesn't guarantee:** the filtered index
guarantees the *audit table* can never show two `SENT` rows for the same
`correlationId` — that part is airtight. It does **not**, by itself, make it physically
impossible for two concurrent processes to both call `sender.send()` before either
records its outcome, which could mean Executor receives the same message twice even
though the audit table only ever shows one `SENT` row. That narrower gap is closed by
orchestration-layer guards that already exist, not by this table: Spring Batch's
`JobInstance` uniqueness (Phase 4 Decision 6) prevents the same report/window from
having two concurrent `reportPipelineJob` executions in the first place, and
`DeadLetterRecoveryJob`'s `@DisallowConcurrentExecution` prevents overlapping recovery
polls. Given those, a true concurrent race for the *same* `correlationId` isn't a
realistic scenario this system can produce — the filtered index is the backstop for that
already-narrow gap, not a replacement for the job-level guards doing the actual heavy
lifting.

| | Pros | Cons |
|---|---|---|
| Pre-check + filtered unique index | Cheap common-case avoidance of wasted sends; DB-enforced guarantee against duplicate `SENT` recording, not just app-level discipline; doesn't require redesigning the row-lifecycle decision (§3). **Applied** to `06-schema-audit-deadletter.sql` | Required a schema change (index addition) — smaller than the Phase 5 nullability edit, but still touching `06-schema-audit-deadletter.sql` a third time; doesn't close the physical-double-send gap on its own (relies on orchestration guards, stated plainly above rather than overclaimed) |
| Pre-send `PENDING` row with a non-filtered unique constraint on `(correlation_id) WHERE status IN ('PENDING','SENT')` (considered, rejected) | Would make the `INSERT` itself the atomic gate, closing the physical-double-send gap too, not just the recording gap | Requires Decision 2's pre-send row lifecycle, which needs its own schema change (§3) — stacking two schema changes to close a race window that orchestration-layer guards already make unrealistic is disproportionate to the actual risk |
| App-level check only, no constraint (rejected) | No schema change at all | Exactly what the exit criterion rules out — "discouraged by convention," not structurally prevented |

---

## 5. Decision 4 — `correlationId` for Recovery-Job Rows: Deserialize the Stored Payload

**Problem:** `DeadLetterMessageRow` carries `messageId` but not `correlationId`.
`DeadLetterRecoveryJob.recover()` needs `correlationId` to write (and dedup-check) its
audit row.

**Decision:** Deserialize `row.messagePayload()` back into `OutboundReportMessage`
(reusing the `ObjectMapper` `MqReportMessageWriter` already uses to serialize it) and
read `.correlationId()` off the result — rather than adding a `correlation_id` column to
`CAMT.DeadLetterMessage`. The data is already there, byte-for-byte, in
`message_payload`; a new column would just duplicate it.

**Note this must happen before `repository.delete(row.id())` on a successful recovery** —
`DeadLetterRecoveryJob.recover()` deletes the row immediately on success today. The audit
write (which needs `correlationId` and `messageId`, both derivable from `row` before
deletion) has to happen first, or read everything it needs from `row` before the delete
call, not after.

| | Pros | Cons |
|---|---|---|
| Deserialize stored payload | No schema change; the value is already durable and correct, since it's the exact payload that would be (re)sent | One extra deserialization per recovery attempt — negligible at `MAX_ROWS_PER_RUN = 100` per poll |
| Add `correlation_id` column to `CAMT.DeadLetterMessage` (rejected) | Avoids a deserialization step | A fourth touch to `06-schema-audit-deadletter.sql` for data that's already present and reliably extractable — not worth it |

---

## 6. Decision 5 — `job_execution_id`/`step_execution_id`: `StepExecution` Injection, Null for Recovery

**Problem:** `MqReportMessageWriter` needs its own `JobExecution`/`StepExecution` IDs;
`DeadLetterRecoveryJob` has no Spring Batch context to source them from at all.

**Decision:** `MqReportMessageWriter` (already `@StepScope`) gains a constructor
parameter `@Value("#{stepExecution}") StepExecution stepExecution`, giving
`stepExecution.getJobExecutionId()` and `stepExecution.getId()` directly. Import from
`org.springframework.batch.core.step.StepExecution` — **not**
`org.springframework.batch.core.StepExecution`, which was the pre-6.0 location.
Verified directly against the Spring Batch 6.0 migration guide rather than assumed,
same discipline as every other Batch-6-specific detail flagged in the Phase 4 guide.

For `DeadLetterRecoveryJob` rows: both columns stay `null`. It's a plain `org.quartz.Job`,
never inside a Spring Batch `StepExecution` context — there's nothing to inject. Both
columns are already nullable, and `correlation_id`/`message_id` plus timestamps are
sufficient to trace a recovery-originated row without them.

| | Pros | Cons |
|---|---|---|
| `StepExecution` injection | Standard Spring Batch pattern for `@StepScope` beans, no new plumbing; correct package avoids a compile error that would only surface once someone actually implements this | None material |
| `null` for recovery rows | Honest about what context genuinely exists — no fabricated IDs | A recovery-driven audit row is traceable by `correlation_id`/`message_id` but not by "which batch job run produced it," because none did |

---

## 7. Decision 6 — `mq_message_id`: The Real Broker ID, Not a Duplicate Column

**Problem:** `mq_message_id` and `message_id` are separate `NVARCHAR(100)` columns.
Populating both with the same value (Commander's own generated `messageId`) would make
one of them redundant. `mq_message_id` reads as the intent being the MQ provider's own
assigned message ID — a genuinely different value, and one `ResilientMqSender` doesn't
currently capture, since `JmsTemplate.convertAndSend(queue, payload)` returns nothing.

**Decision, applied:** Captured the real JMS-provider-assigned message ID —
`ResilientMqSender`/`SendOutcome`, both Phase 5 Part B code already merged, reopened with
explicit go-ahead:

- `SendOutcome.success()` became `SendOutcome.success(String jmsMessageId)` — the
  `Type.SUCCESS` case gained a field, the three failure factory methods unchanged.
- `ResilientMqSender.send()` switched from `jmsTemplate.convertAndSend(queue, payload)`
  to `jmsTemplate.send(queue, MessageCreator)`, capturing the `Message` the
  `MessageCreator` builds via an `AtomicReference` and reading `.getJMSMessageID()` off
  it once `send()` returns — JMS providers assign this by default
  (`MessageProducer.getDisableMessageID()` is `false` unless explicitly turned off,
  which nothing here does). A defensive null-safe read, not a hard failure, if a
  provider config ever disables it.
- Updated the three call sites that referenced the old no-arg
  `SendOutcome.success()`: `MqReportMessageWriterTest`,
  `DeadLetterRecoveryJobTest` (both now pass a placeholder ID), and
  `ResilientMqSenderTest`, which needed a deeper update — its mocks targeted
  `convertAndSend`, which `ResilientMqSender` no longer calls. Also noted directly in
  that test's Javadoc: a mocked `JmsTemplate` never actually invokes the `MessageCreator`
  callback, so `jmsMessageId()` reads as `null` in every unit test there — verifying the
  real capture needs the `ibmmq` integration test, not this one.

**This reopened a class Phase 5 Part B already shipped and already had its own review
rounds — flagged rather than folded in quietly, same discipline as the schema change
above.** The change itself stayed small and contained: one new field, one method body,
three call-site updates.

| | Pros | Cons |
|---|---|---|
| Capture the real JMS message ID | `mq_message_id` means what its name says, not a duplicate of `message_id`; genuinely useful for cross-referencing against MQ-side logs/tooling, which is presumably why the column exists at all | Reopened merged Phase 5 code, including a test whose mocking strategy needed rethinking, not just a signature update; `jmsTemplate.send(...)` with a `MessageCreator` is marginally more verbose than `convertAndSend(...)` |
| Store `messageId` in both columns (rejected) | No code change needed | Makes one of the two columns pointless — if `mq_message_id` was meant to just mirror `message_id`, there'd be no reason for the schema to have both |
| Leave `mq_message_id` always `null` (rejected) | No code change needed | Leaves a documented, presumably intentional column permanently unpopulated for no stated reason |

---

## 8. Decision 7 — `config_id` and `report_frequency`: Straightforward Sourcing

**`config_id`:** `String.valueOf(payload.configId())` — the schema column is
`NVARCHAR(50)`, the domain value is an `int` (always exactly 8 digits, per
`ReportConfigRow`'s own doc comment). No leading-zero concern, since an 8-digit int
never has one. No schema change; just a documented representation choice.

**`report_frequency`:** sourced via `@Value("#{jobParameters['reportFrequency']}")` on
`MqReportMessageWriter`, the same pattern already used there for `reportType`. `null`
for `DeadLetterRecoveryJob` rows — no `JobParameters` context, same reasoning as §6.

| | Pros | Cons |
|---|---|---|
| String conversion + `JobParameters` sourcing | Both trivial, no ambiguity, no schema change | None material |

---

## 9. Decision 8 — The Schema's Own Open Note: Confirmed Still Wanted

**Problem:** The migration's header comment has carried an unresolved note since before
Phase 5: `report_config_id`, `config_id`, `agreement_scope_id`, `report_frequency`, and
`mq_queue_name` are nullable "to support rejection-audit rows... confirm this is still
wanted." Phase 6 is the first phase to actually write to this table, so this needs
resolving now, not carried forward a third time.

**Decision:** Confirmed still wanted — but Phase 6 itself never exercises the
nullability. Every row this phase writes comes from a `PipelineReportMessage` that
already has a resolved `reportConfigId` and a fully-assembled `OutboundReportMessage`;
there's no code path here that writes a row with these fields null. The nullability
stays reserved for Phase 7's on-demand API, whose validation-time rejections
(`REJECTED_CONFIG_NOT_ELIGIBLE`, `REJECTED_INVALID_WINDOW` — names already reserved in
the same comment) can occur before a `ReportConfig`/`AgreementScope` is even resolved.
Nothing to change in the schema for this — just closing out an open question the
migration has been carrying.

---

## 10. Decision 9 — Recipient-Resolution Filter: Deliberately Out of Scope

**Problem:** `RecipientResolvingReportMessageProcessor` already filters unresolvable
recipients (returns `null`, logs a `WARN`) — Phase 5 Part A, already shipped. Should
Phase 6 give filtered messages an audit row too?

**Decision:** No. A filtered message never reaches `ResilientMqSender` — by this guide's
own definition (§2, "the only two places an outcome is actually observed"), it was never
a send attempt. The exit criterion is "every send attempt is traceable end-to-end," not
"every message Commander ever considered." Extending audit coverage to pre-send
filtering would be a reasonable *future* choice, but it's a different, broader scope than
what this phase's exit criterion asks for, and retrofitting it now would mean writing
audit rows through a third code path (the processor) with none of the outcome/
`StepExecution` context the other two naturally have. Left as-is; the `WARN` log stands.

---

## 11. Decision 10 — Retention: A Separate Job, Same Shape as `DeadLetterRecoveryJob`

**Problem:** No retention mechanism exists anywhere. The exit criterion requires 90-day
retention "enforced independently of the main pipeline."

**Decision:** A new Quartz job, `AuditRetentionJob`, deliberately mirroring
`DeadLetterRecoveryJob`'s shape: `@DisallowConcurrentExecution`, a bounded
`MAX_ROWS_PER_RUN`-style cap per firing (a single unbounded `DELETE` against a
potentially large table is exactly the kind of statement worth avoiding), deleting rows
where `sent_at < now - 90 days` — already indexed via `IX_ReportCommandAudit_SentAt`, so
the query driving deletion doesn't need a new index. Daily cadence, off-peak; exact
schedule is a config value, not a design decision.

| | Pros | Cons |
|---|---|---|
| Separate job, same shape as `DeadLetterRecoveryJob` | Consistent with an established, already-reviewed pattern in this codebase rather than a new one; bounded deletes avoid a long-held table lock; reuses the existing `sent_at` index | One more Quartz job to configure — small, well-understood addition |
| Bundled into `AuditRetentionJob`... into the primary pipeline (rejected) | One fewer job | Explicitly contradicts "enforced independently of the main pipeline" — retention timing has nothing to do with report firing schedules |

---

## 12. Testing Strategy

- **Status/outcome mapping:** unit test the `SendOutcome.Type` → audit `status`/
  `error_message` mapping for all four types (`SUCCESS`, `BREAKER_OPEN`,
  `TRANSIENT_EXHAUSTED`, `PERMANENT`) at both call sites.
- **Dedup pre-check:** unit test that an existing `SENT` row for a `correlationId`
  produces a `SKIPPED_DUPLICATE` row and no send attempt, mocking the repository.
- **`correlationId` extraction for recovery rows:** unit test deserializing a stored
  `message_payload` recovers the exact `correlationId`/`messageId` that were serialized
  into it.
- **Integration, against the real SQL Server** (extending `integrationTest`, not
  Testcontainers — matching Phase 4/5's established, environment-appropriate pattern):
  - Filtered unique index: two attempts both trying to insert a `SENT` row for the same
    `correlationId` — the second fails on the constraint, and that failure is handled as
    "already sent," not propagated as an error.
  - End-to-end trace: a message that fails once (dead-lettered), gets recovered, and its
    two audit rows (`FAILED` then `SENT`) both carry the same `correlationId`.
  - Retention: rows older than 90 days are deleted in bounded batches; rows within the
    window are untouched.

---

## 13. Applied Schema/API Changes

1. **Filtered unique index** on `CAMT.ReportCommandAudit` (§4) —
   `UX_ReportCommandAudit_CorrelationId_Sent`, applied to
   `06-schema-audit-deadletter.sql`, header comment updated to close out the note it had
   been carrying since Phase 5.
2. **`SendOutcome`/`ResilientMqSender` API change** to capture the real JMS message ID
   (§7) — applied, including the three call sites that needed updating for the changed
   `SendOutcome.success()` signature.

Everything else in this guide is new code with no existing decision to reopen.
