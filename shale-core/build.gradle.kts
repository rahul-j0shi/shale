// shale-core — the LSM storage engine.
//
// N1 / java-style.md §1: this module has ZERO runtime dependencies. It compiles against
// the JDK and nothing else, and must never depend on any flotilla-* module or on any
// networking, RPC, or clustering code. That boundary is the architectural point of the
// project (CLAUDE.md §2). Test-scope tooling only, from the allowlist.

dependencies {
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
}
