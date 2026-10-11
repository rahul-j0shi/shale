# Architecture Decision Records

An ADR records a decision that was expensive to make and would be expensive to revisit.
It exists so that six months later — or during an interview about this project — the
answer to "why is it built this way?" is written down rather than reconstructed.

## When to write one

Required for:
- Any change to an on-disk or on-wire format (`on-disk-formats.md` §3).
- Any change to the internal key encoding, comparator contract, or sequence numbering.
- Adding a dependency, or adding to the allowlist (`java-style.md` §1).
- Choosing between competing designs: compaction strategy, memtable structure, cache
  eviction policy, consensus read strategy, partitioning scheme.
- Any decision the roadmap marks **hard to reverse**.
- Adding a new module or changing the module dependency graph.
- Any exception to a rule in `documentation/conventions/`.

Not required for: ordinary implementation choices, anything you would happily change
next week, or anything already decided by an existing ADR.

## Process

1. Copy `0000-template.md` to `NNNN-kebab-title.md` with the next free number.
2. Open it as `Proposed` on a branch named `adr/NNNN-kebab-title`.
3. Write it before writing the code. The point is to think, not to document a fait
   accompli — an ADR written after the fact is a rationalisation.
4. Merge as `Accepted` when the decision is made.
5. Never edit or delete an accepted ADR. To change a decision, write a new one and set
   the old one to `Superseded by NNNN`. The history of wrong turns is the most valuable
   part of the record.
6. When a later ADR changes only *part* of a decision, the one edit allowed to the old
   record is its status line: `Accepted — amended by ADR-NNNN: <what changed>`. The body
   stays as written, wrong turns included.

## Index

| # | Title | Status | Reversible |
|---|---|---|---|
| 0001 | Record architecture decisions | Accepted | yes |
| 0002 | Hand-write all core storage mechanisms | Accepted | no |
| 0004 | Internal key encoding | Accepted | **no** |
| 0005 | Little-endian fixed-width integers | Accepted | **no** |
| 0006 | Define the StorageBackend SPI | Accepted | **no** |
| 0007 | Block-structured log format for the WAL | Accepted, amended by 0024 | **no** |
| 0008 | Durability modes, group commit, and an injected clock | Accepted, qualified by 0024, amended by 0012 | partly |
| 0009 | Skiplist memtable: single writer, lock-free readers, on-heap | Accepted | partly |
| 0010 | SSTable on-disk format: a LevelDB block table, uncompressed | Accepted, amended by 0024 | **no** |
| 0011 | Read through one InternalIterator seam, merged by a heap, pinned by the cursor | Accepted | partly |
| 0012 | Make a manifest the authority for live files, and recover through it | Accepted | **no** |
| 0013 | ShaleDB: a see-through database — purpose, scope, modules and dependencies | Accepted | yes |
| 0024 | Protect the log framing, and let recovery decide only on verified bytes | Accepted | **no** |

**0003** (the original module layout) was withdrawn when ADR-0013 replaced it. **0012** records
the M5 manifest and recovery decision ([plan](../roadmap/m5-manifest-and-recovery.md)).
**0014–0023** are reserved for the milestones after it;
the [completion plan](../roadmap/completion-plan.md#6-the-decision-records-each-milestone-writes)
lists which milestone writes which. **0024** (M4.5) was accepted ahead of its milestone, in the
planning change that responded to the October 2026 engineering review, because both the
[M4.5 plan](../roadmap/m4-5-integrity-boundaries.md) and M5's manifest framing depend on it.

Keep this table current in the same commit that adds the ADR.
