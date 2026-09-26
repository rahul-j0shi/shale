# M9 — Single Raft group (optional): implementation plan

**Status:** dropped from scope 2026-09-26 by [ADR-0014](../../adr/0014-drop-flotilla-from-scope.md); kept as a starting point for any separate follow-on project. **Depends on:** M7a (the engine
`Snapshot` becomes the Raft snapshot; a `WriteBatch` is the replicated command), the
`StorageBackend` SPI. **Fills:** the existing `flotilla-raft` module shell.

**Goal:** the engine becomes the replicated state machine behind a hand-written Raft. Three
peers tolerate one failure with no acknowledged write lost, and a linearizability checker says
so under injected partitions. Optionally, ShaleDB runs on the replicated backend.

## Build order inside M9 (the simulator comes first)

A deterministic simulation harness is built **before** Raft itself: a seeded simulated clock,
network (delay, drop, duplicate, reorder, partition) and disk (crash, torn write, lost unforced
data). Raft code is written against interfaces the simulator implements, so every failure
replays from a seed (`testing.md` §2; FoundationDB and TigerBeetle practice).

## Decisions required in the ADR

1. **Persistent state.** Term, vote and log. Recommended: the Raft log reuses the WAL's block
   framing (ADR-0007) with its own record types and magic — **Raft log format v1** under N2.
   Term and vote are forced before any RPC reply that depends on them.
2. **Double logging.** Raft log plus engine WAL means every write is logged twice. Recommended:
   keep both for clarity and write the `Durability.NONE` engine write on apply, relying on the
   Raft log for durability. The ADR records the tradeoff and the replay-on-restart rule: apply
   from the last engine-persisted applied index, which is stored in the engine in the same batch.
3. **RPC stack.** `java-style.md` §1 permits one, chosen by ADR. Recommended: hand-rolled
   length-prefixed binary frames over JDK sockets with virtual threads — zero dependencies and a
   protocol document. gRPC is the alternative if the RPC layer grows at M10.
4. **Snapshots.** `InstallSnapshot` streams a consistent engine checkpoint: the SSTables pinned
   by a `Snapshot` plus a manifest for them. The engine gains a checkpoint API here, via its own
   ADR.
5. **Reads.** Linearizable reads through ReadIndex; lease reads as a measured stretch.
6. **Membership.** Joint consensus, or single-server changes with the known fix; Pre-Vote and
   leadership transfer included.
7. **ShaleDB placement.** Recommended: ShaleDB runs only on the leader, over a
   `ReplicatedBackend` implementing `StorageBackend`, where `write(batch)` proposes the batch
   and returns when it is committed and applied. D4's oracle state lives on the leader; on
   leader change, in-flight transactions abort.

## Scope and task order

1. Simulator (clock, network, disk) with its own tests.
2. Leader election with Pre-Vote; persistence of term and vote.
3. Log replication, commitment, conflict truncation; the safety properties as assertions checked
   on every simulated step (election safety, log matching, leader completeness, state-machine
   safety).
4. Apply to the engine; restart and replay.
5. Snapshots and `InstallSnapshot`; log truncation.
6. ReadIndex reads; membership changes.
7. A hand-written linearizability checker for single-key register histories (the Wing & Gong
   search with Knossos-style pruning); histories recorded from simulated clients.
8. Real processes: `ReplicatedBackend`, three local peers, a kill-the-leader script; ShaleDB on
   the leader; the demo's engine panel shows the Raft role and commit index.
9. Docs: `architecture/m9-raft.md`, release note, tag `m9-raft`.

## Acceptance gates

- Thousands of seeded simulation runs per build with partitions, crashes and message loss: no
  safety-property violation, and every run reproducible from its seed.
- Linearizability holds for every recorded history under the fault schedules.
- Real 3-process cluster: kill the leader mid-load; no acknowledged write lost; service resumes
  after election.

## References

Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm" (USENIX ATC 2014) and
Ongaro's dissertation (2014); MIT 6.824 labs; Wing & Gong, "Testing and Verifying Concurrent
Objects" (1993); Kingsbury, Knossos and Jepsen analyses; FoundationDB simulation (Zhou et al.,
SIGMOD 2021); etcd/raft; Petrov, *Database Internals* ch. 14.
