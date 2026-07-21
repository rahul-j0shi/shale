# 0003. Single repository, four Gradle modules

- **Status:** Accepted
- **Date:** 2026-07-20
- **Milestone:** M0
- **Reversible:** yes — the module split can be changed by moving code and editing build files, with no on-disk or wire consequence.

## Context

The project spans an embeddable single-node storage engine (Shale) and a distributed store
built on it (Flotilla). These have very different dependency profiles: the engine must be
dependency-free and embeddable (N1, [[0002-hand-write-core-mechanisms]]), while the server
legitimately needs an RPC stack and a logging binding. The build must make the boundary
between "engine" and "cluster" a real, enforced thing rather than a naming convention, so
that a cluster concern cannot leak into the engine unnoticed.

## Options considered

### Option A — One module
Everything in a single source set. Simplest build. But nothing stops `shale-core` code from
importing cluster code, and the "embeddable, zero-dependency engine" claim becomes
unenforceable — the exact failure this project is trying to avoid.

### Option B — Separate repositories
One repo per component. Maximum isolation, but heavyweight for a solo project: cross-cutting
changes (e.g. the internal-key encoding used by both engine and Raft snapshotting) span
repos, and the roadmap's milestone ordering is harder to see as one history.

### Option C — One repository, four Gradle modules
`shale-core`, `shale-bench`, `flotilla-raft`, `flotilla-server`, with the dependency
direction enforced in each build script:
`flotilla-server → flotilla-raft → shale-core`, and `shale-core` depending on nothing but
the JDK.

## Decision

Option C. Four modules in one repository with the enforced direction above. `shale-core` has
zero runtime dependencies and must never depend on any `flotilla-*` module or on any
networking, RPC, or clustering code. `shale-bench` holds JMH/YCSB harnesses; the RPC stack
and logging binding live only in the Flotilla modules and are gated on their own ADR.

## Rationale

A single repo keeps the milestone history and cross-cutting changes legible for a solo
learning project, while the module boundary makes the architectural line — engine vs.
cluster — checkable by the build rather than by discipline. The dependency arrow is the
architectural point of the project; encoding it in `build.gradle.kts` is what keeps it real.

## Consequences

**Positive:** the engine stays embeddable and auditable; a stray cluster import fails to
compile; one history for the whole system.

**Negative:** four build scripts to maintain; module boundaries occasionally force an
interface where a single module would not.

**Neutral:** benchmarks and the future B+Tree backend (M8) attach as additional modules/source
sets without disturbing the split.

**If we need to reverse this:** merge or re-split modules by relocating sources and editing
`settings.gradle.kts` and the affected build scripts. No format or wire migration.

## References

- `CLAUDE.md` §2 — repository map and the dependency rule
- `documentation/conventions/java-style.md` §1 — the dependency allowlist
