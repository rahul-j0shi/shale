# M4 — Multi-SSTable reads + merge iterator: implementation plan

**Goal:** replace M3's materialising read path with a **heap-based multi-way merge** over one
iterator seam (ADR-0011). `scan` streams the memtable set and every SSTable, seeking to its
lower bound instead of filtering after the fact, and reconciles newest-per-user-key with
tombstones hidden. The cursor pins the tables it reads.

**Depends on:** M3 (`SSTableReader`, `Block.Iterator`, the flush path), M2 (the memtable set),
M0 (`InternalKey` and its comparator — the user-asc/sequence-desc order this merge consumes).

**Design locked in:** ADR-0011. No on-disk format changes: no version bump, no golden file, no
`Format-Change:` trailer. The public API of `shale-core` *does* change, which is what put the
ADR ahead of the code (`commits.md` §5.2).

## Scope

**In M4:**
- `dev.shale.iterator`: `InternalIterator` (`seek`, `seekToFirst`, `valid`, `next`,
  `internalKey`, `value`, `close`), `MergingIterator` (min-heap over children),
  `ReconcilingCursor` (implements the public `Cursor`).
- `Memtable.iterator()` replacing `Memtable.entries()`, implemented by `SkiplistMemtable`
  (level-0 walk, `findGreaterOrEqual` for seek) and `TreeMemtable` (`NavigableMap`).
- `SSTableReader.iterator()` replacing `SSTableReader.entries()` — a two-level iterator
  descending the index block into lazily-opened data blocks. Lives in `dev.shale.sstable`
  because `Block` is package-private.
- `Shale.scan` rebuilt on `ReconcilingCursor`, with `fromInclusive` pushed down as a real
  `seek` into every source.
- Cursor-held references: `retain()` on every SSTable in the view at construction,
  `release()` on `close()` (N6).
- The flush path (`Shale.java:254`) and the recovery sequence scan (`Shale.java:377`) move to
  the iterator, so flush stops materialising the memtable it writes.

**Deferred:** snapshot sequence filtering and the block cache (M7); range tombstones (M6+);
the manifest and delete-on-zero file lifecycle (M5 — the cursor's retain is the half of that
contract M4 can honour); `get`, which keeps its per-source `ceiling` probe by decision
(ADR-0011) and gains a Javadoc note saying why the point path stays separate.

## Task order (TDD; each task one commit, gate green)

1. **ADR + plan** — ADR-0011, this file, ADR index. *(this commit)*
2. **`InternalIterator`** — the interface, its package-info, its ordering and threading
   contract (`@NotThreadSafe`, owned by the creating thread).
3. **Memtable iterators** — `Memtable.iterator()`; `SkiplistMemtable` walks level 0,
   `TreeMemtable` walks the map; `entries()` removed and its nine test call sites moved to a
   collect-from-iterator helper. Tests: order matches the old `entries()` output, `seek` lands
   on the first entry ≥ target, empty memtable, seek past the end.
4. **`SSTableIterator`** — two-level descent, data blocks opened lazily on advance. Tests:
   streaming output equals what `entries()` produced, `seek` lands correctly including across
   a block boundary, iteration spans multiple data blocks, a CRC-broken block mid-iteration
   throws `CorruptionException` rather than truncating the scan (N4).
5. **`MergingIterator`** — `PriorityQueue` of children keyed on internal key; tie-break by
   child index, documented as unreachable (sequence numbers are unique). Tests: N children
   interleaving, an empty child, a single child, all children empty, the same user key present
   in several sources emerging sequence-descending.
6. **`ReconcilingCursor`** — first-per-user-key wins, tombstones hide, stop at the upper
   bound; retains and releases the view's SSTables. Tests: a tombstone in a memtable hides an
   SSTable value, bounds are respected at both ends, `close()` drops exactly the references it
   took, operating a closed cursor throws.
7. **Wire `scan`** — `Shale.scan` builds the cursor; the list-sort-filter block goes away.
8. **Flush + recovery on the iterator** — `Shale.java:254` and `Shale.java:377` move over.
9. **Harness** — `Backends.java:36` already drains a full scan against the oracle and inherits
   the new path; add bounded scans to it, and a jqwik property that merging N sorted sequences
   equals their sorted union.
10. **Docs** — `dev.shale.iterator/package-info.java`; glossary rows for any new term; the M4
    as-built architecture doc (the seam, the heap, reconciliation, cursor ownership), mermaid
    validated; update the engine `package-info` and `sstable/package-info` where the read path
    is described.
11. **Finish** — full `./gradlew build` + `crashTest` green; README status; M4 release note;
    tag `m4-merge`.

## Invariants to hold (checked by tests, not just prose)

- **Read equivalence:** for every database state reachable by the model harness, the streaming
  scan returns exactly what the M3 materialising scan returned. The rewrite changes cost, not
  results.
- **Newest wins:** across memtables and SSTables, the highest-sequence version of a user key is
  the one returned; a tombstone at a higher sequence hides every older value.
- **Bounded work:** a scan of a narrow range does not decode entries outside it — `seek`, not
  filter. *Asserted by counting how far the cursor advances its sources* (`ReconcilingCursorTest`
  wraps a source in a counting iterator and requires one seek plus at most one advance per returned
  entry). The plan originally said "counting block reads"; that would need a read counter on
  `SSTableReader` which does not exist, and adding production instrumentation for one assertion is
  not worth it when the claim — advances scale with the result, not the source — is testable
  directly at the iterator seam. Block-level accounting arrives naturally with the M7 block cache,
  which needs hit/miss counters anyway.
- **N4:** corruption encountered mid-scan propagates out of `next()` with its offset; the scan
  does not skip the block and does not return a partial result as if it were complete.
- **N6:** a `ReconcilingCursor` holds one reference per SSTable it can read from, and `close()`
  returns the reference count to where it started. A cursor that is never closed is a leak,
  and the test suite says so.
