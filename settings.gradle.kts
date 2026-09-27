// One repository, one module per layer (ADR-0013). The dependency direction is enforced
// in each module's build script — every module depends inward on shale-core, never the
// reverse. shale-core depends on nothing but the JDK (CLAUDE.md §2, N1). The ShaleDB
// modules (shale-db, shale-server, shale-demo) are added by the milestones that create them.

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
)
