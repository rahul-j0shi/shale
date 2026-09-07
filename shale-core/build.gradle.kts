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

// N1, enforced rather than asserted. Checkstyle's IllegalImport catches a banned import at
// the point of writing, but it cannot see a dependency added to this file — and "the engine
// depends on nothing but the JDK" is the one claim the whole project rests on. This resolves
// the runtime graph and fails if anything at all is on it. Wired into `check`, so `build`
// runs it locally and in CI.
val runtimeDependencyNames =
    configurations.named("runtimeClasspath").flatMap { configuration ->
        provider {
            configuration.incoming.artifacts.artifacts.map {
                it.id.componentIdentifier.displayName
            }
        }
    }

val verifyNoRuntimeDependencies by
    tasks.registering {
        group = "verification"
        description = "Asserts N1: shale-core resolves zero runtime dependencies."
        val names = runtimeDependencyNames
        doLast {
            val found = names.get()
            require(found.isEmpty()) {
                "N1 violated: shale-core must have zero runtime dependencies, but resolved " +
                    "${found.size}: $found. The engine compiles against the JDK and nothing " +
                    "else (java-style.md §1). Adding one requires an ADR amending the allowlist."
            }
        }
    }

tasks.named("check") { dependsOn(verifyNoRuntimeDependencies) }
