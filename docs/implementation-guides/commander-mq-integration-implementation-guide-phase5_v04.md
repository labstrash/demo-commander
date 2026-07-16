# Commander — MQ Integration & Resilience Implementation Guide (Phase 5)

**Scope:** Actual delivery of `OutboundReportMessage`s to Executor over IBM MQ, replacing
the Phase 4 stubs (`NoOpReportMessageProcessor` outright, `LoggingReportMessageWriter`
kept and repurposed — see Decision 10) with real recipient resolution and real send
logic, plus the tiered failure handling the solution document requires: in-process retry
for transient failures, a dead-letter tier backed by an active recovery job, and
fail-fast behaviour under a full MQ outage. Phase 6's audit row writing and pre-send
dedup check are explicitly out of scope here — this guide only establishes the
identifier scheme Phase 6 will build on, so that work doesn't have to be redone.

**Correction to the record:** the planning discussion for this phase stated that only
reader unit tests existed and Testcontainers-backed integration coverage was a gap
carried from Phase 4. That was wrong — `src/integrationTest` already contains
`DoubleFireGuardIntegrationTest`, `RestartIntegrationTest`, and
`ChunkCommitBoundaryIntegrationTest`, run via a dedicated `integrationTest` Gradle task
against a real docker-compose SQL Server (not Testcontainers, but the same coverage
intent). Phase 4's testing strategy is in better shape than credited. Corrected here
rather than left standing.

**Revision note:** Decisions 6 and 8 (retry and fail-fast) originally specified
Resilience4j. Revised per direction to keep the resilience story to Spring Retry plus a
small hand-rolled piece where Spring Retry's own tooling doesn't cleanly fit the problem
shape — see §7 and §9 for the reasoning, not just the substitution. Decision 4's identifier
scheme was replaced with a specified generator class, which is a materially different
mechanism (non-deterministic, must be generated once and threaded through) from what was
originally proposed — see §5. Decision 10 is new, added to keep
`LoggingReportMessageWriter` in service as a per-report-type kill switch and debug aid
rather than retiring it.

**Status:** Design finalized. Split into two independently implementable parts — see
"Implementation Sequencing" below.

---

## Implementation Sequencing — Part A / Part B

The nine decisions below split into two parts that don't need to land together, and
implementing them as two ordered milestones (two branches/PRs, same pattern as Phase 1
and Phase 4) is the plan:

- **Part A — Delivery path** (Decisions 1, 2, 3, 4, 10, plus the `PipelineReportMessage`
  chunk-type rework): IBM MQ client wiring, queue name resolution, real recipient
  resolution, the `correlationId`/`messageId` scheme, and the delegating writer that
  keeps `LoggingReportMessageWriter` as a kill-switch/debug aid. Gets a real message onto
  a real queue, replacing the Phase 4 stubs — independently demoable and testable. No
  resilience beyond what Phase 4 already has: a failed send throws, the chunk rolls back,
  the job fails, same as every other unhandled failure in the pipeline today.
- **Part B — Resilience & recovery** (Decisions 5, 6, 7, 8, 9): failure classifier,
  retry tier, the dead-letter schema change and write path, circuit breaker, and the
  dead-letter recovery job. Wraps Part A's send call without changing its shape or
  public types — Part B is additive once `PipelineReportMessage` exists.

**Why the split is clean, not just convenient:** the two parts' new dependencies don't
overlap either — Part A adds only `mq-jms-spring-boot-starter`; `spring-retry` (Decision
6) and the hand-rolled circuit breaker (Decision 8, no library) are Part B-only. Part A
never reads `reportConfigId`/`agreementScopeId` off `PipelineReportMessage` — those
fields exist purely for Part B's dead-letter row — but the type still belongs in Part A,
since Decisions 3 and 10 already force the reader/processor/writer retype regardless of
dead-lettering.

Each decision below is tagged **(Part A)** or **(Part B)**.

---

## 1. Design Decisions Summary

**Part A:**

| Decision | Choice |
|---|---|
| MQ client library | `com.ibm.mq:mq-jms-spring-boot-starter` (IBM's own Spring Boot starter), not raw JMS or a generic broker starter |
| Queue name resolution | Explicit `reportType → queueName` map in configuration, validated at startup — not string transformation (type-code suffixes aren't uniform width) |
| Recipient resolution | Real `ItemProcessor` implementation, resolving `config.messageRecipientId()` via a new `ReportConfigLookupRepository.findRecipientById(long)` method; unresolvable recipient → `null` return, filtering the message, logged as a countable event |
| Message identifier scheme | Two fields: `correlationId` (deterministic — `reportConfigId`+window for bundled/config-only, plus the single account/alias key for unbundled to avoid same-scope collisions) and `messageId` (the given `ReportMessageIdGenerator`, non-deterministic, called **once** at construction and threaded unchanged through every retry/dead-letter row). Maps directly onto `ReportCommandAudit`'s two same-named columns. `messageId` has a confirmed length-budget violation for `CAMT052BT` (exactly 35 characters, not under) — needs a length assertion plus a fix raised against the generator; see §5 |
| Pipeline chunk item type | `PipelineReportMessage(OutboundReportMessage payload, long reportConfigId, Long agreementScopeId)` — replaces `OutboundReportMessage` as the reader/processor/writer chunk type; only `.payload()` is ever serialized to MQ. Reopens Phase 4's `Step<OutboundReportMessage,OutboundReportMessage>` typing, decided here; see §8. `reportConfigId`/`agreementScopeId` are carried only for Part B's benefit — Part A never reads them |
| Delivery writer selection | `LoggingReportMessageWriter` kept unchanged; a new delegating `ItemWriter`, resolved once per `@StepScope` job execution, routes to it when delivery is disabled for that report type, and additionally alongside the real writer when a debug-mode flag is on |

**Part B:**

| Decision | Choice |
|---|---|
| Failure classification | Single `MqFailureClassifier` component, transient (connection-level) vs. permanent (message-level), decided in one place |
| In-process retry tier | Spring Retry `RetryTemplate`, used programmatically (not `@Retryable`, to avoid the self-invocation trap) — 3 attempts, short backoff, transient failures only — a distinct mechanism from Executor's own `REPORT.<TYPE>.RETRY` queues |
| Dead-letter store | Targets `CAMT.DeadLetterMessage` (`06-schema-audit-deadletter.sql`) with one change: `agreement_scope_id` is now nullable (populated for unbundled, `null` for bundled/config-only — bundled messages have no single scope). `report_config_id` unchanged. **Schema edit applied** — see §8 |
| Fail-fast under outage | Small hand-rolled circuit breaker (consecutive-failure counter + cool-down timestamp) in front of the retry call — not Resilience4j, and not Spring Retry's own `CircuitBreakerRetryPolicy` either, since that's built around stateful retry of a repeated invocation, not a shared gate across many distinct messages |
| Dead-letter recovery | A separate Quartz-scheduled job polling `CAMT.DeadLetterMessage` (`status='PENDING_RETRY' AND next_retry_at <= now`), reusing the same classify/retry/breaker path as the primary writer |

---

## 2. Decision 1 — MQ Client: IBM's Own Spring Boot Starter (Part A)

**Problem:** Nothing in the codebase talks to MQ yet. Need a client library and
connection wiring.

**Decision:** `com.ibm.mq:mq-jms-spring-boot-starter`, giving autoconfigured
`JmsTemplate`/`ConnectionFactory` via `ibm.mq.*` properties, rather than hand-wiring
`com.ibm.mq.jakarta.jms.MQConnectionFactory` as a manual bean or reaching for a
vendor-neutral JMS starter.

| | Pros | Cons |
|---|---|---|
| IBM's starter | Idiomatic Spring Boot autoconfiguration; maintained by IBM; matches the infra that already exists — `infra/docker/compose.yaml` runs `ibmcom/mq`, and `config.mqsc`'s ACL comments are IBM MQ-specific (`DEV.*` pre-authorization) | Ties Commander to IBM's client library — a non-issue here, since the target broker is already committed to IBM MQ, not a broker choice this guide is making |
| Manual `MQConnectionFactory` bean (rejected) | No extra starter dependency | Reimplements what the starter already does correctly; more code to own for no benefit given the broker choice is fixed |

**Local dev connection details, from the existing `compose.yaml`:** queue manager `QM1`,
app user `app` / password from `MQ_APP_PASSWORD`, ports `1414` (MQ) / `9443` (web
console). `config.mqsc` doesn't define a channel explicitly, so this relies on the
`ibmcom/mq` image's default `DEV.APP.SVRCONN` — **verify this at implementation time**
rather than assume, the same way the Phase 4 guide flagged Spring Batch API specifics for
verification rather than trusting memory on a fast-moving area.

---

## 3. Decision 2 — Queue Name Resolution: Configuration Map, Not String Transformation (Part A)

**Problem:** The solution document requires queue names "resolved via configuration, not
hardcoded." The actual queues in `config.mqsc` are `CAMT.052B.QUEUE`, `CAMT.052BT.QUEUE`,
`CAMT.053S.QUEUE`, `CAMT.053E.QUEUE`, `CAMT.054C.QUEUE`, `CAMT.054D.QUEUE` — note the
solution document's own Executor section describes `REPORT.<TYPE>` queues, which doesn't
match; this guide treats the actual `config.mqsc` definitions as ground truth, since
that's what the broker will actually have.

**Decision:** An explicit `reportType → queueName` map under a
`commander.mq.queues.*` configuration prefix, validated eagerly at startup (fail fast if
a report type the scheduler can fire lacks a configured queue — better to fail at boot
than discover it on first send).

| | Pros | Cons |
|---|---|---|
| Explicit config map | Handles the irregular suffix widths (`052B` vs `052BT` vs `054C`) without fragile pattern-matching; new report types are a config change, not a code change; startup validation catches a missing mapping before it becomes a runtime send failure | One more property block to keep in sync with `config.mqsc` — acceptable, this is exactly the kind of drift a startup-time validation check catches immediately rather than silently |
| String transformation (rejected) | No config to maintain | Doesn't actually work given the irregular suffix widths — would need type-specific special-casing anyway, which is just the config map with extra steps |

---

## 4. Decision 3 — Recipient Resolution: Real Processor, New Repository Method (Part A)

**Problem:** `ReportPipelineItemReader.contextFor()` hardcodes
`RecipientRef(id, "UNRESOLVED", "UNRESOLVED", "UNRESOLVED")`. `ReportConfigLookupRepository`
only exposes `findRecipientByTypeAndValue(type, value)` — there's no lookup by ID, which
is what's needed here (`config.messageRecipientId()` is already a numeric ID on
`ReportConfigRow`).

**Decision:** Add `Optional<RecipientRow> findRecipientById(long id)` to
`ReportConfigLookupRepository` (single-row lookup, same shape as the existing
type+value method, backed by `SELECT Id, Type, Value, Name FROM CAMT.Recipient WHERE
Id = ?`). `NoOpReportMessageProcessor` is replaced with a real
`ItemProcessor<PipelineReportMessage, PipelineReportMessage>` — the chunk item type
changes here too, see §8's revision — that resolves the recipient and rebuilds the
message with a real `RecipientRef`, copying `reportConfigId`/`agreementScopeId` and both
`messageId`/`correlationId` forward onto the rebuilt `PipelineReportMessage` unchanged —
strictly required for `messageId` (non-deterministic, §5), just good practice for
`correlationId` (reproducible, but recomputing it twice should never be necessary). An
unresolvable ID (not found, or a
future `IsActive`-style flag if one exists on `CAMT.Recipient`) returns `null` from the
processor, filtering the message from the chunk — per the Phase 4 guide's forward note,
this is exactly what `ItemProcessor`'s contract is for. The filter event is logged with
enough detail to investigate (config ID, recipient ID), not silently dropped.

**Per-item lookup, not batched:** Phase 1's staged-batch-read pattern
(`assembleTrees()`) exists specifically to avoid N+1 queries *across a whole page*. A
chunk here is already message-sized (Phase 4 Decision 2), a much smaller granularity —
so a per-item recipient lookup isn't the same N+1 problem Phase 1 solved for. Start
simple; revisit only if profiling shows this lookup dominates processor time.

| | Pros | Cons |
|---|---|---|
| New single-row lookup method, per-item | Small, focused addition matching the existing repository's lookup-method shape; `null`-filter semantics need no new abstraction | None material at current volumes |
| Batch recipient lookup by page (rejected, for now) | Would reduce query count under high fan-out | Premature — no evidence yet that per-item lookup is a bottleneck; adds complexity (batching across a reader-owned buffer that a processor doesn't naturally have visibility into) for an unmeasured problem |

---

## 5. Decision 4 — Message Identifier Scheme: Two Fields, Not One (Part A)

**Problem:** `CAMT.DeadLetterMessage.message_id` is `NOT NULL`, and this table already
exists (see §8) — so Phase 5 has to decide on a message identifier scheme regardless of
Phase 6's audit design not being built yet.

**Decision, revised:** carry both identifiers, not one instead of the other —
`correlationId` (deterministic, derived) and `messageId` (the given
`ReportMessageIdGenerator`, non-deterministic). They serve different purposes and,
usefully, map directly onto `CAMT.ReportCommandAudit`'s two separate `NOT NULL` columns
of the same names — resolving an ambiguity §14 had previously left for Phase 6 to sort
out.

**`correlationId` — deterministic, reproducible from content, no threading discipline
required.** Derived from fields already on `PipelineReportMessage`/`OutboundReportMessage`,
with one correction to the original proposal:

- **Bundled or config-only** (`agreementScopeId == null`, §8): derive from
  `(reportConfigId, windowStartUtc, windowEndUtc)` alone. There is exactly one such
  message per config per firing, so these three fields are already unique — no scope
  needed, and none exists to add.
- **Unbundled** (`agreementScopeId != null`): the original proposal derived from
  `(reportConfigId, agreementScopeId, windowStartUtc, windowEndUtc)` — **this collides**
  when a scope contributes more than one unbundled message (Phase 4 guide Round 4: an
  unbundled message carries exactly one account or alias, and a scope can have several).
  Fixed: also fold in the single allocation's identifying value —
  `payload.paymentTypeAllocations().get(0)`'s one account (`accountNumber`) or alias
  (`aliasId`) — which unbundled messages always have exactly one of. Two unbundled
  messages from the same scope now derive different `correlationId`s.
- Implementation: `UUID.nameUUIDFromBytes(...)` over the concatenated fields (a
  standard, deterministic, name-based UUID — not `UUID.randomUUID()`) rather than a
  hand-rolled hash.

**`messageId` — the given `ReportMessageIdGenerator`, unchanged from the prior draft of
this decision:** non-deterministic (`TSID.Factory...generate()` mints a new value every
call), so it must be generated **exactly once**, at `FanOutAssemblyService.assemble()`
construction time, and threaded forward unchanged through every later transformation
(Decision 3's processor rebuild), retry attempt, and dead-letter row — never regenerated.
This is the field with the confirmed length-budget problem for `CAMT052BT` (below),
unrelated to `correlationId`, which has no such constraint since it isn't
string-concatenation-length-bounded.

**Why both, not just the deterministic one:** `messageId` uses the given generator's
`FIKASE`-prefixed, TSID-based format — presumably a convention Executor or other
downstream tooling already expects, independent of anything this guide would derive.
`correlationId` is what Phase 6's dedup and cross-attempt tracing actually need to be
structurally guaranteed to match on retry/recovery, without relying on the same
call-once-and-thread-through discipline `messageId` requires. Neither one alone covers
both jobs well.

**A confirmed length-budget violation on `messageId`, not a hypothetical one.** Computed
precisely: `FIKASE` (6) + `shortType` + `configId` (fixed 8 digits) + TSID (fixed 13
characters, confirmed against `tsid-creator`'s own documentation) + `0000` (4) =
`31 + shortType.length()`. `shortType` is `reportType.substring(5)` — 3 characters for
every report type in `config.mqsc` except one: `CAMT052BT` is 9 characters, so its
`shortType` is 4, giving `31 + 4 = 35` — exactly at, not under, the generator's own
documented "must be less than 35" ceiling. Every other report type lands at 34, one
character of headroom; `CAMT052BT` fails its own contract with today's real data, not at
some future scale. This needs a decision, not just a defensive check: either the
generator gets fixed (its own algorithm, not something this guide owns — flagging back to
wherever it's maintained), or Commander adds a post-generation length assertion that fails
loudly for `CAMT052BT` specifically rather than silently emitting a spec-violating ID.
Recommended for this phase: add the assertion now (cheap, catches the problem immediately
rather than downstream at Executor), and raise the `CAMT052BT` case with whoever owns
`ReportMessageIdGenerator` as a separate fix — not blocking Phase 5 implementation, but
real enough that it shouldn't ship silently unaddressed.

| | Pros | Cons |
|---|---|---|
| Both fields, distinct roles | `correlationId` maps cleanly onto `ReportCommandAudit.correlation_id`, `messageId` onto `ReportCommandAudit.message_id` — no ambiguity left for Phase 6 to resolve; `correlationId` gives dedup/tracing a structurally-guaranteed match across retries without depending on `messageId`'s call-once discipline holding perfectly everywhere | Two fields to populate and keep straight instead of one — mitigated by their roles being genuinely different, not redundant |
| `messageId` alone (prior draft, superseded) | One field, less to carry | Ties Phase 6 dedup/correlation entirely to a value that depends on disciplined single-generation — no structural backstop if that discipline is ever violated somewhere down the line |
| `correlationId` alone (rejected) | Deterministic throughout, simplest possible guarantee | Drops whatever the given generator's format is for — presumed to matter to Executor/downstream tooling, or it wouldn't have been specified |

---

## 6. Decision 5 — Failure Classification: One Component, Not Scattered Try/Catch (Part B)

**Problem:** The solution document requires permanent vs. transient errors "decided in
one place," not re-litigated at every call site that might fail.

**Decision:** A single `MqFailureClassifier` component, used by both the primary writer
and the dead-letter recovery job (§9) — one policy, two callers. Transient: connection-
level `JMSException`s (broker unreachable, connection reset, timeout) — worth retrying.
Permanent: anything indicating the message itself is the problem (resolved queue name
doesn't exist on the broker, payload serialization failure) — retrying changes nothing,
straight to dead-letter.

| | Pros | Cons |
|---|---|---|
| Single classifier component | One place to extend when a new exception type needs categorizing; both the writer and recovery job share the exact same policy, so a message classified transient during a live send is also classified transient during recovery — no drift between the two paths | None material |

---

## 7. Decision 6 — In-Process Retry: Bounded, via Spring Retry's `RetryTemplate` (Part B)

**Problem:** The solution document calls for "an in-process retry tier for transient
failures." The existing `REPORT.<TYPE>.RETRY` queues mentioned in the solution document's
Executor section are explicitly a different mechanism — those belong to Executor's side
(failing to *process* a received message), this is Commander's side (failing to
*deliver* the message at all).

**Decision, revised:** A short bounded retry around the send call — 3 attempts, short
backoff — wrapping only calls `MqFailureClassifier` marks transient. Implemented via
Spring Retry's `RetryTemplate`, used **programmatically** (`retryTemplate.execute(...)`),
not the `@Retryable` annotation. `RetryTemplate` is configured with a
`SimpleRetryPolicy` (max 3 attempts, scoped to the exception types
`MqFailureClassifier` marks transient) and a `FixedBackOffPolicy` (or exponential, if a
short ramp turns out to matter in practice — not load-bearing for this decision).

**Why programmatic `RetryTemplate`, not `@Retryable`:** the annotation relies on a Spring
AOP proxy, which only intercepts calls made *from outside* the bean — a call from one
method to another on the same bean (self-invocation) silently bypasses the proxy and the
retry never fires, with no error to indicate it. That's an easy, silent trap in a writer
class that's likely to have the send call invoked from its own `write()` method.
`RetryTemplate` used directly has no such caveat — it's just a plain method call, no
proxying involved. This also sidesteps needing `spring-boot-starter-aop` at all.

**No Resilience4j.** Per direction: keep this simple, Spring Retry only, hand-rolled
where Spring Retry doesn't cleanly fit (see §9's circuit breaker, which is exactly that
case).

| | Pros | Cons |
|---|---|---|
| `RetryTemplate`, programmatic | Already adding `spring-retry` as a dependency (needed regardless, and reused by §9's failure counting); no AOP proxy, no self-invocation trap; explicit and easy to read at the call site | One more dependency beyond what's already in `build.gradle` — small, well-established library (part of the Spring portfolio), not a new ecosystem to learn |
| `@Retryable` annotation (rejected) | Slightly less code at the call site | The self-invocation trap is a real, silent failure mode for exactly this shape of class (a writer calling its own send logic from `write()`) — not worth the risk for the small amount of boilerplate saved |
| Resilience4j `Retry` (rejected per direction) | Standard, well-tested composition with `CircuitBreaker` | Explicitly ruled out — an additional resilience library on top of what Spring Retry already covers, more than this phase needs |

---

## 8. Decision 7 — Dead-Letter Store: Existing Schema, With One Nullability Change (Part B)

**Problem:** Where do messages go when in-process retry is exhausted (or the circuit is
open) and MQ still can't accept them?

**Decision:** `CAMT.DeadLetterMessage` — already migrated by
`06-schema-audit-deadletter.sql`, not a new table this guide is designing. Columns:
`message_id`, `report_config_id`, `agreement_scope_id`, `report_type`, `message_payload`
(the full serialized message, so a recovery attempt doesn't need to re-derive it),
`target_queue`, `retry_count`/`max_retries` (default 5), `last_error`, `status` (default
`PENDING_RETRY`), `created_at`/`updated_at`/`next_retry_at`. This shape — specifically
`retry_count`/`max_retries`/`next_retry_at`/`status` — is designed for an *active*
recovery mechanism, not a passive dump for manual inspection; §9 builds that mechanism
rather than treating this table as a write-only log.

**Revision — the sourcing problem raised earlier, resolved:** `report_config_id` and
`agreement_scope_id` are `NOT NULL`, and as of the Phase 4 guide's Round 5, neither is on
`OutboundReportMessage` anymore. Resolved as follows, not left open:

- **`report_config_id` — sourced internally, schema unchanged.** Every message, bundled or
  not, always originates from exactly one `ReportConfigTree`. `FanOutAssemblyService`
  already has `tree.config().id()` in hand at construction time — it's simply not on the
  wire-facing record. Fix: `FanOutAssemblyService.assemble()` returns a small internal
  pipeline record, not `OutboundReportMessage` directly —
  `PipelineReportMessage(OutboundReportMessage payload, long reportConfigId, Long
  agreementScopeId)` — and `ReportPipelineItemReader`'s buffer, Decision 3's processor,
  and Decision 4/6/7's writer all operate on `PipelineReportMessage`. Only `.payload()`
  is ever serialized to MQ or logged by `LoggingReportMessageWriter`; `reportConfigId`
  exists solely for this table.
- **`agreement_scope_id` — genuinely undefined for bundled messages, not just
  unsourced.** A bundled message merges allocations from multiple scopes by design
  (Phase 4 guide Round 4) — there is no single correct value, independent of where the
  code reads it from. `AccountAllocation`/`AliasAllocation` deliberately don't carry
  scope either (Round 5's trim). Fix: `PipelineReportMessage.agreementScopeId` is
  `Long`, not `long` — populated for unbundled messages (unambiguous, the single
  originating scope), left `null` for bundled and config-only messages. That requires
  **`CAMT.DeadLetterMessage.agreement_scope_id` to become nullable** — a real change to
  a migration this guide previously deferred to as "already reviewed and migrated."
  Precedent for this exact pattern already exists in the same script:
  `CAMT.ReportCommandAudit` made several columns nullable specifically to support rows
  "not attributable to exactly one `AgreementScope`" — this is the same situation,
  arrived at independently, and should follow the same precedent rather than invent a
  different one.

**What this reopened, and its resolution:** two things previously called settled changed
here — Phase 4's `Step<OutboundReportMessage, OutboundReportMessage>` chunk typing
(becomes `Step<PipelineReportMessage, PipelineReportMessage>`), decided as an
implementation-shape call; and the `06-schema-audit-deadletter.sql` migration, which
already carried its own unresolved "confirm this is still wanted" note for the analogous
`ReportCommandAudit` nullability choices. Given explicit go-ahead, `agreement_scope_id`
on `CAMT.DeadLetterMessage` has been changed to `NULL` in that migration script —
`report_config_id` untouched, still `NOT NULL`. Confirmed the precedent held exactly as
expected: `ReportCommandAudit.agreement_scope_id` in that same script was already
`BIGINT NULL`, for the same reason.

**Why a database table, not another MQ destination:** if the entire point is surviving a
full MQ outage without losing messages, writing the dead-lettered message *back onto MQ*
is circular — under a full outage there's nowhere on MQ to put it. SQL Server, already
the resource everything else in Commander depends on, is available precisely when MQ
isn't.

**Scope boundary, confirmed against the schema:** `report_config_id` stays `NOT NULL` —
every message dead-lettered here was fully assembled and only failed at the MQ-send step
(config-resolution failures are a different, later concern, not this table's job) —
`agreement_scope_id`'s nullability (above) reflects bundled messages genuinely having no
single scope, not a weakening of that boundary.

| | Pros | Cons |
|---|---|---|
| Existing schema, `report_config_id` unchanged, `agreement_scope_id` made nullable | Keeps the table's shape almost entirely as reviewed; the one change follows a precedent the same migration script already established for `ReportCommandAudit`, rather than inventing a new pattern; `PipelineReportMessage` keeps internal bookkeeping out of the wire payload, consistent with Round 5's own reasoning for trimming `OutboundReportMessage` | Reopened a migration already called reviewed and a chunk type already called final — both real changes, not paperwork; applied with explicit go-ahead rather than unilaterally |
| Store scope IDs in a child table (rejected) | No information loss for bundled messages — every contributing scope recorded | A bigger schema change to a "signed off" table for a dead-letter/retry log, where operational value of enumerating every contributing scope is low — `report_config_id` + `report_type` + the full `message_payload` are already enough to diagnose and resend |
| Leave schema as strictly `NOT NULL`, block on this (rejected) | No schema question to resolve | Not actually possible — a bundled message has no single scope value to put there regardless of implementation effort; this isn't a gap that more code closes |

---

## 9. Decision 8 — Fail-Fast Under Outage: Small Hand-Rolled Circuit Breaker (Part B)

**Problem:** The solution document explicitly calls for "fail-fast behaviour under a full
MQ outage, rather than burning through retries per-message." §7's retry tier alone would
still attempt a fresh 3-attempt sequence for every single message in a chunk during a
sustained outage — each attempt paying a connection-timeout cost — making a chunk (or the
whole job) take drastically longer under outage than under normal conditions, exactly
what this requirement rules out.

**Decision, revised — hand-rolled, not a library:** a small stateful component
(consecutive-failure counter + an "open until" timestamp) sitting in front of §7's
`RetryTemplate` call. Once consecutive full-exhaustion failures (a message that used all
3 retries and still failed) cross a threshold, the breaker opens: for a cool-down window,
subsequent sends skip straight to dead-letter without attempting a connection at all. A
call after the cool-down elapses is let through as a probe; success closes the breaker
again, failure resets the cool-down.

**Why not Spring Retry's own `@CircuitBreaker`/`CircuitBreakerRetryPolicy`, given Spring
Retry is already the chosen library for §7:** checked directly against the library rather
than assumed — Spring Retry's circuit breaker is built on *stateful* retry, designed
around the same logical operation being **re-invoked** (e.g. a redelivered JMS message)
and tracked via a retry-context cache keyed by the call's arguments. Our shape is the
opposite: one call per chunk item, each with genuinely different arguments (a different
`OutboundReportMessage` every time), and the breaker needs to gate across *all* of them
as one shared circuit — not treat each distinct message as an independent retry
sequence. Forcing that shared-state shape through `CircuitBreakerRetryPolicy`'s
default per-argument keying means either fighting the default key resolution or wiring a
constant `RetryState` key by hand — at that point the "using a library" benefit is mostly
gone, and a plain counter is more legible than a workaround. This is exactly the case
the "hand-rolled if needed" allowance was for: not avoiding Spring Retry generally (§7
still uses it directly), but not force-fitting one specific piece of it where the
library's own assumptions don't match the problem shape.

**Implementation shape, kept intentionally small:** `AtomicInteger` for the consecutive-
failure count (reset on any success), a `volatile Instant openUntil` for the cool-down
window. No half-open state machine beyond "has `openUntil` passed" — that's sufficient
for "skip sends during an outage, resume trying once the window elapses," which is what
the requirement asks for. Threshold and cool-down duration aren't sized in this guide —
same "needs real outage/recovery data" deferral as chunk-size tuning in Phase 4.

| | Pros | Cons |
|---|---|---|
| Small hand-rolled breaker | No dependency beyond what §7 already adds; the actual state needed (count + timestamp) is genuinely small, so hand-rolling doesn't trade away much versus a library; avoids fighting Spring Retry's stateful-retry keying model for a shape it wasn't designed for | Not a battle-tested library implementation — correctness rests on this guide's (small) design being implemented as specified, with its own test coverage (§12) rather than inherited from a library's test suite |
| Resilience4j `CircuitBreaker` (rejected per direction) | Battle-tested state machine, handles half-open probing more richly | Explicitly ruled out — a second resilience library on top of Spring Retry for a requirement this small doesn't fit "keep it simple" |
| Spring Retry `CircuitBreakerRetryPolicy` (considered, rejected) | Same library as §7, no new dependency | Its stateful-retry/per-argument-keying model doesn't match "one shared gate across many distinct messages" without working against its defaults — the fit is poor enough that a plain counter is the more honest choice, not just the simpler one |

---

## 10. Decision 9 — Dead-Letter Recovery: A Separate Scheduled Job, Same Send Path (Part B)

**Problem:** The exit criterion requires the system to "recover once MQ is back" — a
dead-letter table that nothing ever reads again doesn't satisfy that; the schema's
`retry_count`/`max_retries`/`next_retry_at` columns are clearly designed for something to
actively use them.

**Decision:** A separate Quartz-scheduled job (not part of `reportPipelineJob`) that
polls `CAMT.DeadLetterMessage WHERE status = 'PENDING_RETRY' AND next_retry_at <= now`,
and for each row: reconstructs the send from `message_payload`, runs it through the
*same* classify (§6) → `RetryTemplate` (§7) → circuit breaker (§9) path the primary
writer uses (not a parallel implementation), and on success marks the row sent (or
deletes it, depending on whether a sent-history is wanted — left as an implementation-time
choice, not load-bearing for this guide's decisions); on further failure, increments
`retry_count`, recomputes `next_retry_at` with backoff, and — once `retry_count` reaches
`max_retries` — flips `status` to a terminal failed state for manual/dashboard attention
rather than retrying forever.

| | Pros | Cons |
|---|---|---|
| Separate scheduled job, shared send path | Recovery isn't bolted onto the primary pipeline's chunk/transaction shape, which has nothing to do with dead-letter timing; reusing the same classifier/retry/breaker guarantees the recovery path never treats a failure differently than the primary writer does | One more Quartz job to configure/monitor — a small, well-understood addition given `SchedulingProperties`/`ReportJobScheduleBuilder` already exist for exactly this kind of job |
| Recovery embedded in `reportPipelineStep` (rejected) | One fewer moving part | Conflates two unrelated timing models — report firings run on report schedules, dead-letter recovery should run on its own cadence (e.g. every few minutes) independent of when reports happen to fire next |

---

## 11. Decision 10 — Delivery Selection: Config-Driven Delegation, Keeping `LoggingReportMessageWriter` As-Is (Part A)

**Problem, raised on review:** the plan implicitly assumed `LoggingReportMessageWriter`
gets replaced outright by the real MQ writer. It shouldn't be — it has two ongoing jobs
beyond being a Phase 4 placeholder: (1) a per-report-type kill switch, so a report type
can be onboarded to real MQ delivery independently of the others (matching the solution
document's cross-cutting feature-flag philosophy — "stages rollout of ... new report
types"), and (2) a debug aid, printing message content even while real delivery is
active.

**Decision:** Leave `LoggingReportMessageWriter` completely unchanged — including its
type, `ItemWriter<OutboundReportMessage>`, not `PipelineReportMessage`. It only ever
needs the wire payload for its debug-print purpose, never `reportConfigId`/
`agreementScopeId`, so there's no reason for §8's chunk-typing change to touch it at all.
Introduce a new `ItemWriter<PipelineReportMessage>` — the one actually wired into
`reportPipelineStep`, replacing the direct `LoggingReportMessageWriter` reference from
Phase 4's `BatchPipelineConfig` — that delegates rather than duplicates. When routing to
`LoggingReportMessageWriter` specifically, it unwraps the chunk first
(`chunk.getItems().stream().map(PipelineReportMessage::payload).toList()` into a new
`Chunk<OutboundReportMessage>`) — a small adapter step, not a change to the writer being
adapted to:

- `@StepScope`, resolving `reportType` from `JobParameters` once (same pattern as
  `ReportPipelineItemReader`), then looking up a per-type config flag
  (`commander.mq.delivery-enabled.<reportType>`) **once**, not per chunk — a job
  firing only ever processes one report type (Phase 4 Decision 6), so there's nothing to
  re-check mid-step.
- If delivery is disabled for that type: delegate the whole chunk to
  `LoggingReportMessageWriter`, full stop — no MQ code path touched at all.
- If enabled: check a separate, global debug-mode property; if on, delegate to
  `LoggingReportMessageWriter` first (visibility into payloads), then always proceed to
  the real writer (§2–§9) regardless of the debug check.

No new abstraction beyond one small delegating `ItemWriter` — no feature-flag framework
dependency, since a per-type Spring `@ConfigurationProperties` map is enough and matches
Decision 2's existing config-driven pattern for queue names.

| | Pros | Cons |
|---|---|---|
| Delegating writer, `LoggingReportMessageWriter` untouched | The Phase 4 stub keeps doing double duty (kill switch + debug trace) without its own logic being duplicated anywhere; resolving the flag once per `@StepScope` instance avoids a per-message or per-chunk config lookup | One more bean in the wiring graph — small, and it's exactly the kind of composition `ItemWriter` as an interface was already set up (Phase 4 Decision 4) to support |
| Replace `LoggingReportMessageWriter` outright (rejected) | Fewer classes | Loses both the debug-print capability and the natural place to hang a per-type rollout switch — would have to reinvent both, likely inside the real writer, coupling delivery logic to debug/flag concerns it shouldn't need to know about |

---

## 12. Infrastructure Note — Dependencies and Local Dev

**Part A:**

- **New Gradle dependency:** `com.ibm.mq:mq-jms-spring-boot-starter` — confirm exact
  artifact coordinates against Spring Boot 4.1's dependency management at implementation
  time, not assumed from training data, same discipline as Phase 4's Spring Batch version
  corrections.
- **No new docker-compose services needed** — `ibmmq` already exists in
  `infra/docker/compose.yaml` (`QM1`, ports `1414`/`9443`), and the target queues are
  already defined in `config.mqsc`. Only application-side configuration
  (`ibm.mq.*` connection properties, `commander.mq.queues.*` name mapping,
  `commander.mq.delivery-enabled.*` per-type flags) is new.

**Part B:**

- **New Gradle dependency:** `spring-retry` (§7 — used programmatically via
  `RetryTemplate`, not the annotation, so `spring-boot-starter-aop` is **not** needed).
  The circuit breaker (§9) is hand-rolled, no library. Same version-verification
  discipline as above.
- **No new schema migration needed for the dead-letter table** — it's already in
  `06-schema-audit-deadletter.sql`, with the `agreement_scope_id` nullability change
  already applied (§8). `CAMT.ReportCommandAudit` is also already migrated but stays
  unwritten-to until Phase 6.

---

## 13. Testing Strategy

Consistent with the solution document's testing strategy (§8) and Phase 4's precedent —
this phase adds MQ as new infrastructure the test suite needs to cover, on top of what
already exists. Split by part, matching the sequencing above — Part A's tests don't
depend on anything from Part B existing yet.

**Part A:**

- **Recipient resolution processor:** unit-testable with a mocked
  `ReportConfigLookupRepository` — found/not-found/filtered-to-null cases, same pattern
  as `ReportPipelineItemReaderTest`.
- **Queue name resolution:** unit test the startup validation (missing mapping for a
  schedulable report type fails fast) and the happy-path lookup.
- **Message identifiers, both fields (§5):** unit test `correlationId`'s determinism
  directly — same inputs always produce the same value, bundled/config-only never
  collides with the (now-fixed) unbundled derivation, and two unbundled messages from the
  same scope with different accounts/aliases produce different values (the collision case
  the fix addresses). Separately, unit test `messageId` **propagation, not determinism**
  (it isn't a pure derivation, so there's nothing to assert is reproducible): the value
  set at `FanOutAssemblyService.assemble()` time must survive Decision 3's processor
  rebuild unchanged — the silent-regeneration trap §5 flags, and the test most likely to
  catch an implementation that rebuilds the record without copying the field forward.
- **Delivery-selection writer (§11):** unit test all three branches — delivery disabled
  (delegates to `LoggingReportMessageWriter` only, real writer never touched), delivery
  enabled without debug (real writer only), delivery enabled with debug (both, logging
  first).
- **Integration, against the real `ibmmq` container** (extending the existing
  `integrationTest` source set, same pattern as `DoubleFireGuardIntegrationTest` et al.):
  happy path — a message sent through the real writer lands on the correct queue with
  correct payload. This is Part A's whole integration surface; everything below is Part
  B's.

**Part B:**

- **Failure classification:** unit test `MqFailureClassifier` against representative
  transient vs. permanent exception types.
- **Circuit breaker (§9):** unit-testable in isolation, no MQ needed — feed it a
  sequence of successes/failures and assert it opens at the configured threshold, skips
  attempts (not just retries) while open, and closes again after the cool-down window
  elapses.
- **Integration, against the real `ibmmq` container**, extending Part A's happy-path
  coverage:
  - Circuit-breaker fail-fast under real MQ unavailability: point at a nonexistent queue
    manager, verify the breaker opens, subsequent sends fail fast without a connection
    attempt, and messages land in `CAMT.DeadLetterMessage`.
  - Recovery: pre-populate `CAMT.DeadLetterMessage` with a `PENDING_RETRY` row past its
    `next_retry_at`, bring MQ back up, run the recovery job, verify the row is resolved
    and the message is actually delivered.
  - Exhaustion: a row that fails recovery `max_retries` times ends in the terminal failed
    status, not retried indefinitely.

---

## 14. Open Questions Carried Into Phase 6

1. `CAMT.ReportCommandAudit`'s own open note — "carried forward from the original
   migration's sign-off... confirm this is still wanted" (referring to the nullable
   `report_config_id`/`config_id`/`agreement_scope_id`/`report_frequency`/`mq_queue_name`
   columns supporting rejection-audit rows) — is explicitly flagged in the migration
   script itself as needing confirmation. Not this phase's decision to make; Phase 6
   should resolve it before writing to that table.
2. Whether a successfully-recovered dead-letter row is deleted or retained with a
   terminal "sent" status (§10) — left as an implementation-time choice; doesn't affect
   any decision in this guide, but Phase 6/8 dashboards may have a preference worth
   settling before implementation.
3. Dead-letter alerting thresholds (backlog size/age triggering a page vs.
   dashboard-only signal) — already an explicitly open item in the solution document
   itself (§7.3), not resolved here; this guide only guarantees the data (`status`,
   `created_at`, `retry_count`) such a threshold would key off of.
4. Circuit-breaker threshold and cool-down duration (§9) — needs real MQ
   outage/recovery-time data, not sized in this guide, same category as Phase 4's
   deferred chunk-size tuning.
