// Single repository, four Gradle modules (ADR-0003). The dependency direction is
// enforced in each module's build script:
//   flotilla-server ─▶ flotilla-raft ─▶ shale-core
// shale-core depends on nothing but the JDK (CLAUDE.md §2, N1).

rootProject.name = "shale"

dependencyResolutionManagement {
    // Declared centrally here, not per-project, so the dependency surface is auditable
    // in one place. Core mechanisms are hand-written; see java-style.md §1 allowlist.
    repositories {
        mavenCentral()
    }
}

include(
    "shale-core",
    "shale-bench",
    "flotilla-raft",
    "flotilla-server",
)
