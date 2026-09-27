# D5 — The PostgreSQL wire protocol: implementation plan

**Status:** planned; starts after D4 is tagged. **Depends on:** D2–D4 (`Session`,
`PreparedStatement`, `Result`, command tags, SQLSTATEs, transaction states, `expireIdle`).
**Creates:** `shale-server` (package `dev.shale.server`), zero runtime dependencies. **ADR:** 0021.
**Estimate:** 2–3 focused weeks, in 7 steps.

**Goal.** ShaleDB becomes a database you connect to with tools that already exist: `psql` as the
shell, and the standard PostgreSQL JDBC driver for applications. There is no custom protocol,
client or shell. The server survives `kill -9` without losing an acknowledged commit, proven by
a test that kills a real process.

---

## 1. Where the code will be at the start (after D4)

`Session` executes SQL with `$n` parameters. `PreparedStatement` parses once and reports
parameter types and result columns. `Result` is `RowResult` (streaming) or `CountResult`
(PostgreSQL command tags). Every error has a SQLSTATE and a position. Session states are `IDLE`,
`IN_TRANSACTION` and `FAILED`.

## 2. The design (decided — ADR-0021, "PostgreSQL wire protocol subset", records it)

### 2.1 The protocol subset (version 3.0; `protocol.md` lists every message)

**Startup:**
1. `SSLRequest` or `GSSENCRequest` → the byte `N` (pgjdbc's default `sslmode=prefer` then
   continues in plaintext).
2. `StartupMessage` (protocol 196608). Parameters are read and ignored, except `user`,
   `database`, `application_name`, `client_encoding` (must be `UTF8`, else `08P01`).
3. `AuthenticationOk` — trust authentication. The server binds `127.0.0.1` by default.
4. `ParameterStatus` for:
   - `server_version` = `16.0` — pgjdbc parses it to choose features, so it must look like a
     PostgreSQL version;
   - `server_encoding` and `client_encoding` = `UTF8`;
   - `DateStyle` = `ISO, MDY`;
   - `integer_datetimes` = `on`;
   - `standard_conforming_strings` = `on`;
   - `TimeZone` = `UTC`;
   - `is_superuser` = `off`;
   - `application_name`.
5. `BackendKeyData` (process id, secret), then `ReadyForQuery`.

**Simple query:**
- `Query` → for each statement: `RowDescription` + `DataRow`* + `CommandComplete`, or
  `CommandComplete`, or `EmptyQueryResponse`.
- Then one `ReadyForQuery`.
- On error: `ErrorResponse`, skip the rest of the string, then `ReadyForQuery`.
- **Several statements in one `Query`** run as one implicit transaction (unless they contain their
  own `BEGIN`/`COMMIT`), exactly as PostgreSQL does.

**Extended query:**

| Message | Reply |
|---|---|
| `Parse` (named or unnamed statement, parameter type OIDs, 0 = unspecified) | `ParseComplete` |
| `Bind` (portal, parameter format codes and values, result format codes) | `BindComplete` |
| `Describe` S | `ParameterDescription` + `RowDescription` or `NoData` |
| `Describe` P | `RowDescription` or `NoData` |
| `Execute` (portal, row limit) | `DataRow`* + `CommandComplete`, or `PortalSuspended` at the limit |
| `Close` S/P | `CloseComplete` |
| `Sync` | `ReadyForQuery`; ends an implicit transaction |
| `Flush` | send what is buffered |

- After an error, every message is discarded until `Sync`.
- A portal holds its open `RowResult` until it is closed or the transaction ends.

**Other:**
- `Terminate` closes the connection.
- **`CancelRequest`,** on a new connection with the process id and secret, sets a flag the
  session's operators check between rows → `57014`.
- **`NoticeResponse`** carries warnings (for example `COMMIT` with no transaction, `25P01`) and
  D6's cost notices.
- `ErrorResponse` fields: `S`/`V` severity, `C` SQLSTATE, `M` message, `P` position.

**Anything else** → `ErrorResponse` `0A000` naming the message type; the connection survives.
**Not supported:** `COPY`, `FunctionCall`, replication, SASL or MD5 authentication.

### 2.2 Types and formats

| ShaleDB type | Result OID (text/binary) | Accepted parameter OIDs |
|---|---|---|
| BIGINT | `int8` 20 | 20, `int4` 23, `int2` 21 (widened) |
| DOUBLE | `float8` 701 | 701, `float4` 700 (widened), 20/23/21 (converted) |
| TEXT | `text` 25 | 25, `varchar` 1043 (pgjdbc's default for `setString`), 0 |
| BOOLEAN | `bool` 16 | 16 |

- **An unspecified parameter (OID 0)** takes the binder's type.
- **Text format** everywhere by default:
  - BIGINT is decimal;
  - DOUBLE uses PostgreSQL's shortest round-trip form, with `Infinity`, `-Infinity`, `NaN`;
  - BOOLEAN is `t`/`f`;
  - NULL is length −1.
- **Binary format** for parameters and results of every OID in the table:
  - int2, int4 and int8 are big-endian;
  - float4 and float8 are IEEE-754 big-endian;
  - bool is one byte;
  - text and varchar are raw UTF-8.

  pgjdbc switches to binary after `prepareThreshold` (5) executions of a statement.
- **Mismatches:** an unknown parameter OID → `42804`; a malformed value → `22P02`.

### 2.3 Sessions, transactions, durability

- **One `Session` per connection,** on its own virtual thread (`java-style.md` §2).
- **`ReadyForQuery` status** comes from the session state: `I` idle, `T` in a transaction, `E`
  failed.
- **`SET` and `SHOW`:**
  - `SET` accepts `application_name`, `client_encoding` (`UTF8` only), `extra_float_digits`
    (accepted, ignored), `DateStyle` (accepted if `ISO`), `synchronous_commit`, and D6's
    `shale.cost_notices`;
  - `SHOW` returns their values;
  - anything else → `0A000`.
- **`synchronous_commit`** maps to durability: `on` → `SYNC`, `off` → `NONE`. So a PostgreSQL
  user's own knob controls the fsync.
- **Timers:** every second a scheduler thread calls `Database.expireIdle()`. The statement
  timeout (`statement_timeout`, accepted by `SET`) is checked between rows on the injected
  `Clock` → `57014`.

### 2.4 Limits, safety, shutdown

- **Limits:** a message length over 64 MiB → `08P01`, and the connection closes; more than
  `maxConnections` (default 64) → `53300`. Every length field is bounds-checked before its body is
  read.
- **Binding:** `127.0.0.1` on port 5433 by default (5432 is left for a real PostgreSQL). There is
  no authentication or TLS, which is a documented non-goal.
- **Shutdown on SIGTERM or `close()`:**
  1. stop accepting;
  2. let in-flight statements finish, up to a grace period;
  3. roll back open transactions;
  4. close the database.

### 2.5 Packaging

- **`ShaleDbServer.main`:** `--dir`, `--port`, `--bind`.
- **Distribution:** `installDist` produces `bin/shaledb`.
- **Docker:** a `Dockerfile` on an Eclipse Temurin 25 JRE image, exposing 5433 with a volume for
  the data directory.

## 3. New types and files (`shale-server`)

| Item | Package | Step |
|---|---|---|
| module build; `verifyNoRuntimeDependencies`; `verifyModuleGraph` edge `shale-server → shale-db`; `testImplementation` of pgjdbc | `shale-server/build.gradle.kts`, root | 2 |
| `protocol.md` | `dev.shale.server.pgwire` | 1 |
| `MessageReader`, `MessageWriter`, `FrontendMessage` (sealed records), `BackendMessage` (sealed records) | `dev.shale.server.pgwire` | 3 |
| `TypeCodec` (the §2.2 conversions) | `dev.shale.server.pgwire` | 3 |
| `Connection` (the per-connection state machine), `Portal`, `CancelRegistry` | `dev.shale.server` | 4, 5 |
| `ShaleDbServer` (listener, virtual threads, timers, shutdown) | `dev.shale.server` | 4, 6 |

## 4. Steps

### Step 1 — ADR-0021 and `protocol.md` (`adr/0021-pgwire`), ~1 day
`docs(pgwire)`: ADR-0021 records §2. Alternatives:
- HTTP/JSON (rejected: a custom client and shell, with nothing standard able to connect);
- a hand-rolled binary protocol (rejected: same);
- per-statement autocommit inside a multi-statement `Query` (rejected: PostgreSQL makes it one
  transaction, and clients rely on that).

`protocol.md` holds every message's layout and one annotated byte-level exchange (startup, one
`Parse`/`Bind`/`Execute`/`Sync`). **Done when:** merged.

### Step 2 — the module (`d05/module`), ~½ day
`build(build)`: the module, checks and graph edge; pgjdbc pinned in `libs.versions.toml`, test
scope only; `java-style.md` §1 updated; CLAUDE.md §2 and `project-scope.md`. **Done when:** green.

### Step 3 — the codec (`d05/codec`), ~3 days
`feat(pgwire)`: `MessageReader`, `MessageWriter`, the message records, `TypeCodec`. Tests:
- `MessageCodecTest`: every message round-trips; the byte sequences from `protocol.md`'s example
  decode exactly.
- A length overrunning the stream → `08P01`; a truncated message waits for more bytes (it is not
  corruption).
- `TypeCodecPropertyTest`: text and binary round-trips for every OID in §2.2, including NaN,
  ±Infinity, `Long.MIN_VALUE`, empty and non-ASCII text.

**Done when:** green.

### Step 4 — startup and simple query (`d05/simple-query`), ~3 days
`feat(api)`: `Connection` startup, `Query` handling, implicit transactions, error and notice
mapping, `ReadyForQuery` status; the `ShaleDbServer` listener. Tests (`SimpleQueryTest`, over a
raw socket in-process):
- startup sequence bytes;
- a multi-statement query;
- an error mid-string skips the rest;
- `BEGIN` / error / `SELECT` → `25P02` → `ROLLBACK`;
- `EmptyQueryResponse`.

**Done when:** green.

### Step 5 — extended query, cancel, `SET`/`SHOW` (`d05/extended-query`), ~4 days
`feat(api)`: `Parse`/`Bind`/`Describe`/`Execute`/`Close`/`Sync`/`Flush`, `Portal` with row
limits, binary formats, `CancelRegistry`, `SET`/`SHOW`, `synchronous_commit`,
`statement_timeout`. The D2 parser gains `SET name { TO | = } value` and `SHOW name`, which the
`Session` executes against its settings. Tests:
- `ExtendedQueryTest`: error-until-`Sync`; a suspended portal resumes; `Describe` before `Bind`.
- `PgJdbcConformanceTest`, through the real driver:
  - autocommit and explicit transactions;
  - `setLong`/`setInt`/`setString`/`setDouble`/`setBoolean`/`setNull`;
  - a prepared statement executed 10 times (so it crosses into binary);
  - `fetchSize` paging;
  - `40001` surfacing as `SQLException.getSQLState()`;
  - `synchronous_commit = off`;
  - cancel.

**Done when:** green.

### Step 6 — the real-process crash test and shutdown (`d05/kill-test`), ~2 days
1. `feat(api)`: graceful shutdown, `maxConnections`, the timers.
2. `test(recovery)`: `ServerKillTest` (tag `crash`).
   - **Setup:** start `ShaleDbServer` in a child JVM (`ProcessBuilder` on the test classpath).
   - **Load:** over JDBC with `synchronous_commit = on`, insert rows, recording each acknowledged
     id.
   - **Kill:** `destroyForcibly()` (SIGKILL) after a seeded number of acknowledgements; restart
     the server on the same directory.
   - **Assert:** every acknowledged id is present, no unacknowledged id is half-written (each row
     whole or absent), and `verify()` is clean.
   - Runs 20 seeds.
3. `build(ci)`: a CI step installs `postgresql-client` if missing and runs `scripts/psql-smoke.sql`
   through `psql` against a started server: DDL, DML, a transaction, an error with position.
4. `build(ci)`: **a second language.** A CI step installs `psycopg2-binary` with `pip` and runs
   `scripts/python-smoke.py` against a started server. The script:
   - creates a table;
   - inserts with parameters;
   - selects;
   - commits, in psycopg2's default mode, which opens a transaction implicitly;
   - checks that a unique violation surfaces as `psycopg2.errors.UniqueViolation`.

   **Why:** "any PostgreSQL client" is a claim, and pgjdbc alone does not prove it. psycopg2
   uses only the simple query protocol with client-side parameters, the opposite path from
   pgjdbc's extended protocol, so the two drivers together cover both. Python is a CI tool
   here, not a dependency of any module.

**Done when:** `ServerKillTest` passes 20 of 20 in `crashTest`; the CI `psql` and Python steps are
green.

### Step 7 — packaging, documentation, tag (`d05/docs`), ~2 days
- `installDist`, `Dockerfile`, `scripts/shaledb.sh`.
- `architecture/d5-postgres-wire-protocol.md`: the connection state machine, a `Parse`–`Sync`
  message flow, threading.
- Glossary rows (portal, prepared statement, `ReadyForQuery` status).
- README quickstart: `bin/shaledb --dir data` then `psql -h 127.0.0.1 -p 5433`.
- **`documentation/guides/shaledb-quickstart.md`**, for someone who wants to *use* the database.
  It covers:
  - **Running the server three ways:** `bin/shaledb`, `scripts/shaledb.sh`, and Docker. It gives
    the flags, the port, and where the data lives.
  - **Connecting, with a copy-paste example for each:**
    - `psql`;
    - JDBC: the URL, and a 15-line Java program;
    - Python: psycopg2;
    - any other PostgreSQL driver, with what to expect.
  - **A first session:** create a table, insert, query, a transaction, and `EXPLAIN`.
  - **Writing an application against it:**
    - retry on `40001`;
    - generate ids in the application;
    - store times as epoch milliseconds;
    - set `synchronous_commit` per session.
  - **What will not work, and why:**
    - `pg_catalog` introspection: ORMs' schema discovery and `psql`'s `\d`;
    - authentication and TLS: bind to localhost only;
    - `COPY`.
  - **Stopping it safely**, and backing it up. Until D7's `BACKUP TO`, backup means stopping the
    server and copying the directory.

  Its snippets are the CI smoke scripts' contents, so they cannot drift.
- `guides/shaledb-sql.md` gains a "Sessions and settings" section: `SET`/`SHOW` and
  `synchronous_commit`. The FAQ's "How will I connect" answer loses "planned".
- Changelog; the completion plan's status table.
- **Reconciliation pass for D6.** Tag `d5-pgwire`.

## 5. Milestone acceptance gates

- `psql` runs DDL, DML, queries and transactions, and shows errors with positions (CI).
- pgjdbc passes the conformance suite, including binary transfer after `prepareThreshold`.
- `ServerKillTest`: no acknowledged commit lost across 20 seeded SIGKILLs.
- Malformed input never crashes the server or corrupts data (codec tests).
- `shale-server`'s runtime dependency graph is empty.

## 6. Not in D5

TLS, password authentication, `COPY`, `LISTEN`/`NOTIFY`, the `pg_catalog` views that `psql`'s
`\d` commands query (they return `0A000`), a connection pooler.

## References

PostgreSQL documentation, chapter 55 "Frontend/Backend Protocol" (message flow and formats) and
Appendix A (error codes); the pgjdbc source (`QueryExecutorImpl`, `PgPreparedStatement`) for what
a real driver sends; JEP 444 (virtual threads).
