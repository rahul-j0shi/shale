// flotilla-raft — Raft consensus, hand-written (N1). Depends on shale-core only; the
// engine is the replicated state machine behind the log. No RPC stack here — message
// transport belongs to flotilla-server.

dependencies {
    api(project(":shale-core"))

    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
}
