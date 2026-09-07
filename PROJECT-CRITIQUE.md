# Shale / Flotilla — validated critique and completion plan

**Revision 2 (2026-09-07).** Revision 1 was an intern's review. This revision re-checked every
claim in it against the repository, corrects the ones that were wrong, keeps the ones that
survived, and adds the thing revision 1 did not have: **a bounded plan all the way to
"finished", written for the stated goal — depth you can defend in an interview.**

**Method and its limits.** Every finding below cites `path:line` and was read in this session.
I did **not** run `./gradlew build` or `crashTest` in this session (revision 1 says it did and
reported green; I neither confirm nor dispute that). I did **not** contact GitHub — publication
claims rest on local tracking refs and are marked as such. I found **no API key** in this
session's input; revision 1's key-rotation warning is unverifiable here. Treat it as: if you
ever pasted a live key anywhere, rotate it; nothing in this repo contains one.

---

## 1. Verdict

**The project is right. Do not pivot. Narrow it and finish it.**

A hand-written LSM engine taken to Raft is one of the few solo projects that survives an
expert interviewer. The sequencing (M0→M4) is textbook-correct, the discipline (ADR-first,
golden files, refcounting, bit-flip tests, citations) is above most professional codebases,
and the engine code is genuinely good.

Three things stand between "good private repo" and "the thing that gets you the interview":

1. **It is unpublished.** Local tracking refs say `origin/main` is 11 commits behind local
   `main` (`git rev-list --count origin/main..main` → 11; `origin/main` = `e80eedb`, the doc
   restructure *before* M4). Tags `m0-skeleton`…`m4-merge` exist locally; whether they are on
   the remote is unverified here. Run `git fetch --tags` first, then push. Nothing else on this
   list buys as much for as little.
2. **Four small doc/code contradictions.** Confirmed below. Each is trivial; together they
   teach a reader to distrust every other claim.
3. **There is no measurement.** The charter's thesis is the RUM conjecture — read/write/space
   amplification, measured. Nothing in the repo measures anything. `perf` commits are required
   to carry a `Benchmark:` trailer (`commits.md:114-122`) and there is no harness to produce one.
   This is the single biggest gap between the project's stated thesis and its artifacts, and
   revision 1 under-weighted it.

---

## 2. Revision 1's findings, re-checked

| # | Revision 1 claim | Status | Evidence |
|---|---|---|---|
| 1 | CI is asserted but does not exist | **Confirmed** | No `.github/`. Claims at `java-style.md:18`, `adr/0002:50`, `commits.md:65`, `config/checkstyle/checkstyle.xml:158` |
| 2 | `origin/main` 11 behind, M4 invisible | **Confirmed (locally)** | `origin/main` = `e80eedb`; 11 commits ahead locally. Remote not queried |
| 3 | fsync + whole flush run inside the global write lock | **Confirmed** | `Shale.java:202,212` `synchronized (writeLock)` → `append` → `wal.append(..., durability)`; `switchAndFlush` (`Shale.java:235-254`) writes the SSTable, fsyncs, renames and deletes the segment under the same lock |
| 4 | This forecloses ADR-0008's group-commit mechanism | **Confirmed, and this is the real finding** | ADR-0008 option B2 (`0008:29-31`) requires "append under a *short* lock, then one leader forces" — impossible while the force happens inside the lock |
| 5 | The lock-across-I/O deviation is "not acknowledged anywhere" | **FALSE** | ADR-0010 accepts synchronous flush explicitly (`0010:87-111`); `architecture/m3-sstable-and-flush.md:56-71` draws the write lock spanning the flush. What is genuinely unreconciled is the *blanket* rule in `concurrency-and-resources.md:47` (§2) ("never hold a lock across I/O"), which the code contradicts without the doc noting the exception |
| 6 | Crash test has no lower bound; compares keys only | **Confirmed** | `ShaleCrashTest.java:44-46` asserts only `size <= 5` and `recovered == written.subList(0, size)`. An engine that recovered nothing every time passes. Values are never compared (`liveKeys` reads `cursor.key()` only). The class Javadoc claims "any write whose record is fully on disk … must survive" — nothing tests that |
| 7 | No engine-level concurrency test behind `@ThreadSafe` | **Confirmed** | Only `SkiplistMemtableConcurrencyTest`. No test exercises concurrent `put`/`get`/`scan` against `ReadView` publication + SSTable refcounts |
| 8 | An assertion was weakened to keep the build green (N7 violation) | **Substantially FALSE** | The strong assertion did not vanish — it *moved*, in the same commit (`fc15dee`), to `ShaleFlushTest.java:35`: `assertThat(files(".wal")).as("only the active segment remains").hasSize(1)`, which is strictly better than the old `hasSizeGreaterThan(1)`. What remains at `ShaleMemtableSwitchTest.java:37` is a vacuous leftover with a comment pointing at its new home. Delete the leftover; it is untidiness, not an N7 breach, and revision 1 filing it as "in the history for anyone to find" is unfair |
| 9 | `shale-bench` has "no JMH dependency even declared"; "JMH ✓ wired" is false | **FALSE** | `shale-bench/build.gradle.kts` applies `me.champeau.jmh 0.7.2`, pins `libs.versions.jmh`, and declares `jmh(project(":shale-core"))`. The plugin *is* wired; the task exists. What is missing is **benchmark source**. The README claim is defensible; the honest wording is "harness wired, no benchmarks written" |
| 10 | `FaultyFileSystem` / soak tier are "described but don't exist" → delete the claims | **Misframed** | `testing.md` is a normative spec, not an as-built report: the same document also requires compaction and snapshot coverage that cannot exist before M6/M7. Both `README.md:243` and `m0-skeleton-and-interfaces.md:55` already date `FaultyFileSystem` to M5. Fix by **dating each tier**, not by deleting the spec. The `soakTest` task existing with zero `@Tag("soak")` tests (`build.gradle.kts:83`) is a fair, minor hit |
| 11 | `Durability.GROUP` "lies" | **Partly** | ADR-0008 marks the mechanism "freely revisable" (`0008:6`) and `m1-release-note.md:39-41` states plainly that GROUP forces like SYNC. Only the enum's own Javadoc over-claims: `Durability.java:21` "Batched with concurrent writers into a shared fsync". Also stale: `Durability.java:9-11` still says "At M0 the only `StorageBackend` is in-memory" |
| 12 | Every reopen writes a new SSTable; `get()` doesn't pin; crash between rename and delete duplicates data | **Confirmed** | `switchAndFlush` (`Shale.java:250-254`): `Files.move` then `Files.delete(frozenSegment)`; a crash between them leaves both, and recovery flushes the replayed segment into a second table. `get` (`Shale.java:288-300`) takes a bare volatile read with no `retain()` |
| 13 | Demote the B+Tree capstone (M8) — "no interviewer will ask about it" | **Disagree — see §5** | The LSM-vs-B+Tree comparison is the stated payoff of the SPI in `adr/0006:10-15` and of the whole hand-writing rule in `adr/0002:82-84`. Dropping it is not free pruning; it deletes the project's measured thesis |
| 14 | Docs are 2.3× the code, therefore too voluminous | **Wrong metric, right instinct** | Ratio is not a defect for a study project. The actual defect is specific: `m0-skeleton-and-interfaces.md` is a 2,000-line task script with the source pasted in, while M1–M4 plans are 71–90 lines. Archive that one file |
| 15 | The charter reads as AI output; rewrite in your voice | **Fair, and narrow** | `shale-roadmap.md:13,200,228` contain "as stated verbatim by…", the jqwik "anti-AI clause" aside, and "(some were gathered via a research subagent)". It is a research memo filed as a charter |

### Findings revision 1 missed

- **ADR-0011 and the M4 doc assert that exact internal-key ties are impossible** — but the
  crash-between-rename-and-delete path above produces two SSTables containing identical
  `(user key, sequence)` pairs. `MergingIterator`'s documented tie-break ("unreachable, sequence
  numbers are unique", `m4-merge-iterator.md` plan §5) is therefore reachable today. This is a
  correctness-adjacent documentation error, and it is precisely what M5 must resolve.
- **`on-disk-formats.md:148` refers to `flotilla-rpc/src/main/proto/`** — a fifth module that
  does not exist and that ADR-0003 does not authorise.
- **`errors-and-logging.md:60` mandates SLF4J as the facade** while `shale-core` has zero runtime
  dependencies and `java-style.md:24-26` permits SLF4J only in `flotilla-*`. The whole logging
  section is unimplemented *and* unimplementable in `shale-core` without an allowlist ADR.
  Nothing in the engine logs at all today.
- **`adr/0002:4` has `Date: (fill in)`** — confirmed.
- **Stale cross-references:** `architecture/m1-wal-and-map.md:110-111` says the manifest arrives
  at M3 (it is M5); `internal/coding/package-info.java:2-3` still describes varints as arriving
  "at M1" in present-M4 code.
- **The crash model is not stated.** `concurrency-and-resources.md:151-153` treats killing the
  process as evidence of power-loss survival. It is not: `kill -9` proves the *process*-crash
  model only; power loss additionally involves the device write cache. Say which model you test
  (process crash) and which you assert by construction (power loss, via `force()`).

---

## 3. Where the project actually is

A correct, well-documented, **single-writer** LSM **write and read path** with no manifest, no
compaction, no filters, no cache, no snapshots, no benchmarks, and no cluster. That is an
excellent M4. A database engineer would not yet call it "an LSM engine" — that word starts to
apply at M6, when compaction closes the loop and the file count stops growing without bound.

Real today: WAL (block log, CRC32C, torn-tail policy) · lock-free skiplist memtable · LevelDB
block-table SSTable with golden + bit-flip tests · flush with fsync-before-WAL-delete ordering ·
heap merge iterator with reconciliation and cursor-held refcounts · a `TreeMap` model harness
that restarts mid-sequence.

Not real: manifest/CURRENT · compaction (the engine accumulates one SSTable per flush forever) ·
bloom filters · block/table cache · snapshots and write batches · group commit · background
anything · benchmarks · `flotilla-*` (empty build shells).

**What to protect:** `EngineModelTest` (5,000 ops, 256-byte buffer, restart every 700 ops,
diffed against a `TreeMap`) is the strongest asset in the repo. The SSTable package is the best
code. `ReconcilingCursor`'s retain/release discipline is correct. ADRs 0007/0009/0010/0011 list
real rejected alternatives — that honesty is the rarest thing here.

---

## 4. What "finished" means

Stop treating this as one 12-milestone march. It is **three products**, each independently
defensible on a resume, each with a real stopping point. Ship them in order; you may stop after
any of them and still have something strong.

| Product | Contents | Claim it earns |
|---|---|---|
| **P1 — Shale 1.0, a real engine** | M5 manifest · M5.5 concurrent write path · M6 compaction · M7 filters/cache/MVCC · a benchmark harness · deterministic fault injection | "I wrote a crash-consistent LSM engine and measured its amplification." |
| **P2 — The RUM capstone** | M8 COW B+Tree as a second `StorageBackend` + YCSB/db_bench across both | "I built both access methods and measured the tradeoff the literature asserts." |
| **P3 — Flotilla** | M9 single Raft group + linearizability check · M10 multi-Raft sharding | "My engine is the replicated state machine behind a Raft I wrote." |

**P1 is non-negotiable.** P2 and P3 are both worth doing and you will probably only do one —
§5 tells you how to choose. **M11 (Percolator) becomes an explicit non-goal**, kept as one line
in the charter, removed from the README diagrams. A roadmap that ends where the work ends reads
as judgement; one that ends at a milestone you will never reach reads as fantasy.

---

## 5. The B+Tree question — where I disagree with revision 1

Revision 1 says demote M8 because "no interviewer will ask about a B+Tree". That inverts the
argument. Interviewers ask about B-trees *constantly* — they are the other half of storage. The
weakness of M8 is not the topic; it is that a second full backend is a second full project.

The distinction that matters: **the comparison is the deliverable, not the tree.**

- `adr/0006:10-15` justifies the `StorageBackend` SPI *specifically* as the seam that makes the
  comparison possible. `adr/0002:82-84` makes the same argument for hand-writing. If you delete
  M8, those two ADRs become decisions taken for a purpose you abandoned — and an interviewer who
  reads them will ask about exactly that.
- So do not delete it. **Bound it.** Build the smallest honest COW B+Tree: fixed-size pages,
  copy-on-write page shadowing, a meta page pair flipped on commit, no in-place update, no free
  list beyond a simple one, no concurrency beyond single-writer/many-readers. Two to four weeks.
  Ship it as `shale-btree` behind the existing SPI and run the same harness across both.
- If it overruns three weeks, ship the *benchmark* half and write a one-page "what I would have
  measured" note. A measured LSM alone, graphed across bits-per-key and compaction policy, is
  already the RUM story; the B+Tree strengthens it, it is not load-bearing.

**Choosing between P2 and P3.** P3 (Raft) is the bigger resume headline and the more common
interview topic; P2 is cheaper, finishes what the charter started, and is nearly unique among
solo projects. Decide by what you want to be hired *for*: infra/distributed → P3; storage/database
internals → P2. Do not start P3 before P1 is done, and do not keep empty `flotilla-*` modules in
the repo while you decide — an empty module reads as vapourware. Delete them; recreate at M9.

---

## 6. The plan, milestone by milestone

Each entry: **goal · ADRs first · format impact · acceptance gates (tests that must exist, not
prose) · the interview artifact it creates.** Sizes are focused weeks, not calendar weeks.

### W0 — Publication and honesty pass (one weekend, do it first)

Not a milestone; a prerequisite for everything else being visible.

1. `git fetch --tags`, verify what the remote actually has, then push `main` and all five tags.
   Delete or push the stray branches (`artful-dingo`, `eager-koala`, `chore/agent-harness`).
2. `.github/workflows/build.yml`: `./gradlew build crashTest` on Temurin JDK 25 (do not depend
   on a vendored `.tools/` JDK in CI). Badge in the README. This makes the four CI assertions
   true rather than deleting them.
3. One `docs:` commit fixing the contradictions: `Durability.GROUP` Javadoc → "reserved;
   currently identical to SYNC — batching mechanism deferred (ADR-0008 §B2, scheduled M5.5)";
   drop the stale "At M0…" sentence; date ADR-0002; date each tier in `testing.md` with the
   milestone that implements it; fix `flotilla-rpc` → `flotilla-server`; fix the M3-vs-M5
   manifest reference and the varints package-info; delete the vacuous
   `ShaleMemtableSwitchTest:37` assertion; add one paragraph to
   `concurrency-and-resources.md §2` naming the flush-under-lock exception, why it is accepted
   at M3–M5, and when it is retired (M5.5).
4. Either implement one `@Tag("soak")` test or remove the `soakTest` task until M6.
5. Archive `documentation/roadmap/m0-skeleton-and-interfaces.md` to `roadmap/archive/`. Cap
   future plans at ~150 lines, as M1–M4 already are.
6. Rewrite the README to ≤ 80 lines: one sentence of what, an honest **built / not built**
   table, one diagram, how to run, links. Move the four large Mermaid diagrams to
   `documentation/architecture/README.md`, where they already have a home.

### M5 — Manifest, version set, and file lifecycle (2–3 weeks)

**Goal.** The database's file set becomes an explicit, atomically-installed, crash-recoverable
fact instead of a directory scan.

**ADR first:** ADR-0012 manifest + version-edit log format (`Reversible: no` — new on-disk
format ⇒ `Format-Change:` trailer, `format.md`, golden file).

**Scope.** `VersionEdit` (files added/removed, next file number, last sequence, comparator
name) · a manifest log reusing the WAL block-log codec · `CURRENT` written via temp+rename ·
`Version` as the immutable live-file set, reference-counted, with **delete-on-zero** ·
`get()` and `scan()` both pinning the version they read (fixes §2 finding 12) · recovery =
read `CURRENT` → replay manifest → replay WAL newer than the last flush · comparator-name
mismatch refuses to open · **retire the recovery-flush** (a reopen must stop creating a table).

**Acceptance gates.**
- Reopening 1,000 times over a one-record database creates **zero** new SSTables.
- Opening with a different comparator name throws, with both names in the message.
- Crash injected between `Files.move` and the WAL delete → reopen yields exactly the
  acknowledged writes and **no duplicate `(user key, sequence)` pair exists in any two live
  tables**. This is the named test for the ADR-0011 tie claim; either the invariant holds or
  ADR-0011's tie-break note gets corrected in the same commit.
- A cursor open across a version install keeps reading; the superseded tables are deleted only
  after it closes (assert file-existence, not just refcounts).
- Manifest truncated at every byte offset → opens cleanly or throws `CorruptionException`,
  never both partly.

**Interview artifact.** "How does your database know which files it consists of after a crash?"
— the answer most toy engines cannot give.

### M5.5 — Concurrent write path (1–2 weeks) — *new, and the highest-leverage refactor*

**Goal.** Retire the rule/code conflict and unblock everything background.

**ADR first:** ADR-0013 superseding the *mechanism* half of ADR-0008: writers append under a
short lock, release it, then one leader issues a single `force()` covering every record appended
before it (B2 as originally chosen); flush moves to an injected single-thread executor so tests
stay deterministic (N8 — no sleeps, no wall-clock waits).

**Scope.** Short critical section (WAL append + sequence assignment + memtable insert) · fsync
outside it · leader/follower group commit, making `Durability.GROUP` finally mean what its
Javadoc says · background flush with a bounded immutable-memtable queue and a **write stall**
when it fills · `concurrency-and-resources.md:47` restored to a rule the code obeys.

**Acceptance gates.**
- N concurrent `SYNC` writers produce fewer `force()` calls than writes (assert via the metrics
  spy) and every write is still durable at ack.
- The engine-level concurrency test (see §7) passes 50 seeded runs in the fast tier.
- A flush in progress does not block a `put` (assert by ordering, via the injected executor —
  not by timing).

**Interview artifact.** Group commit is a top-five storage interview question, and "I moved the
fsync out of the lock and measured the difference" is the answer with a number in it.

### M6 — Compaction (4–6 weeks; the hardest single milestone before Raft)

**ADR first:** ADR-0014 compaction strategy. Do **size-tiered first, then leveled**, and keep
both selectable — the comparison between them *is* the RUM demonstration, and having both is
worth far more than having the "better" one.

**Scope.** Level scoring and trigger · file picking with overlap expansion · compaction reusing
`MergingIterator` unchanged with the opposite retention rule · tombstone retention to the
bottom level (the resurrection problem) · trivial move · subcompactions · write-stall
backpressure · **WA/RA/SA counters** wired at the same time, not later.

**Acceptance gates.**
- Continuous `fillrandom` for 10 minutes: file count and level sizes stay bounded, no permanent
  stall, throughput does not trend to zero.
- Property test: compaction preserves the newest visible version of every key and drops nothing
  reachable by a live snapshot.
- A tombstone that has not reached the bottom level never resurrects its value — asserted by
  forcing a partial compaction, not by luck.
- Soak tier goes live: one hour, mixed workload with restarts, model-checked, with file
  descriptors and heap asserted flat.

**Interview artifact.** The measured WA/SA curve for tiered vs leveled on your own engine. This
is the single most impressive graph you can put in the README.

### M7 — Filters, cache, MVCC (3–4 weeks)

**Format change** (filter block handle in the metaindex ⇒ version bump, golden, trailer).

**Scope.** Hand-written bloom filter, uniform bits-per-key first, then Monkey-style per-level
allocation as a measured variant · block cache (start LRU; the interesting part is the eviction
policy comparison, not the plumbing) · table cache · sequence-number snapshots (`ReconcilingCursor`
gains its fourth rule) · atomic `WriteBatch`.

**Acceptance gates.** Bloom never returns a false negative (property, exhaustive on small sets) ·
measured FPR within 15% of theory at the configured bits-per-key · a snapshot taken before N
writes sees exactly the pre-N state after those writes and after a compaction · `readrandom`
improves measurably with filters on, and the number goes in the release note.

**Interview artifact.** "Why does allocating bloom bits uniformly across levels waste memory?" —
answered with your own measurement.

### M8 — The RUM capstone (2–4 weeks, bounded — see §5)

COW B+Tree as a second `StorageBackend`; YCSB A–F and db_bench-style workloads across both
backends; one write-up with graphs of read/write/space amplification. Hard stop at four weeks:
ship the harness and the LSM numbers regardless.

### M9 — Single Raft group (6–10 weeks)

**Prerequisite:** P1 done and pushed. Recreate `flotilla-raft` at this point, not before.

Leader election with Pre-Vote · log replication · safety · snapshotting where **the Raft
snapshot is an engine snapshot** (this is the payoff of M7's MVCC and the most interesting
thing in the whole distributed half) · membership via joint consensus · ReadIndex/lease reads.

**Acceptance gates.** Deterministic simulation harness (seeded clock, network, faults) — every
failure reproducible from a seed · 3 nodes, kill the leader mid-write, no acknowledged write lost ·
a linearizability check over single-key histories passes under injected partitions.

### M10 — Multi-Raft range sharding (6–10 weeks)

Range partitions, split/merge/rebalance, routing, a placement/metadata service. Gate: a shard
splits under load with no lost or duplicated key, verified by the model harness across the split.

### M11 — Percolator — **explicit non-goal.** One line in the charter, out of the diagrams.

---

## 7. The test work that carries the most weight

Two current tests are load-bearing in the README and thin in reality. Fix both **before** M5;
each is a day.

1. **Crash test needs a lower bound.** Today `ShaleCrashTest:44-46` only proves "no extra, no
   wrong". Add: for a truncation at offset *k*, every record **wholly below** *k* must be present
   — the writer can expose record end-offsets in test scope — and compare **values**, not just
   keys. Without this the headline claim ("always recovers a clean prefix") is proved by an
   engine that recovers nothing.
2. **An engine-level concurrency test.** `@ThreadSafe` on `Shale` has no coverage. N writers +
   M readers + a tiny write buffer forcing constant switches, seeded, 50 repetitions in the fast
   tier. Assert what the code actually promises **today**: every point read returns some value
   that was written, never a torn or absent one for an acknowledged key, and the refcount returns
   to its starting value after every cursor closes. Do **not** assert snapshot isolation for
   cursors — neither `Cursor` nor `StorageBackend` promises it before M7, and asserting it now
   would encode a semantics you have not chosen.

Also worth the hour each: a **writer-side** golden check (today's golden proves the reader still
reads v1 bytes, not that the writer still writes them — the format doc only requires the former,
so this is an extra guard, not a fixed violation); make `GoldenWalTest` and `GoldenSSTableTest`
agree on classpath-vs-cwd fixture loading; and stop quoting a test *count* anywhere — quote what
is covered.

---

## 8. What actually makes this stand out

Ranked by signal per hour. The top three are worth more than two extra milestones.

1. **Deterministic simulation testing** (seeded clock/disk/scheduler, faults injected, every
   failure reproducible from a seed). Almost no solo project has it; FoundationDB and TigerBeetle
   are the reference points, and naming them in an interview lands. Start small at M5 (the
   `FaultyFileSystem` `testing.md` already specifies), extend at M6, make it the backbone at M9.
2. **Measured amplification.** A README graph of write/space amplification for tiered vs leveled,
   from your own counters, is unfakeable and directly instantiates the charter's thesis.
3. **A 30-second demo.** `shale-cli`: write 1M keys, `kill -9`, reopen, show the count and the
   recovery time. An asciinema GIF at the top of the README outperforms all four Mermaid diagrams.
4. **CI badge + green crash suite** — cheap, and it converts four false claims into true ones.
5. **A per-milestone write-up you can defend.** You already have these; keep them honest — the
   "what changed from the plan" section in `m4-release-note.md:49-62` is the single most
   senior-sounding thing in the repository. Do that every time.
6. **The charter in your own voice.** Move the research memo to
   `documentation/reference/background-research.md` and write a 40-line charter. Keep the
   citations — they are an asset; drop "as stated verbatim by…" and the subagent aside.

**On AI assistance.** Not a problem for the code, which is clean and clearly reviewed. It is a
problem only where the register leaks: the charter, the 2,000-line M0 task script, rule-number
citations inside Javadoc ("per N6", "(N3, concurrency-and-resources.md §5)" — the code narrating
its own linter), and `opencode.json` committed at the repo root. Interviewers assume AI
assistance for everyone now; what they test is whether *you* can whiteboard why the footer is 52
bytes, why readers take no lock, why a tombstone must survive to the bottom level, and why the
fsync must leave the critical section. Rehearse those four. If you can give them, the process was
a good one and the artifact is honestly yours.

---

## 9. Ordered action list

### Progress — W0 is complete (2026-09-07)

| W0 item | Status |
|---|---|
| 1. Push `main` + tags; clean up branches | ✅ `main` and all five tags on `origin`; the four merged branches and remote `eager-koala` deleted; only `main` remains |
| 2. CI workflow + badge | ✅ `.github/workflows/build.yml` (`build` then `crashTest`, Temurin 25); badge in the README. The four "checked in CI" claims are now true — and the strongest of them is *enforced*: `verifyNoRuntimeDependencies` resolves `shale-core`'s runtime graph and fails if it is non-empty, verified by mutation |
| 3. Doc/code contradictions | ✅ `Durability.GROUP` Javadoc, stale "At M0…", varints package-info, ADR-0002 date, `flotilla-rpc` path, M3-vs-M5 manifest reference, the lock-across-I/O deviation, the process-crash vs power-loss model, the SLF4J/zero-dependency conflict |
| 4. `soakTest` task with no soak tests | ✅ Kept and dated: `testing.md` now states the task is registered, selects nothing, and that the tier arrives at M6 |
| 5. Archive the M0 task script | ✅ `documentation/roadmap/archive/`, with a README saying why and what the plan format should be |
| 6. README rewrite | ✅ 317 → 101 lines; built/not-built table by component; the four scope diagrams moved intact to `documentation/architecture/project-scope.md` |
| — | ✅ **Roadmap updated** (not in the original W0 list, and necessary): M5.5 inserted, M8 time-boxed, M11 struck to a non-goal — in the README table *and* charter §E, so the tracker and this file no longer disagree |

`./gradlew build crashTest` was run and is green.

**Still open from §6/§9:** the two tests in §7; the `flotilla-*` module decision (needs an ADR
amending ADR-0003 — the module graph is on `CLAUDE.md`'s "never without being asked" list);
and where this file itself should live once its decisions are absorbed.

**Next:** §7's two tests, then M5.

---

**This weekend:** W0, items 1–6 (§6). Nothing here is engineering; all of it changes how the
repo reads.

**Next two evenings:** the crash-test lower bound and the engine concurrency test (§7).

**Then, in order:** M5 → M5.5 → M6 → M7. Do not reorder; each is a strict prerequisite for the
next, and M5.5 must land before M6 because background compaction cannot be built on a write path
that holds a lock across fsync.

**Decide once M7 is green:** P2 (bounded B+Tree + measured RUM) or P3 (Raft). Not both at once.
Delete the empty `flotilla-*` modules today and recreate them the day M9 starts.

---

## 10. Direct answers

**Is this a valid, good project?** Yes. For "depth a hiring manager can probe", it is close to
the best available choice, and the parts you have built are built correctly.

**Are we heading in the right direction?** Technically yes. Process-wise, two drifts: the docs
have started to outrun the code (§2), and the repository is private-by-neglect, which defeats the
stated goal entirely.

**Should we pivot?** No. Narrow: engine first, one capstone, cluster only if the engine ships.

**Add or remove what?** Add: CI, a benchmark harness *before* M6 rather than at M8, WA/RA/SA
counters, deterministic fault injection, an engine concurrency test, a crash-test lower bound, a
CLI demo. Remove: the M0 task script, the empty `soakTest` task, M11 from the roadmap diagrams,
the empty `flotilla-*` shells, and the four false CI sentences (by making them true).

**Will it stand out?** It already would, to anyone who reads it — and today nobody can. Publish
it, prove it with CI, show it with a demo, and make every sentence in the README verifiable. A
smaller project where every claim checks out beats a larger one with four false statements on
page one.
