# D7 — The demo, the findings, and the v1.0 launch: implementation plan

**Status:** planned; the last milestone of v1.0. Starts after D6 is tagged. **Depends on:** D5
(the server), D6 (`EXPLAIN ANALYZE`, system tables), M8 (the engine report). **Creates:**
`shale-demo`.

**Goal:** make the whole claim visible in five minutes. An ordinary application runs on ShaleDB
through the standard PostgreSQL driver. A panel shows the engine working underneath, and any
action can be explained down to the disk. A scripted `kill -9` loses nothing. Then answer, with
measurements, the questions the project was built to answer — and package it so a reviewer
understands it from the README alone.

## The application: "Shelf", a small library-lending app

Chosen because it exercises every layer:

| Feature | SQL | What it shows |
|---|---|---|
| Books and members: create, list, edit, delete | `INSERT` / `UPDATE` / `DELETE` / `SELECT … LIMIT` | primary index, CRUD |
| Search by author, by title prefix | `WHERE author = ?`, `LIKE 'pre%'` | secondary index, range rewrite |
| Check out a book | `BEGIN`; check availability; insert loan; update book; `COMMIT` | serializable OCC; retry on `40001` |
| Overdue loans | `WHERE due_at < ?` on an index | index range scan |
| Loans with book and member names | two `JOIN`s | index nested-loop join |
| Stats: loans per member, top authors | `GROUP BY`, `COUNT`, `ORDER BY … LIMIT` | hash aggregate, top-N |

Two browser tabs checking out the last copy at once: one succeeds, the other gets a clear
"already taken" — the D4 guarantee, visible.

- **Stack** (ADR-0013's exception for the demo): a JDK `HttpServer` backend serving static HTML,
  CSS and plain JavaScript; the PostgreSQL JDBC driver; parameterised SQL only. No frontend
  framework and no build step.
- **Engine panel:** polls `shale_levels` and `shale_metrics` over SQL, and draws memtable fill,
  files and bytes per level, flushes, compactions, write amplification, compaction debt and
  group-commit size. A "generate load" button makes it move.
- **"Explain this":** every action in the UI can show the `EXPLAIN ANALYZE` of the statements it
  ran. This is the thesis inside the application.
- **Scripts:**
  - `scripts/demo.sh`: build, start the server and the app, open the URL;
  - `scripts/crash-demo.sh`: a `SYNC` load; `kill -9` the server; restart; every acknowledged loan
    present and `verify()` clean — prints `PASS` or the missing ids;
  - `docker compose up`: the same, with Docker only.

## The findings (the SQL-level measurements)

Each finding is a question, a measured answer and a chart in `documentation/benchmarks/sql.md`,
run on the Shelf schema with the M8 harness's rules:

| # | Question | Sweep |
|---|---|---|
| F1 | What does an `INSERT` really cost as indexes are added? | 0–3 secondary indexes: keys, WAL bytes, compaction debt, latency |
| F2 | Are deletes free? | scan cost after deleting half a table, before and after compaction |
| F3 | Leveled or tiered for this application? | the Shelf workload under both policies: p50/p99 per statement, write and space amplification |
| F4 | What do bloom filters buy a SQL workload? | `INSERT` uniqueness checks and point lookups, filters on vs off |
| F5 | Where does ShaleDB stand? | the same workload on SQLite, as a reference point |

SQLite runs through its JDBC driver in `shale-bench` only (ADR-0013).

## The launch checklist

- [ ] **README:**
  1. the one-line pitch;
  2. a 60-second GIF: `psql` → `EXPLAIN ANALYZE` of an `INSERT` → the app → `kill -9` → restart,
     nothing lost;
  3. quickstart in 60 seconds;
  4. the architecture diagram;
  5. two headline charts (one from M8, one from F1–F5);
  6. the verification story;
  7. status;
  8. the docs map.
- [ ] **Three write-ups** in `documentation/writeups/`, each ≤ 1,500 words with diagrams:
      "What an INSERT costs on an LSM, measured", "Testing crash consistency at every byte", and
      "SQL on a key-value engine: encoding, planning and serializable OCC".
- [ ] **A guided tour** (`documentation/tour.md`): one `INSERT` and one `SELECT` followed from the
      wire protocol to the fsync and back, with `file:line` links.
- [ ] **A build note** in the README on how the project was built, including any AI assistance.
      The project's claim is understanding, so saying how it was built strengthens it.
- [ ] **Release:** tag `v1.0`, a GitHub release listing what is built, measured and verified; CI
      green; every milestone tag present.

## Task order (each task one commit, gate green)

1. Module skeleton, with the scoped Checkstyle suppression for `com.sun.net.httpserver`; CLAUDE.md
   §2 and `project-scope.md` updated.
2. Schema, seed generator (fixed seed), REST API over JDBC, with API tests against an in-process
   server.
3. Frontend pages; the checkout conflict path; "Explain this".
4. Engine panel.
5. `demo.sh`, `crash-demo.sh`, `docker-compose.yml`; a CI job running `crash-demo.sh` with a
   fixed seed.
6. The SQLite adapter in `shale-bench`; findings F1–F5 and `documentation/benchmarks/sql.md`.
7. The launch checklist, in order; tag `v1.0`; update the completion plan's status table.

## Acceptance gates

- From a fresh clone with only JDK 25 (or only Docker), `scripts/demo.sh` shows the app in under
  five minutes on Linux and macOS.
- `crash-demo.sh` passes 20 of 20 seeded runs locally and in CI.
- Every claim in the README links to its evidence: a test, a chart or an ADR.
- A reader who knows databases but not this repository can explain the write path from the tour
  alone. Ask one person to try it.

## References

The application is original; its layers cite their own milestones. As models for explaining a
storage system briefly: the READMEs of LevelDB, mini-lsm and TigerBeetle.
