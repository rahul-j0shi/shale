# CLAUDE.md

Grounding for AI agents and humans working in this repository. Read this fully before
your first edit in a session. If a rule here conflicts with your defaults, this file wins.

---

## 1. What this project is

**Shale** is a hand-written LSM-tree storage engine. **Flotilla** is the Raft-replicated,
range-sharded distributed store built on top of it. Both live in this repository.

**The prime directive: the implementation *is* the product.**

This is a learning and portfolio project. Its value is that every core mechanism —
write-ahead logging, skiplists, SSTable encoding, compaction, bloom filters, MVCC,
consensus — is implemented from first principles and understood in depth. A working
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
├── config/checkstyle/         enforced style + banned APIs
├── documentation/
│   ├── roadmap/               milestones M0–M11, scope, non-goals
│   ├── adr/                   architecture decision records
│   └── conventions/           the detailed rules (this file summarises them)
├── shale-core/                the engine. Depends on nothing but the JDK.
├── shale-bench/               JMH + YCSB-style harnesses
├── flotilla-raft/             consensus. Depends on shale-core.
└── flotilla-server/           RPC, sharding, routing. Depends on both.
```

### The dependency rule

```
flotilla-server ──> flotilla-raft ──> shale-core
                └────────────────────────┘
```

`shale-core` **must never** depend on any `flotilla-*` module, or on any networking,
RPC, or clustering code. It is an embeddable single-node engine and must remain
usable, testable, and benchmarkable with zero cluster machinery present.

If you find yourself wanting to add a cluster concern to `shale-core`, stop and write
an ADR instead. This boundary is the architectural point of the project.

---

## 3. Commands

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
compression codec *before* the format that uses it is hand-written and understood, a
Raft implementation, a caching library, or a B-tree library.

The permitted dependency allowlist lives in `documentation/conventions/java-style.md`.
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

**Stay inside the milestone.** The roadmap is strictly ordered and each milestone must
end in a working, tested artifact. Do not implement compaction while the SSTable format
is unfinished, do not add bloom filters before compaction works, do not stub the
distributed layer into the engine. If a task seems to require a later milestone's work,
say so rather than building a placeholder.

**Prefer the obvious implementation first.** Correctness, then measurement, then
optimisation — in that order, with the benchmark committed before the optimisation.
Do not micro-optimise a path nobody has measured. "This avoids an allocation" is not
a justification without a JMH result.

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
| Decision records | `documentation/adr/README.md` |
| Milestones and scope | `documentation/roadmap/` |
