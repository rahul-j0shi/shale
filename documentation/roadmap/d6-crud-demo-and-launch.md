# D6 — CRUD demo and the v1.0 launch: implementation plan

**Status:** planned 2026-09-26; the last milestone of v1.0. Starts after D5 is tagged.
**Depends on:** D5 (server, client, metrics), M8 (benchmark report — on the fast-track, the
report is marked "coming" and added when M8 lands). **Creates:** the `shale-demo` module.

**Goal:** make the whole system visible in five minutes. A real CRUD application runs on
ShaleDB through the server; a panel shows the storage engine working underneath; a scripted
`kill -9` shows no acknowledged data is lost. Then package the project so a reviewer understands
it from the README alone.

## The application: "Shelf", a small library-lending app

Chosen because it needs every layer. The schema covers these features:

| Feature | SQL it exercises | Layer it shows off |
|---|---|---|
| Books, members: create, list, edit, delete | `INSERT`, `UPDATE`, `DELETE`, `SELECT … LIMIT` | primary index, CRUD |
| Search books by author | `WHERE author = ?`, `LIKE 'pre%'` | secondary index, range rewrite |
| Check out a book | `BEGIN`; check availability; insert loan; update book; `COMMIT` | serializable OCC; retry on 409 |
| Return a book, overdue list | `WHERE due_at < ?` on an index | index range scan |
| Loans with book and member names | `JOIN` ×2 | index nested-loop join |
| Stats: loans per member, top authors | `GROUP BY`, `COUNT`, `ORDER BY … LIMIT` | hash aggregate, top-N |

Two browser tabs checking out the last copy at once: one succeeds, and the other sees a conflict
and a clear "already taken". That demonstrates the D4 guarantee.

## Scope

- **Backend:** a JDK `HttpServer` app in `dev.shale.demo` serving a REST API. It talks to
  `shale-server` only through `ShaleClient` with parameterised SQL. Schema creation and a seeded
  dataset generator (fixed seed) run at first start.
- **Frontend:** static HTML, CSS and plain JavaScript served by the backend. No framework and
  no build step, so the demo runs with only a JDK.
- **Engine room panel:** polls `/v1/metrics` and draws memtable fill, SSTables and bytes per
  level, flush and compaction activity, write amplification, cache hit rate, group-commit batch
  size, and commits/aborts. A "generate load" button makes it move.
- **Scripts:**
  - `scripts/demo.sh` builds, starts server and app, and opens the URL;
  - `scripts/crash-demo.sh` runs a `SYNC` load and counts acknowledged loans, sends `kill -9` to
    the server mid-load, restarts it, and checks every acknowledged loan is present and
    `verify()` is clean — printing `PASS` or the missing ids;
  - `docker compose up` does the same as `demo.sh` with Docker only.

## The launch checklist (the portfolio part)

- [ ] **README rewrite:**
  1. a one-paragraph pitch;
  2. a demo GIF;
  3. "run it in 60 seconds";
  4. the architecture diagram (app → server → SQL → engine → disk);
  5. results charts from the M8 report;
  6. the verification story (crash tests, model tests, logic tests, the kill test);
  7. the status table;
  8. the docs map.
- [ ] **Write-ups** in `documentation/writeups/`, each ≤ 1,500 words with diagrams:
      "Testing crash consistency in an LSM engine", "The RUM tradeoff, measured", and "SQL on a
      key-value store: encoding, planning and serializable OCC".
- [ ] **A guided code tour** in `documentation/tour.md`: one write and one query followed from
      the HTTP request to the fsync and back, with `file:line` links.
- [ ] **Recording:** a 2–3 minute demo video (owner-recorded) linked from the README.
- [ ] **Release:** tag `v1.0`, a GitHub release whose notes list what is built, measured and
      verified; CI badge green; every milestone tag present.
- [ ] **A dated assessment** in `documentation/assessments/` checking every README claim
      against the code.
- [ ] **Resume bullets** drafted from the measured numbers (kept by the owner, not in the repo).

## Task order (each task one commit, gate green)

1. Module skeleton; CLAUDE.md §2 and `project-scope.md` updated.
2. Schema, seed generator, REST API over `ShaleClient`, with API tests against an in-process
   server.
3. Frontend pages; the conflict path on checkout.
4. Engine room panel.
5. `demo.sh`, `crash-demo.sh`, `docker-compose.yml`; a CI job running `crash-demo.sh` with a
   fixed seed.
6. The launch checklist items, in the order listed.
7. Tag `v1.0`; update the completion plan's status table.

## Acceptance gates

- From a fresh clone with only JDK 25 (or only Docker), `scripts/demo.sh` shows the app in under
  five minutes on Linux and macOS.
- `crash-demo.sh` passes 20 of 20 seeded runs locally and in CI.
- Every README claim links to its evidence: a test, a benchmark chart or an ADR.
- A reader who knows databases but not this repo can explain the write path from the tour alone
  (ask one person to try it).

## References

The application is an original example; its layers cite their own milestones. For README
practice: the READMEs of LevelDB, mini-lsm and TigerBeetle as models of explaining a storage
system briefly.
