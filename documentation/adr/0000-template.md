# NNNN. Title in imperative mood

- **Status:** Proposed | Accepted | Superseded by ADR-NNNN
- **Date:** YYYY-MM-DD
- **Milestone:** MN
- **Reversible:** yes | no — and what specifically makes it so

## Context

What forces are at play? What did we know at the time, and what did we not know? Include
the constraint that actually drives the decision — usually one of: correctness under
crash, amplification tradeoff, concurrency, or the project's learning objective.

State the problem without presupposing the answer.

## Options considered

### Option A — name
How it works, in a few sentences. What it costs. Who does it this way (LevelDB, RocksDB,
Cassandra, TiKV, LMDB) and what their experience suggests.

### Option B — name
As above.

### Option C — name
As above.

At least two real options. If there is only one, this is not a decision and does not
need an ADR.

## Decision

We will do X.

## Rationale

Why X beat the others, in terms of the forces in Context. Be specific about the
tradeoff accepted — for this project, usually a position on the read/write/space
amplification triangle, or a deliberate choice of the more instructive implementation
over the faster one.

Where the choice was made for learning value rather than engineering merit, say so
plainly. That is a legitimate reason in this repository, and pretending otherwise makes
the record dishonest.

## Consequences

**Positive:** what gets easier.

**Negative:** what gets harder, what we can no longer do, what we now have to maintain.

**Neutral:** what changes without clearly helping or hurting.

**If we need to reverse this:** what would it take? Which files, which formats, is a
migration possible? For `Reversible: no` decisions, describe the migration path or state
plainly that there is none.

## References

Papers, chapters of *Database Internals*, reference implementations, benchmark results.
