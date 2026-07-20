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

## Index

| # | Title | Status | Reversible |
|---|---|---|---|
| 0001 | Record architecture decisions | Accepted | yes |
| 0002 | Hand-write all core storage mechanisms | Accepted | no |
| 0003 | Single repository, four Gradle modules | Accepted | yes |
| 0004 | Internal key encoding | Proposed | **no** |
| 0005 | Little-endian on-disk integers | Proposed | **no** |

Keep this table current in the same commit that adds the ADR.
