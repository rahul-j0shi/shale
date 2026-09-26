# 0014. Drop Flotilla from the project's scope

- **Status:** Proposed — awaiting the owner's decision (see the completion plan's header)
- **Date:** 2026-09-26
- **Milestone:** S0 (scope housekeeping before M5)
- **Reversible:** yes — no Flotilla code exists; reviving it means restoring two build shells
  and the archived plans.

## Context

The project began as "an LSM engine, then a Raft-replicated, range-sharded store on it"
(charter §A). ADR-0013 then widened the scope to a relational layer and a CRUD demo on the
engine, and made Flotilla (M9, M10) optional work after v1.0. On re-examination, the question is
no longer *when* to build Flotilla but *whether* it belongs in this project at all.

The forces:

- **The owner's goal is a complete database powering an application.** That is a single-node
  system. Replication adds nothing the demo can show. ADR-0013 already rules out multi-region
  transactions, so a sharded ShaleDB could not even run the demo's checkout across regions.
- **Portfolio value per week.** Raft is a common interview topic, but it is also one of the most
  common portfolio projects: MIT 6.824's labs are a standard course exercise, and public
  implementations are plentiful. A credible one needs a deterministic simulator and a
  linearizability checker (6–10 focused weeks for M9, 6–10 more for M10). A half-built one
  invites exactly the safety questions it cannot answer. A hand-written LSM engine *with* a SQL
  layer, serializable transactions and a measured tradeoff is much rarer.
- **Completion risk dominates.** The v1.0 plan is already 28–40 focused weeks. A finished,
  focused project reads as judgement; an unfinished ambitious one reads as the opposite. The
  Sept 7 critique made the same point about the charter ending at a milestone that would never be
  reached.
- **Empty modules cost credibility now.** `flotilla-raft` and `flotilla-server` are build shells
  with no source. The Sept 7 critique recommended deleting them ("an empty module reads as
  vapourware"). The Sept 10 review kept them only because Flotilla was still planned.

## Options considered

### Option A — Keep Flotilla as optional post-v1.0 work (the ADR-0013 position)
Keeps the door open and the name. But the modules stay empty through v1.0, the docs keep
describing a system that probably will not exist, and every reader has to learn that
"optional" means "not built".

### Option B — Replace Flotilla with a smaller replication feature
For example, asynchronous WAL-shipping read replicas: 2–3 weeks, and it reuses the WAL. But it
teaches less than Raft, adds little to the database story, and would still be the least
finished-looking part of the project.

### Option C — Drop Flotilla; archive its plans; remove the shells
The project becomes Shale (engine) + ShaleDB (database) + the optional B+Tree comparison. The
M9/M10 plans move to `roadmap/archive/`, so a later distributed project can start from them.
The two empty modules are deleted in a scope-housekeeping step (S0) before M5.

## Decision

**Option C.** Flotilla (M9, M10) is out of scope, alongside M11 which already was. Task **S0**
removes `flotilla-raft` and `flotilla-server` from `settings.gradle.kts` and deletes their
directories. This ADR **supersedes ADR-0003**: the module set is now `shale-core` and
`shale-bench`, plus the ShaleDB modules of ADR-0013, which ADR-0013's dependency rule governs.
It also **amends ADR-0013's sequencing sentence** that kept M9–M10 as optional work after D6.

If distribution is wanted after v1.0, start it as a separate follow-on project with its own
charter, from the archived plans. The engine's SPI, `WriteBatch` and `Snapshot` (M7a) are the
seam it would use. Nothing in this project needs to change to allow that.

## Rationale

Every remaining milestone should earn its place in the project's actual claim: *a database
built from first principles, measured and demonstrably crash-safe.* Flotilla does not serve that
claim, costs 12–20 weeks, and is the least differentiated component on the list. Dropping it
concentrates the work where it is rarest and most visible. Removing the shells makes the
repository describe what exists and what will exist, nothing more.

This is a scope decision made for portfolio value and completion risk, not because distributed
systems are unimportant. The project states that plainly rather than hiding it behind "optional".

## Consequences

**Positive:** a smaller, finishable plan; no empty modules; README, charter and conventions
describe one coherent system; the project's name and pitch match its contents.

**Negative:** no consensus or replication in the portfolio. An interviewer asking "does it
replicate?" gets "no, deliberately — here is the ADR". The Raft-specific conventions (glossary
rows, RPC allowlist, protobuf wire rules) become dormant.

**Neutral:** ADR-0002's hand-write rule still names Raft; it simply has nothing to govern. The
checkstyle bans on Raft libraries stay as harmless guards.

**If we need to reverse this:** restore the two module directories and their `settings.gradle.kts`
entries, move the M9/M10 plans back from `roadmap/archive/`, and write an ADR superseding this one.

## References

- [ADR-0003](0003-single-repo-four-modules.md) (superseded) and
  [ADR-0013](0013-build-shaledb-relational-layer.md) (amended).
- `assessments/2026-09-07-project-critique.md` §4–5 — on stopping points and empty modules.
- Archived plans: [`roadmap/archive/m9-single-raft-group.md`](../roadmap/archive/m9-single-raft-group.md),
  [`roadmap/archive/m10-multi-raft-sharding.md`](../roadmap/archive/m10-multi-raft-sharding.md).
