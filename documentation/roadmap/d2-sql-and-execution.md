# D2 — SQL front end and single-table execution: implementation plan

**Status:** planned 2026-09-26; starts after D1 is tagged. **Depends on:** D1 (catalog, `Table`,
indexes). **Artifact:** `session.execute(sql, params)` runs CRUD statements on single tables,
and `EXPLAIN` shows the chosen plan.

**Goal:** the path from text to rows. Hand-written lexer, parser, binder, rule-based planner and
Volcano executor, each small, cited and tested. Every statement is atomic: it reads at one
snapshot and commits one `WriteBatch`.

## Decisions required in the ADR ("SQL dialect and query processing")

1. **Grammar (frozen for D2).**
   - `CREATE TABLE`, with column types, `NOT NULL` and `PRIMARY KEY (…)`;
   - `CREATE [UNIQUE] INDEX`, `DROP TABLE`, `DROP INDEX`;
   - `INSERT INTO t (cols) VALUES (…), (…)`;
   - `SELECT cols | * FROM t [WHERE] [ORDER BY] [LIMIT n [OFFSET m]]`;
   - `UPDATE t SET … [WHERE]`, `DELETE FROM t [WHERE]`, `EXPLAIN <statement>`;
   - positional parameters `?`.

   Anything else is a syntax error that names the unsupported construct.
2. **Types and nulls.** BIGINT, DOUBLE, TEXT, BOOLEAN. Types are strict; the only implicit cast
   is BIGINT→DOUBLE in arithmetic and comparison. `NULL` follows SQL three-valued logic, and
   `WHERE` keeps only rows where the predicate is TRUE.
3. **Parser shape.** Hand-written lexer; recursive descent for statements; Pratt (precedence
   climbing) for expressions; an AST of sealed records; every error carries line and column.
4. **Planner.** Rule-based, no statistics. Split `WHERE` into AND-ed conjuncts, then choose the
   access path (Selinger's term) in this order:
   1. `PointGet` when the whole primary key is equality-bound;
   2. primary-key prefix or range → `PrimaryRangeScan`;
   3. a secondary index whose prefix is equality- or range-bound → `IndexScan` plus row lookup;
   4. otherwise `TableScan`.

   Remaining conjuncts become a `Filter`. `ORDER BY` becomes a `Sort` unless the access path
   already yields that order.
5. **Executor.** Volcano iterators: `Operator { open(); Row next(); close(); }`. Operators own
   the engine cursors they open and close them (N6). A `Sort` materialises in memory, up to the
   `query.memory.bytes` option, and fails cleanly beyond it (no spilling in D2).
6. **Statement atomicity and the Halloween problem.** DML reads its input at the statement's
   snapshot and writes into one batch. An `UPDATE` of an indexed column therefore cannot see its
   own writes and loop — the Halloween problem is ruled out by construction.
7. **Errors.** A `SqlException` family (syntax, semantic, constraint violation) that stays
   separate from the engine's exceptions (`errors-and-logging.md` §1).

## Scope

**In D2:** `dev.shale.db.sql` (lexer, parser, AST, pretty-printer), `dev.shale.db.plan` (binder,
logical and physical plans, `AccessPath`, `EXPLAIN` renderer), `dev.shale.db.exec` (operators),
and `Session` / `Result`. Also the **logic-test runner**: `.slt` files under
`shale-db/src/test/resources/logic/`, in sqllogictest style (`statement ok`,
`statement error <text>`, `query <types> [rowsort]` + `----` + expected rows).

**Deferred:** joins, aggregates, `DISTINCT`, top-N, `LIKE`, `IN` and `BETWEEN` (D3);
`BEGIN/COMMIT` (D4); the server (D5).

## Task order (TDD; each task one commit, gate green)

1. ADR (grammar in EBNF, typing and null tables), this plan, ADR index.
2. Lexer with position tracking; token tests including every error path.
3. Parser and AST; pretty-printer; round-trip property `parse(print(ast)) == ast` on generated
   ASTs.
4. Binder: name resolution against the catalog, type checking, parameter typing.
5. Operators: `Values`, `TableScan`, `PointGet`, `PrimaryRangeScan`, `IndexScan`, `Filter`,
   `Project`, `Sort`, `Limit`, and DML `Insert` / `Update` / `Delete`.
6. Planner and `EXPLAIN`; planner tests assert the chosen access path from `EXPLAIN` output.
7. Logic-test runner and the first suites: DDL, CRUD, nulls, constraints, ordering, parameters.
8. Differential test: a **reference executor** (full scan + filter over in-memory rows, no
   indexes) and a seeded random query generator. Every generated query must give the same rows
   (as a multiset, or in order under `ORDER BY`) on both executors.
9. Docs: `architecture/d2-sql-and-execution.md` (text → AST → plan → operators diagram, one
   query traced end to end), glossary, README status, release note, tag `d2-sql`.

## Acceptance gates

- Every construct in the frozen grammar has logic tests, including error cases with positions.
- The differential test runs thousands of seeded queries per build with zero divergence; a
  failure prints the seed and the minimal query.
- `EXPLAIN` shows `PointGet` or `IndexScan` wherever the rules say it must.
- A 1,000-row `UPDATE` of an indexed column updates each row exactly once; `verify()` is clean.
- A statement that fails midway (a constraint violation on row 500) writes nothing.

## References

Graefe, "Volcano" (IEEE TKDE 1994); Selinger et al., "Access Path Selection in a Relational
DBMS" (SIGMOD 1979) — the vocabulary, not the cost model; Pratt, "Top Down Operator Precedence"
(POPL 1973); SQLite "sqllogictest"; CockroachDB logic tests; SQL:2016 on three-valued logic;
CMU 15-445 lectures on query processing; Petrov, *Database Internals* ch. 1.
