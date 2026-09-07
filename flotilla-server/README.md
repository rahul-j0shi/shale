# flotilla-server — *empty by design until M10*

**This module has no source yet, and that is deliberate.** It sits at the top of the module
graph so the build enforces the direction: it may depend on `flotilla-raft` and `shale-core`,
and neither may depend on it ([`CLAUDE.md`](../CLAUDE.md) §2).

**What lands here at M10:** the RPC layer, the router, range sharding into Regions with
split/merge/rebalance, a placement-driver-style metadata service with a timestamp oracle, and
failure detection.

**Why there is no gRPC dependency yet.** `java-style.md` §1 permits exactly one RPC stack and
one SLF4J facade in `flotilla-*` — chosen in an ADR, not ad hoc. Until that ADR exists the
module wires nothing, which is why its `build.gradle.kts` names only the two project modules.
