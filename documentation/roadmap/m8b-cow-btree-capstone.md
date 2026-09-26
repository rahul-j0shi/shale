# M8b — Copy-on-write B+Tree capstone (optional): implementation plan

**Status:** planned 2026-09-26; optional, after v1.0 (D6). **Time-boxed to four focused weeks.**
**Depends on:** the `StorageBackend` SPI including M7a's `write(WriteBatch)` and `snapshot()`,
M8's benchmark harness, D1–D6 (ShaleDB runs unchanged on the new backend). **Creates:**
`shale-btree`.

**Goal:** the comparison the SPI was designed for (ADR-0006) — measured. A second, hand-written
access method behind the same interface; the M8 suite and the ShaleDB demo run on both. **The
comparison is the deliverable, not the tree** (Sept 7 critique §5). If the tree overruns three
weeks, ship the harness changes and a one-page "what we would have measured" note, and stop.

## Decisions required in the ADR (B+Tree page format v1, `Reversible: no` for that format)

1. **Shape.** The smallest honest LMDB-style tree:
   - fixed 4 KiB pages; leaf pages hold keys and values;
   - values above a threshold go to overflow pages;
   - branch pages hold separator keys and child page numbers;
   - each page is checksummed (CRC32C) with a type, page number and transaction id.
2. **Commit protocol.** Copy-on-write shadow paging. A write transaction copies the path from leaf
   to root, writes the new pages, `force()`s, then writes the inactive one of two meta pages
   (root, freelist root, transaction id, CRC) and `force()`s again. Recovery picks the valid meta
   page with the higher transaction id; a torn meta page falls back to the other.
3. **Free space.** A simple freelist of pages freed by committed transactions, reusable only when
   no open snapshot can reach them (the oldest reader's transaction id). No compaction of the
   file.
4. **Concurrency.** Single writer, many readers. A `Snapshot` is a pinned root plus transaction
   id, so snapshots come for free — the contrast with the LSM's sequence-number filtering is part
   of the write-up.
5. **Durability mapping.** `SYNC` and `GROUP` follow the two-force commit; `NONE` skips both
   forces. Stated per N3.

## Scope

**In M8b:** `shale-btree` implementing `StorageBackend` (put, delete, get, scan, write, snapshot);
`format.md`, goldens and bit-flip tests; the model harness and crash tests pointed at it; the M8
suite on both backends; ShaleDB and the demo runnable on either via a startup option.

**Out:** page merging on delete (underfull pages are allowed), prefix compression, in-place
update, write-ahead logging, and concurrency beyond single writer / many readers.

## Task order

1. ADR, `format.md`, goldens.
2. Page codec; meta page pair; recovery; crash test at every file-operation index.
3. Search, insert with splits, delete without merge, range cursor.
4. `WriteBatch` as one write transaction; snapshots; freelist with reader tracking.
5. `EngineModelTest` and `StorageBackendModelTest` pass against the B+Tree unchanged.
6. The M8 suite on both backends; ShaleDB logic tests on both; the demo on both.
7. `documentation/benchmarks/lsm-vs-btree.md`: write, read and space amplification and tail
   latency, side by side, explained through the RUM conjecture. Release note; tag `m8b-btree`.

## Acceptance gates

- The same model, crash and ShaleDB logic suites pass on both backends.
- A torn meta page or data page at any crash point recovers to the previous committed state.
- The comparison report has every M8 experiment that applies to both backends, with charts.

## References

Chu, "MDB: A Memory-Mapped Database and Backend for OpenLDAP" (LDAPCon 2011) and the LMDB
source; Petrov, *Database Internals* ch. 2, 4 and 6 (copy-on-write B-Trees); Comer, "The
Ubiquitous B-Tree" (ACM Computing Surveys 1979); Athanassoulis et al. (EDBT 2016).
