# 0006. Define the StorageBackend SPI

- **Status:** Accepted
- **Date:** 2026-07-21
- **Milestone:** M0
- **Reversible:** no — this is the seam every backend and every caller implements against; widening or reshaping it later ripples through the engine, the benchmark harness, and (eventually) the Flotilla state machine.

## Context

The roadmap's centerpiece comparison (M8) runs the same benchmark harness against two
storage engines — the LSM tree and a copy-on-write B+Tree — to *measure* the RUM tradeoff
rather than assert it. That is only possible if both sit behind one interface fixed early
(roadmap §D marks this interface hard-to-change). The same interface is what the model test
harness drives from M0 onward, and what the Flotilla Raft state machine will call at M9. So
the shape of the single-node storage contract must be decided now, before any engine exists.

The constraint is to keep the contract minimal and honest: byte keys and values only (no
query layer — a non-goal), an explicit durability guarantee on every acknowledging write
(N3), and ordering defined entirely by a pluggable, named comparator.

## Options considered

### Option A — A rich key-value API (batches, snapshots, column families, options)
Model the full RocksDB surface up front. Future-proof, but most of it (snapshots, batches,
MVCC read views) is M7 work, and committing to it now fixes decisions we do not yet
understand. Speculative surface is the opposite of "prefer the obvious implementation first".

### Option B — A minimal byte-KV SPI
`put`/`delete`/`get` over `byte[]`, ordered iteration via a cursor, an explicit `Durability`
on writes, and the backend's `KeyComparator` exposed. Everything else (snapshots, batches) is
added when the milestone that needs it arrives.

### Option C — Return `Optional<byte[]>` from `get`
Avoids a `null` return. But it boxes an `Optional` on every point lookup — the hottest path —
for a value that is legitimately "absent". `java-style.md` §6 permits exactly this one
documented `null` path for the lowest-level lookup.

## Decision

Option B (with Option C's `null` return). The `StorageBackend` SPI is:

```
put(byte[] userKey, byte[] value, Durability)   // overwrites
delete(byte[] userKey, Durability)              // writes a tombstone semantics
byte[] get(byte[] userKey)                       // null iff absent (the one documented null path)
Cursor scan(byte[] fromInclusive, byte[] toExclusive)   // ordered; null bound = open-ended
KeyComparator comparator()                       // its name() gates reopen compatibility
close()                                           // AutoCloseable
```

- Keys and values are opaque bytes; ordering is defined solely by `comparator()`.
- Every write that can be acknowledged takes an explicit `Durability` — no default that
  silently picks the weak option (N3, D1). At M0 the only backend is in-memory and
  non-durable; the parameter locks the API shape now so no acknowledging path can ever be
  added without stating its guarantee.
- Iteration is a forward `Cursor` over half-open `[from, to)` ranges, which composes into the
  merge iterator (M4) and range scans.
- `comparator().name()` is persisted in the manifest from M5; reopening a database with an
  incompatible comparator must be refused.

## Rationale

A small, byte-oriented contract is exactly what a benchmark needs to compare two engines
fairly, and it keeps the engine embeddable and the module boundary honest
([[0003-single-repo-four-modules]]). Deferring snapshots/batches to M7 follows the roadmap's
"stay inside the milestone" rule: we do not encode decisions we have not had to make. The
`null`-return `get` is a deliberate, documented exception to the no-`null` rule because
boxing an `Optional` per lookup is a measurable cost on the one path where "absent" is a
normal answer.

## Consequences

**Positive:** one seam for LSM and B+Tree (M8) and for the Raft state machine (M9); a
harness-drivable contract from M0; durability is impossible to leave unstated.

**Negative:** the SPI will need additive evolution for snapshots, write batches, and prefix
iteration; those additions must stay backward-compatible or carry their own ADR. `get`'s
`null` return is a sharp edge callers must respect.

**Neutral:** the comparator is pluggable, but its identity is a persisted, hard-to-change
concern tracked separately ([[0004-internal-key-encoding]]).

**If we need to reverse this:** method additions are cheap and backward-compatible; a
*reshape* (changing `get`'s return, the key/value type, or the durability model) is a
breaking change across every implementation, the harness, and callers, and needs a new ADR
plus a coordinated migration. That blast radius is why it is fixed at M0.

## References

- `documentation/roadmap/shale-roadmap.md` §D, §E — the StorageBackend comparison seam
- `documentation/conventions/java-style.md` §6 — the single permitted `null` return
- `documentation/conventions/concurrency-and-resources.md` §5 — durability (N3, D1)
- [[0004-internal-key-encoding]], [[0003-single-repo-four-modules]]
