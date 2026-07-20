# Testing

A storage engine's tests are not a quality gate bolted onto the side — they are the only
reason to believe any durability claim the code makes. The roadmap sets the thresholds
for advancing between milestones, and every one of them is a test result.

---

## 1. The five tiers

| Tier | Tag | Runtime | Runs |
|---|---|---|---|
| Unit | (none) | < 10 s total | Every build |
| Model | `@Tag("model")` | ~1 min | Every build |
| Property | `@Tag("property")` | ~2 min | Every build |
| Crash | `@Tag("crash")` | ~10 min | Pre-merge, nightly |
| Soak | `@Tag("soak")` | hours | Nightly, before a milestone tag |

`./gradlew build` runs the first three. `crashTest` and `soakTest` are separate tasks.

### Unit
Ordinary focused tests. Fast, no filesystem unless the class under test is about the
filesystem, no sleeping, no randomness without a logged seed.

### Model — the highest-value test in this project
Run a randomised operation sequence against the engine and against a reference
`TreeMap<byte[], byte[]>` with the same comparator. After every operation, and after
every restart, assert the two agree — on point lookups, on full iteration order, and on
range scans.

Build this in **M0, before the engine exists.** It costs an afternoon and it will catch
more real bugs than every other test you write, because it checks the thing you actually
care about (the engine behaves like a sorted map) rather than the thing you thought to
assert.

The model harness must exercise: put, delete, overwrite, range delete, restart, flush,
compaction, and snapshot reads — with the operation mix and key distribution
configurable, and with the seed printed on failure and pinned into a regression test.

### Property
jqwik, with shrinking. Properties worth stating:
- Encode/decode round-trip for every on-disk structure.
- Comparator is a total order: antisymmetric, transitive, consistent with equality.
- Compaction preserves the newest visible version of every key and drops nothing
  reachable by a live snapshot.
- Recovery from any prefix of the WAL yields a state consistent with some prefix of the
  acknowledged writes.
- A bloom filter never returns a false negative. Ever, for any input.

### Crash
Fault injection through a `FaultyFileSystem` wrapper that can, deterministically:
kill at a chosen operation index, truncate a file at an arbitrary offset, write a
partial (torn) record, fail an fsync, reorder writes not separated by an fsync, return
`ENOSPC`, and corrupt a chosen byte.

The core assertion is always the same: **no acknowledged write is lost, and the engine
either opens correctly or reports `CorruptionException` — never both partly.**

Systematic coverage beats random: for a small database, crash at *every* operation index
and truncate at *every* byte offset. Both spaces are small enough to enumerate and both
find real bugs immediately.

### Soak
Hours of continuous mixed workload with periodic restarts, checked against the model,
with memory and file-descriptor counts asserted flat. This is what finds the reference-
counting leak and the compaction backlog that only appears after 40 minutes.

---

## 2. Determinism

Flaky tests are fatal to this project specifically: when a crash test fails once in
fifty runs, you cannot tell a real durability bug from test noise, and the natural
response — rerunning until green — trains you to ignore the exact signal you built the
suite to produce.

- **No `Thread.sleep`, anywhere.** (`CLAUDE.md` N8.) Time comes from an injected
  `Clock`; tests use a manual clock they advance explicitly.
- **No wall-clock timeouts as assertions.** Never "this should finish within 5 seconds".
- **Every random source is seeded**, the seed is derived from a single test-run seed,
  and it is printed on every failure:
  ```
  FAILED: seed=8412339070165231107 — reproduce with -Dshale.test.seed=8412339070165231107
  ```
- **Every failure is reproducible from its seed alone.** If it is not, the test has an
  unmanaged source of nondeterminism — fix that before fixing the bug.
- **Background threads are controllable.** Flush and compaction executors are injectable;
  tests use a deterministic executor that runs tasks on demand, so a test can say
  "flush now, then compact once, then read" instead of waiting and hoping.

This discipline is also the on-ramp to deterministic simulation testing
(FoundationDB-style) for the Flotilla layer at M9. If the engine is already clock-,
random-, and executor-injected, simulation is an extension rather than a rewrite.
Design for it now even if you never build it.

---

## 3. Naming and structure

```java
@Test
void get_afterFlush_returnsValueFromSSTable() { }

@Test
void recover_withTornRecordAtTail_dropsOnlyTheTornRecord() { }

@Test
void compaction_withLiveSnapshot_retainsShadowedVersion() { }
```

`methodOrScenario_condition_expectedResult`. No `test` prefix, no `shouldXxx`, no prose
sentences in `@DisplayName` that duplicate the method name.

- Arrange / act / assert, separated by blank lines, in that order.
- **One behaviour per test.** Multiple assertions are fine when they describe one
  behaviour; two acts are not.
- AssertJ for assertions, with a message when the failure would otherwise be cryptic.
- No logic in tests — no loops that build expectations, no conditionals. A test with an
  `if` in it is a test that can pass without checking anything.
- Test fixtures build through named helpers that read as intent:
  `givenSSTableWith(keys(1, 100))`, not forty lines of inline setup.

---

## 4. Rules that are not negotiable

- **Every bug fix begins with a failing test**, committed together with the fix, and the
  commit body states that the test fails without it.
- **Never disable, skip, or weaken a test to get a green build.** (`CLAUDE.md` N7.)
  A quarantined test is a deleted test with extra steps.
- **Never regenerate a golden file to make a test pass.**
  (`on-disk-formats.md` §4.)
- **Every durability claim in Javadoc has a corresponding crash test**, and the Javadoc
  names it. An untested claim gets deleted from the Javadoc.
- **No test touches a shared directory.** Each test gets its own temp directory, and
  asserts it is empty of unexpected files at teardown — leftover files are how you find
  a lifecycle bug.
- Coverage is not a target and is not gated on. A high coverage number on a storage
  engine with no crash tests means nothing. Measure it, look at what is uncovered, do
  not optimise the number.

---

## 5. Benchmarks are not tests

Benchmarks live in `shale-bench`, run through JMH, and never run in the normal build.

- Every JMH benchmark declares `@Fork`, `@Warmup`, and `@Measurement` explicitly, and
  consumes results via `Blackhole` — a naive timing loop on the JVM will report numbers
  that are wrong by an order of magnitude because of dead-code elimination and
  constant folding.
- Macro workloads mirror recognisable names so results are comparable to published
  numbers: `fillseq`, `fillrandom`, `readrandom`, `readwhilewriting`, `seekrandom`,
  and YCSB A–F.
- Every benchmark run records: commit SHA, JDK version, hardware, and full engine
  configuration. A number without its configuration is not a result.
- Benchmark results referenced in a `perf` commit are committed under
  `documentation/benchmarks/NNNN-description.md`, so the trailer's claim can be checked
  later. (See `commits.md` §4.)
