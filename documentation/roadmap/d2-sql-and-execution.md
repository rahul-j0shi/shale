# D2 — SQL front end and single-table execution: implementation plan

**Status:** planned; starts after D1 is tagged. **Depends on:** D1 (`Database`, `Transaction`,
`Table`, `Catalog`, `Value`, `KeyRange`). **ADR:** 0019. **Estimate:** 3–4 focused weeks, in 8
steps.

**Goal.** The path from text to rows: a hand-written lexer, parser, binder, rule-based planner and
Volcano executor, each small, cited and tested. `session.execute(sql, params)` runs CRUD on single
tables; `EXPLAIN` shows the chosen plan. Every statement is atomic: it runs inside a D1
`Transaction`, between `endStatement` and `abortStatement`.

---

## 1. Where the code will be at the start (after D1)

`Database`, `Transaction` (a snapshot, the two overlay layers, a writer lock), `Table` (CRUD and
scans over encoded key ranges), `Catalog`, `Value` (with the canonical order), and
`ConstraintViolationException` with SQLSTATE `23505`/`23502`.

## 2. The design (decided — ADR-0019, "SQL dialect and query processing", records it)

### 2.1 The frozen grammar (EBNF; keywords case-insensitive)

```text
script      = statement { ";" statement } [ ";" ] ;
statement   = create_table | create_index | drop_table | drop_index
            | insert | select | update | delete | explain ;
create_table= "CREATE" "TABLE" ident "(" column_def { "," column_def } [ "," pk_clause ] ")" ;
column_def  = ident type [ "NOT" "NULL" ] [ "PRIMARY" "KEY" ] ;
pk_clause   = "PRIMARY" "KEY" "(" ident { "," ident } ")" ;
type        = "BIGINT" | "INT" | "INTEGER" | "INT8"                      (* → BIGINT *)
            | "DOUBLE" [ "PRECISION" ] | "FLOAT8"                        (* → DOUBLE *)
            | "TEXT" | "VARCHAR"                                         (* → TEXT *)
            | "BOOLEAN" | "BOOL" ;                                       (* → BOOLEAN *)
create_index= "CREATE" [ "UNIQUE" ] "INDEX" ident "ON" ident "(" ident { "," ident } ")" ;
drop_table  = "DROP" "TABLE" ident ;
drop_index  = "DROP" "INDEX" ident ;                 (* index names are unique database-wide *)
insert      = "INSERT" "INTO" ident [ "(" ident { "," ident } ")" ]
              "VALUES" row { "," row } ;
row         = "(" expr { "," expr } ")" ;
select      = "SELECT" select_list "FROM" ident [ "WHERE" expr ]
              [ "ORDER" "BY" order_item { "," order_item } ]
              [ "LIMIT" expr ] [ "OFFSET" expr ] ;
select_list = "*" | select_item { "," select_item } ;
select_item = expr [ [ "AS" ] ident ] ;
order_item  = expr [ "ASC" | "DESC" ] ;
update      = "UPDATE" ident "SET" ident "=" expr { "," ident "=" expr } [ "WHERE" expr ] ;
delete      = "DELETE" "FROM" ident [ "WHERE" expr ] ;
explain     = "EXPLAIN" ( select | insert | update | delete ) ;
expr        = (* Pratt, lowest to highest precedence: OR; AND; NOT; comparison
                (= <> != < <= > >=, IS [NOT] NULL); + -; * / %; unary -; primary *) ;
primary     = literal | "$" digits | ident | "(" expr ")" ;
literal     = integer | decimal | string | "TRUE" | "FALSE" | "NULL" ;
```

- **Identifiers:** unquoted, lowercased. Double-quoted identifiers are a syntax error that names
  them as unsupported.
- **Strings:** single-quoted, with `''` for a quote.
- **Parameters:** `$1`, `$2`, … as in PostgreSQL. pgjdbc rewrites JDBC's `?` to these before
  sending, and the D5 server passes them through.
- **Anything outside the grammar** is a syntax error naming the unsupported construct, never a
  generic "parse error".

### 2.2 Types, NULLs and errors

- **Strict typing.** The only implicit cast is BIGINT → DOUBLE, when the two meet in arithmetic
  or comparison. `'1' = 1` is a type error (`42883`).
- **Arithmetic.** BIGINT uses `Math.addExact`-style operations (overflow → `22003`); `/` truncates
  toward zero; `%` is remainder; division by zero → `22012`. DOUBLE follows IEEE-754.
- **Three-valued logic.** Any comparison with NULL is NULL; `NOT NULL` is NULL; `AND`/`OR` follow
  the SQL truth tables; `WHERE` keeps a row only when the predicate is `TRUE`.
- **Parameter types** come from context: the column compared or assigned, or the other side of
  an operator. A parameter with no context is an error (`42P18`).
- **Every error** is an `SqlException` carrying a SQLSTATE and a 1-based source position:

  | Code | Meaning |
  |---|---|
  | `42601` | syntax error |
  | `42P01` | undefined table |
  | `42703` | undefined column |
  | `42P07` | duplicate table or index (a relation, as in PostgreSQL) |
  | `42883` | type error |
  | `42P18` | parameter type unknown |
  | `22003` | numeric overflow |
  | `22012` | division by zero |
  | `23505` | unique violation (from D1) |
  | `23502` | not-null violation (from D1) |
  | `53200` | query memory limit exceeded |
  | `0A000` | feature not supported |

### 2.3 Planning (rule-based, after parameters are bound)

- **When.** A statement is parsed once and bound per execution, with its parameter *values*. So
  the planner sees constants, and `WHERE id = $1` becomes a point lookup. There is no plan cache.
- **Conjuncts.** `WHERE` is split into its AND-ed conjuncts. A conjunct is **sargable** on a
  column when it is `col op const` or `const op col`, with op in = < <= > >=, or `col IS NULL`.
- **Access paths, first match wins:**
  1. **`PointGet`:** every primary-key column is bound by `=`.
  2. **`PrimaryRangeScan`:** a prefix of the primary key is bound by `=`, and optionally the next
     column by a range.
  3. **`IndexScan`** on a `PUBLIC` index:
     - a unique index with every column bound by `=` wins;
     - otherwise the index with the longest bound prefix (a range on the last bound column counts
       as half a column);
     - ties go to the index created first.

     It is followed by a primary-row fetch.
  4. **`TableScan`.**
- **What is left over.** Every conjunct not consumed by the path becomes a `Filter` over the
  fetched rows.
- **Ordering.**
  - `ORDER BY` columns that match the path's order, all `ASC`, need no `Sort`. The primary order
    is the primary-key columns; an index's order is its columns, then the primary key.
  - `DESC` always sorts: the engine has no reverse cursor, and none is planned.
  - `NULL`s sort last for `ASC` and first for `DESC`, as in PostgreSQL. The key encoding already
    matches the `ASC` case.
- **`LIMIT`/`OFFSET`** take non-negative BIGINT constants or parameters.

### 2.4 Execution (Volcano)

- **The operator interface:** `interface Operator extends AutoCloseable { void open(); Row next();
  List<ColumnInfo> columns(); void close(); }`. `next()` returns `null` at the end.
- **Operators:** `Values`, `PointGet`, `PrimaryRangeScan`, `IndexScan`, `TableScan`, `Filter`,
  `Project`, `Sort`, `Limit`, `Insert`, `Update`, `Delete`.
- **Ownership.** An operator that opens a `RowCursor` closes it in `close()`, and `close()`
  cascades to children (N6).
- **Memory.** `Sort` holds rows in memory and counts their estimated bytes: 16 per value, plus the
  length of each TEXT's bytes. Above `DatabaseOptions.queryMemoryBytes` it throws `53200`. No
  spilling.
- **DML.**
  - `Update` and `Delete` read their input through the transaction's reads, which never include
    the running statement's writes (D1 §2.6). So an `UPDATE` of an indexed column cannot meet its
    own output — no Halloween problem.
  - `Insert` stages each row; D1's checks see earlier rows of the same statement.
- **Statement boundaries** (the `Session` does this):
  - success → `txn.endStatement()`;
  - any exception → `txn.abortStatement()`, then rethrow.
  - In autocommit (every statement, until D4), the `Session` also begins and commits the
    transaction around it.

### 2.5 `EXPLAIN` output

One text row per plan node, indented two spaces per depth. Each line gives the node name, then
`key=value` details in a fixed order. For example:

```text
Limit count=10
  Sort keys=[title ASC]
    Filter predicate=(year > 2000)
      IndexScan table=books index=books_by_author range=[author = 'Le Guin']
```

- **`DDL`** statements cannot be explained (`0A000`).
- **The format is part of the contract.** Planner tests and D6 depend on it; changing it means
  updating those tests in the same commit.

### 2.6 The session API

```java
public final class Session implements AutoCloseable {          // one per client, @NotThreadSafe
  public Session(Database db);
  public Result execute(String sql, List<Value> params);      // one statement
  public PreparedStatement prepare(String sql);               // parse once; D5's Parse message
}
public sealed interface Result permits RowResult, CountResult { }
public final class RowResult implements Result, AutoCloseable {  // streams rows
  public List<ColumnInfo> columns();                          // name + type
  public Row next();                                          // null at the end
  public void close();
}
public record CountResult(String tag, long count) implements Result { }   // e.g. "INSERT 0 3"
```

- **`CountResult.tag`** follows PostgreSQL's command tags (`INSERT 0 n`, `UPDATE n`, `DELETE n`,
  `CREATE TABLE`, …), because D5 sends them verbatim.
- **`PreparedStatement`** exposes its parameter types after binding and describes its result
  columns (both D5 needs); it is executed with values.

### 2.7 How D2 is tested

- **Logic tests** (sqllogictest style): `.slt` files under `shale-db/src/test/resources/logic/`,
  run by `LogicTestRunner` (JUnit 5 dynamic tests, one per record). Records:

  ```text
  statement ok
  CREATE TABLE t (id BIGINT PRIMARY KEY, name TEXT)

  statement error 23505
  INSERT INTO t VALUES (1, 'a'), (1, 'b')

  query IT rowsort
  SELECT id, name FROM t
  ----
  1
  a
  ```

  - **Type letters:** `I` BIGINT, `R` DOUBLE (printed with 3 decimals), `T` TEXT, `B` BOOLEAN.
  - **Output:** one value per line; `NULL` prints as `NULL`.
  - **Ordering:** `rowsort` sorts result rows before comparing; without it, order is compared
    exactly.
- **The differential test.** `ReferenceExecutor` evaluates the same bound AST directly: a full
  scan of an in-memory copy of each table, then filter, sort and limit. It shares nothing with the
  planner or the indexes.
  - **Input:** a seeded `QueryGenerator` produces queries over three fixed schemas, with random
    sargable and non-sargable conjuncts, random `ORDER BY`, and random `LIMIT`.
  - **Comparison:** results are compared as multisets, or in order when `ORDER BY` covers the
    primary key (a total order).
  - **On failure:** print the seed, and the smallest failing query found by dropping conjuncts one
    at a time.

## 3. New types (all in `shale-db`)

| Package | Types | Step |
|---|---|---|
| `dev.shale.db.sql` | `Lexer`, `Token`, `TokenType`, `Parser`, AST (sealed: `Statement` records; `Expr` records), `AstPrinter`, `SqlException`, `SqlState` | 2, 3 |
| `dev.shale.db.plan` | `Binder`, `BoundStatement` / `BoundExpr`, `Planner`, `PhysicalPlan`, `AccessPath`, `ExplainRenderer`, `ColumnInfo` | 4, 6 |
| `dev.shale.db.exec` | `Operator` and the operators of §2.4, `ExprEvaluator`, `MemoryBudget` | 5 |
| `dev.shale.db` | `Session`, `PreparedStatement`, `Result`, `RowResult`, `CountResult` | 7 |
| test | `LogicTestRunner`, `ReferenceExecutor`, `QueryGenerator` | 7, 8 |

## 4. Steps

### Step 1 — ADR-0019 (`adr/0019-sql-dialect`), ~1 day
`docs(sql)`: records §2. Alternatives:
- JDBC-style `?` parameters (rejected: PostgreSQL clients send `$n`);
- a plan cache (rejected: no need at this scale, and planning with values gives better plans);
- implicit text-number casts (rejected: silent coercion hides bugs);
- a reverse cursor for `DESC` (rejected: a sort is enough).

**Done when:** merged.

### Step 2 — the lexer (`d02/lexer`), ~2 days
`feat(sql)`: `Lexer`, `Token`, positions. `LexerTest`: every token type, keyword case-insensitivity,
`''` escape, an unterminated string (`42601` at its start), `$0` rejected, and a number too large
for BIGINT → `22003` at bind time, not lex time. **Done when:** green.

### Step 3 — the parser (`d02/parser`), ~4 days
`feat(sql)`: the AST, `Parser` (recursive descent + Pratt), `AstPrinter`. Tests:
- `ParserTest`: every production, with precedence cases (`a OR b AND NOT c = d`).
- Every unsupported construct named: `JOIN` before D3, `GROUP BY`, a quoted identifier.
- `ParserRoundTripPropertyTest`: `parse(print(ast)) == ast` over generated ASTs.

**Done when:** green.

### Step 4 — the binder (`d02/binder`), ~3 days
`feat(plan)`: name resolution against `Catalog`, `*` expansion, type checking with the one
implicit cast, parameter typing. `BinderTest`: every error code of §2.2 that is detected at bind
time, with its position. **Done when:** green.

### Step 5 — the operators (`d02/operators`), ~4 days
`feat(exec)`: every operator of §2.4, `ExprEvaluator` (three-valued logic, overflow, division by
zero), `MemoryBudget`. Tests:
- `ExprEvaluatorTest`: the SQL truth tables, exhaustively.
- `OperatorTest`: each operator over hand-built inputs; `close()` releases cursors; `Sort` past
  the budget → `53200`.

**Done when:** green.

### Step 6 — the planner and `EXPLAIN` (`d02/planner`), ~3 days
`feat(plan)`: `Planner` (§2.3), `ExplainRenderer` (§2.5). `PlannerTest` asserts `EXPLAIN` text for:
- each access path;
- the index precedence rules;
- an order satisfied by the path;
- `DESC` → `Sort`;
- a parameter becoming a point lookup.

**Done when:** green.

### Step 7 — the session and logic tests (`d02/session`), ~4 days
1. `feat(api)`: `Session`, `PreparedStatement`, `Result` types, command tags, autocommit and
   statement boundaries.
2. `test(sql)`: `LogicTestRunner`, and the suites `ddl.slt`, `insert.slt`, `select.slt`,
   `update.slt`, `delete.slt`, `nulls.slt`, `constraints.slt`, `ordering.slt`, `params.slt`,
   `errors.slt`.
3. `test(sql)`: `HalloweenTest` — `UPDATE t SET k = k + 1000` over 1,000 rows with an index on
   `k`: each row is updated exactly once, and `verify()` is clean.
4. `test(sql)`: `StatementAtomicityTest` — a 500-row `INSERT` whose 300th row violates a
   constraint leaves nothing.

**Done when:** green.

### Step 8 — the differential test, documentation, tag (`d02/differential`), ~3 days
1. `test(sql)`: `ReferenceExecutor`, `QueryGenerator`, `DifferentialQueryTest` (5,000 seeded
   queries per build; 200,000 locally before the tag).
2. Docs:
   - `architecture/d2-sql-and-execution.md` (text → tokens → AST → bound tree → plan → operators,
     one query traced);
   - glossary rows (`AccessPath`, `Operator`, `LogicalPlan`/`PhysicalPlan`, sargable, binder);
   - README status, changelog, the completion plan's status table.
3. **Reconciliation pass for D3.** Tag `d2-sql`.

## 5. Milestone acceptance gates

- Every grammar production and every error code has a logic test.
- 5,000 differential queries per build with zero divergence.
- `EXPLAIN` shows the access path the rules require.
- No Halloween problem; a failed statement leaves nothing.
- Every operator closes what it opened (a cursor-leak check in `OperatorTest`).

## 6. Not in D2

Joins, aggregates, `DISTINCT`, `IN`, `BETWEEN`, `LIKE`, top-N (D3); `BEGIN`/`COMMIT` (D4); the
PostgreSQL protocol (D5); `EXPLAIN ANALYZE` (D6). Not planned: subqueries, `INSERT … SELECT`,
defaults, `ALTER TABLE`, quoted identifiers, a plan cache.

## References

Graefe, "Volcano" (IEEE TKDE 1994); Selinger et al., "Access Path Selection in a Relational
DBMS" (SIGMOD 1979); Pratt, "Top Down Operator Precedence" (POPL 1973); SQLite "sqllogictest";
PostgreSQL documentation, "SQL Syntax", "Sorting Rows" and Appendix A (error codes); CMU 15-445
lectures on query processing.
