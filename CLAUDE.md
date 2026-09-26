# CLAUDE.md

Grounding for AI agents and humans working in this repository. Read this fully before
your first edit in a session. If a rule here conflicts with your defaults, this file wins.

---

## 1. What this project is

**ShaleDB** is a relational database built from scratch in Java, designed so the cost of every
statement is visible from the SQL down to the disk. **Shale** is its hand-written LSM storage
engine (`shale-core`); the relational layer, the PostgreSQL wire-protocol server and the demo sit
on top. The purpose and scope are in `documentation/roadmap/charter.md` and ADR-0013. Replication,
sharding, a second storage backend and a block cache are out of scope — do not add them.

**The prime directive: the implementation *is* the product.**

This is a learning and portfolio project. Its value is that every core mechanism —
write-ahead logging, skiplists, SSTable encoding, compaction, bloom filters, MVCC, SQL
planning and execution, concurrency control — is implemented from first principles and understood in depth. A working
system assembled from libraries would be worthless here even if it were faster and
more correct.

Therefore: **never introduce a dependency that implements a core concept.** See §4.

---

## 2. Repository map

```
.
├── CLAUDE.md                  ← you are here
├── CONTRIBUTING.md            human-facing workflow
├── .editorconfig              whitespace/encoding, IDE-agnostic
├── .gitmessage                commit template (git config commit.template .gitmessage)
├── CHANGELOG.md               what each finished milestone shipped
├── config/checkstyle/         enforced style + banned APIs
├── documentation/
│   ├── roadmap/               charter (why, scope, non-goals), completion plan, milestone plans
│   ├── adr/                   architecture decision records
│   └── conventions/           the detailed rules (this file summarises them)
├── shale-core/                the engine. Depends on nothing but the JDK.
└── shale-bench/               JMH microbenchmarks and workload harnesses

Planned (ADR-0013) — created by the milestone named, not before:
    shale-db/                  record layer, catalog, SQL, planner, executor, transactions (D1)
    shale-server/              PostgreSQL wire-protocol server (D5)
    shale-demo/                the CRUD demo application, a client of the server (D7)
```

### The dependency rule

```
shale-demo ──(PostgreSQL protocol)──> shale-server ──> shale-db ──> shale-core <── shale-bench
```

`shale-core` **must never** depend on any module above it, or on any SQL, schema, networking or
protocol code. It is an embeddable single-node engine and must remain usable, testable, and
benchmarkable on its own. `shale-db` uses only `shale-core`'s public SPI, never `internal`.
`shale-demo` reaches the database only over the wire protocol, as any application would.

If you find yourself wanting to add a relational or network concern to `shale-core`, stop and
write an ADR instead. This boundary is the architectural point of the project.

---

## 3. Commands

A fresh machine or container has no JDK 25; the build uses a vendored one. Once per shell:

```bash
./scripts/bootstrap.sh       # fetch + checksum-verify JDK 25 into .tools/ (idempotent)
source scripts/env.sh        # point JAVA_HOME and GRADLE_USER_HOME at .tools/
```

Then:

```bash
./gradlew build              # compile + checkstyle + unit tests
./gradlew test               # unit tests only
./gradlew :shale-core:test --tests '*Recovery*'
./gradlew crashTest          # fault-injection suite (slow, tagged)
./gradlew soakTest           # long-running randomised model check (very slow)
./gradlew :shale-bench:jmh   # microbenchmarks
./gradlew spotlessApply      # autoformat
```

Target JDK: **25 (LTS)**. The Foreign Function & Memory API (`Arena`, `MemorySegment`)
is used for off-heap work; it is final since JDK 22 and requires no preview flags.

---

## 4. Non-negotiables

These are the rules most likely to be violated by well-meaning autocompletion.

**N1 — No third-party implementations of core concepts.**
Anything in the roadmap's component inventory must be hand-written. Concretely, do not
add: a skiplist or concurrent sorted map library, a bloom filter library (Guava's
included), a serialisation framework for on-disk formats (Protobuf, Kryo, Avro), a
compression codec *before* the format that uses it is hand-written and understood, or a SQL
parser, planner or query engine (Calcite, H2, JSqlParser).

The permitted dependency allowlist lives in `documentation/conventions/java-style.md`; the only
exceptions are the demo application and benchmark baselines (ADR-0013).
Adding to it requires an ADR. When you need a data structure that already exists in
the JDK and is *not* a project subject (e.g. `ArrayDeque`, `ReentrantLock`), use it
freely — the rule targets the things we are here to learn, not general plumbing.

**N2 — Never silently change an on-disk format.**
Any change to bytes written to disk requires: a format version bump, an update to the
adjacent `format.md`, a round-trip test against a checked-in golden file, and a
`Format-Change:` trailer on the commit. See `documentation/conventions/on-disk-formats.md`.

**N3 — Every write path states where durability happens.**
Any method that can acknowledge a write to a caller must make its durability guarantee
explicit in its signature (a `Durability` parameter) or its Javadoc. Mark the exact
line where data becomes durable with a `// DURABILITY:` comment. Never add a path that
acknowledges before the guarantee it claims.

**N4 — Corruption is never repaired silently.**
On a checksum mismatch or structural inconsistency, throw `CorruptionException` with
the file, offset, and expected/actual values. Do not skip the record, do not truncate,
do not "best effort" continue. Recovery policy is the caller's decision, made
explicitly. A storage engine that hides corruption is worse than one that crashes.

**N5 — Every mutable field declares its concurrency contract.**
Either the class is annotated `@NotThreadSafe` (and says which thread owns it), or
every mutable field is `final`, `volatile`, `@GuardedBy("lock")`, or an atomic. No
exceptions, no "obviously fine" fields.

**N6 — Every off-heap allocation and file handle has a named owner.**
`Arena` and `MemorySegment` lifetimes are explicit and scoped. SSTable files are
reference-counted with `retain()`/`release()`. Never rely on `Cleaner`, finalizers, or
GC timing for correctness — only as a leak-detection backstop.

**N7 — Do not disable, skip, or weaken a failing test.**
No `@Disabled`, no loosened assertion, no widened tolerance to make a build green. If a
test is wrong, fix the test in its own commit with an explanation of why it was wrong.

**N8 — No `Thread.sleep` in tests, ever.**
Use the injected `Clock` and deterministic scheduling. Sleeps make the crash and
concurrency suites flaky, and a flaky suite in this project is indistinguishable from
a real bug.

**N9 — Every core type cites its source.**
Public types implementing a known technique carry a Javadoc reference to the paper,
book chapter, or reference implementation they follow, including where we deviate and
why. This is a study project; the citation is part of the deliverable.

**N10 — Match the literature's vocabulary exactly.**
Use the canonical term from the LSM literature for every concept, and only that term.
The glossary in `documentation/conventions/naming.md` is authoritative. Do not invent
synonyms; do not use two names for one thing.

---

## 5. Working style for agents

**Read before writing.** Before implementing in a module, read that module's
`package-info.java` and any `format.md`. Before changing behaviour, read the relevant
milestone in `documentation/roadmap/`.

**Document as you build.** The codebase must read like a book: explain *why*, not just
*what*, on the surface that owns it, in the same commit as the code. The rules — and the
per-milestone checklist — are `documentation/conventions/documentation.md`. This is a study
project; the explanation is part of the deliverable, not an afterthought.

**Stay inside the milestone.** The end-to-end order of every remaining milestone is
`documentation/roadmap/completion-plan.md`; start from the first milestone not marked done
there, and follow its plan file. The roadmap is strictly ordered and each milestone must
end in a working, tested artifact. Do not implement compaction while the SSTable format
is unfinished, do not add bloom filters before compaction works, do not stub the
relational layer into the engine. If a task seems to require a later milestone's work,
say so rather than building a placeholder.

**Prefer the obvious implementation first.** Correctness, then measurement, then
optimisation — in that order, with the benchmark committed before the optimisation.
Do not micro-optimise a path nobody has measured. "This avoids an allocation" is not
a justification without a JMH result.

**Branch before you build.** Every change — feature, fix, refactor, doc, or experiment
— starts on its own purpose-named branch cut from `main`; never commit to `main`
directly. One branch, one unit of work. Branch anchored to hard-to-reverse work opens
with the ADR, not code. Prefixes, scope, and lifecycle: `documentation/conventions/commits.md` §5.

**Small, single-purpose commits.** One logical change per commit. Formatting churn goes
in its own commit. See `documentation/conventions/commits.md`.

**When uncertain, ask — do not guess.** Especially for: on-disk layout, durability
semantics, thread ownership, and anything the roadmap marks as hard-to-reverse. A
wrong guess in these areas is expensive to unwind. Propose an ADR and stop.

**Never do these without being asked:**
- add a dependency
- change a public API in `shale-core`
- change an on-disk format
- add a new module
- reformat files you did not otherwise modify
- write a README or summary document nobody requested

---

## 6. Detailed rules

| Topic | File |
|---|---|
| Naming, glossary, package layout | `documentation/conventions/naming.md` |
| Java style, dependency allowlist | `documentation/conventions/java-style.md` |
| Commit messages, branches, trailers | `documentation/conventions/commits.md` |
| Threading, resources, lifecycles | `documentation/conventions/concurrency-and-resources.md` |
| Byte layouts, versioning, compatibility | `documentation/conventions/on-disk-formats.md` |
| Exceptions, logging, metrics | `documentation/conventions/errors-and-logging.md` |
| Test tiers, naming, determinism | `documentation/conventions/testing.md` |
| Documentation, readability, diagrams | `documentation/conventions/documentation.md` |
| Decision records | `documentation/adr/README.md` |
| Milestones and scope | `documentation/roadmap/` |
| The end-to-end plan: order, estimates, cut lines, status | `documentation/roadmap/completion-plan.md` |

Lost? [`documentation/README.md`](documentation/README.md) is the map of every doc and the
order to read them in.
