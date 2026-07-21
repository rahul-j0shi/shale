# 0005. Little-endian fixed-width integers

- **Status:** Accepted
- **Date:** 2026-07-21
- **Milestone:** M0
- **Reversible:** no — the byte order is baked into every fixed-width integer written to disk or the wire; changing it makes existing data unreadable.

## Context

The engine will write many fixed-width integers to disk and, later, to the wire: the
internal-key trailer ([[0004-internal-key-encoding]]), block handles, footers, checksums,
sequence numbers, file numbers. A byte order must be chosen once and applied everywhere,
because a fixed-width integer is only meaningful if reader and writer agree on it, and a
platform-default order is a latent portability bug that surfaces only when data moves between
machines.

## Options considered

### Option A — Platform/native order
Use `ByteOrder.nativeOrder()`. Fast, zero-thought. But it makes the on-disk format
non-portable and, worse, silently so: a file written on one architecture may be misread on
another with no error, just wrong numbers. Unacceptable for a storage format.

### Option B — Big-endian ("network byte order")
The traditional wire convention. Fine, but it disagrees with the native order of every
platform this project targets (x86-64, ARM64 are little-endian), so every read/write pays a
byte swap, and it disagrees with the reference implementations we study.

### Option C — Little-endian, explicit at every site
Match LevelDB/RocksDB and the native order of the target platforms. State the order
explicitly at each read/write via a single coder rather than relying on any default.

## Decision

Option C. Every fixed-width integer written to disk or the wire is **little-endian**, encoded
through `dev.shale.internal.coding.LittleEndian` (M0 provides `putFixed64`/`getFixed64`;
`fixed32` and varints arrive with the WAL at M1). Never inherit byte order from a platform
default or an ambient `ByteBuffer` order; the coder names the order at the call site.

Checkstyle downgrades `ByteOrder.BIG_ENDIAN`/`nativeOrder()` to a warning that must be
justified as wire/RPC code (`config/checkstyle/checkstyle.xml`); on-disk code uses the coder.

## Rationale

Little-endian matches both LevelDB (so our formats and code stay comparable to the reference
implementations) and the native order of every target CPU (so no byte swap on the hot path).
Routing all fixed-width integers through one coder makes the order a single, tested,
greppable decision instead of an assumption scattered across encoders — and the act of
calling `LittleEndian.putFixed64` documents the choice at every site.

## Consequences

**Positive:** portable, unambiguous on-disk integers; no per-read byte swap on target
hardware; one place to test the byte order; grep-compatible with LevelDB.

**Negative:** big-endian wire protocols (if any are added later) must convert explicitly at
the boundary and justify the checkstyle warning.

**Neutral:** endianness is invisible to callers who only use the coder.

**If we need to reverse this:** no in-place migration; every fixed-width field in every file
would have to be rewritten under a new `FORMAT_VERSION`. This is why it is settled at M0.

## References

- `documentation/conventions/on-disk-formats.md` §2 — universal format rules
- LevelDB `util/coding.cc` (`EncodeFixed64`, `DecodeFixed64`)
- [[0004-internal-key-encoding]]
