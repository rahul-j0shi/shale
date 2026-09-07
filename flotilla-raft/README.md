# flotilla-raft — *empty by design until M9*

**This module has no source yet, and that is deliberate.** It exists now so the dependency
direction is enforced by the build rather than asserted in prose: `flotilla-raft` may depend
on `shale-core`, and `shale-core` may never depend on it. That boundary is the architectural
point of the project ([`CLAUDE.md`](../CLAUDE.md) §2), and a boundary Gradle checks is worth
more than one a diagram draws.

**What lands here at M9:** leader election (`RequestVote`, Pre-Vote), log replication
(`AppendEntries` doubling as heartbeat), the safety argument, snapshotting — where the Raft
snapshot *is* an engine snapshot rather than a separate serialisation — membership changes via
joint consensus, and linearizable reads through ReadIndex or leases. Hand-written: a Raft
library is banned by N1, like every other core concept.

**Prerequisite:** the engine ships first (M5–M7). See the roadmap in the
[README](../README.md#roadmap) and the charter's §C for the design references.
