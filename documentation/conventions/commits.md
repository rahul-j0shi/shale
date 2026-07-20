# Commits

The git history is part of the portfolio. Someone evaluating this project will read
`git log` before they read the code, and a history of "wip", "fix stuff", and "更新"
undoes a lot of careful engineering. Treat each commit as a small piece of writing.

---

## 1. Format

Conventional Commits, with project-specific scopes and trailers.

```
<type>(<scope>): <subject>

<body — why, not what>

<trailers>
```

### Subject line
- Imperative mood: "add", not "added" or "adds". It completes the sentence
  *"If applied, this commit will ___"*.
- No trailing period. Lowercase after the colon.
- Hard limit 72 characters, aim for 50.
- Describe the change, not the file: `fix(wal): reject records with truncated payload`,
  not `fix(wal): update WalReader.java`.

### Types

| Type | Use for |
|---|---|
| `feat` | New capability |
| `fix` | Corrected behaviour (must reference the test that now passes) |
| `perf` | Optimisation (**must** cite a benchmark; see §4) |
| `refactor` | Behaviour-preserving restructure |
| `test` | Tests only |
| `docs` | Documentation, ADRs, Javadoc-only changes |
| `build` | Gradle, toolchain, CI |
| `style` | Formatting only, zero semantic change |
| `chore` | Housekeeping that fits nothing above |

### Scopes

Scopes mirror packages and modules, so `git log --grep 'compaction'` is useful:

`wal`, `memtable`, `sstable`, `compaction`, `filter`, `manifest`, `iterator`, `cache`,
`mvcc`, `recovery`, `api`, `bench`, `raft`, `region`, `rpc`, `pd`, `docs`, `build`.

Use one scope. If a change genuinely spans three scopes it is probably three commits.

### Body

Optional for trivial changes, expected otherwise. Wrap at 72 columns.

The body answers **why**, not what — the diff already says what. Good bodies cover:
the problem, the approach, alternatives rejected and why, and any consequence a future
reader would be surprised by. Two sentences beats none; four beats twelve.

---

## 2. Trailers

Trailers are how this repo stays navigable. They are grepable, and CI checks them.

```
Milestone: M6
Format-Change: sstable v2 — adds filter block handle to footer
Reversible: no — on-disk footer layout
Benchmark: readrandom 412k → 596k ops/s (JMH, 3 forks, see bench/0031)
ADR: 0007
Refs: leveldb table_format.md; Database Internals ch. 7
Co-Authored-By: Claude <noreply@anthropic.com>
```

| Trailer | Required when |
|---|---|
| `Milestone:` | Always, on `feat` and `fix`. Ties work to `documentation/roadmap/`. |
| `Format-Change:` | **Mandatory** for any change to bytes written to disk or the wire. Omitting it is the single most serious process violation in this repo. |
| `Reversible:` | Any change to public API, on-disk format, key encoding, or the module graph. State `yes`/`no` and why. |
| `Benchmark:` | Mandatory on every `perf` commit. |
| `ADR:` | When the commit implements a decision record. |
| `Refs:` | When implementing a paper or mirroring a reference implementation. |

Install the template so these are in front of you:

```bash
git config commit.template .gitmessage
```

---

## 3. Granularity

**One logical change per commit.** Each commit compiles and passes `./gradlew build`
on its own — `git bisect` is a primary debugging tool for a storage engine, and it is
worthless across commits that do not build.

Specifically:
- Formatting and renames go in their own `style`/`refactor` commit, never mixed with
  behaviour. A diff where three real lines hide among four hundred reformatted ones is
  unreviewable.
- A bug fix commit contains the failing test *and* the fix. The test must fail without
  the fix; say so in the body.
- Generated or vendored files are separate commits.

**Never commit:** commented-out code, `TODO` without an owner and a reason, debug
prints, `.orig`/`.rej` files, IDE directories, or benchmark output.

---

## 4. The `perf` rule

A `perf` commit without a `Benchmark:` trailer will be rejected. The trailer must give
before/after numbers, the harness, and enough configuration to reproduce.

This exists because a storage engine offers infinite plausible-looking optimisations,
and on the JVM a large fraction of them are neutral or negative once JIT, escape
analysis, and the allocator are accounted for. If it was worth the added complexity,
it was worth measuring. If it was not worth measuring, revert it.

---

## 5. Branches

```
m06/leveled-compaction        milestone work
fix/wal-torn-tail             bug fix
spike/art-memtable            throwaway experiment, never merged
adr/0007-compaction-strategy  decision record
```

Lowercase, hyphenated, no personal names, no ticket numbers (there is no tracker —
the roadmap is the tracker).

`main` always builds and always passes the full non-soak suite. Milestone branches may
be long-lived; rebase them onto `main` rather than merging `main` into them, so the
history reads linearly.

Tag milestone completions: `m06-compaction`, and write a short release note in
`documentation/roadmap/`. Those tags are the checkpoints you will demo from.

---

## 6. Examples

Good:

```
feat(compaction): pick overlapping L0 files by key range, not file count

Previously the picker took the oldest N L0 files, which meant a compaction
could rewrite files whose key ranges did not overlap the target L1 files —
pure write amplification for no benefit.

Now the picker expands the L0 selection to the transitive closure of files
overlapping the chosen range, matching LevelDB's PickCompaction. On the
fillrandom workload this cuts bytes-written-per-key by roughly a third.

Milestone: M6
Refs: leveldb db/version_set.cc PickCompaction
Benchmark: fillrandom WA 11.2x → 7.4x (bench/0024)
```

```
fix(wal): fail recovery on torn record instead of silent truncation

A crash mid-fsync can leave a partial record at the tail of the active
segment. Recovery treated a short read as clean EOF and continued, which
silently dropped any complete record written after it in the same batch.

Now a partial record at any position other than the exact tail raises
CorruptionException; a partial record at the tail is reported to the
RecoveryPolicy, which decides. Added a crash test that truncates the
segment at every byte offset and asserts no acknowledged write is lost.

Milestone: M5
Reversible: yes — recovery policy only, no format change
```

Bad, and why:

```
fix: fixed bug                          no scope, no subject, no body
update SSTableWriter                    not imperative, describes the file
feat(sstable): add filter block         missing Format-Change trailer
perf(memtable): faster skiplist         missing Benchmark trailer
refactor: cleanup + fix compaction bug  two changes, one commit
```
