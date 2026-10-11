# Completion plan — from M4 to ShaleDB v1.0

**Status:** the working plan of record (2026-09-26; every milestone plan critiqued and rewritten
to implementation level on 2026-09-27; **M4.5 inserted and M5–M8 amended on 2026-10-10** after the
October 2026 engineering review, §11). **Starts from:** `main` at `c66adea`: M0–M4 complete,
`./gradlew build crashTest` green on JDK 25 (161 tests). **Why and what:** the
[charter](charter.md) and [ADR-0013](../adr/0013-shaledb-see-through-database.md).

This page orders everything left to build. Pick up the first milestone in §10 that is not ✅ and
follow its plan. **Every plan uses one template:**
- where the code will be at its start;
- the decided design, down to byte layouts, algorithms and error codes;
- a table of the new and changed types;
- branch-sized steps, each with commits, named tests and a "done when";
- the milestone's acceptance gates.

Plans for later milestones name types that earlier ones will create. §5 step 1's reconciliation
pass keeps them true to the code as it actually lands.

## 1. What "finished" means

**The v1.0 claim:** *ShaleDB is a relational database built from scratch in Java. `psql`
connects to it. `EXPLAIN ANALYZE` prints, and a browser view draws, what each statement cost: from
the plan down to the fsync, and the compaction it leaves for later. Its storage tradeoffs are
measured through SQL workloads, and it survives `kill -9` without losing an acknowledged commit.*

What a reader actually sees at v1.0, who it is for, and how each part of the claim is checked:
[charter §5](charter.md#5-the-end-goal-what-someone-sees-at-v10).

| Phase | Milestones | Ends with |
|---|---|---|
| **Engine** | M4.5, M5, M5.5, M6, M7, M8 | **Shale 1.0** — a complete, measured LSM engine (tag `shale-1.0`) |
| **Database** | D1–D7 | **ShaleDB v1.0** — SQL, transactions, the PostgreSQL protocol, cost accounting and the demo (tag `v1.0`) |

**Portfolio checkpoints.** Each is a finished, honest artifact if work stops there; update the
README to match at each:
- after **M4.5** — the recovery case study: two one-bit header faults that silently lost
  acknowledged writes, the invariant they broke, the format decision, and the regressions and
  limits (a smaller scope than Shale 1.0, and named as such);
- after **M6** — an LSM engine with compaction;
- after **M8** — Shale 1.0, measured against RocksDB;
- after **D5** — a SQL database `psql` connects to;
- after **D7** — v1.0.

## 2. The order

```mermaid
flowchart LR
  subgraph engine["Engine → Shale 1.0"]
    m45["M4.5<br/>integrity"] --> m5["M5<br/>manifest"] --> m55["M5.5<br/>write path"] --> m6["M6<br/>compaction"] --> m7["M7<br/>batches · snapshots · bloom"] --> m8["M8<br/>benchmarks"]
  end
  subgraph database["Database → v1.0"]
    d1["D1<br/>record layer"] --> d2["D2<br/>SQL"] --> d3["D3<br/>joins · aggregates"] --> d4["D4<br/>transactions"] --> d5["D5<br/>PostgreSQL protocol"] --> d6["D6<br/>cost accounting"] --> d7["D7<br/>demo · findings · launch"]
  end
  m8 --> d1
```

The order is strict; each milestone ends in a tested, tagged artifact. §8 has the one sanctioned
reorder.

## 3. Why each step is here

Every step was tested against the v1.0 claim: does it make cost **visible**, **measured**, or
**proven**, or is it required for one that does? Scope that failed this test is not in the plan;
ADR-0013 records it and why.

| Step | Why it is in the plan |
|---|---|
| **M4.5** integrity boundaries | Two reproduced one-bit WAL header faults make recovery succeed with every acknowledged write gone; the claim "durable" is false until they fail loudly. It precedes M5 because M5's manifest reuses the WAL framing, which must be trustworthy first (ADR-0024). |
| **M5** manifest | Without durable metadata no file can be deleted safely, so compaction is impossible. It also closes the known lifecycle gaps: operations after `close`, the failed state after an I/O error, handles leaked by a failed `open` or cursor, the six-digit file-number limit, no directory fsync. |
| **M5.5** write path | A dedicated WAL-writer thread gives group commit, and a submission that never does I/O in the caller's thread, which D4's commit protocol needs. Background flush and write stalls are prerequisites for compaction. The shared fsync is what `EXPLAIN ANALYZE` shows as "shared(*N*)". |
| **M6** compaction | What makes this an LSM engine, and the source of the deferred costs the thesis is about. Leveled first; size-tiered (RocksDB-universal style, which keeps runs in age order) second, so the tradeoff can be measured (E1, F3). |
| **M7** batches, snapshots, bloom, statistics | Atomic batches and snapshots are what a relational layer's correctness rests on. Bloom filters make the duplicate-key check of every `INSERT` cheap. Per-operation statistics are the hook cost accounting needs. |
| **M8** benchmarks, Shale 1.0 | Turns engine claims into numbers, with RocksDB as the reference. Makes the engine operable by someone else: an online checkpoint (backup), an operating guide, a compatibility policy, a released jar. Ends a complete stopping point. |
| **D1** record layer | Tables and indexes as ordered keys — the core idea of SQL on an LSM (MyRocks, CockroachDB). |
| **D2** SQL | The query-engine half of a database. Its grammar is frozen, to stop scope creep. |
| **D3** joins, aggregates | The demo's loan list is a join; its stats page is a `GROUP BY`. |
| **D4** transactions | Serializable isolation, falsifiable by tests; the demo's checkout depends on it. |
| **D5** PostgreSQL protocol | `psql` and standard drivers instead of a custom client — less to build, far more convincing. |
| **D6** cost accounting | The thesis itself: one statement trace, from the plan down to the disk, printed by `EXPLAIN ANALYZE` and streamed as JSON for D7 to draw. |
| **D7** demo, findings, launch | Makes the claim visible in five minutes — the trace view draws each action's cost — answers the measured questions, and packages it for a reviewer. |

## 4. Milestones

Estimates are focused weeks at a hobby pace. M0–M4 took about 2.5 active weeks against a charter
estimate of 3–6, so recalibrate after M5 (§10).

| Id | Milestone | Plan | ADR | Format change (N2) | New module | Est. | Tag |
|---|---|---|---|---|---|---|---|
| M4.5 | Integrity boundaries | [plan](m4-5-integrity-boundaries.md) | 0024 | WAL v2 (header CRC) | — | 2–3 | `m4.5-integrity` |
| M5 | Manifest + recovery | [plan](m5-manifest-and-recovery.md) | 0012 | manifest v2 (born on log framing v2; no v1) | — | 3–4 | `m5-manifest` |
| M5.5 | Concurrent write path | [plan](m5-5-concurrent-write-path.md) | 0014 | — | — | 2–3 | `m5.5-write-path` |
| M6 | Compaction | [plan](m6-compaction.md) | 0015 | — (M5 defined levels and tag 5) | — | 4–6 | `m6-compaction` |
| M7 | Batches, snapshots, bloom, statistics | [plan](m7-batches-snapshots-bloom.md) | 0016, 0017 | WAL v3, SSTable v2 | — | 3–4 | `m7-mvcc-bloom` |
| M8 | Engine benchmarks, checkpoint, Shale 1.0 | [plan](m8-benchmark-suite.md) | 0023 | — | — | 3–4 | `shale-1.0` |
| D1 | Record layer | [plan](d1-record-layer.md) | 0018 | key + row v1 | `shale-db` | 2–3 | `d1-record` |
| D2 | SQL + single-table execution | [plan](d2-sql-and-execution.md) | 0019 | — | — | 3–4 | `d2-sql` |
| D3 | Joins, aggregates, ordering | [plan](d3-joins-aggregates-ordering.md) | — | — | — | 2–3 | `d3-query` |
| D4 | Serializable transactions | [plan](d4-transactions.md) | 0020 | — | — | 2–3 | `d4-txn` |
| D5 | PostgreSQL wire protocol | [plan](d5-postgres-wire-protocol.md) | 0021 | (conforms to PostgreSQL's) | `shale-server` | 2–3 | `d5-pgwire` |
| D6 | Cost accounting | [plan](d6-cost-accounting.md) | 0022 | statement-trace JSON v1 | — | 2–3 | `d6-cost` |
| D7 | Demo, findings, launch | [plan](d7-demo-and-launch.md) | — | — | `shale-demo` | 4–5 | **`v1.0`** |

**Totals:** engine ≈ 17–24 weeks, database ≈ 17–24 → **v1.0 ≈ 34–48 focused weeks** on these
estimates, or roughly half that at the M0–M4 pace. With every cut in §8 taken: ≈ 29–42.

## 5. How to execute any milestone

1. **Reconciliation pass** (the last task of the previous milestone). Re-read the next plan
   against the code that actually landed. Fix any type name, signature or file path that
   changed, and any "where the code will be" fact that is no longer true, in a `docs` commit
   before the milestone starts. The plans are written ahead of the code they build on, and this
   pass is what keeps them exact.
2. Toolchain, once per shell: `./scripts/bootstrap.sh && source scripts/env.sh`.
3. For each step, branch `mNN/<slug>` (engine) or `dNN/<slug>` (database) from an up-to-date
   `main`. Read the plan, the `package-info.java` and `format.md` of the packages it touches, and
   the ADRs it cites.
4. **Decision first:** the milestone's first step writes its ADR, which records the plan's design
   and the alternatives it rejected. It is `Accepted` before any implementation commit.
5. **Format first:** for new bytes, `format.md`, then the golden fixture and the round-trip and
   bit-flip tests, then the encoder. Commit with `Format-Change:` and `Reversible: no`.
6. Implement in the plan's step order: TDD, one logical change per commit, each commit green.
7. Satisfy every acceptance gate with a test, not with prose.
8. Docs: `package-info` and Javadoc with N9 citations, glossary rows, the as-built
   `architecture/<milestone>-*.md` with validated Mermaid, the README status, a changelog entry.
   **The user-facing pages too:**
   - the guides in `documentation/guides/`;
   - the [FAQ](../faq.md).

   Each must say what is now true: the new API, the lifted limitations, and answers that no
   longer say "planned". Each plan's docs step lists its changes.
   A bug that reached a commit before being caught gets a [bug-log](../bug-log.md) entry.
9. `./gradlew build crashTest` green (plus `soakTest` from M6). Merge, tag, delete the branch.
10. Update §10 with actual versus estimated weeks.

If a task seems to need a later milestone's work, stop and say so (CLAUDE.md §5).

## 6. The decision records each milestone writes

Every design below is decided in its milestone's plan; the ADR records it, with the alternatives
it rejected, as that milestone's first step. The numbers are reserved now.

| ADR | Milestone | Records |
|---|---|---|
| 0024 | M4.5 | log framing v2 (header CRC); the tail classification table; v1's unverifiable-tail rule; sync at switch; the decoder contract; guarantees per failure model — **already `Accepted`** (written with this plan) |
| 0012 | M5 | manifest format (with per-file sequence ranges); open and flush order; `Version` ownership; failed state and close; the public `Env` SPI; `ShaleOptions` |
| 0014 | M5.5 | a dedicated WAL-writer thread (amends ADR-0008's leader/follower); watermarks; fail-stop on fsync error; background flush and stalls; `GROUP` as an alias of `SYNC` |
| 0015 | M6 | leveled first, then RocksDB-universal-style tiered; level invariants; point-lookup order; `flush`/`compactRange`; compaction debt |
| 0016 | M7 (1) | `WriteBatch`, `writeAsync`, `Snapshot`, `ReadOptions`, `OperationStats`; key and value size limits; WAL v3 |
| 0017 | M7 (2) | whole-table bloom filter, double hashing, 10 bits/key; SSTable v2 |
| 0018 | D1 | order-preserving key encoding; keyspaces; row format (with a column count, for `ADD COLUMN`); catalog; index states |
| 0019 | D2 | the frozen SQL subset; strict types; three-valued logic; rule-based planner |
| 0020 | D4 | serializable backward-validation OCC; commits enqueued in commit order; DDL through the oracle, blocking writes |
| 0021 | D5 | the PostgreSQL protocol subset; types; error codes; threading |
| 0022 | D6 | per-operator statistics; the statement trace and its three renderings (text, `FORMAT JSON`, notices); the trace JSON format, versioned; system tables |
| 0023 | M8 | the online checkpoint: flush, pin a `Version`, hard-link, commit by directory rename; `Env.link` |

**Public API changes to `shale-core`** (each in its ADR, with a `Reversible:` trailer):
- M4.5: none. Behaviour: an unverifiable v1 WAL tail fails `open`, and a segment is synced
  when it is closed.
- M5: `ShaleOptions`; the `Env` SPI (`dev.shale.env`); `open(…, Env)`; closed-state and
  repeated-close semantics.
- M5.5: `ShaleOptions` fields; `Durability.GROUP` documented as an alias.
- M6: `ShaleOptions` fields; `flush()`; `compactRange(from, to)`.
- M7: `WriteBatch`, `WriteResult`, `write`, `writeAsync`, `Snapshot`, `ReadOptions`,
  `OperationStats`; `bloomBitsPerKey`; the key (16 KiB) and value (16 MiB) limits.
- M8: `checkpoint(Path)`; `Env.link`; version `1.0.0` and the compatibility policy.

**Test tiers come alive:**
- M4.5: the log-framing matrices — every header bit, every truncation offset, zero-filled tails —
  and bit-flip at every bit for the WAL and SSTables;
- M5: `FaultInjectionEnv` — a crash and simulated power loss at every file operation;
- M6: soak;
- D2: SQL logic tests and differential queries;
- D4: serializability checks;
- D5: process-level `kill -9`, pgjdbc conformance, and `psql` and Python smoke tests in CI.

## 7. Dependencies introduced, and where

All per ADR-0013. `shale-core`, `shale-db` and `shale-server` stay at zero runtime dependencies.
- **M8:** RocksDB (JNI) in `shale-bench`, as a reference baseline.
- **D5:** pgjdbc in `shale-server`'s *test* scope, for the conformance tests.
- **D7:**
  - pgjdbc in `shale-demo`, at runtime;
  - SQLite (JDBC) in `shale-bench`, as a reference baseline.

Each arrives with an update to `java-style.md` §1. Where a Checkstyle import ban is in the way,
the exception is a Checker-level `SuppressionSingleFilter`: it targets the ban's module `id`
(`bannedDependencies` for `org.rocksdb` in `shale-bench/…/baseline/`; `bannedJdkApis` for
`com.sun.net.httpserver` in `DemoHttpServer.java`), scoped by file path. `verifyModuleGraph`
(M8) enforces the module arrows of CLAUDE.md §2 in `check`.

**CI-only tools**, not dependencies of any module:
- D5: `postgresql-client` for the `psql` smoke test;
- D5: Python with `psycopg2-binary`, for the second-language smoke test.

## 8. Cut lines

**Fast-track (the one sanctioned reorder).** If the demo matters more than finishing the engine
phase first: after M7, go to D1–D6, then M8, then D7 (D7 needs M8's report).

**If time runs short, cut in this order** (least loss first):
1. SQLite and RocksDB baselines (the numbers lose their context, not their meaning); M8's E7
   (the GC comparison); D7's `BACKUP TO` (the engine's `checkpoint` stays: backup becomes "stop
   and copy" for the server);
2. size-tiered as a second policy (E1 and F3 become memtable-size and bloom sweeps);
3. D3's outer joins, `HAVING` and `DISTINCT`;
4. the demo's engine panel, and the trace view's compare mode and Perfetto export (keep the trace
   view itself — path, plan, LSM, breakdown, transaction strip: it is the thesis, drawn);
5. one of the three write-ups.

**Never cut:** M4.5, M5, M6's leveled compaction, M7, D4's serializable commit, D5, D6, or the crash
demonstrations. They are the claim.

## 9. Risks

| Risk | Guard |
|---|---|
| A decoder or recovery path trusts an unverified field (the class of SH-08, SH-18) | ADR-0024 §6's decoder contract; bit-flip at every bit of every new format; a malformed-structure fixture for every structural field |
| Docs claim a guarantee the tests do not cover | every claim names its failure model (ADR-0024 §7); a milestone's docs step re-checks the guide, FAQ and README against its tests |
| Compaction and flush races corrupt the file set | M5's simulated-filesystem crash tests and M6's crash-at-every-operation gates; soak runs before tags |
| SQL scope creep | each D-plan freezes its grammar; anything else is written down, not built |
| PostgreSQL protocol surface creep (drivers probing `pg_catalog`) | D5's subset is frozen; the demo uses only pgjdbc features the conformance suite covers |
| OCC and snapshot interaction bugs | D4's anomaly, commit-order and serial-replay checks run in every build |
| Cost numbers that are wrong but plausible | D6's exact scenario tests on a deterministic engine, and the invariants that tie per-operator totals to statement totals |
| Estimates slip | recalibrate after M5 and M6; take §8's cuts rather than stretching the schedule |
| CI network flakiness (a Maven Central 429 was seen 2026-09-26) | `setup-gradle`'s dependency cache; if 429s recur, retry dependency resolution only, never tests |

## 10. Status

| Id | Status | Estimate (weeks) | Actual | Notes |
|---|---|---|---|---|
| M0–M4 | ✅ | 3–6 | ~2.5 | tag `m4-merge`; see the [changelog](../../CHANGELOG.md) |
| M4.5 | ✅ | 2–3 | ~2 days | tag `m4.5-integrity` once merged; 18 commits, 161 → 279 tests |
| M5 | ⏭ in progress | 3–4 | | Step 1 done: [ADR-0012](../adr/0012-manifest-and-recovery.md) accepted |
| M5.5 · M6 · M7 · M8 | ☐ | 12–17 | | |
| D1–D7 | ☐ | 17–24 | | |

## 11. The October 2026 engineering review: where each finding is closed

An external review of `main` at `c66adea` (five rounds, 2026-10-08/09) recorded 18 findings,
`SH-01` to `SH-18`. They are defects, unproven guarantees, measurements and presentation issues;
**the count is not a bug count**. Each finding has exactly one owner below, so none is open
without a place to close it. The reproduced defects' evidence is kept in the bug log's
[open-defect register](../bug-log.md#open-defects).

| Id | Kind | Finding, in brief | Closed by |
|---|---|---|---|
| SH-01 | defect | writes accepted after a failed flush; `get` and `scan` then disagree after reopen | M5 §2.6 failed state, regression in M5 Step 7 |
| SH-02 | defect | a failed `open` leaks the table handles it already opened | M5 §2.3 open rollback, Step 7 |
| SH-03 | wrong claim | the guide promised cursor snapshots | claim corrected in docs and Javadoc (`bd5c350`); real snapshots M7 Step 4 |
| SH-04 | wrong claim + gap | `SYNC` called power-loss safe without directory syncs | claim corrected in docs (2026-10-10); directory syncs M5 Step 4; simulated in M5 Step 8 |
| SH-05 | test gap | tests cover narrower failures than the claims (and the WAL had no bit-flip test) | WAL half **fixed** — M4.5 `83d2eae`, `9b1217f`; composed failure histories and metamorphic runs remain M5 Step 8 |
| SH-06 | presentation | the README led with planned SQL; the charter overstated "only owners can attribute cost"; a CockroachDB example used RocksDB, not Pebble | corrected in docs (2026-10-10) |
| SH-07 | measurement | restart, read fan-out, narrow-scan allocation and flush-tail costs are unmeasured | M5.5 Step 2 (baseline); M8 E5, E8 |
| SH-08 | **P0 defect** | `FULL`→`ZERO` bit: open succeeds empty and deletes the WAL | **fixed** — M4.5: `000f67b`, `7c92861`; [bug log B5](../bug-log.md#b5--one-flipped-bit-in-a-wal-header-lost-every-acknowledged-write-m1m4) |
| SH-09 | defect | seven-digit file names written but never read back | M5 Step 4 (numeric names, rollover regression) |
| SH-10 | defect | a cursor whose construction fails keeps its table pins | M5 Step 7 (adopted-Version rollback, regression) |
| SH-11 | defect | an overflowing 10-byte varint decodes as 0 | **fixed** — M4.5: `84d3183`; bug log B6 |
| SH-12 | measurement | recovery replays every WAL into one memtable; no memory budget | M8 E8 (measure, then retain, change or defer) |
| SH-13 | defect | a 2^32 + 9 key length narrows to 9 | **fixed** — M4.5: `a1f64e0`; bug log B6 |
| SH-14 | defect | a restart count overflows the offset arithmetic | **fixed** — M4.5: `a01c028`; bug log B6 |
| SH-15 | defect | a footer can alias the metaindex as the index | **fixed** — M4.5: `1d79264`; bug log B6 |
| SH-16 | defect | bad handle sizes escape as `ArithmeticException` or `ArrayIndexOutOfBoundsException` | **fixed** — M4.5: `1d79264`; bug log B6 |
| SH-17 | measurement | an empty scan or a missing key reads one block per table | M5.5 Step 2 (baseline counts); M6 Step 2 (range selection for `get` *and* `scan`); M7 Step 5 (counters) |
| SH-18 | **P0 defect** | a length bit makes a damaged header look like a torn tail: open succeeds empty and deletes the WAL | **fixed** — M4.5: `7c92861` (v2), `9b1217f` (v1), `7f6573d`; bug log B5 |

**How a finding's status changes.** It moves only with evidence, in this form: *work step →
finding ids → commit → command and its output → remaining limitation*.
- "Fixed" means the behaviour changed and the named regression passes.
- A decision or a measurement is "decided" or "measured", never "fixed".
- A finding closes in the bug log (defects) or in its milestone's changelog entry (measurements),
  and its row here gets the commit.

**Two levels of completion.** A step is done when its named regressions and controls pass and
its limits are written down. A milestone is done only by its acceptance gates. Closing a finding
does not accept its milestone, and a milestone's tag does not close a finding that its tests do
not name.

**What the review recommended that this plan does not adopt, and why:**
- **A replacement engine (RocksDB) or a copied format** (SQLite's WAL). The mechanisms are the
  point (N1); those systems are references.
- **Coverage-guided fuzzing (Jazzer)** — deferred. It would need an ADR for the test-dependency
  allowlist, and the deterministic fixtures come first.
- **Broader fault histories than the M5 crash matrix and the M6 soak.** Those two are the plan;
  any new requirement must name the invariant it serves and the step it extends.
