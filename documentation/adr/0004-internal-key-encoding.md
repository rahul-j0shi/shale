# 0004. Internal key encoding

- **Status:** Accepted
- **Date:** 2026-07-21
- **Milestone:** M0
- **Reversible:** no — the encoding is referenced by the memtable, every SSTable, the WAL, compaction, and MVCC; changing it after M4 means rewriting every component and any data already written.

## Context

Every record in the engine needs to carry, alongside the user's key, enough information to
support multi-versioning (MVCC) and deletion: which version this is, and whether it is a
value or a tombstone. This combined key — the *internal key* — is the single most
load-bearing format decision in the engine (`on-disk-formats.md` §5). Its sort order
determines whether a point lookup returns the newest value or stale data, and its width caps
the number of mutations the database can ever hold.

The driving constraint is correctness under the read path: a seek for a user key must land
directly on its newest visible version, and older versions and tombstones must sort
immediately after it, so a merge iterator can reconcile by taking the first entry per user
key.

## Options considered

### Option A — Separate columns (Bigtable/HBase style)
Store (key, timestamp, type) as distinct fields. Flexible, but every comparison and every
on-disk record must handle three fields, and the ordering logic is spread out. More moving
parts than the engine needs at byte-key granularity.

### Option B — LevelDB/RocksDB packed internal key
Append a fixed 8-byte trailer to the user key: `(sequenceNumber << 8) | valueType`, compared
as user key ascending then trailer descending. One contiguous byte string; a single
comparator orders it; a seek with a maximal trailer lands on the newest version. Proven in
LevelDB, RocksDB, and Pebble. 56 bits of sequence space; 8 bits of type.

### Option C — Key + big-endian timestamp suffix, no type byte
Simpler, but leaves no room for tombstones or future record types in the key itself, forcing
a side channel for deletes. Rejected: tombstones are core (M4).

## Decision

Option B. The internal key is:

```
InternalKey := userKey (bytes) || trailer
trailer     := (sequenceNumber << 8) | valueTypeCode        // packed into a fixed64
```

The trailer is written as a fixed **8-byte little-endian** integer ([[0005-little-endian-fixed-integers]])
appended after the user key.

- `sequenceNumber` occupies the high 56 bits ⇒ `MAX_SEQUENCE = 2^56 − 1`. A larger value is
  a programming error (`IllegalArgumentException`), not a corruption.
- `valueTypeCode` occupies the low 8 bits: `DELETE = 0x00`, `PUT = 0x01`. Codes `0x02–0xFE`
  are **reserved** for record types not yet invented and are rejected on decode
  (`CorruptionException`, N4); `0xFF` is illegal.
- **Sort order:** user key ascending by the configured `KeyComparator`, then trailer
  **descending** (unsigned), so the largest sequence — the newest version — sorts first.
- **Lookups** are constructed with `sequenceNumber = MAX_SEQUENCE` and
  `valueType = FOR_SEEK = PUT`, giving the maximal trailer so a ceiling/seek lands on the
  newest real version of the user key.

**Worked example.** user key `"key"` = `0x6B 0x65 0x79`, sequence `5`, type `PUT`:
trailer = `(5 << 8) | 0x01 = 0x0501 = 1281`; encoded bytes =
`6B 65 79 | 01 05 00 00 00 00 00 00` (trailer little-endian).

## Rationale

The packed form beats separate columns because one contiguous byte string is orderable by a
single comparator and storable verbatim by the memtable, WAL, and SSTable without per-field
handling — the whole engine manipulates one key type. Descending trailer order is the crux:
it makes "newest version of a user key" the first entry at that key, which is what lets both
a point lookup (seek to maximal trailer) and a merge iterator (take-first-per-key) be simple
and correct. Getting the direction backwards returns stale data while passing casual tests,
so it is fixed here deliberately. We follow LevelDB's layout for direct comparability with
the reference implementations this project studies.

56/8 split: 56 bits is ~7.2×10¹⁶ mutations — effectively unbounded for this project — while
a full byte of type space leaves ample room for range tombstones and future record kinds.

## Consequences

**Positive:** one key type across memtable/WAL/SSTable/compaction/MVCC; correct newest-first
reads by construction; grep-compatible with LevelDB/RocksDB/Pebble sources.

**Negative:** an 8-byte overhead per stored key; a hard 2^56 mutation ceiling; the ordering
rule is subtle and must be tested directly (property test that newest sorts first).

**Neutral:** the `KeyComparator` identity that user keys are ordered by is a separate,
persisted concern ([[0006-storage-backend-spi]]).

**If we need to reverse this:** there is no in-place migration once data exists on disk (M3+).
Before the first tagged release of M3 the change is a fresh database; after, it requires a
full read-and-rewrite of every SSTable and WAL under a new `FORMAT_VERSION`.

## References

- `documentation/conventions/on-disk-formats.md` §5 — key encoding rules
- LevelDB `db/dbformat.h` (`InternalKey`, `PackSequenceAndType`, `kValueTypeForSeek`)
- Petrov, *Database Internals*, ch. 7 — log-structured storage, reconciliation
- [[0005-little-endian-fixed-integers]], [[0006-storage-backend-spi]]
