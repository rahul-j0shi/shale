# 0001. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-07-20
- **Milestone:** M0
- **Reversible:** yes — we could stop writing ADRs at any time; nothing depends on them mechanically.

## Context

This is a study-and-portfolio project (`documentation/roadmap/`). Its value is not only the
running system but the demonstrated understanding behind it. Many of the decisions — the
internal-key encoding, the on-disk formats, the comparator identity, the consensus read
strategy — are expensive to make and expensive to revisit, and six months later (or during
an interview about the project) the answer to "why is it built this way?" needs to be
written down rather than reconstructed from memory or `git blame`.

## Options considered

### Option A — No formal record
Rely on commit messages and code comments. Cheapest. But a commit message explains one
change; it does not capture the options rejected or the forces in tension, which is exactly
the part that has value later. The reasoning evaporates.

### Option B — A design doc per milestone only
Milestone docs (which the roadmap already implies) capture scope but not point decisions,
and they are edited over time, so the record of a *superseded* decision is lost.

### Option C — Architecture Decision Records
One immutable file per significant decision, numbered, never edited once accepted, superseded
only by a new record. The history of wrong turns is preserved, which is the most valuable
part.

## Decision

Adopt ADRs as described in `documentation/adr/README.md`: numbered `NNNN-kebab-title.md`
files from `0000-template.md`, `Proposed` on a branch, `Accepted` when merged, never edited
after acceptance, superseded only by a newer ADR. An index table in the ADR `README.md` is
kept current in the same commit that adds an ADR.

## Rationale

The immutability rule is the point: an ADR written after the fact is a rationalisation, and
an ADR edited to match the current design erases the record it exists to keep. Requiring the
ADR *before* the code on hard-to-reverse work forces the thinking to happen when it is
cheap. This is chosen for its learning and communication value, explicitly (see
[[0002-hand-write-core-mechanisms]]).

## Consequences

**Positive:** durable, greppable rationale; a visible decision trail for reviewers.

**Negative:** a small standing cost per significant decision, and the discipline to write the
ADR before the implementation rather than after.

**Neutral:** the ADR set grows monotonically; superseded records stay in the tree.

**If we need to reverse this:** stop writing ADRs. Existing records remain as history. No
migration required.

## References

- `documentation/adr/README.md` — process and index
- Michael Nygard, "Documenting Architecture Decisions" (2011)
