# D5 — The PostgreSQL wire protocol: implementation plan

**Status:** planned; starts after D4 is tagged. **Depends on:** D2–D4 (sessions, statements,
transactions). **Creates:** `shale-server` (package `dev.shale.server`), zero runtime dependencies.

**Goal:** ShaleDB becomes a database you connect to with tools that already exist. `psql` works
as the shell, and the standard PostgreSQL JDBC driver works for applications. There is no custom
protocol, client or shell to build. And the server survives `kill -9` without losing an
acknowledged commit — proven by a test that kills a real process.

## Decisions required in the ADR ("PostgreSQL wire protocol subset")

1. **Protocol version 3.0, a documented subset.** `protocol.md` lists every message implemented.
   Anything else gets a correct `ErrorResponse` (SQLSTATE `0A000`), never a dropped connection
   (`on-disk-formats.md` §6).
   - **Startup:**
     - `SSLRequest` and `GSSENCRequest` are answered `N`;
     - `StartupMessage` gets `AuthenticationOk` (trust auth; the server binds `127.0.0.1` by
       default);
     - then `ParameterStatus`: `server_version`, `server_encoding`, `client_encoding` (UTF8),
       `DateStyle` (ISO), `integer_datetimes`, `standard_conforming_strings`, `TimeZone`;
     - then `BackendKeyData` and `ReadyForQuery`.
   - **Simple query:** `Query` → `RowDescription` / `DataRow` / `CommandComplete` /
     `EmptyQueryResponse` / `ErrorResponse`, then `ReadyForQuery`. Several statements per string.
   - **Extended query** (pgjdbc's default):
     - `Parse`, `Bind`, `Describe` (statement and portal), `Execute` (a row limit gives
       `PortalSuspended`), `Close`, `Sync`, `Flush`;
     - the matching `ParseComplete`, `BindComplete`, `ParameterDescription`, `NoData`,
       `CloseComplete`;
     - after an error, messages are discarded until `Sync`, as the protocol requires.
   - **`Terminate`.** **`CancelRequest`:** matched by backend key; sets a flag that operators
     check between rows.
2. **Types.** BIGINT → `int8` (OID 20), DOUBLE → `float8` (701), TEXT → `text` (25), BOOLEAN →
   `bool` (16). Text format everywhere. Binary format for these four, because pgjdbc switches to
   binary for server-prepared statements. Parameters sent as `int2`/`int4` are widened to BIGINT;
   an unspecified (OID 0) parameter takes its type from the binder.
3. **Session state.** `ReadyForQuery` reports `I`, `T` or `E`. After an error inside `BEGIN`,
   statements fail with `25P02` until `ROLLBACK`, as in PostgreSQL. `SET` and `SHOW` support a
   small documented set. `synchronous_commit` maps to durability (`on` → `SYNC`, `off` → `NONE`),
   so a PostgreSQL user's own knob controls the fsync.
4. **Errors.** `ErrorResponse` carries severity, SQLSTATE, message and position:
   | Condition | SQLSTATE |
   |---|---|
   | syntax error | `42601` |
   | undefined table / undefined column | `42P01` / `42703` |
   | unique violation / not-null violation | `23505` / `23502` |
   | serialization failure (D4 conflict) | `40001` |
   | in a failed transaction | `25P02` |
   | feature not supported | `0A000` |
   | query memory limit exceeded | `53200` |
   | query cancelled | `57014` |
   | `CorruptionException` (after which the server refuses writes, N4) | `XX001` |
5. **Threading and limits.** One virtual thread per connection (`java-style.md` §2). Limits on
   connections and message size. A statement timeout on the injected `Clock`. Every length field
   is bounds-checked before its body is read.
6. **Test-scope pgjdbc.** `shale-server`'s conformance tests use the real PostgreSQL JDBC driver,
   test scope only; this milestone adds that to `java-style.md` §1. A CI job also runs a `psql`
   script (from the distribution's `postgresql-client` package) against the server.

## Scope

**In D5:** the protocol codec and state machine (`dev.shale.server.pgwire`); sessions; the
listener; graceful shutdown (stop accepting, finish in-flight statements, close the database);
`installDist` launch scripts (`bin/shaledb`) and a `Dockerfile` on a JDK 25 base image.

**Not planned:** SSL/TLS, password authentication, `COPY`, `LISTEN`/`NOTIFY`, the `pg_catalog`
views that `psql`'s `\d` commands query (they return `0A000`).

## Task order (TDD; each task one commit, gate green)

1. ADR and `protocol.md` (message tables, one annotated byte-level exchange); the ADR index.
2. Module skeleton, the shared dependency check, `package-info`s; CLAUDE.md §2 and
   `project-scope.md` in the same commit.
3. Message codec: every message round-trips; tests replay byte sequences from the protocol
   documentation; bit-flip and truncation tests reject malformed lengths.
4. Startup and simple-query flow; error mapping; transaction status.
5. Extended-query flow; binary formats for the four types; portal row limits.
6. `SET` / `SHOW`, `synchronous_commit`, cancel, statement timeout.
7. Conformance tests over pgjdbc (autocommit, transactions, prepared statements, errors,
   `40001` retry) and the CI `psql` script.
8. **Process-level crash test** (tagged `crash`): start the server in a child JVM; commit rows
   over JDBC under `synchronous_commit = on`, recording each acknowledgement; SIGKILL at a seeded
   point; restart. Every acknowledged row is present, and `Database.verify()` is clean.
9. Packaging: `installDist`, `Dockerfile`. Docs: `architecture/d5-postgres-wire-protocol.md`
   (the connection state machine, a query's message flow), README status, changelog, tag
   `d5-pgwire`.

## Acceptance gates

- `psql` connects, runs DDL, DML, queries and transactions, and shows errors with positions.
- pgjdbc passes the conformance suite, including prepared statements past its `prepareThreshold`
  (binary transfer).
- The process-level kill test passes 20 seeded runs in `crashTest`.
- `shale-server`'s runtime dependency graph is empty.

## References

PostgreSQL documentation, "Frontend/Backend Protocol" (chapter 55: message flow and formats) and
Appendix A (error codes); the pgjdbc source (`QueryExecutorImpl`) for what a real driver sends;
JEP 444 (virtual threads).
