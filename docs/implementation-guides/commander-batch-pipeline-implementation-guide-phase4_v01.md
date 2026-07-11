# Commander — Batch Pipeline Implementation Guide (Phase 4)

**Scope:** This guide covers Phase 4 only — replacing the inline read/assemble/fan-out
loop currently in `ScheduledConfigReader` with a chunked, transactional, restartable
Spring Batch pipeline, launched from `ReportSchedulingJob`. It does not cover MQ delivery
mechanics or retry/dead-letter tiering (Phase 5), audit-row writing or the dedup check
itself (Phase 6), or window/frequency computation (covered by the Scheduling Module
guide). The writer stage this guide introduces is an explicit stub with a stable seam —
Phase 5/6 fill in real behaviour behind it without changing the pipeline's shape.

**Relationship to the Phase 1 guide:** Phase 1's Round 8 explicitly removed Spring Batch
from the *read* path and declined to take a position on whether Phase 3/4 should adopt it
for the write/orchestration step — that choice belongs here, on this guide's own merits.
Round 8 also established that Phase 6's audit dedup (keyed on
`(configId, scopeId, windowStart, windowEnd)`) is what makes reprocessing safe, and that
exact-position restart is a nice-to-have on top of that, not a substitute for it. This
guide adopts Spring Batch and inherits that stance: restart/checkpoint is implemented
because it's nearly free once Spring Batch is in the picture, not because correctness
depends on it.

**Status:** Design finalized, pending implementation.

---

## 1. Design Decisions Summary

| Decision | Choice |
|---|---|
| Framework adoption | Spring Batch, chunk-oriented step, one generic `Job` bean parameterized at launch |
| Reader shape | Custom `ItemStreamReader<OutboundReportMessage>` — internally pages `ReportConfigRow`, batches through `assembleTrees()`/fan-out per page exactly as `ScheduledConfigReader` does today, buffers resulting messages |
| Chunk/commit boundary | Message count (`commit-interval` = messages per chunk), not tree count or page count |
| Processor stage | Real `ItemProcessor<OutboundReportMessage, OutboundReportMessage>`, no-op today — seam for Phase 5 recipient resolution |
| Writer stage | `ItemWriter<OutboundReportMessage>`, logging-only implementation — seam for Phase 5 (MQ) / Phase 6 (audit) |
| Quartz → Batch launch | Synchronous `JobOperator.start()` call from `ReportSchedulingJob`, no async executor — **not** `JobLauncher.run()`, which is deprecated-for-removal in Spring Batch 6.0 (see Round 3) |
| `JobParameters` | `reportType`, `reportFrequency`, `windowStartUtc`, `windowEndUtc` — doubles as a double-fire guard via Spring Batch's same-parameters-can't-rerun-a-completed-job behaviour. Note: including `reportFrequency` means a `JobInstance` is scoped per type+frequency+window, not just per type+window — intentional, since it keeps frequency changes (e.g. a report type moving from `DAILY` to `EVERY_4_HOURS`) producing distinct `JobInstance`s rather than colliding with a prior run under the old frequency |
| Restart/checkpoint | Real `ItemStream` implementation, checkpointed at **tree completion**, not message or byte offset — persists a single `lastSeenId` (config ID) to the step `ExecutionContext` using Spring Batch's standard mechanism, no custom serialization. Efficiency optimization on top of Phase 6 dedup, not a correctness mechanism in its own right |
| `JobParameters` types | Typed (`Instant` via `JobParameter<T>` — a Spring Batch 5.0 feature, not new in 6.0; corrected in Round 3), not stringified — see Round 1, Q5 |
| Job repository metadata | Spring Batch's own `BATCH_JOB_*`/`BATCH_STEP_*` tables in SQL Server, via the explicit `spring-boot-starter-batch-jdbc` dependency (Spring Boot 4.x defaults to an in-memory, non-durable job repository otherwise) — separate schema migration from application tables |

---

## 2. Decision 1 — Reader Shape: Custom `ItemStreamReader`, Not a Stock Paging Reader

**Problem:** `ConfigurationReadRepository.assembleTrees()` is deliberately batch-shaped —
staged bulk reads (scopes by configIds, assignments by scopeIds, accounts/aliases by
assignmentIds) across a whole page, specifically to avoid N+1 query bursts. A stock
`JdbcPagingItemReader<ReportConfigRow>` paired with a separate per-item `ItemProcessor`
would call the assembly/fan-out logic once per item, since Spring Batch's standard
reader/processor contract is one row in, one row (or list) out per call.

**Decision:** A custom `ItemStreamReader<OutboundReportMessage>` that, internally, still
calls `repository.findConfigPage()` + `repository.assembleTrees()` + fan-out once per
page — identical to `ScheduledConfigReader` today — then serves individual
`OutboundReportMessage` items out of that page's buffer via `read()`, fetching the next
page only once the buffer is drained.

| | Pros | Cons |
|---|---|---|
| Custom reader | Preserves existing batched-query efficiency; page size and Spring Batch's chunk size can be tuned independently since the reader controls both | Not a drop-in Spring Batch component — more code to own and test than wiring a stock reader |
| Stock `JdbcPagingItemReader` (rejected) | Less code, standard component, well understood | Forces per-item assembly, regressing the query-count behaviour Phase 1 specifically designed around |

**Error handling during page assembly:** no page-level retry logic inside the reader.
If `assembleTrees()` throws (e.g. a connection drop mid-page), the exception propagates
out of `read()`; Spring Batch rolls back the in-flight chunk's transaction and fails the
step/job (no retry/skip policy is configured in this phase). This mirrors the Phase 1
guide's own stance on partial staged-read failure — no partial persistence, failure
propagates to the caller, which owns retry/skip policy. Here, "the caller" is a relaunch
of the failed `JobInstance`, which (§8) resumes from the last fully-committed tree rather
than from scratch. Adding fault-tolerant step-level retry/skip (`faultTolerant()`,
skip/retry policies) is left as a Phase 8/9 concern if operational experience shows
transient failures are common enough to warrant it — not assumed necessary here.

---

## 3. Decision 2 — Chunk/Commit Boundary: Message Count

**Problem:** One `ReportConfigTree` can fan out to many `OutboundReportMessage`s
(bundled) or one-per-account (unbundled). The Phase 4 exit criterion asks for a "bounded
transaction blast radius per chunk" — that only means something concrete if the chunk
unit matches the unit Phase 6 will actually key dedup/audit on, which is one message per
send attempt.

**Decision:** `commit-interval` counts messages, not trees or pages. The reader's
internal page buffering (§2) is invisible to the chunk boundary — a single page's
messages may span multiple chunks, or a chunk may span multiple pages, depending on
fan-out ratio.

| | Pros | Cons |
|---|---|---|
| Message-level chunking | Blast radius is consistent regardless of bundling ratio; aligns with Phase 6's future audit unit | Chunk size no longer has a fixed relationship to page size — needs its own tuning, separate from page size (§1 of the Phase 1 guide already made this same read/write separation) |
| Tree-level chunking (rejected) | Simpler mental model, matches today's per-tree fan-out call | Blast radius varies wildly by bundling flag — a bundled config with 8 messages and an unbundled one with 1 get treated identically at the chunk level, which defeats the point of bounding it |

**Transaction boundary at chunk commit:** each chunk is one local JDBC transaction,
managed by the step's `PlatformTransactionManager`, covering the writer's operations
(§5) for that chunk's messages. Whether the reader's `ExecutionContext` checkpoint update
(§8) commits atomically *with* that same transaction depends on infrastructure, not on
Spring Batch itself: Commander uses a single SQL Server `DataSource` for application
tables, `BATCH_*` tables, and (already) `QRTZ_*` tables, sharing one
`PlatformTransactionManager` — so in practice, writer output and checkpoint advancement
commit together as one local transaction. This is a deliberate infrastructure choice
(§9), not a Spring Batch guarantee that would hold regardless of `DataSource` topology —
worth stating explicitly so it isn't assumed to still hold if a future phase ever splits
the job-repository `DataSource` from the application one.

**Reader page size and chunk size are orthogonal tuning knobs:** the reader's internal
page size (Phase 1's 500-row page) bounds query round-trips on the read side; chunk size
(commit-interval, this section) bounds transaction blast radius on the write side. They
are tuned against different costs — round-trip/memory cost for page size, MQ-round-trip-
plus-audit-insert cost for chunk size — and there's no reason to expect them to land on
the same number. In practice, chunk size will likely end up well below the message count
a single page can produce, since a page of 500 configs can fan out to several times that
many messages under bundling.

---

## 4. Decision 3 — Processor Stage: Real, but a No-Op for Now

**Problem:** `ScheduledConfigReader.contextFor()` currently hardcodes
`RecipientRef("UNRESOLVED", "UNRESOLVED", "UNRESOLVED")` — real recipient resolution was
explicitly deferred to "whichever step actually publishes the message."

**Decision:** Include a real `ItemProcessor<OutboundReportMessage, OutboundReportMessage>`
step in the pipeline now, implemented as a pass-through. Phase 5 fills in recipient
resolution there without touching reader or writer.

| | Pros | Cons |
|---|---|---|
| Processor seam now | Phase 5 change is additive, not structural; step wiring/tests for the processor stage exist before there's real logic in it | A pipeline stage that currently does nothing is, by definition, unnecessary complexity today |
| Skip the processor, add it in Phase 5 (rejected) | Slightly less code now | Phase 5 would need to restructure the step (reader → writer becomes reader → processor → writer), touching tested wiring for no functional gain now vs. later |

**Forward note for Phase 5, not decided here:** `ItemProcessor<In, Out>`'s standard
contract already supports returning `null` to filter an item out of the chunk entirely —
the natural fit for an unresolvable recipient (filter the message, log/dead-letter it as
a side effect inside the processor) rather than propagating a sentinel "failed" message
through to the writer. Noted so Phase 5 doesn't have to rediscover that the seam already
supports this; the actual failure-handling policy is still Phase 5's decision to make.

---

## 5. Decision 4 — Writer Stage: Logging Stub Behind a Stable Interface

**Problem:** MQ delivery is Phase 5, audit writing is Phase 6. Phase 4 needs to prove
the pipeline end-to-end without either.

**Decision:** `ItemWriter<OutboundReportMessage>` interface, with a logging-only
implementation (`LoggingReportMessageWriter` or similar) wired in for Phase 4. Phase 5/6
replace the implementation bean, not the interface or step configuration.

| | Pros | Cons |
|---|---|---|
| Interface + stub impl | Step definition and chunk/transaction wiring are final in Phase 4, not touched again in Phase 5/6; testable in isolation now | None material — this is close to free given Spring Batch's writer contract already expects an interface |

---

## 6. Decision 5 — Quartz → Batch Launch: Synchronous, Parameter-Driven

**Problem:** `ReportSchedulingJob` currently does the work inline. Something has to
launch the Spring Batch job and decide how `JobParameters` are constructed.

**Decision:** `ReportSchedulingJob.execute()` calls `JobOperator.start(job, jobParameters)`
synchronously — no async task executor — with `JobParameters` built from `reportType`,
`reportFrequency`, `windowStartUtc`, `windowEndUtc`.

**API note, corrected in Round 3:** the original draft of this decision specified
`JobLauncher.run()`. `JobLauncher` is `@Deprecated(since="6.0", forRemoval=true)` in
Spring Batch 6.0 (scheduled for actual removal in 6.2+), in favor of
`JobOperator.start(Job, JobParameters)` — `JobOperator extends JobLauncher` and `start()`
is a direct behavioral replacement for `run()`, throwing the same set of exceptions
(`JobInstanceAlreadyCompleteException`, `JobExecutionAlreadyRunningException`,
`JobRestartException`, `InvalidJobParametersException`), so nothing else in this
decision's reasoning changes — only the interface used.

**Implementation note — the exception-handling order matters:** `execute()` needs a
specific `catch (JobInstanceAlreadyCompleteException e)` block placed *before* any
general `catch (Exception e)`. A single broad catch would swallow the double-fire guard's
expected exception into a generic job failure — treating a correctly-skipped duplicate
firing as a real error in logs/alerting, exactly backwards from the intent in §10's
testing strategy.

**Why synchronous:** Quartz already serializes execution per `JobDetail` via
`@DisallowConcurrentExecution`. Running the batch job asynchronously would let Quartz
consider the trigger "fired and complete" before the batch job actually finishes,
decoupling Quartz's own misfire/next-fire-time bookkeeping from real completion — with no
corresponding benefit, since nothing else is competing for that thread.

**Why these `JobParameters`:** window boundaries are already unique per firing, so
Spring Batch's default behaviour — refusing to create a new `JobInstance` for
`JobParameters` that already completed successfully — becomes a free double-fire guard,
not an obstacle. This is in the same spirit as the idempotent-by-intent principle from
the solution document, just enforced one layer earlier than Phase 6's dedup.

| | Pros | Cons |
|---|---|---|
| Synchronous launch | Keeps Quartz's completion signal honest; double-fire guard falls out of `JobParameters` for free | Long-running batch job blocks the Quartz worker thread for that trigger — acceptable at the documented **per-execution average** of ~18,500 rows (Phase 1 guide §1: ~1,000,000 total `ReportConfig` rows spread over ~6 types × ~9 frequencies), worth revisiting at Phase 9 load validation if not |
| Async launch (rejected) | Frees the Quartz thread faster | Decouples "trigger fired" from "work actually done," complicating misfire recovery and making the Phase 3 `getScheduledFireTime()` based recovery logic harder to reason about |

**Forward note for Phase 9, not decided here:** the per-execution average (~18,500 rows)
is comfortable, but a single type/frequency combination with a disproportionate share of
configs could run long enough to matter — e.g. a lopsided distribution concentrating far
more than the average under one `DAILY` combination. Since this phase commits to
synchronous launch, that risk is worth watching operationally rather than re-architecting
for pre-emptively: Phase 9 (or Phase 8's metrics work) should track
`ReportSchedulingJob.execute()` duration per report type/frequency and alert on an
excessive-runtime threshold, so a skewed distribution shows up as a signal rather than as
unexplained Quartz thread contention. Not sized here — needs real distribution data.

---

## 7. Decision 6 — One Generic `Job`, Not Six Per-Report-Type Jobs

**Problem:** Six report types, each independently schedulable, all going through the
same pipeline shape.

**Decision:** A single `reportPipelineJob` bean. `reportType`/`reportFrequency` are
runtime `JobParameters`, not compile-time wiring — the step's query predicate and window
already vary by these values at runtime today.

| | Pros | Cons |
|---|---|---|
| One generic `Job` | No duplicated Job/Step definitions; Phase 8's per-report-type metrics come from tagging on the `reportType` job parameter rather than needing six distinct beans to attach metrics to | None material — report types genuinely share identical pipeline shape; there's no hidden per-type divergence today that would justify separate Jobs |

**Implementation note — a real naming collision, not a design issue:** `ReportSchedulingJob`
already `implements org.quartz.Job` (Quartz's `Job`). Spring Batch 6 moved its `Job`
interface from `org.springframework.batch.core.Job` (v5) to
`org.springframework.batch.core.job.Job` (v6) — and per this decision,
`ReportSchedulingJob` also needs a reference to the `reportPipelineJob` bean
(`org.springframework.batch.core.job.Job`) to pass into `JobOperator.start()` (§6). Two
frameworks, both exposing a type simply named `Job`, needed in the same class. Not a
design problem, just an implementation trap: fully-qualify one at the field declaration
(e.g. `org.springframework.batch.core.job.Job reportPipelineJob`) rather than importing
both unqualified and hitting an ambiguous-import compile error.

**Operational caution on the double-fire guard, given how new Spring Batch 6 is:** as of
this guide, there's an open upstream issue
([spring-projects/spring-batch#5125](https://github.com/spring-projects/spring-batch/issues/5125))
where `JobInstance` identity/signature handling misbehaves specifically when a
`JobParametersIncrementer` is attached to the `Job`. This design doesn't use an
incrementer (§1: `JobParameters` are the caller-supplied window/type/frequency values,
not incremented), so the reported bug's precondition likely doesn't apply — but it's the
exact subsystem §6's double-fire guard depends on, in a genuinely new major version.
§10's double-fire test should be treated as load-bearing verification, not a formality:
run it against the exact Spring Batch patch version Spring Boot 4.1 resolves, not assumed
correct by design.

---

## 8. Decision 7 — Restart/Checkpoint: Real, but Explicitly Not Load-Bearing

**Problem (and the correction from the original plan):** The first pass of this
decision argued for real checkpoint/resume on the grounds that Phase 6's dedup "doesn't
exist yet, so restartable has to mean something real today." That reasoning conflicts
with Phase 1 guide's Round 8, which already settled this: exact-position restart is a
nice-to-have layered on top of Phase 6's dedup, not a requirement in its own right, and
deliberately left open for whichever guide picks up the write/orchestration step.

**Decision:** Implement restart properly anyway — the custom reader (§2) implements
`ItemStream` (`open`/`update`/`close`) — but position it correctly: this is an
**efficiency** optimization (a restarted job skips work already committed, rather than
re-reading and re-fanning-out from scratch), not the mechanism that prevents duplicate
sends. That mechanism is, and remains, Phase 6's audit dedup check before send.

**Checkpoint granularity — tree completion, tracked in drain order, not production order:**
the reader persists exactly one value to the `ExecutionContext`: `lastSeenId`, the config
ID of the last `ReportConfigTree` whose entire fan-out has been **drained through
`read()`** — not merely assembled into the buffer. This distinction matters: because a
page's trees are assembled all at once (§2), ahead of chunk-by-chunk consumption, several
trees' worth of messages can sit fully buffered-but-unread at the moment `update()` is
called. If `lastSeenId` advanced as soon as a tree was *assembled* rather than *drained*,
a restart could skip messages that were computed but never actually written — data loss,
not just inefficiency. So the reader tracks drain progress explicitly: each buffered item
carries its owning tree's config ID, and `lastSeenId` only advances past a tree once every
item belonging to it (and every tree before it) has been returned by `read()`.

**Zero-fan-out trees, corrected:** a tree that produces no messages (e.g. no active
assignments) has nothing to drain — so under the drain-order rule above, waiting for a
`read()` event to trigger its advancement would wait forever, and `lastSeenId` would
permanently lag behind any run of zero-fan-out trees. The fix: zero-fan-out trees are
folded into the drain-pointer chain **immediately and eagerly** at assembly time — as
long as every tree before them is already fully drained, a zero-fan-out tree counts as
instantly drained the moment it's processed, and the pointer advances through it (and
any further zero-fan-out trees immediately following) without waiting on a `read()` call.
This is safe precisely because there's nothing un-drained to lose for that tree — it only
ever applies to trees with zero items, never to trees with unread buffered items. Without
this, a report type with a long run of inactive configs would re-process that entire run
on every restart, which gets worse as the proportion of zero-fan-out configs grows.

On restart, the reader re-pages from `lastSeenId` and re-runs assembly/fan-out for that
config onward. A tree whose fan-out was interrupted mid-drain (chunk committed partway
through a bundled config's messages) may have some of its messages re-emitted after
restart — bounded by at most one tree's worth of messages, not a full page. That overlap
is exactly what Phase 6 dedup exists to absorb, consistent with this decision not being
load-bearing for correctness.

**Why implement it at all, given Round 8's stance:** once Spring Batch is adopted for
chunk transaction boundaries (§1, §3), `ItemStream` is the standard contract a
chunk-oriented reader implements — skipping it doesn't avoid complexity, it just means
writing a reader that ignores state Spring Batch already tracks for you. The marginal
cost is one persisted `Long`, not a parallel restart subsystem.

| | Pros | Cons |
|---|---|---|
| Real `ItemStream` restart, tree-level checkpoint | A crash partway through a large firing doesn't force a full re-read/re-assemble of everything already committed; single `Long` in `ExecutionContext`, no custom serialization; bounded, known overlap window (≤1 tree) on restart | Adds reader-side state management that must be tested (interrupted-job resume scenarios); must be documented clearly as non-load-bearing so a future reader doesn't mistake it for the duplicate-send safeguard |
| Message-level checkpoint (rejected) | Zero re-emission overlap on restart | Requires deterministic, stable ordering of a tree's fan-out to resume mid-tree correctly, and couples checkpoint state to fan-out internals — real complexity for a gain that dedup already covers |
| Rerun-from-scratch, no checkpoint state (rejected) | Simplest possible reader; matches Round 8's literal framing of restart as unnecessary | Discards work already committed on every failure, which gets more expensive as configuration volume grows (Phase 1 guide §1: ~1,000,000 total `ReportConfig` rows) — a real cost for no corresponding simplicity benefit once Spring Batch is already the framework in use |

---

## 9. Infrastructure Note — Job Repository Schema

Spring Batch requires its own metadata tables (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`,
`BATCH_STEP_EXECUTION`, etc.) in SQL Server to track `JobInstance`/`JobExecution`/
`ExecutionContext` state — this is what makes §8's restart behaviour and §6's
duplicate-launch guard work. These live alongside Quartz's `QRTZ_*` tables and the
application's own tables, as a **separate** schema migration from the application-table
migrations, shipped with this phase.

**Dependency correction, specific to this codebase's Spring Boot 4.1 baseline:** Spring
Boot 4.x's plain `spring-boot-starter-batch` now defaults to an **in-memory,
non-durable** `JobRepository` (Spring Batch decoupled `JobRepository` from JDBC; Boot's
default auto-configuration follows that). A SQL Server-backed, durable `JobRepository` —
which §6's duplicate-launch guard and §8's restart both depend on surviving an actual
process restart — requires explicitly depending on `spring-boot-starter-batch-jdbc`
instead, plus `spring.batch.jdbc.initialize-schema` set appropriately (`always` for a
first pass, or `never` with the schema owned by the project's own migration tool once one
exists — see Round 1, Q8 below for which). This is not a design decision with a
pro/con — Commander does not function correctly under this design without it: an
in-memory `JobRepository` would silently reset on every Commander process restart,
losing checkpoint state and letting a Quartz misfire re-launch a `JobInstance` Spring
Batch no longer remembers as completed, defeating §6 and §8 outright without an obvious
failure signal.

**Deployment verification, not just a build-time dependency:** because the failure mode
above is silent (the app still starts and runs, just without durable restart/dedup-guard
state), this needs an explicit pre-deployment check, not just the presence of
`spring-boot-starter-batch-jdbc` in `build.gradle`. Before first production deployment of
this phase: confirm `spring.batch.jdbc.initialize-schema` is set deliberately (`always`
for the first rollout so the `BATCH_*` tables get created, or `never` once schema
ownership moves to the project's own migration tooling alongside the app-table
migrations) — and confirm the `BATCH_JOB_INSTANCE` etc. tables actually exist in the
target SQL Server database post-deploy, not just that the app started without error.

---

## 10. Testing Strategy

Consistent with the solution document's cross-cutting testing strategy (§8): Phase 4 is
past the pure-logic boundary — the reader touches SQL Server (via the existing
repository) and Spring Batch's own job repository tables, so this phase needs integration
tests against a real or containerized SQL Server, not unit tests against a DB-agnostic
abstraction, matching the precedent already set for the TVP-based repository methods in
Phase 1.

- **Reader (`ItemStream` contract):** Testcontainers-backed — page boundaries, buffer
  drain/refill, and `open`/`update`/`close` round-tripping through a real
  `ExecutionContext`.
- **Restart scenario:** a job interrupted mid-chunk resumes without re-emitting messages
  from already-committed pages — the concrete behaviour §8's decision is buying.
- **Zero-fan-out restart scenario:** a page containing a mix of message-producing and
  zero-fan-out trees (e.g. configs with no active assignments), interrupted partway
  through — verify `lastSeenId` advances through the zero-fan-out trees eagerly (§8) so
  restart resumes from the last *drained* tree, not stalling behind a run of empty ones;
  and verify it does **not** advance past any tree with unread buffered messages, since
  that would be the data-loss failure mode §8 was corrected to avoid.
- **Chunk/commit boundary:** a bundled config's fan-out messages correctly span/close
  chunks at the message-count boundary, not the tree boundary.
- **Double-fire guard:** launching the same `JobParameters` twice (simulating a Quartz
  misfire double-trigger) does not create a second `JobInstance`/`JobExecution` in the
  job repository — verified via `JobRepository` (which now extends the deprecated
  `JobExplorer` directly in Spring Batch 6.0, so query against `JobRepository`, not
  `JobExplorer`), not just inferred from the launch call's outcome — **and** the second
  `start()` call's `JobInstanceAlreadyCompleteException` is caught and logged as
  expected/benign inside `ReportSchedulingJob`, not allowed to surface as a Quartz job
  failure (an unhandled exception here would otherwise make a correctly-skipped
  duplicate firing look like a real failure in logs/alerting). Treat this test as
  load-bearing, not a formality — run it against the exact Spring Batch patch version
  resolved by Spring Boot 4.1, given the open upstream issue noted in §7.
- **Writer stub:** isolated unit test — no infrastructure needed, since it's
  logging-only in this phase.

---

## 11. Open Questions Carried Into Phase 5/6

These are explicitly not decided by this guide — noted here only so Phase 5/6 don't have
to rediscover them:

1. Where exactly does the real writer (Phase 5/6) draw its own transaction boundary
   relative to MQ send vs. audit-row commit? This guide only commits to the chunk being
   message-sized (§3) — the send-then-audit vs. audit-then-send ordering question is
   explicitly the writer guide's to make, same as Phase 1's Round 5 deferred it.
2. Processor-stage recipient resolution (§4) — data source and failure handling for an
   unresolvable recipient are Phase 5 concerns, not sketched here.
3. Chunk size (message count) — needs a starting value analogous to Phase 1's page size
   of 500, but tuned against write-side cost (MQ round-trip + audit insert) once Phase 5/6
   exist, not read-side cost. Not set in this guide.

---

## 12. Round 1 — Colleague Pre-Review Clarifying Questions

Raised before the detailed review, to make sure the review itself starts from an
accurate picture rather than assumptions. Each answer either points to where this
revision (v01) already resolves it, or states the gap plainly.

| # | Question | Answer |
|---|---|---|
| 1 | `ExecutionContext` persistence — standard mechanism or custom serialization for buffered `OutboundReportMessage`s? | Standard mechanism only. §8 was revised in this pass: checkpoint granularity is **tree completion** — a single `Long` (`lastSeenId`, the last fully-emitted tree's config ID) — not a serialized message buffer or offset. No custom serialization needed. |
| 2 | Does each chunk = one DB transaction, covering both writer output and the reader's checkpoint update? | Yes in practice, but that's an infrastructure fact, not an inherent Spring Batch guarantee — see the new "Transaction boundary at chunk commit" note in §3. Single shared `DataSource`/`PlatformTransactionManager` across app tables, `BATCH_*`, and `QRTZ_*` is what makes it true here. |
| 3 | Does `assembleTrees()` failure roll back and retry at chunk level, or does the reader retry pages internally? | Rolls back and fails the job — no internal page-level retry. See the new "Error handling during page assembly" note in §2. No retry/skip policy is configured in this phase; recovery is via relaunch, which resumes from the last checkpointed tree (§8), not from scratch. |
| 4 | ~18,500 rows/execution vs. ~1,000,000 rows — which is current, and does the discrepancy matter? | Not a discrepancy — same Phase 1 guide §1 source, two different figures: ~1,000,000 is the **total** `ReportConfig` row count across all types/frequencies; ~18,500 is the **per-execution average** (total ÷ ~54 type/frequency combinations). Both are design-time assumptions, not measured production data — flagged as such where cited (§6, §8) in this revision; corrected two spots in this guide that cited the total where the per-execution figure was the relevant one. |
| 5 | `windowStartUtc`/`windowEndUtc` format for `JobParameters` — ISO-8601 string, epoch millis, other? | Passed as typed `Instant` `JobParameter<Instant>` values — not stringified. Avoids a parse/format round-trip and keeps parameter equality (relevant to the double-fire guard, §6) exact rather than string-format-dependent. Added to the Decision Summary table (§1). **Correction from Round 3:** originally attributed to "Spring Batch 6" — typed `JobParameter<T>` is actually a Spring Batch 5.0 feature (2022); 6.0 just removed the last remnants of the old string-only API. The decision is unchanged, only the "why now" framing was wrong. |
| 6 | Are window boundaries taken from `getScheduledFireTime()` or computed at current time? | Unchanged from Phase 3: `ReportSchedulingJob` already computes `fireTime` from `context.getScheduledFireTime().toInstant()`, then derives the window via `ReportingPeriodCalculator` *before* this phase's `JobOperator.start()` call — so a misfired/delayed execution still computes the historically-correct window, and the already-computed window is what gets passed into `JobParameters`. Phase 4 doesn't change this; it only adds the launch call after window computation, in the same place `ScheduledConfigReader.readAssembleAndFanOut()` is called today. |
| 7 | Does the double-fire guard test check for a specific `BatchStatus`, or just repository state? | Both — revised in the Testing Strategy section (§10): repository state via `JobRepository` (no second `JobInstance`/`JobExecution`), plus explicit verification that `ReportSchedulingJob` catches `JobInstanceAlreadyCompleteException` from the second `start()` call and logs it as benign rather than letting it surface as a Quartz job failure. |
| 8 | Schema migration — same file as app tables or separate? Which Spring Batch schema version? | Separate migration file — framework-owned infrastructure schema is a distinct concern from domain schema changes, independently upgradeable. Also corrected in this pass: Spring Boot 4.1's default `spring-boot-starter-batch` uses an **in-memory** `JobRepository`; a durable SQL Server-backed one requires the explicit `spring-boot-starter-batch-jdbc` dependency, targeting Spring Batch 6.x's `schema-sqlserver.sql` DDL (shipped inside `spring-batch-core`). See the revised §9. |

---

## 13. Round 2 — Detailed Review Feedback

A colleague's full review of v01 (post-Round 1) raised the items below. Each was
triaged individually rather than accepted wholesale — most were genuine gaps and are
now fixed in the sections above; a few were correctly-scoped observations that needed no
design change, noted here as acknowledged rather than silently dropped.

| # | Item Raised | Outcome |
|---|---|---|
| 1 | `reportFrequency` in `JobParameters` scopes `JobInstance` per type+frequency+window, worth being explicit that a frequency change produces a distinct instance | **Agreed, acknowledged.** This is the intended behaviour, not an oversight. Added as an explicit note on the `JobParameters` row in §1 rather than left implicit. |
| 2 | Checkpoint could stall on a run of zero-fan-out trees, causing repeated reprocessing on restart | **Agreed, corrected — the most significant fix in this round.** On closer analysis this was actually sharper than reprocessing inefficiency: naively advancing the checkpoint on tree *assembly* rather than tree *drain* risked skipping unwritten messages on restart (data loss), not just redundant work. Fixed in §8 by tracking `lastSeenId` in strict drain order, with zero-fan-out trees folded into that chain eagerly (since they have nothing to drain) rather than waiting on a `read()` event that would never come for them. |
| 3 | Reader page size (Phase 1) and chunk/commit size (this guide) are independent tuning knobs likely to land on different values | **Agreed, added.** Noted explicitly in §3 — different costs on each side (round-trip/memory vs. MQ-plus-audit), no reason to expect the same number. |
| 4 | Phase 5's unresolvable-recipient handling — filter via `null` return from the processor, or propagate a failed-state message? | **Noted as forward guidance, not decided here.** Standard `ItemProcessor` `null`-return-to-filter semantics already fit; actual failure-handling policy (logging, dead-letter) is explicitly left to Phase 5, added as a forward note in §4 so Phase 5 doesn't have to rediscover the seam supports this. |
| 5 | Synchronous launch could block the Quartz thread for minutes if one type/frequency combination has a disproportionate config count | **Agreed, added as a forward note.** Not re-architected for in this phase (no evidence of skew today), but added a Phase 8/9 monitoring recommendation in §6: track `ReportSchedulingJob.execute()` duration per report type/frequency and alert on excess. |
| 6 | `spring.batch.jdbc.initialize-schema` needs explicit deployment-time verification, not just a build-time dependency, since misconfiguration is silent | **Agreed, added.** §9 now includes an explicit pre-deployment verification step (confirm the property is set deliberately, confirm `BATCH_*` tables actually exist post-deploy), not just the dependency addition. |
| 7 | Add a test case for the zero-fan-out restart scenario | **Agreed, added.** New bullet in §10 — a mixed page with zero- and non-zero-fan-out trees, interrupted partway through, verifying `lastSeenId` both advances through empty trees and never advances past unread buffered messages. |

**Reviewer's overall verdict:** design coherent, no structural blockers, ready for
implementation pending the fixes above — which are now folded into the sections they
concern rather than left as a standalone addendum.

---

## 14. Round 3 — API Currency Review (Spring Batch 6.0 Specifics)

A colleague cross-referenced this guide's Spring Batch API claims against current
documentation (rather than trusting memory, given Spring Batch 6 postdates most training
data) and against the actual code on the branch. One real defect found and fixed; the
rest were accurate citation corrections or forward-looking operational notes.

| # | Item Raised | Outcome |
|---|---|---|
| 1 | `JobLauncher` is `@Deprecated(since="6.0", forRemoval=true)` — the guide should commit to `JobOperator` instead | **Confirmed and fixed — the substantive issue in this round.** Verified directly against the Spring Batch 6.0 API docs and the official 6.0 migration guide. `JobLauncher.run()` → `JobOperator.start()` throughout (§1, §6); `JobExplorer` → `JobRepository` in §10, since `JobRepository` now extends `JobExplorer` directly. Same exception contract in both cases, so no other reasoning in §6/§10 changes — only the interface. |
| 2 | Naming collision: `ReportSchedulingJob implements org.quartz.Job`, and Spring Batch 6 moved its `Job` interface to `org.springframework.batch.core.job.Job` — two `Job` types needed in one class | **Confirmed and added.** Verified the package move against the 6.0 migration guide directly (`Job`, `JobExecution`, `JobInstance`, etc. moved from `org.springframework.batch.core` to `org.springframework.batch.core.job`). Not a design problem, but a real implementation trap — noted in §7 with the fully-qualified-reference fix, so it doesn't cost implementation time to an ambiguous-import error. |
| 3 | Typed `JobParameter<T>` was attributed to "Spring Batch 6" — it's actually a 5.0 feature (2022) | **Confirmed and corrected.** Verified against the Spring Batch 5.0 migration guide and GA announcement — `JobParameter<T>` shipped in 5.0; 6.0 only removed the last string-only remnants. The decision to use typed `Instant` parameters is unchanged, just the "why now" framing in Round 1 Q5 and §1. |
| 4 | Open upstream issue (spring-projects/spring-batch#5125) about `JobInstance` signature freezing with a `JobParametersIncrementer` — worth knowing about, not necessarily applicable | **Confirmed the issue exists and matches the description** (verified directly against the GitHub issue). This design doesn't use an incrementer, so the reported precondition likely doesn't apply — but added as an explicit operational caution in §7: treat §10's double-fire guard test as load-bearing, run against the exact resolved patch version, not assumed correct by design given how new this subsystem is. |
| 5 | Today's `execute()` has a single broad `catch (Exception e)`, which would swallow `JobInstanceAlreadyCompleteException` unless special-cased first | **Confirmed against the actual code** (`ReportSchedulingJob.java`'s current single catch block) and added as an explicit implementation note in §6 — the specific catch for `JobInstanceAlreadyCompleteException` must come before the general one, or the double-fire guard's expected exception gets treated as a real failure, backwards from the intent. |

**Reviewer's note on method:** confirmed `ScheduledConfigReader`'s pagination loop
matches §2's description exactly, `ReportSchedulingJob.execute()`'s
`getScheduledFireTime()` → `ReportingPeriodCalculator` flow matches Round 1 Q6 exactly,
and `OutboundReportMessage` already carries `reportConfigId` — exactly what §8's
drain-order checkpoint needs, no domain model changes required. Everything else in the
guide checked out cleanly against the actual branch and current framework docs.
