# D5 — Server, protocol and shell: implementation plan

**Status:** planned 2026-09-26; starts after D4 is tagged. **Depends on:** D4 (sessions and
transactions). **Creates:** the `shale-server` module (package root `dev.shale.server`).

**Goal:** ShaleDB becomes a database you connect to. A single-node server speaks a documented
protocol, exposes the engine's metrics, and survives `kill -9` without losing an acknowledged
commit — proven by a test that actually kills a process. A shell lets a person use it.

## Decisions required in the ADR ("Client protocol and server threading")

1. **Protocol.** Recommended: HTTP/1.1 + JSON, versioned by path (`/v1/…`). Reasons: every
   language and the browser can speak it, and the JDK ships a server (`jdk.httpserver`) and a
   client (`java.net.http`), so the module stays dependency-free. Rejected for now: the
   PostgreSQL wire protocol (lets `psql` connect, but it is a project of its own — stretch) and
   a custom binary TCP protocol (no tooling).
2. **JSON.** A small hand-written RFC 8259 encoder and parser in `dev.shale.server.json`. JSON is
   plumbing, not a project subject. It is hand-written to keep the zero-dependency property
   (ADR-0013). If it becomes a burden, an ADR may admit a JSON library to `shale-server` only.
3. **Endpoints.**
   - `POST /v1/sql` `{sql, params, session?}` → `{columns:[{name,type}], rows:[[…]]}` or
     `{rowsAffected}`;
   - `POST /v1/sessions` → `{session}`, for multi-request transactions;
     `DELETE /v1/sessions/{id}`;
   - `GET /v1/health`;
   - `GET /v1/metrics` → engine and DB counters: memtable bytes, SSTables and bytes per level,
     write/read/space amplification, compactions, stalls, cache hit rate, group-commit size,
     commits and aborts.
4. **Error mapping.**
   | Error | Response |
   |---|---|
   | Syntax, semantic or constraint `SqlException` | 400 with `{code, message, line, column}` |
   | `TransactionConflictException` | 409, retryable |
   | `EngineStateException` (stalled or closed) | 503 |
   | `CorruptionException` | 500; the server stops accepting writes (N4) |
5. **Threading.** A virtual-thread-per-request executor (`java-style.md` §2 allows virtual
   threads for connection handling). Sessions live in a registry with idle expiry on the
   injected `Clock`. The engine's background work keeps its own bounded platform threads.
6. **Limits and safety.** Binds `127.0.0.1` by default. No authentication or TLS: a documented
   non-goal (ADR-0013). There are a maximum request size, a maximum number of result rows (a
   larger result is an error telling the client to add `LIMIT`) and a statement timeout on the
   `Clock`. Parameters are the only way the demo passes values.
7. **Wire document.** `shale-server/src/main/java/dev/shale/server/protocol.md` is authoritative.
   Fields are added, never renamed or retyped, following the spirit of `on-disk-formats.md` §6.

## Scope

**In D5:** the server, sessions, protocol, metrics endpoint, graceful shutdown (stop accepting,
finish in-flight requests, close the database); `ShaleClient`, a small Java client over
`java.net.http`, in `dev.shale.server.client`; the `shale` shell — a REPL in embedded
(`--dir`) or remote (`--url`) mode with table output, `\d` (describe), `\timing` and
`EXPLAIN`; `installDist` launch scripts and a `Dockerfile` (JDK 25 base).

**Deferred:** result paging tokens; prepared-statement caching; PostgreSQL wire protocol; a
JDBC driver (stretch).

## Task order (TDD; each task one commit, gate green)

1. ADR, `protocol.md` with example exchanges, this plan, ADR index.
2. Module skeleton and dependency check; CLAUDE.md §2 and `project-scope.md` updated in the
   same commit.
3. JSON codec; round-trip property and RFC 8259 example tests.
4. Server core, `/v1/sql` autocommit, error mapping; in-process tests with `java.net.http`.
5. Sessions and transactions over HTTP; idle expiry.
6. `/v1/metrics` and `/v1/health`.
7. `ShaleClient` and the shell.
8. **Process-level crash test** (tagged `crash`): start the server in a child JVM; drive `SYNC`
   commits from the test, recording each acknowledgement; `destroyForcibly()` (SIGKILL) at a
   seeded point; restart. Every acknowledged commit is present and `verify()` is clean. This is
   the charter's "`kill -9` + restart with zero acknowledged-write loss" criterion, met literally.
9. Packaging: `installDist`, `Dockerfile`, `scripts/shale-server.sh`.
10. Docs: `architecture/d5-server.md` (request lifecycle, threading, error mapping), README
    status, release note, tag `d5-server`.

## Acceptance gates

- Every endpoint and error mapping in `protocol.md` has a test; the examples in the document are
  checked by a test so they cannot drift.
- The process-level kill test passes 20 seeded runs in `crashTest`.
- Graceful shutdown under load loses no acknowledged commit and leaks no session or cursor.
- The runtime dependency graph of `shale-server` is empty.

## References

RFC 8259 (JSON); RFC 9110 (HTTP semantics); JDK `com.sun.net.httpserver` and `java.net.http`
docs; JEP 444 (virtual threads); PostgreSQL frontend/backend protocol docs (the stretch path);
CockroachDB and TiDB HTTP status endpoints as models for `/v1/metrics`.
