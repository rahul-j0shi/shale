# D3 — Joins, aggregates and ordering: implementation plan

**Status:** planned; starts after D2 is tagged. **Depends on:** D2 (parser, binder, planner,
operators, `ReferenceExecutor`, `QueryGenerator`, logic tests). **ADR:** none — D3 extends
ADR-0019's dialect and planner shape; a change to either needs a new ADR first. **Estimate:** 2–3
focused weeks, in 6 steps.

**Goal.** The queries a real application needs, and exactly the ones the D7 demo runs:
multi-table reads, grouping and counting, ordered pages, and predicates that should use an index
but that D2 cannot yet plan.

---

## 1. Grammar additions (frozen for D3; extends ADR-0019's EBNF)

```text
select      = "SELECT" [ "DISTINCT" ] select_list "FROM" from_item { join }
              [ "WHERE" expr ] [ "GROUP" "BY" expr { "," expr } ] [ "HAVING" expr ]
              [ "ORDER" "BY" order_item { "," order_item } ] [ "LIMIT" expr ] [ "OFFSET" expr ] ;
from_item   = ident [ [ "AS" ] ident ] ;
join        = [ "INNER" | "LEFT" [ "OUTER" ] ] "JOIN" from_item "ON" expr ;
primary     = … | ident "." ident                       (* qualified column *)
            | aggregate ;
aggregate   = "COUNT" "(" "*" ")" | ( "COUNT" | "SUM" | "MIN" | "MAX" | "AVG" ) "(" expr ")" ;
comparison  = … | expr [ "NOT" ] "IN" "(" expr { "," expr } ")"
            | expr [ "NOT" ] "BETWEEN" expr "AND" expr
            | expr [ "NOT" ] "LIKE" expr ;
```

- **`GROUP BY`** takes column references only, not arbitrary expressions.
- **`ORDER BY`** accepts a column, a select-list alias, or a 1-based ordinal.

## 2. The design (decided)

### 2.1 Semantics

- **Joins:**
  - `INNER` keeps pairs whose `ON` is `TRUE`.
  - `LEFT` keeps every outer row, extending it with NULLs when no inner row matches.
  - Bare `JOIN` means `INNER`.
  - Joins are left-deep in `FROM` order.
- **Aggregates** skip NULL inputs, except `COUNT(*)`.

  | Aggregate | Result type | Over no rows |
  |---|---|---|
  | `COUNT` | BIGINT | 0 |
  | `SUM` of BIGINT | BIGINT (overflow → `22003`) | NULL |
  | `SUM` of DOUBLE | DOUBLE | NULL |
  | `MIN`, `MAX` | the input type (canonical `Value` order) | NULL |
  | `AVG` | always DOUBLE — PostgreSQL returns `numeric`; ShaleDB has no numeric type, and the docs say so | NULL |

  Without `GROUP BY`, an aggregate query over zero rows returns one row.
- **Grouping rule.** Every select-list and `HAVING` column outside an aggregate must be in `GROUP
  BY` (`42803`). NULLs form one group.
- **`IN`, `BETWEEN`, `LIKE`:**
  - `x IN (a, b)` ≡ `x = a OR x = b`, with three-valued logic, so `1 NOT IN (2, NULL)` is NULL.
  - `BETWEEN` is inclusive at both ends.
  - `LIKE`: `%` matches any run of characters, `_` exactly one; there is no escape character (a
    documented limitation). It matches by code point.
- **`DISTINCT`** compares by canonical `Value` equality, with NULL equal to NULL.

### 2.2 Planning additions

1. **Predicate pushdown.** Split `WHERE` into conjuncts. A conjunct that references one table
   goes to that table's access path (D2 §2.3). An `INNER JOIN`'s `ON` conjuncts join the
   `WHERE` set. A `LEFT JOIN`'s `ON` conjuncts stay on the join: pushing them below it would change
   the result.
2. **Join method,** chosen per join, for the inner (right) side:
   - **`IndexNestedLoopJoin`** when some `ON` conjunct is `inner.col = outer-expression`, and
     `inner.col`, with any constant-bound columns, is a prefix of the inner table's primary key or
     of a `PUBLIC` index. Each outer row then does one key lookup or range scan.
   - **Otherwise `NestedLoopJoin`:** the inner side's filtered rows are materialised once (counted
     against `queryMemoryBytes`) and scanned per outer row.
   - **Not planned:** hash joins and join reordering.
3. **Rewrites into access paths:**
   - `col IN (c1..cn)` on the first primary-key or index column → n point lookups or ranges,
     merged in key order, duplicates removed.
   - `col BETWEEN a AND b` → the range [a, b].
   - `col LIKE 'p%'`, where the pattern is a literal (or a bound parameter), `p` contains no
     `%`/`_`, and the pattern ends with exactly one `%` → the **encoded prefix range**. The
     `LIKE` stays as a residual filter, so correctness never depends on the rewrite.
     - Let `e = 0x40 ‖ escape(utf8(p))`, without the terminator.
     - The range is `[e, successor(e))`, where `successor` increments the last byte below `0xFF`
       and truncates after it.
     - Every string starting with `p` encodes to `e` followed by more bytes, so it is inside the
       range; every other string is outside. (`['abc', 'abd')` on the raw string would be wrong,
       because keys carry the escape and the terminator.)
4. **Aggregation:** a `HashAggregate` over the (filtered, joined) input. `HAVING` becomes a
   `Filter` above it.
5. **`DISTINCT`:** a `HashDistinct` over the projected rows.
6. **Order and limit:**
   - `ORDER BY … LIMIT n [OFFSET m]` with a `Sort` needed becomes `TopN(n + m)`, a bounded heap,
     then `Limit`.
   - With no `Sort` needed (D2's order rules), just `Limit`.
   - An order satisfied by the *outer* side of a nested-loop join still needs no sort for an
     `INNER` join, because nested loops preserve outer order.
7. **Memory.** `HashAggregate`, `HashDistinct`, `TopN` and a materialised inner side all draw
   from one per-query `MemoryBudget`; exceeding it → `53200`. No spilling.

### 2.3 `EXPLAIN` additions

New node names with their details, for example:

```text
Limit count=10
  TopN count=10 keys=[loans ASC]
    HashAggregate groups=[m.name] aggregates=[count(*)]
      IndexNestedLoopJoin type=inner on=(l.member_id = m.id)
        TableScan table=loans alias=l
        PointGet table=members alias=m key=[id = l.member_id]
```

## 3. New types

| Package | Types | Step |
|---|---|---|
| `dev.shale.db.sql` | AST additions: `Join`, `FromItem`, `Aggregate`, `InList`, `Between`, `Like`, `Distinct` | 2 |
| `dev.shale.db.plan` | `Pushdown`, `JoinPlanner`, `LikePrefix` (the encoded range) | 3, 5 |
| `dev.shale.db.exec` | `NestedLoopJoin`, `IndexNestedLoopJoin`, `HashAggregate`, `HashDistinct`, `TopN`, `MultiRangeScan` (for `IN`), `LikeMatcher` | 3, 4, 5 |

## 4. Steps

### Step 1 — the grammar and binder (`d03/grammar`), ~3 days
`feat(sql)`, `feat(plan)`: every production of §1; aliases and qualified names (an ambiguous
unqualified column → `42702`); the grouping rule (`42803`); aggregate typing. Tests: parser and
binder tests for each, including every error code. **Done when:** green.

### Step 2 — joins (`d03/joins`), ~4 days
`feat(plan)`, `feat(exec)`: `Pushdown`, `JoinPlanner`, both join operators. Tests:
- `JoinTest`:
  - `INNER` and `LEFT` with no match, one match and many matches;
  - NULL join keys never match;
  - a `LEFT JOIN` `ON` filter is not pushed down (a case where pushing it would change the result);
- `PlannerTest` additions: `EXPLAIN` shows `IndexNestedLoopJoin` exactly when §2.2's rule holds.

**Done when:** green.

### Step 3 — aggregates and `DISTINCT` (`d03/aggregates`), ~3 days
`feat(exec)`: `HashAggregate`, `HashDistinct`. `AggregateTest`:
- every row of §2.1's table, including empty input with and without `GROUP BY`;
- a NULL group;
- `SUM` overflow;
- `HAVING` on an aggregate.

**Done when:** green.

### Step 4 — order and top-N (`d03/topn`), ~2 days
`feat(exec)`: `TopN`; ordinals and aliases in `ORDER BY`. Tests:
- `TopN` equals sort-then-limit over random inputs (a property test);
- `EXPLAIN` shows no sort when the path supplies the order, including through a nested-loop
  join's outer side.

**Done when:** green.

### Step 5 — `IN`, `BETWEEN`, `LIKE` (`d03/predicates`), ~3 days
`feat(plan)`, `feat(exec)`: `MultiRangeScan`, `LikePrefix`, `LikeMatcher`. Tests:
- `LikePrefixPropertyTest`: for random prefixes (including `0x00`, `0xFF`-heavy UTF-8, emoji) and
  random strings, the string starts with the prefix ⇔ its encoding is inside the range.
- `LikeMatcherTest`: `%` and `_` at every position.
- `NOT IN` with a NULL in the list returns no rows.

**Done when:** green.

### Step 6 — the differential test, logic tests, documentation, tag (`d03/harness`), ~3 days
1. `test(sql)`: `ReferenceExecutor` gains joins, grouping, `DISTINCT` and the predicates;
   `QueryGenerator` generates them; `DifferentialQueryTest` passes 5,000 per build (200,000
   locally before the tag).
2. `test(sql)`: `joins.slt`, `aggregates.slt`, `distinct.slt`, `topn.slt`, `predicates.slt`, and
   `demo_queries.slt` — **every query D7's application will run**, with expected results over a
   fixed fixture.
3. Docs:
   - `architecture/d3-joins-aggregates.md` (join choice, the aggregation pipeline, the `LIKE`
     range with a worked byte example);
   - glossary rows (index nested-loop join, top-N, pushdown);
   - README status, changelog, the completion plan's status table;
   - `guides/shaledb-sql.md`:
     - joins, aggregates, `DISTINCT`, `IN`, `BETWEEN` and `LIKE`, each with an example that is
       also in `guide.slt`;
     - which queries use an index, and how to see it with `EXPLAIN`;
     - the differences from PostgreSQL this milestone adds (no hash joins, no subqueries).
4. **Reconciliation pass for D4.** Tag `d3-query`.

## 5. Milestone acceptance gates

- Zero divergence in 5,000 differential queries per build, with joins, grouping and ordering.
- `EXPLAIN` shows the join method, top-N, and no sort where the rules say so.
- `demo_queries.slt` passes: D7's queries are proven before D7 starts.
- Memory-bound queries fail with `53200` and leak no cursor.

## 6. Not in D3

Hash joins, join reordering, subqueries, `GROUP BY` expressions, window functions, `LIKE ESCAPE`,
spilling to disk, a `numeric` type.

## References

Graefe, "Query Evaluation Techniques for Large Databases" (ACM Computing Surveys 1993); Selinger
et al. (SIGMOD 1979); PostgreSQL documentation, "Aggregate Functions", "Pattern Matching" and
"Joined Tables"; CMU 15-445 lectures on joins, sorting and aggregation.
