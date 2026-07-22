# M0 — Skeleton & Interfaces (release note)

**Tag:** `m0-skeleton` · **Status:** complete, `./gradlew build` green on JDK 25.

## Delivered

The hard-to-reverse core contracts the rest of the engine is built on, plus the test
harness that will validate every later milestone. No storage mechanism yet — that begins
with the WAL at M1.

- **Public API (`dev.shale`):** `StorageBackend` SPI (byte-key/byte-value, explicit
  `Durability` on writes, `null`-return `get`, `Cursor` range scans), `KeyComparator` +
  `BytewiseComparator` (unsigned lexicographic, named for reopen-compatibility), `ByteRange`,
  and the `ShaleException` hierarchy (`CorruptionException` carries offset/expected/actual).
- **Internal-key encoding (`dev.shale.internal.key`, ADR-0004):** `InternalKey`
  (`userKey || fixed64LE((seq << 8) | type)`), `ValueType` with a reserved code range,
  and `InternalKeyComparator` ordering user key ascending then sequence descending so a
  seek lands on the newest version.
- **Coding (`dev.shale.internal.coding`, ADR-0005):** little-endian `fixed64` coder — the
  one byte-order decision, made once and reused everywhere.
- **Concurrency annotations:** `@ThreadSafe` / `@NotThreadSafe` / `@Immutable`, one per type.
- **Model harness (test scope):** an in-memory `ReferenceBackend` built on the real M0
  encoding, diffed against a `TreeMap` `ReferenceModel` oracle after every batch of a seeded
  random op sequence, with a `HarnessSelfTest` that proves the diff routine fails on a
  backend that ignores deletes.

## Decisions ratified

ADR-0004 (internal-key encoding), ADR-0005 (little-endian integers), ADR-0006
(`StorageBackend` SPI) — all `Accepted`, all `Reversible: no`. ADR-0001 and ADR-0003 were
backfilled to match the index.

## Exit criteria (met)

- `./gradlew build` green; `shale-core` has zero runtime dependencies (N1).
- 29 tests pass across 11 classes: encode/decode and comparator-total-order property tests,
  unit tests, the 5,000-op model harness (reproducible from `-Dshale.test.seed`), and the
  harness self-test.
- Every new type carries its concurrency annotation and a citation; every package a
  `package-info.java`.

## Notes for the next milestone

The model harness is the durable asset here: M1+ plug their real engine into the same
`StorageBackend` seam and the same oracle, and extend the op mix with restart / flush /
compaction / snapshot as those mechanisms arrive. The first real durability (WAL, `Clock`,
and the first `Metrics` emitters) lands at M1.

First run of the build gate on real source also surfaced and fixed several latent issues in
the pre-existing tooling config (google-java-format vs. JDK 25, invalid checkstyle module
placement, the test-method-name pattern vs. testing.md §3) — see the `build:` commits on
this milestone.
