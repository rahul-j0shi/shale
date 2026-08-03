# Documentation

The codebase is meant to **read like a book**. Someone who has never seen it should be able to
follow the engine end to end — *why* each mechanism exists, *how* it works, and *where* it deviates
from the literature — from the docs and comments alone. A correct-but-unexplained mechanism only half
satisfies this project's prime directive ("the implementation *is* the product"). This file is the
rule that keeps it that way; it is itself written to the standard it describes.

---

## 1. Every surface has one job

Documentation lives on a fixed set of surfaces. Put each thing in the one place that owns it; never
split a topic across two, never invent a parallel system.

| Surface | Owns | Written |
|---|---|---|
| `documentation/adr/NNNN-*.md` | a **decision** — the forces, the options, why one won, what it costs | before the code it decides |
| `<package>/format.md` | an **exact byte layout** — table, worked hex example, golden-pinned CRCs | before the encoder (on-disk-formats.md §1) |
| `documentation/architecture/mN-*.md` | the **as-built explainer** — how the shipped code actually works, with diagrams | alongside the code, same milestone |
| `documentation/roadmap/` | **intent** — the charter, the plan for a milestone, and its release note | plan before, release note after |
| `package-info.java` | a **package's** purpose, threading model, and entry point | with the package |
| Javadoc + `// comments` | a **type's** contract and its source citation (N9) | with the type |

The [documentation map](../README.md) ties these together for a reader arriving cold.

## 2. Teach, don't just record

- **Why before what.** The diff already says what changed; prose says *why it is built this way* and
  *what it costs*. A paragraph that only restates the code earns its deletion.
- **Name the tradeoff.** Most decisions here are a position on read/write/space amplification, or a
  deliberate choice of the more instructive implementation over the faster one. Say which, plainly —
  "we chose this for learning value" is a legitimate and required sentence when it is true.
- **Match the literature (N9, N10).** Use the canonical term and cite the paper, book chapter, or
  reference implementation the mechanism follows, including where and why we diverge.
- **Worked examples beat description.** For anything with a byte layout or a non-obvious flow, show a
  small real instance — annotated hex, or a two-key walkthrough — not just a field table.

## 3. Draw the picture when a picture is clearer

A control-flow, an ownership graph, a state machine, or a data layout is usually clearer as a
diagram than as a paragraph. Use one when it earns its place — and not when a sentence would do.

- Diagrams are **Mermaid**, inline in the Markdown, so they render on GitHub and stay in version
  control (no binary image blobs).
- Every Mermaid block **must render** before it is committed. Validate with the pinned CLI:
  `npx @mermaid-js/mermaid-cli@11 -i diagram.mmd -o out.svg`. A diagram that does not parse is worse
  than no diagram.
- Reserved-word node ids (`call`, `end`, `class`, …) break the parser — pick other ids.
- Label edges and nodes with the real identifiers from the code, so the picture and the source agree.

## 4. Keep it honest and current

- Docs describe **what is true now**. When behaviour changes, its doc changes in the *same commit* —
  a stale explanation is a bug. The `architecture/` docs in particular track the code, not the plan.
- **Link liberally.** Cross-reference the ADR, the `format.md`, the release note, and the source
  (relative links, `file:line` where useful). A reader should never have to guess where the next
  piece lives.
- Golden files and pinned CRCs are **never** edited to make prose match; if they disagree, the code
  changed — investigate (on-disk-formats.md §4).

## 5. Don't overdo it

Readable is the goal; **volume is not**. The shortest thing that fully teaches the idea wins.

- Two good sentences beat none; four beat twelve. Cut every sentence that neither explains a *why*
  nor prevents a real misunderstanding.
- One diagram that carries the idea beats three that restate each other.
- Do not write a README, summary, or overview that nobody will read. Every doc has a reader and a
  question it answers; if you cannot name them, do not write it.

---

## 6. The milestone checklist

A milestone is not done until, in addition to green tests:

- [ ] the **decision** is in an `adr/` record (accepted before the code landed),
- [ ] any new byte layout has a **`format.md`** with a worked example and a golden file,
- [ ] the **`architecture/mN-*.md`** as-built explainer exists, with rendered diagrams,
- [ ] the **release note** and the **README status** are updated,
- [ ] new `package-info` / Javadoc carry their **N9 citations**, and the **glossary** (naming.md §1)
      has any new term.
