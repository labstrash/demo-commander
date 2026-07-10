# Commander — Scheduling Module Implementation Guide

**Scope:** This guide covers the design of Commander's Quartz-based scheduled processing
path — job/trigger structure, window computation, properties schema, clustering, and
startup registration. It does not cover the Spring Batch pipeline body (read/assemble/
publish), on-demand processing, or the dead-letter retry mechanism — those are separate
guides.

**Status:** Design finalized, pending implementation.

---

## 1. Design Decisions Summary

| Decision | Choice |
|---|---|
| Job granularity | One Quartz job per (ReportType × ReportFrequency) |
| Job registration | **Static** — declared in `application.properties`, registered at startup |
| Configuration shape | Schedule-centric: one entry per frequency, each listing the report types that share it |
| Trigger type | `CronTrigger` uniformly (no `SimpleTrigger` usage) |
| Window computation | Clock-aligned, derived from **scheduled fire time**, never `Instant.now()` |
| Locking / clustering | **Quartz native JDBC clustering** (`isClustered = true`); no ShedLock |
| Quartz `instanceId` | `AUTO` (default hostname + timestamp generation) |
| Business timezone | `Europe/Stockholm` (all cron expressions and window math) |
| DST transition handling | Documented, accepted behavior — no special handling |
| Storage | `window_start_utc` / `window_end_utc` always persisted as UTC `Instant` |

---

## 2. Two Scheduling Patterns

Every (ReportType × ReportFrequency) combination falls into exactly one pattern. This is
a conceptual distinction only — as of §5, nothing in the properties file names the
pattern explicitly; it is inferred from whether a schedule entry supplies `cron` or
`boundaries` (see §5's field rules).

### Pattern A — Fixed Clock-Aligned Intervals
Reports fire at evenly spaced, non-configurable clock boundaries. The reporting window
is a fixed-length lookback ending at the fire time. Identified by the presence of a
`cron` value on the schedule entry.

| Frequency | Cron (Europe/Stockholm) | Window length |
|---|---|---|
| `DAILY` | `0 0 0 * * ?` | 24h |
| `EVERY_30_MIN` | `0 0/30 * * ?` | 30min |
| `EVERY_1_HOUR` | `0 0 * * * ?` | 1h |
| `EVERY_2_HOURS` | `0 0 0/2 * * ?` | 2h |
| `EVERY_4_HOURS` | `0 0 0/4 * * ?` | 4h |

**Window formula — `EVERY_30_MIN` / `EVERY_1_HOUR` / `EVERY_2_HOURS` / `EVERY_4_HOURS`:**
```
windowEnd   = scheduledFireTime (Europe/Stockholm)
windowStart = windowEnd - fixedInterval
```
These frequencies fire exactly on midnight-aligned boundaries (0, 4, 8, 12...), so the
rolling subtraction always lands cleanly on a clock boundary — no special-casing needed.

**Window formula — `DAILY` (calendar-day based, not rolling subtraction):** `DAILY`'s
fire time is offset from midnight (e.g. `06:00`), so a plain rolling subtraction of
24h would land on `06:00` the *previous* day rather than midnight. Instead:
```
windowStart = midnight (00:00 local) of the calendar day immediately before the fire's calendar date
windowEnd   = scheduledFireTime
```
Example: a Tuesday 06:00 fire (under a `TUE-SAT` cron) produces **Monday 00:00 →
Tuesday 06:00** (30 hours: the full previous calendar day plus the same-day lag to
06:00) — not a rolling "24 hours before fire time." This is calendar-date arithmetic,
independent of which weekdays the job actually fires on. Confirmed accepted
consequence: under `TUE-SAT`, Saturday's window is Friday 00:00 → Saturday 06:00, and
the next fire (Tuesday) is Monday 00:00 → Tuesday 06:00 — Saturday afternoon/evening
and all of Sunday fall outside every window, permanently. This is intentional and
accepted from a business standpoint, not a gap to be bridged.

**Second accepted consequence — overlap between consecutive firing days:** because
`windowStart` always resets to "yesterday's midnight" regardless of the actual time of
the previous firing, any two *consecutive* active days overlap by exactly the fire
time's offset from midnight. E.g. under `TUE-SAT` at `06:00`: Wednesday's window is
Tuesday 00:00 → Wednesday 06:00, and Thursday's window is Wednesday 00:00 → Thursday
06:00 — the six hours from Wednesday 00:00 to Wednesday 06:00 appear in **both**
reports. This is confirmed **accepted, same as the weekend gap above** — no
last-actual-firing lookup is used to close it. It only disappears entirely if the fire
time is moved to exactly midnight (`0 0 0 ? * TUE-SAT`), which produces perfectly
adjacent, non-overlapping windows by the same formula with no special-casing.

One `CronTrigger` per job for all Pattern A frequencies, `DAILY` included. No
sequencing needed.

### Pattern B — Configurable Clock-Aligned Boundaries
Reports fire at explicitly configured times of day (not evenly spaced). The reporting
window is the interval between the current boundary and the *previous configured
boundary* — or midnight, if this is the first boundary of the day. Identified by the
presence of a `boundaries` value on the schedule entry.

| Frequency | Boundary count | Report type |
|---|---|---|
| `ONE_TIME_PER_DAY` | 1 | CAMT054 Credit |
| `FOUR_TIMES_PER_DAY` | 4 | CAMT054 Credit |
| `EIGHT_TIMES_PER_DAY` | 8 | CAMT054 Credit |

**Window formula:**
```
windowEnd   = boundaries[sequence]
windowStart = (sequence == 0) ? midnight (00:00 local) : boundaries[sequence - 1]
```

Example — boundaries `[10:00, 13:00, 18:00, 21:00]`:

| Fires at | sequence | windowStart | windowEnd |
|---|---|---|---|
| 10:00 | 0 | 00:00 | 10:00 |
| 13:00 | 1 | 10:00 | 13:00 |
| 18:00 | 2 | 13:00 | 18:00 |
| 21:00 | 3 | 18:00 | 21:00 |

**N `CronTrigger`s per job** — one per boundary — all pointing at the same `JobDetail`.
Each trigger carries its own `sequence` so the job knows which window to compute at
fire time. The day resets at midnight every time; there is no lookback into the
previous calendar day's configuration.

---

## 3. JobDetail Definition

One `JobDetail` per (ReportType × ReportFrequency) combination — derived by the
registrar from each schedule entry's `report-types` list (see §5).

| Property | Value |
|---|---|
| `JobKey.name` | `{ReportType}-{ReportFrequency}` — e.g. `CAMT054C-FOUR_TIMES_PER_DAY` |
| `JobKey.group` | `camt-scheduling` |
| `durable` | `true` (job definition persists even between trigger attach/detach) |
| `requestRecovery` | `true` (misfired/interrupted executions are recoverable on cluster failover) |
| Job class | A single shared `Job` implementation (e.g. `ReportSchedulingJob`) parameterized entirely via `JobDataMap` — not one class per report type |

**JobDetail `JobDataMap` contents** (shared across all triggers on this job):

| Key | Type | Applies to | Notes |
|---|---|---|---|
| `reportType` | String | All | e.g. `CAMT054C` |
| `reportFrequency` | String | All | e.g. `FOUR_TIMES_PER_DAY`. Also used by the job at execution time to detect the `DAILY` special case (see below) |
| `windowIntervalMinutes` | String (parsed to Integer) | Pattern A only, **except `DAILY`** | The **lookback duration**, sourced directly from the schedule entry's explicit `window-minutes` property (§5) — not inferred from the `frequency` label. Not populated for `DAILY` jobs — see below. |
| `boundaries` | String (CSV) | Pattern B only (present only when this job's schedule entry has `boundaries`) | Ordered local times, e.g. `"10:00,13:00,18:00,21:00"` — parsed to `LocalTime` list at execution time |

**`DAILY` is a distinct branch, not just another `windowIntervalMinutes` value.** Per §2,
`DAILY` uses the calendar-day rule (`windowStart` = midnight of the day before the fire's
calendar date), not a rolling subtraction — so a `DAILY` job's `JobDataMap` carries
**neither** `windowIntervalMinutes` **nor** `boundaries`; the job detects this case via
`reportFrequency == "DAILY"` and applies the calendar-day formula directly. All other
Pattern A frequencies (`EVERY_30_MIN`, `EVERY_1_HOUR`, `EVERY_2_HOURS`, `EVERY_4_HOURS`)
populate `windowIntervalMinutes` and use the rolling-subtraction formula unchanged.

Note there is no `schedulePattern`/`pattern` key here either — the job determines how to
compute its own window from `reportFrequency` and which of `windowIntervalMinutes` or
`boundaries` is present: `reportFrequency == "DAILY"` → calendar-day rule (neither key
present); `windowIntervalMinutes` present → rolling-subtraction rule; `boundaries`
present → boundary-lookup rule. Exactly one of these three cases applies per job; the
registrar enforces this at `JobDetail`-build time (see §7).

---

## 4. Trigger Definition

### Pattern A (one trigger per job)

| Property | Value |
|---|---|
| `TriggerKey.name` | `{JobName}-trigger` |
| `TriggerKey.group` | `camt-scheduling` |
| Schedule | `CronScheduleBuilder.cronSchedule(cronExpression).inTimeZone(TimeZone.getTimeZone("Europe/Stockholm"))` |
| Misfire instruction | `withMisfireHandlingInstructionFireAndProceed` — the *correct* instruction here: if a fire is missed (e.g. node down at trigger time), Quartz still fires it once recovery happens rather than discarding it, and normal cadence resumes from there. "Self-correcting" means **no accumulating skew in future windows** — the *next* fire's lookback window is computed fresh from its own `scheduledFireTime`, unaffected by the earlier miss. It does **not** mean the missed interval's data is recovered: e.g. for `EVERY_2_HOURS` firing at 00:00/02:00/04:00, if 02:00 misfires and isn't recovered before 04:00 fires, the `00:00–02:00` window is permanently unreported — only `02:00–04:00` gets captured by the 04:00 fire. |
| Trigger `JobDataMap` | None required (job reads everything from `JobDetail`'s map) |

### Pattern B (N triggers per job, one per boundary)

| Property | Value |
|---|---|
| `TriggerKey.name` | `{JobName}-window-{sequence:02d}` — e.g. `CAMT054C-FOUR_TIMES_PER_DAY-window-02` |
| `TriggerKey.group` | `camt-scheduling` |
| Schedule | `CronScheduleBuilder.cronSchedule(cronForThisBoundary).inTimeZone(TimeZone.getTimeZone("Europe/Stockholm"))` — single-hour cron per boundary, e.g. `0 0 13 * * ?` |
| Misfire instruction | `withMisfireHandlingInstructionFireAndProceed` — same instruction as Pattern A, and for the same reason: it guarantees the trigger fires (late, if necessary) rather than being silently discarded on recovery. This matters more here than for Pattern A: since Pattern B's window boundaries are adjacent to each other (not independently re-derivable, per §2), a boundary that's discarded rather than fired late leaves a **permanent, unrecoverable gap** — there's no later fire whose lookback window could ever capture it, unlike Pattern A's self-correcting cadence. "Must fire, not skip" describes this business consequence, not a different Quartz setting — `FireAndProceed` is exactly the instruction that satisfies it, by firing late rather than skipping. |
| Trigger `JobDataMap` | `sequence` (Integer, 0-based) — identifies which boundary this trigger represents |

---

## 5. Properties Schema

Bound to a `@ConfigurationProperties(prefix = "commander.scheduling")` class
(e.g. `CommanderSchedulingProperties`), with a `List<Schedule>` field. Each `Schedule`
groups one frequency's timing definition together with the report types that share it —
authored once per frequency rather than once per (report-type, frequency) pair.

```properties
commander.scheduling.timezone=Europe/Stockholm

# --- Pattern A: single cron per schedule (inferred from presence of `cron`) ---
commander.scheduling.schedules[0].frequency=DAILY
commander.scheduling.schedules[0].cron=0 0 6 ? * TUE-SAT
commander.scheduling.schedules[0].report-types=CAMT053E,CAMT053S,CAMT054D

commander.scheduling.schedules[1].frequency=EVERY_30_MIN
commander.scheduling.schedules[1].cron=0 0/30 0-21 ? * MON-FRI
commander.scheduling.schedules[1].window-minutes=30
commander.scheduling.schedules[1].report-types=CAMT052B,CAMT052BT

commander.scheduling.schedules[2].frequency=EVERY_1_HOUR
commander.scheduling.schedules[2].cron=0 0 1-21 ? * MON-FRI
commander.scheduling.schedules[2].window-minutes=60
commander.scheduling.schedules[2].report-types=CAMT052B,CAMT052BT

commander.scheduling.schedules[3].frequency=EVERY_2_HOURS
commander.scheduling.schedules[3].cron=0 0 0/2 ? * MON-FRI
commander.scheduling.schedules[3].window-minutes=120
commander.scheduling.schedules[3].report-types=CAMT052B,CAMT052BT

commander.scheduling.schedules[4].frequency=EVERY_4_HOURS
commander.scheduling.schedules[4].cron=0 0 0/4 ? * MON-FRI
commander.scheduling.schedules[4].window-minutes=240
commander.scheduling.schedules[4].report-types=CAMT052B,CAMT052BT

# --- Pattern B: ordered boundary list per schedule (inferred from presence of `boundaries`) ---
commander.scheduling.schedules[5].frequency=ONE_TIME_PER_DAY
commander.scheduling.schedules[5].boundaries=21:00
commander.scheduling.schedules[5].report-types=CAMT054C

commander.scheduling.schedules[6].frequency=FOUR_TIMES_PER_DAY
commander.scheduling.schedules[6].boundaries=10:00,13:00,18:00,21:00
commander.scheduling.schedules[6].report-types=CAMT054C

commander.scheduling.schedules[7].frequency=EIGHT_TIMES_PER_DAY
commander.scheduling.schedules[7].boundaries=03:00,06:00,08:00,10:00,12:00,15:00,18:00,21:00
commander.scheduling.schedules[7].report-types=CAMT054C
```

**Field rules:**
- Each `schedules[i]` entry supplies **exactly one** of `cron` or `boundaries` — never both, never neither. This single field determines Pattern A vs. Pattern B; there is no separate discriminator field to keep in sync.
- `window-minutes` is **required** on every `cron`-based entry **except `DAILY`** (which uses the calendar-day rule and must leave it unset), and **must be absent** on every `boundaries`-based entry. This is the lookback duration in minutes (e.g. `30` for `EVERY_30_MIN`, `240` for `EVERY_4_HOURS`) — kept as an explicit value rather than inferred from the `frequency` label, so it's visible directly next to its cron expression and adding a new interval-based frequency never requires a code change.
- `boundaries`, when present, must be non-empty, strictly ascending by time-of-day, and
    must not include `00:00` — sequence 0 already implies "midnight to first boundary" per
    §2's window formula, so a `00:00` entry would be a redundant, zero-length degenerate
    window rather than a meaningful boundary.
- `report-types` must be non-empty.
- `(report-type, frequency)` pairs are implicitly unique — the registrar fails fast if the same report type appears twice for the same frequency (e.g. duplicated across two schedule entries with the same `frequency`, or listed twice within one entry's `report-types`).
- This list is the **sole source of truth** for which jobs exist. It is independent of `CAMT.ReportTypeFrequency` (which remains an unenforced documentation aid per the existing schema comment) and independent of which `ReportConfig` rows are currently active — a job can be registered here with zero active configs behind it; it will simply have nothing to do when it fires (see §7, step 1).

---

## 6. Quartz Core Configuration

| Setting | Value |
|---|---|
| `org.quartz.jobStore.class` | `org.springframework.scheduling.quartz.LocalDataSourceJobStore` (or `JobStoreTX`) |
| `org.quartz.jobStore.isClustered` | `true` |
| `org.quartz.jobStore.useProperties` | `true` |
| `org.quartz.scheduler.instanceId` | `AUTO` |
| `org.quartz.scheduler.instanceName` | Fixed, shared across all cluster nodes (required for clustering to recognize nodes as peers) — e.g. `CommanderScheduler` |
| `org.quartz.jobStore.dataSource` | Points at the existing `REPORTDB` datasource; schema already provisioned via `03-quartz-schema.sql` |

No ShedLock dependency. `QRTZ_LOCKS` (already in the provisioned schema) is Quartz's own
clustering mutex — it guarantees only one cluster node fires a given trigger, which is
exactly the guarantee ShedLock would otherwise provide, so adding it would be redundant.

**`useProperties=true`** restricts every `JobDataMap` value (on both `JobDetail` and
`Trigger`) to `String` — no full Java object serialization. This trades a small amount
of verbosity (e.g. `sequence` and `windowIntervalMinutes` are stored and parsed as
strings rather than native `Integer`) for immunity to serialization-compatibility
issues across redeploys, and makes `JobDataMap` contents directly inspectable in
`QRTZ_JOB_DETAILS`/`QRTZ_TRIGGERS` if ever needed for debugging. All `JobDataMap`
values described in §3 and §4 (`reportType`, `reportFrequency`, `windowIntervalMinutes`,
`boundaries`, `sequence`) are stored under this constraint — string-typed, parsed to
their working type at job-execution time.

**Concurrent node startup safety:** the registrar (§7) runs independently on every
cluster node at startup. This is safe without any additional application-level
transaction wrapping — Quartz's clustered `JobStoreTX` already serializes
`scheduleJob`/`unscheduleJob` calls internally via the same `QRTZ_LOCKS` mutex used for
trigger acquisition, so two nodes registering concurrently cannot race each other or
corrupt scheduler state.

---

## 7. Startup Registrar Responsibilities

A registrar component (`ApplicationRunner` or `SmartLifecycle`, running once at startup,
on every node — Quartz clustering makes this safe/idempotent) is responsible for:

1. **Read** `CommanderSchedulingProperties` (the static schedule list from §5).
2. **Validate** each schedule entry before registering anything:
   - Exactly one of `cron` / `boundaries` present (never both, never neither) — this is what determines Pattern A vs. Pattern B for that entry.
   - `window-minutes` present and positive on every `cron`-based entry except `DAILY`; absent on `DAILY` and on every `boundaries`-based entry.
   - `boundaries`, when present, non-empty and strictly ascending.
   - `report-types` non-empty.
   - No `(report-type, frequency)` pair registered more than once across the whole list.
   - Fail fast (refuse to start) on any violation — misconfiguration should never
     silently produce a partially-scheduled system.
3. **Expand** each schedule entry into one (ReportType × ReportFrequency) job per
   report type in its `report-types` list.
4. **Build** one `JobDetail` per expanded entry per §3, with the appropriate `JobDataMap`.
5. **Build** trigger(s) per entry per §4 — one `CronTrigger` for a `cron`-based entry, N
   `CronTrigger`s for a `boundaries`-based entry (deriving each boundary's single-hour
   cron expression from the configured `LocalTime`).
6. **Diff and clean orphaned triggers** before registering: for each job, fetch its
   currently-scheduled trigger keys (`scheduler.getTriggerKeysOfJob(jobKey)`), compute
   the expected trigger-key set from the current config, and `unscheduleJob(...)` any
   existing trigger key that's no longer expected. This matters specifically for
   Pattern B — `scheduleJob(..., replace = true)` only replaces triggers it's given by
   name; it does **not** delete triggers that existed under a previous config and are
   absent from the new one. If `boundaries` shrinks from 4 entries to 3 (e.g.
   `[10:00, 13:00, 18:00, 21:00]` → `[10:00, 13:00, 18:00]`), the old `-window-03`
   trigger (previously the `21:00` boundary) would otherwise keep firing forever on
   its stale schedule. Trigger naming (§4, §10) is stable by **sequence position**,
   not by the boundary time value — `-window-03` always means "the 4th configured
   boundary, whatever time it currently is," so a boundary's *time* can change in
   place without any trigger-identity churn; only a change in the *number* of
   boundaries creates orphans that this step must remove.
7. **Register** via `scheduler.scheduleJob(jobDetail, triggerSet, replace = true)` —
   using `replace = true` makes registration idempotent across redeploys/restarts;
   re-registering an unchanged job/trigger set is a safe no-op in effect.
8. Registration is **additive only** at this stage — there is no reconciliation against
   `ReportConfig` (static registration, per §1). A registered job with no matching
   active `ReportConfig` rows simply finds nothing to do on its Spring Batch read step
   and completes as a no-op (this is expected, not an error condition).

---

## 8. Window Computation — Implementation Notes

- All window arithmetic happens in `Europe/Stockholm` (`ZonedDateTime`), anchored to
  `JobExecutionContext.getScheduledFireTime()` — **never** `Instant.now()` or
  `getFireTime()` (the latter reflects actual fire time, which can differ from
  scheduled time after a misfire recovery).
- Only at the point of persisting to `ReportCommandAudit.window_start_utc` /
  `window_end_utc` is the value converted to `Instant` (UTC).
- With `useProperties=true` (§6), all `JobDataMap` values are read as `String` and
  parsed to their working type at execution time (`windowIntervalMinutes` → `Integer`,
  `boundaries` → `List<LocalTime>`, `sequence` → `Integer`).
- `JobExecutionContext.getScheduledFireTime()` returns a `java.util.Date`, which carries
  no timezone of its own — the `Europe/Stockholm` zone must be applied explicitly when
  converting, not assumed from the trigger's `inTimeZone(...)` setting: `scheduledFireTime.toInstant().atZone(ZoneId.of("Europe/Stockholm"))`. All window arithmetic (§2) is then performed on the resulting `ZonedDateTime`.
- **`DAILY`** (`reportFrequency == "DAILY"`, no `windowIntervalMinutes`/`boundaries`
  present): `windowStart` = local midnight of the calendar day immediately before
  `scheduledFireTime`'s calendar date; `windowEnd` = `scheduledFireTime`. See §2 for the
  worked example and the accepted weekend-gap consequence. **Detection order matters:**
  the job checks `reportFrequency == "DAILY"` *before* checking for the presence of
  `windowIntervalMinutes` or `boundaries`. `DAILY` should never have either key
  populated (the registrar enforces this at build time, §7), but checking frequency
  first ensures the calendar-day rule takes precedence unconditionally, rather than
  relying on those keys being absent as an implicit signal.
- **Other Pattern A** (job's `JobDataMap` has `windowIntervalMinutes`):
  `windowStart = scheduledFireTime.minus(windowIntervalMinutes)`.
- **Pattern B** (job's `JobDataMap` has `boundaries`): look up `sequence` from the
  firing trigger's `JobDataMap`, index into the parsed `boundaries` list from the job's
  `JobDataMap`; `sequence == 0` uses local midnight of the scheduled fire date as
  `windowStart`.
- **Late recovery (any pattern):** if a job is recovered well after its
  `scheduledFireTime` (node failover, extended downtime, etc.), the window is still
  computed strictly from that original scheduled time, per the rule above — never from
  the actual (late) recovery time. Commander still constructs and publishes the report
  command message for that window regardless of how much real time has elapsed since
  it closed. There is no staleness cutoff and no `maxRecoveryStaleness` concept — a
  window that closed hours or days ago is still reported on recovery, not skipped or
  dead-lettered for being old. See §9 for how this interacts with misfire handling.

---

## 9. Known Limitation — DST Transitions (Accepted Behavior)

Cron triggers in `Europe/Stockholm` follow local wall-clock time, which shifts twice a
year:

- **Spring forward** (clocks skip 02:00→03:00): a boundary or interval fire time that
  falls inside the skipped hour does not fire that day. Quartz silently skips it.
- **Fall back** (02:00–03:00 occurs twice): a boundary or interval fire time inside the
  repeated hour fires once, not twice — Quartz does not double-fire.

**Effect:**
- **Pattern A** (`EVERY_2_HOURS`, `EVERY_4_HOURS`, etc.): the count of fires on the
  transition day shifts by one, so one window that day is either shorter or longer
  than its nominal length by up to one hour (e.g. a "4-hour" window becomes 3 or 5
  hours of real elapsed time).
- **Pattern B**: a boundary landing in a skipped/repeated hour is similarly affected;
  the surrounding windows absorb the discrepancy.

**Decision:** this is accepted as normal behavior and is **not** specially detected or
corrected. It occurs on at most two calendar days per year, is bounded to ±1 hour, and
CAMT052/CAMT054 windows are query filters over transaction data (not a contractual
promise of exact elapsed duration), so the practical impact is negligible. No
compensating logic should be built for this in the scheduling module.

**Related, distinct issue — misfire recovery staleness:** separately from DST, a
recovered misfire (§4's `withMisfireHandlingInstructionFireAndProceed`) may execute
significantly later than its `scheduledFireTime` (extended node downtime, cluster
failover delay, etc.). Because window computation always anchors to
`scheduledFireTime` (§8), the resulting window can be stale — closed hours or even
days before the report command is actually published. This is confirmed **acceptable
for both Pattern A and Pattern B**: Commander always publishes the report command for
the computed window regardless of recovery delay, with no staleness cutoff. This is a
deliberate behavioral decision (§8), not an oversight — noted here as well since it's
the direct consequence of the misfire-handling instruction chosen for both patterns.

**Related, distinct issue — execution idempotency:** if a job recovers after
`requestRecovery = true` (§3) — e.g. the original execution completed its work but the
node died before its `ReportCommandAudit` entry was written — could the recovered
execution publish a duplicate report command for a window already handled? This
scheduling module makes **no attempt** to detect or suppress duplicate executions
beyond Quartz's own recovery semantics; idempotency of the Spring Batch step and
duplicate prevention (e.g. via `ReportCommandAudit` status checks before publishing)
are the responsibility of the separate Spring Batch/audit guide (§12).

---

## 10. Naming Conventions Reference

| Element | Convention | Example |
|---|---|---|
| Job group | `camt-scheduling` | — |
| Job name | `{ReportType}-{ReportFrequency}` | `CAMT054C-FOUR_TIMES_PER_DAY` |
| Trigger group | `camt-scheduling` | — |
| Trigger name (Pattern A) | `{JobName}-trigger` | `CAMT053S-DAILY-trigger` |
| Trigger name (Pattern B) | `{JobName}-window-{seq:02d}` | `CAMT054C-FOUR_TIMES_PER_DAY-window-02` |
| Quartz `instanceName` | Fixed, cluster-wide constant | `CommanderScheduler` |

---

## 11. Open Items (Not Blocking, Flagged for Later)

- **Boundary retiming without redeploy** — Pattern B boundaries are static config today;
  if ops eventually needs to retime customer cut-off boundaries without a code push,
  this module would need to move from static to dynamic/data-driven registration
  (revisit if/when that requirement materializes).
- **Cross-cluster throughput coordination** — not currently required; each job's lock
  scope is per-trigger only (no shared-resource throttling across jobs).
- **Per-report-type schedule overrides** — deferred entirely for now. If a report type
  ever needs to diverge from its schedule group's shared timing, it can be split into
  its own `schedules[]` entry as an interim solution; a dedicated override mechanism
  can be designed later if that becomes common enough to warrant it.

---

## 12. Explicitly Out of Scope for This Guide

- Spring Batch job body (reader/processor/writer) invoked from within each Quartz job's
  `execute()` — separate guide.
- Feature flag check placement within the job execution flow — separate guide.
- `ReportCommandAudit` / `DeadLetterMessage` write logic — separate guide.
- On-demand (MQ-triggered) processing path — separate guide.

---

## 13. Decision Log

Record of review feedback and the decisions made in response, kept for future
reference. Newest round at the top.

### Round 5 — Implementation follow-up

| # | Feedback / Question | Decision |
|---|---|---|
| 1 | §4 documents the Pattern B trigger `JobDataMap` key as `sequence`, but the implementation uses `windowSequence`. | Guide updated to match implementation: the trigger `JobDataMap` key is `windowSequence`, not `sequence`. No functional change — `windowSequence` is clearer given `JobDetail`'s own `windowIntervalMinutes` key already uses the fuller name. |
| 2 | Implementation rejects `boundaries` entries containing `00:00`, which isn't called out in §5. | Confirmed correct and intentional: `00:00` as a boundary would produce a zero-length window (sequence 0's start is already implicitly midnight). Added to §5's field rules. |

### Round 4 — Local test run review

| # | Feedback / Question | Decision |
|---|---|---|
| 1 | Local test logs showed `DAILY` windows spanning ~47 hours — looked wrong at first glance. Traced to the local dev cron (`0 0/1 * * * ?`, firing every minute) rather than a code bug; the calendar-day rule itself computed correctly. | No code change — confirmed working as designed for the dev-speed cron. Separately surfaced a real characteristic of the calendar-day rule: two *consecutive* active firing days overlap by the fire time's offset from midnight (e.g. under `TUE-SAT` at `06:00`, Wednesday's and Thursday's windows both include Wednesday 00:00–06:00). Walked through both the "temporary" (`0 0 6 ? * TUE-SAT`) and "ideal" (`0 0 0 ? * TUE-SAT`) cron cases requested — both already match the existing `computeDailyWindow` implementation exactly, no code changes needed for either. **Confirmed accepted**, same as the weekend-gap consequence from Round 1 — no last-actual-firing lookup will be built to close it. Documented in §2. Moving the fire time to exact midnight eliminates the overlap entirely as a side effect of the same formula, with no special-casing, if that's ever wanted. |

### Round 3 — Implementation code review

| # | Feedback / Question | Decision |
|---|---|---|
| 1 | `SchedulingRegistrar.registerJob()` manually reimplemented job/trigger registration via `addJob()` + per-trigger `checkExists`/`rescheduleJob`/`scheduleJob`, with a comment claiming Quartz has no atomic `scheduleJob(jobDetail, triggers, replace)` overload — it does, since Quartz 2.0. | Fixed: `registerJob()` now calls `scheduler.scheduleJob(jobDetail, triggerSet, true)` directly, matching §7 as originally specified. Removed the manual multi-call implementation. |
| 2 | Startup log line `"Registered {} jobs"` used `expanded.size()`, which counts distinct **report types**, not the actual number of (report-type, frequency) jobs registered. | Fixed: total job count is now computed once (`expanded.values().stream().mapToInt(Map::size).sum()`) and reused for the final log line. |
| 3 | `ReportSchedulingJob` hardcoded its own `Europe/Stockholm` zone constant, independent of `commander.scheduling.timezone` — if that property were ever changed, triggers would fire in the new zone while windows stayed computed in Stockholm time. | Fixed: `ReportSchedulingJob` now takes `CommanderSchedulingProperties` as a constructor dependency and resolves its business zone from `properties.getTimezone()` once, at construction — the same source `SchedulingRegistrar` already uses for trigger construction. |
| 4 | Where should Pattern A's per-frequency lookback duration (`windowIntervalMinutes`) come from — a hardcoded `frequency` → minutes `switch` statement in the registrar, or an explicit config field? | **Added an explicit `window-minutes` field to `Schedule`** (§5), required on every cron-based entry except `DAILY`, absent on `DAILY` and on `boundaries`-based entries. Removed the `getIntervalMinutesForFrequency` switch statement entirely — the registrar now reads `schedule.getWindowMinutes()` directly, validated present/absent per the rules above (§7). Adding a new interval-based frequency now requires only a new properties entry, no code change. |

### Round 2 — External review (friend review of v2 guide)

| # | Feedback / Question | Decision |
|---|---|---|
| 1 | Detection order between `reportFrequency == "DAILY"` and presence of `windowIntervalMinutes`/`boundaries` should be made explicit, so `DAILY` takes precedence even if stale data exists. | Added explicit note to §8: the job checks `reportFrequency == "DAILY"` *before* checking for either key, as defense-in-depth rather than relying on absence-of-key as an implicit signal. |
| 2 | "Self-correcting" (Pattern A misfire wording) could be misread as "missed data is recovered," when it actually only means "no accumulating skew in future windows." Also: is `FireAndProceed` the right instruction for Pattern B given "must fire, not skip"? | Confirmed `FireAndProceed` is correct for both patterns — it fires late rather than discarding. Reworded §4 for both patterns: Pattern A note now states explicitly that a missed interval's data is permanently unreported, not recovered; Pattern B note reframed to clarify "must fire, not skip" describes the business consequence that `FireAndProceed` satisfies, not a contradiction with it. |
| 3 | Suggest a brief cross-reference noting the scheduling module doesn't itself handle duplicate-execution prevention on job recovery — that's the Spring Batch/audit guide's job. | Added as a new "execution idempotency" note in §9, alongside the existing misfire-staleness note, pointing to §12. |
| 4 | `scheduledFireTime` from Quartz is a `java.util.Date` with no inherent timezone — the `Europe/Stockholm` conversion should be spelled out literally, not left implied by the trigger's `inTimeZone(...)` setting. | Added explicit conversion line to §8: `scheduledFireTime.toInstant().atZone(ZoneId.of("Europe/Stockholm"))`. |

### Round 1 — External review (friend review of v1 guide)

| # | Feedback / Question | Decision |
|---|---|---|
| 1 | `DAILY` with a non-midnight fire time (e.g. `0 0 6 ? * TUE-SAT`) — rolling `windowEnd - 24h` lands on `06:00` the previous day, not midnight. What's the intended window? | `DAILY` uses a **calendar-day rule**, not rolling subtraction: `windowStart` = midnight of the calendar day before the fire's date; `windowEnd` = fire time. A Tuesday 06:00 fire → Monday 00:00 → Tuesday 06:00 (30h). Confirmed as `DAILY`-specific only — the other Pattern A frequencies (`EVERY_30_MIN`/`EVERY_1_HOUR`/`EVERY_2_HOURS`/`EVERY_4_HOURS`) already land cleanly on clock boundaries and keep the original rolling-subtraction formula unchanged. Confirmed accepted consequence: under `TUE-SAT`, Saturday afternoon/evening and all of Sunday fall outside every window, permanently — acceptable from a business standpoint. |
| 2 | Suggest deferring the per-report-type `overrides` mechanism entirely until actually needed, to simplify initial implementation. | Agreed. `overrides` removed from §5/§7. If divergence is needed later, split the diverging report type into its own `schedules[]` entry as an interim measure; a dedicated mechanism can be revisited later (§11). |
| 3 | `JobDataMap` values must be `Serializable`/primitive for Quartz's JDBC job store (`LocalTime` is `Serializable` since Java 8) — but what's the actual serialization strategy? | Adopted `org.quartz.jobStore.useProperties=true` — restricts all `JobDataMap` values to `String`, avoiding Java object serialization entirely (immune to cross-redeploy serialization-compatibility issues; directly inspectable in `QRTZ_JOB_DETAILS`/`QRTZ_TRIGGERS`). Added to §6; all `JobDataMap` keys in §3/§4 documented as string-typed, parsed at execution time. |
| 4 | With `replace = true`, does Quartz clean up triggers that no longer exist in the new config (e.g. a Pattern B boundary removed, shrinking the trigger count)? Suggested trigger naming be stable by sequence position, not boundary time, and that orphaned triggers need explicit cleanup. | Confirmed: `replace = true` does **not** delete orphaned triggers. Added an explicit **diff-and-clean registrar step** (§7, step 6) — fetch each job's existing trigger keys, compute the expected set from current config, `unscheduleJob` anything orphaned, before/around registration. Trigger naming confirmed stable by sequence position (`-window-03` always means "4th configured boundary," regardless of its current time value) — only a change in boundary *count* creates orphans. |
| 5 | Registrar runs on every cluster node at startup — is concurrent registration safe, or does it need its own transaction wrapping? | No new application-level wrapping needed. Quartz's clustered `JobStoreTX` already serializes `scheduleJob`/`unscheduleJob` internally via `QRTZ_LOCKS`, the same mutex used for trigger acquisition — concurrent node startup is already race-free. Clarifying note added to §6. |
| 6 | What happens to the `ReportCommandAudit` entry if a job is recovered mid-execution after a node goes down? | Confirmed out of scope for this guide — belongs in the (separate) Spring Batch / audit guide, which must address idempotency and audit record state during recovery. Forward-pointer retained in §12. |
| 7 | `windowIntervalMinutes` naming could be misread as "the gap between firings" rather than "the lookback duration" — needs clarifying, since a `DAILY` job restricted to 5 days/week still has a 1440-minute lookback, not a 7-day one. | §3 reworded to state explicitly: lookback duration, independent of actual firing cadence. (Superseded in part by decision #1 — `DAILY` no longer uses `windowIntervalMinutes` at all, having its own calendar-day branch, but the "lookback ≠ firing gap" clarification still applies to the remaining Pattern A frequencies.) |
| 8 | If a Pattern B (or A) job recovers hours/days late, it still computes its window from the original `scheduledFireTime`, which may now be stale. Is this intended, and should it be documented for both patterns? | Confirmed intended for both patterns: no staleness cutoff, no `maxRecoveryStaleness` concept — Commander always publishes the report command for the computed window regardless of recovery delay. Documented explicitly in §8 and §9 (as a distinct issue from the DST-transition note, though both stem from "never use `Instant.now()`"). |
