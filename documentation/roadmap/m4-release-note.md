# M4 — Multi-SSTable reads + merge iterator (release note)

**Tag:** `m4-merge` · **Status:** complete, `./gradlew build` + `crashTest` green on JDK 25.

## Delivered

The read path is now streaming. One iterator seam, one heap, one place where reconciliation lives.

- **The seam (`dev.shale.iterator.InternalIterator`, ADR-0011):** `seek`, `seekToFirst`, `valid`,
  `next`, `internalKey`, `value`, `close`, ordered by `InternalKeyComparator`. Implemented by the
  skiplist (level-0 walk, seeking through the same `findGreaterOrEqual` descent `ceiling` uses), the
  M1 `TreeMemtable`, and the SSTable.
- **The two-level SSTable iterator (`dev.shale.sstable.SSTableIterator`):** LevelDB's
  `TwoLevelIterator` — binary-search the index for the block whose *last* key is ≥ the target, then
  binary-search inside that one data block, opening blocks only as iteration reaches them. A seek
  landing past the end of the chosen block falls through to the next; the same loop drives ordinary
  advance.
- **The heap (`MergingIterator`):** a `PriorityQueue` with one slot per non-exhausted child;
  `next()` pops, steps, and pushes back. It merges and does **not** interpret — tombstones and
  superseded versions pass through untouched, which is what lets M6's compaction reuse it with the
  opposite retention rule.
- **Reconciliation (`ReconcilingCursor`):** newest version per user key wins, tombstones hide the
  key, the upper bound ends the scan. Source order is never consulted — precedence rides entirely in
  the sequence number.
- **`Shale.scan` rebuilt on it:** the lower bound is now a `seek` into every source rather than a
  filter applied after decoding everything. `get` deliberately keeps its separate per-source
  `ceiling` probe and now documents why.
- **Ownership (`ReferenceCounted`, N6):** the cursor retains every SSTable it can read from and
  releases them on `close()`. `Cursor.close()` is now load-bearing.
- **Removed:** `Memtable.entries()`, `SSTableReader.entries()`, `Shale.ListCursor` — the
  materialising path and the cursor that existed only to return it. Flush and the recovery sequence
  scan moved to the iterator as a consequence, so a flush no longer copies the whole memtable into a
  list first.

## Exit criteria (met)

- `./gradlew build` green; `crashTest` green; `shale-core` still has zero runtime deps (N1).
- **159 test cases** across unit, property, model, concurrency, and crash tiers (98 at M3).
- **No format change:** M4 writes no new bytes, so no version bump, no golden file, no
  `Format-Change:` trailer. The public API *did* change, which is what put ADR-0011 ahead of the
  code (`commits.md` §5.2).
- **Cost, not answers:** the engine model harness drives the real engine against a `TreeMap` oracle
  through constant flushes and restarts and passed unchanged; the crash test still recovers a clean
  prefix from a WAL truncated at every offset.
- **Two tests were verified by mutation** rather than trusted: the seek property caught a
  deliberately injected one-entry off-by-one, and the copy-out test failed when the copy was removed
  (mutating `cursor.value()` rewrote the memtable in place).

## What changed from the plan

- **The "bounded work" invariant is asserted by counting cursor advances, not block reads.**
  `SSTableReader` has no read counter and adding production instrumentation for one assertion was
  the wrong trade; the claim — advances scale with the result, not the source — is directly testable
  at the iterator seam. Block-level accounting arrives free at M7 with the block cache's hit/miss
  counters.
- **`MergingIterator.close()` does not aggregate child-close failures.** The first draft collected
  them into suppressed exceptions; checkstyle's `IllegalCatch` rejected the blanket catch and was
  right to — no child's close can fail, so the machinery guarded an impossible case while obscuring
  which child had broken.
- **`ReferenceCounted` was not in the plan.** It fell out of the ownership decision: the cursor
  cannot take `SSTableReader` directly without a package cycle, and handing it only a release action
  would have split the retain/release pair across two classes.

## Notes for the next milestone

M5 adds the **manifest**: version edits, atomic version install, `CURRENT`, and the comparator-name
check — retiring the directory-scan discovery and the recovery-flush this milestone still relies on.
It also completes the file lifecycle with **delete-on-zero**, at which point the cursor's pin stops
being hygiene and becomes correctness: it is what keeps a compaction from deleting a table underneath
a running scan. M7's snapshot reads attach a fourth rule to `ReconcilingCursor`; M6's compaction
reuses `MergingIterator` unchanged.
