# On-disk and on-wire formats

Formats are the most expensive thing in this repository to change. Code can be
rewritten; bytes already on someone's disk cannot. This document governs them.

The roadmap classifies the on-disk format as **hard to reverse**. Treat every change
here with the seriousness that implies.

---

## 1. Every format is documented before it is written

For each package defining a byte layout, a `format.md` sits beside the source. It is
written **before** the encoder, not after — the act of writing the table below is how
you discover that you forgot the block type byte.

Each `format.md` contains:

1. A byte-level table: offset, size, type, endianness, meaning.
2. The magic number and current format version.
3. A worked example: a small real instance, dumped as annotated hex.
4. A rationale section: why this layout, what was considered, what it costs.
5. A version history table with every past version and its compatibility status.

Example:

```
## Footer (fixed 48 bytes, at end of file)

| Offset | Size | Type      | Field                | Notes                        |
|--------|------|-----------|----------------------|------------------------------|
| 0      | var  | varint64  | metaindex offset     | BlockHandle                  |
| var    | var  | varint64  | metaindex size       |                              |
| var    | var  | varint64  | index offset         | BlockHandle                  |
| var    | var  | varint64  | index size           |                              |
| ...    | pad  | zeros     | padding to offset 40 | 2 * kMaxEncodedLength        |
| 40     | 8    | fixed64LE | magic                | 0xdb4775248b80fb57           |
```

---

## 2. Universal rules

- **Little-endian**, always, for every fixed-width integer on disk and on the wire.
  Stated explicitly at every site; never inherited from platform default.
- **Every file starts or ends with a magic number.** A file whose magic does not match
  is not a file of that kind, and the error message says which kind was expected.
- **Every format has a version field**, read before anything else is interpreted.
- **Every block, record, and page carries a checksum** (CRC32C) over its own bytes,
  verified on every read unless verification is explicitly disabled by configuration.
  Never trust a length field before its checksum has passed — a corrupt length read as
  a trusted allocation size is how a corrupt file becomes an OOM or a JVM crash.
- **Lengths precede payloads**, and a length is bounds-checked against the remaining
  extent of the containing block before use. Every time. No exceptions for hot paths.
- **No padding without a stated purpose**, and padding bytes are always zero and always
  verified as zero on read, so that a future field can be added there detectably.
- **Reserved fields are written as zero and rejected if non-zero.** This is what makes
  a future extension detectable rather than silently misread.

---

## 3. Changing a format

A change means anything altering the bytes produced: a new field, a reordering, a
different encoding, a changed default, a new compression codec.

The full procedure, all steps required:

1. **Write an ADR.** Formats are the archetypal hard-to-reverse decision.
2. **Bump `FORMAT_VERSION`.** Never reuse a version number.
3. **Update `format.md`**, including the version history table.
4. **Decide and document the compatibility stance.** One of:
   - *Backward compatible* — new code reads old files. The default expectation, and
     required for anything in `main`.
   - *Forward compatible* — old code reads new files, ignoring unknown fields.
   - *Breaking* — requires an explicit migration or a fresh database. Allowed only
     before the first tagged release of the affected milestone.
5. **Add a golden file.** A committed binary fixture written by the *previous* version,
   plus a test asserting the current reader still reads it correctly. This is the only
   mechanism that actually prevents accidental breakage; a checklist will not.
6. **Add a round-trip property test** — encode arbitrary valid inputs, decode, assert
   equality, with jqwik generating the inputs.
7. **Add a corruption test** — flip a bit at every offset in a small instance and
   assert every case is either detected or provably harmless. Bit-flip-at-every-offset
   is cheap for small structures and finds unchecked length fields immediately.
8. **Commit with a `Format-Change:` trailer** and `Reversible: no`.

Skipping step 5 or step 8 is the most serious process violation in this repository,
because both failures are silent and only surface as unreadable data much later.

---

## 4. Golden files

```
shale-core/src/test/resources/golden/
├── sstable/v1/basic.sst
├── sstable/v1/basic.sst.json      # expected decoded contents
├── sstable/v2/with-filter.sst
├── wal/v1/single-batch.wal
└── manifest/v1/three-edits.manifest
```

Golden files are **never regenerated to make a test pass.** If a golden test fails, the
reader changed behaviour — which is either the bug you are looking for or an
intentional format change that needs the §3 procedure. Regenerating the fixture to get
a green build destroys the only evidence you had.

Each golden file has a sibling `.json` describing its logical contents, so the test
asserts semantics rather than byte equality, and so a human can see what the fixture
is supposed to contain without a hex editor.

---

## 5. Key encoding

The internal key encoding is the single most load-bearing format decision in the engine.
It is referenced by the memtable, every SSTable, the WAL, compaction, and MVCC.

```
InternalKey := userKey (variable) || sequenceNumber:56 || valueType:8
                                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                     packed into one fixed64LE trailer
```

Sort order: user key ascending by the configured `Comparator`, then sequence number
**descending**, so the newest version of a key sorts first and a seek lands on it
directly. Getting this backwards makes every read return stale data in a way that
passes casual testing.

Consequences to hold in mind: 56 bits caps the database at 2^56 mutations, the
`Comparator` name is persisted in the manifest and an incompatible one must refuse to
open the database, and the 8-bit value type must leave room for types not yet invented
(reserve a range and reject unknown values).

Changing this encoding after M4 means rewriting every component. It is worth an hour of
deliberation now.

---

## 6. Wire formats

The Flotilla RPC schema follows the same discipline with different tooling: protobuf
field numbers are never reused or renumbered, fields are added as optional and never
made required, enums always carry an `UNKNOWN = 0` default, and every message is
versioned by the service, not by ad hoc fields.

The `.proto` files live in `flotilla-rpc/src/main/proto/` and are the authoritative
definition. Do not hand-edit generated code.

Note the deliberate asymmetry: protobuf is permitted on the wire but banned for on-disk
formats (`java-style.md` §1). The wire format is plumbing; the storage format is the
subject of the project.
