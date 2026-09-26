# D3 — Joins, aggregates and ordering: implementation plan

**Status:** planned 2026-09-26; starts after D2 is tagged. **Depends on:** D2 (parser, binder,
planner, operators, logic tests, reference executor). **No ADR needed:** D3 extends D2's
decided dialect and planner shape. If a decision here would change them, write an ADR first.

**Goal:** the queries a real application needs: multi-table reads, grouping and counting,
ordered pages of results, and predicates that should use an index but D2 cannot yet plan.

## Scope (grammar additions, frozen for D3)

- `FROM a [AS x] [INNER | LEFT [OUTER]] JOIN b [AS y] ON <expr>`, chains of joins, table aliases,
  qualified column names.
- `GROUP BY`, `HAVING`, and aggregates `COUNT(*)`, `COUNT(expr)`, `SUM`, `MIN`, `MAX`, `AVG`,
  with SQL null rules (aggregates skip nulls; `COUNT(*)` does not).
- `SELECT DISTINCT`.
- Predicates `IN (…)`, `BETWEEN`, and `LIKE` with `%` and `_`.

## Planner and executor additions

1. **Joins.** Left-deep, in `FROM` order; no join reordering (stated non-goal). For each join,
   choose an **index nested-loop join** when the inner side's primary key or an index prefix is
   bound by the `ON` equality, else a **nested-loop join** over a materialised inner input. A
   hash join is a stretch, measured against nested loops on the demo schema before it is kept.
2. **Aggregation.** A hash aggregate within `query.memory.bytes`. A streaming aggregate when
   input already arrives in group-key order is a stretch.
3. **Ordering.** `ORDER BY … LIMIT n` becomes a **top-N** operator (a bounded heap) instead of a
   full sort. `ORDER BY` matching the access path's order needs no sort; `EXPLAIN` must show that.
4. **Predicate rewrites.** `LIKE 'abc%'` → range `['abc', 'abd')` on an index or primary key,
   with the `LIKE` kept as a residual filter; `IN (…)` on a key → several point lookups;
   `BETWEEN` → a range.
5. **Memory bounds.** Hash tables and materialised inputs count toward `query.memory.bytes`.
   Exceeding it fails the query with a clear error. Spilling to disk is out of scope.

## Task order (TDD; each task one commit, gate green)

1. Parser and binder: aliases, qualified names, joins, `GROUP BY` / `HAVING`, aggregates,
   `DISTINCT`, `IN`, `BETWEEN`, `LIKE`; grammar-doc and pretty-printer updates.
2. `NestedLoopJoin` and `IndexNestedLoopJoin` operators and the join planning rule.
3. `HashAggregate` and `Distinct`.
4. `TopN`, order-satisfying access paths, and the rewrites for `LIKE`, `IN` and `BETWEEN`.
5. Extend the reference executor with joins, grouping and distinct; extend the random query
   generator; logic-test suites for each construct.
6. Docs: update `architecture/d2-sql-and-execution.md` (or add `d3-*.md` if it grows past a
   page) with the join and aggregate operators; glossary; README status; release note; tag
   `d3-query`.

## Acceptance gates

- Differential tests with joins, grouping and ordering: zero divergence from the reference
  executor over thousands of seeded queries per build.
- `EXPLAIN` shows an index nested-loop join on an indexed join key, top-N for
  `ORDER BY … LIMIT`, and no sort when an index supplies the order.
- `LEFT JOIN` null-extension and aggregate null handling each have their own logic tests.
- A query over the memory bound fails with the documented error and leaks no cursor (N6).

## References

Graefe, "Query Evaluation Techniques for Large Databases" (ACM Computing Surveys 1993);
Selinger et al. (SIGMOD 1979); CMU 15-445 lectures on joins, sorting and aggregation; the SQL
standard's null semantics for aggregates.
