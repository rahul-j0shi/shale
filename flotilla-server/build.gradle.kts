// flotilla-server — RPC, range sharding, routing, placement/metadata. Top of the graph.
//
// The one permitted RPC stack (gRPC + protobuf, or Netty) and the SLF4J binding are NOT
// chosen ad hoc: java-style.md §1 requires an ADR first, so no such dependency is added
// here yet. Until then this module only wires the modules below it.

dependencies {
    implementation(project(":flotilla-raft"))
    implementation(project(":shale-core"))

    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
}
