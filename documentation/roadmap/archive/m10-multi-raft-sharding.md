# M10 — Multi-Raft range sharding (stretch): implementation plan

**Status:** dropped from scope 2026-09-26 by [ADR-0014](../../adr/0014-drop-flotilla-from-scope.md); kept as a starting point for any separate follow-on project. **Depends on:** M9. **Fills:** the existing `flotilla-server` module shell.

**Goal:** split the key space into Regions, each its own Raft group, with a placement driver
that tracks, splits and rebalances them and a router that sends each request to the right
leader — the TiKV/CockroachDB model.

## Decisions required in the ADR

1. **Partitioning.** Range partitioning into Regions (contiguous key ranges), not hashing, so
   range scans stay local. Split by size, with thresholds scaled down from TiKV's (96 MiB split,
   144 MiB max) so tests run on kilobytes.
2. **Placement driver (PD).** A Raft-replicated metadata group holding the Store and Region
   registry and region epochs, plus a timestamp oracle (TSO) for future use. Stores heartbeat
   to it.
3. **Routing.** Clients cache the region map; a stale epoch returns a "region moved" error with
   the new location, and the client retries.
4. **Split, merge, rebalance.** A split is a Raft command in the parent region that produces two
   regions atomically; rebalancing moves replicas by membership change. Merge is last and may
   be cut.
5. **Failure detection.** Heartbeats with phi-accrual suspicion (Hayashibara et al., 2004).
6. **ShaleDB on M10.** Distributed transactions remain a non-goal (M11). ShaleDB over M10
   supports transactions only within one Region; a statement touching two Regions fails with a
   clear error. The ADR states this limit plainly rather than hiding it.

## Scope and task order

1. Multiple Raft groups per Store sharing one engine: Region-prefixed keys and a shared WAL,
   decided in the ADR.
2. PD group: registry, heartbeats, TSO.
3. Router and region cache; the stale-epoch protocol.
4. Size-based split; replica rebalancing; merge last.
5. Simulation coverage extended to multiple regions, splits during partitions, and moves during
   leader changes.
6. A 3-store local cluster script; the demo's engine panel shows regions.
7. Docs: `architecture/m10-sharding.md`, release note, tag `m10-shards`.

## Acceptance gates

- Simulated runs with splits, moves and partitions: no lost acknowledged write, no key served by
  two regions, and linearizability per key.
- A region splits under load without an availability gap beyond one election timeout.

## References

TiKV deep-dive docs (multi-Raft, PD, region split); Taft et al., "CockroachDB" (SIGMOD 2020);
Huang et al., "TiDB" (VLDB 2020); Hayashibara et al., "The φ Accrual Failure Detector" (SRDS
2004); Petrov, *Database Internals* ch. 9, 13 and 14.
