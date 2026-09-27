# D4 — Serializable transactions: implementation plan

**Status:** planned; starts after D3 is tagged. **Depends on:**
- M7: `Snapshot`, `writeAsync` with its call-order guarantee, `WriteResult`;
- M5.5: group commit;
- D1: `Transaction`, with its overlays and statement boundaries;
- D2–D3: `Session`, the binder.

**ADR:** 0020. **Estimate:** 2–3 focused weeks, in 7 steps.

**Goal.** `BEGIN … COMMIT | ROLLBACK` across many statements, with **serializable** isolation,
using optimistic concurrency control over snapshots. D1's writer lock is retired for DML:
read-write transactions run concurrently and are validated at commit.

---

## 1. Where the code will be at the start (after D3)

`Transaction` = a snapshot, `committedWrites` / `statementWrites`, and a database-wide writer lock
held from begin to commit. `Session` runs each statement in autocommit. DDL runs under the DDL
lock and the writer lock.

## 2. The design (decided — ADR-0020, "Concurrency control and isolation", records it)

### 2.1 Isolation and why

- **Serializable,** by backward-validation OCC (Kung & Robinson): a transaction reads a snapshot,
  buffers its writes, and at commit checks that nothing it read was overwritten since its snapshot.
- **Why not snapshot isolation.** SI is simpler but permits write skew. The project wants a
  guarantee a test can falsify, and write skew is exactly that test.

### 2.2 The oracle (after BadgerDB's)

| State | Meaning |
|---|---|
| `lastCommitTs` | the last commit timestamp assigned; guarded by the commit lock |
| `appliedTs` | every commit ≤ it has been applied to the engine. A watermark: commits are marked applied out of order, and it advances over a contiguous prefix (Badger's `WaterMark`) |
| `committed` | a list of `(commitTs, sorted write keys)`, newest last |
| `active` | a multiset of the `readTs` of open read-write transactions |

**Begin (read-write):**
1. `readTs = lastCommitTs`, read under the commit lock;
2. wait until `appliedTs ≥ readTs` (a condition, not a sleep);
3. take an engine `Snapshot`;
4. add `readTs` to `active`.

- **The snapshot can hold more than `readTs`.** Engine application happens in `commitTs` order
  (§2.4), so the snapshot is always a *prefix* of the commit order — sometimes a longer prefix
  than `readTs`. Validation checks against every commit after `readTs`, so the extra commits can
  only cause a spurious abort, never an anomaly.
- **Begin (read-only)** takes a snapshot and registers nothing.

**Commit (read-write), under the commit lock:**
1. If the write set is empty, commit as read-only (below).
2. **Validate:** for every `(ts, keys)` in `committed` with `ts > readTs`, if any key is in
   `readSet` (§2.3) → release, abort with `40001`.
3. `commitTs = ++lastCommitTs`; append `(commitTs, write keys)` to `committed`.
4. `future = engine.writeAsync(batch, durability)`. This only enqueues, and it fixes the batch's
   engine order at this call (M7 §2.2).
5. Release the lock. Wait for `future`; then `markApplied(commitTs)` (`// DURABILITY:` at the
   wait).

**Commit (read-only):** release the snapshot. No validation.
- **Why that is safe:** its snapshot is a prefix of a serial order (the commit order), so it
  serialises at that point.

**Why the commit order must equal the engine order** (a bug found reviewing an earlier draft):
- if two transactions' batches reached the engine in the opposite order to their `commitTs`, two
  blind writes to one key would leave the *older* transaction's value, and the history would not
  be serial in commit order;
- holding the commit lock across step 4, and `writeAsync`'s call-order guarantee, prevent it.

**Pruning:** when a transaction ends, drop `committed` entries with `ts ≤ min(active)`, or all of
them when `active` is empty.

**Failure:** if a commit's future fails, the engine is in its failed state (M5.5 §2.5). The
database marks itself failed too:
- every waiting `begin` wakes and fails;
- every later call fails with `EngineStateException`;
- without this, `appliedTs` would never pass the failed commit, and every later `begin` would wait
  forever.

### 2.3 Read sets and write sets

**Point reads** — the encoded key of:
- every primary-key lookup;
- every uniqueness check (the unique-index key and the primary key);
- every primary row fetched through an index.

**Range reads** — for every scan (primary or index):
- `[from, to)` if it ran to its end;
- `[from, lastKeyReturned]` if it stopped early (`LIMIT`, a join that closed it). Keys it never
  reached cannot have affected the result, so a phantom there is not a conflict.

**Schema reads:** the binder records `0x01 'D' tableId` for every table a statement names. So a
DDL that commits on that table aborts the transaction.

**Structure:**
- `ReadSet` = a sorted set of point keys, plus a list of ranges merged when they overlap.
- A key hits the read set when it is a member, or falls inside a range. That check is a binary
  search, so validation costs O(|writes| · log |reads|).
- `WriteSet` is `committedWrites` from D1, whose keys the oracle records.

### 2.4 DDL under concurrency

- **DDL is autocommit only.** `CREATE`/`DROP` inside `BEGIN` → `25001`.
- **It takes the DDL lock and the commit lock for its whole duration,** index backfill included.
  - **Blocks:** read-write commits wait.
  - **Does not block:** reads, and the statements of open transactions.
  - This is PostgreSQL's plain (not `CONCURRENTLY`) `CREATE INDEX` behaviour, and the reason for
    it is the same: a row committed mid-backfill would otherwise be missing from the index.
- **It goes through the oracle.** Every DDL batch takes a `commitTs` and records its keys,
  including the descriptor key. So a transaction that bound the old descriptor fails validation.

### 2.5 Sessions and SQL

- **Statements added:** `BEGIN [TRANSACTION] [READ ONLY]`, `START TRANSACTION [READ ONLY]`,
  `COMMIT`, `END`, `ROLLBACK`. `COMMIT` outside a transaction is a warning, not an error, as in
  PostgreSQL.
- **Session state:** `IDLE`, `IN_TRANSACTION` or `FAILED`.
  - A statement error inside a transaction → `FAILED`. Every later statement except `ROLLBACK`
    fails with `25P02` ("current transaction is aborted").
  - `COMMIT` in `FAILED` performs a rollback and returns tag `ROLLBACK`, as PostgreSQL does.
- **Autocommit:** a statement outside a transaction runs in its own transaction; read-only when
  it is a `SELECT`.
- **Durability:** `Session.setDurability` (default `SYNC`); D5 maps `synchronous_commit` onto it.
- **Limits:**
  - a write set over `maxTransactionBytes` (default 32 MiB, the engine's batch limit) → `54000`;
  - idle transactions older than `idleTransactionTimeout` (default 60 s, on the injected `Clock`)
    are rolled back by `Database.expireIdle()`. D5's server calls that on a timer; tests call it
    directly with a `ManualClock`.
- **Aborts:** a conflict is `TransactionConflictException`, SQLSTATE `40001`, and is retryable.
  Retrying is the client's job; the demo does it.

### 2.6 Metrics

`txn.commits`, `txn.commits.readonly`, `txn.aborts.conflict`, `txn.aborts.timeout`,
`txn.validation.duration`, and `txn.committed_log.size` (a gauge).

## 3. New and changed types

| Type | Package | Step |
|---|---|---|
| `Oracle`, `WaterMark` | `dev.shale.db.txn` | 3 |
| `ReadSet`, `WriteSet` | `dev.shale.db.txn` | 3 |
| `Transaction` — read tracking, oracle commit; writer lock removed | `dev.shale.db.txn` | 4 |
| `TransactionConflictException` (`40001`) | `dev.shale.db.txn` | 4 |
| `Session` — the §2.5 statements and states, `setDurability` | `dev.shale.db` | 5 |
| `DatabaseOptions` — adds `maxTransactionBytes`, `idleTransactionTimeout`; `Database.expireIdle()` | `dev.shale.db` | 5 |
| test: `ScheduledSessions` (the deterministic scheduler), `SerialReplayChecker` | `shale-db` test | 2, 6 |

## 4. Steps

### Step 1 — ADR-0020 (`adr/0020-concurrency-control`), ~1 day
`docs(txn)`: records §2. Alternatives:
- snapshot isolation (rejected: write skew);
- serializable snapshot isolation (Cahill 2008; rejected: rw-dependency tracking is more machinery
  than validation);
- two-phase locking (rejected: deadlock handling, and readers blocking writers);
- `CREATE INDEX CONCURRENTLY` (rejected: two extra schema states for no demonstrated need).

**Done when:** merged.

### Step 2 — the deterministic scheduler (`d04/scheduler`), ~2 days
`test(txn)`: `ScheduledSessions` runs several `Session`s on **one** test thread, interleaving their
statements by a seeded or scripted schedule, and records each statement's result. Commits block
only on the engine's write future, which the writer thread completes, so no sleeps are needed.
`ScheduledSessionsTest`: a script reproduces the same results on every run. **Done when:** green.

### Step 3 — the oracle and read sets (`d04/oracle`), ~3 days
`feat(txn)`: `WaterMark`, `ReadSet`, `WriteSet`, `Oracle` (begin, validate, commit, prune). Tests:
- `WaterMarkTest`: out-of-order marks, and the watermark advancing over contiguous prefixes;
- `ReadSetTest`: membership, range merging, the early-stop range rule;
- `OracleTest`: validation against hand-built committed logs; pruning bounds; failure wakes
  waiters.

**Done when:** green.

### Step 4 — optimistic transactions (`d04/occ`), ~4 days
`feat(txn)`: `Transaction` records reads (§2.3) and commits through the `Oracle`; the writer lock
goes; the binder records schema reads. Tests (`ScheduledSessions` scripts), each run
deterministically:
- **Write skew:** two doctors on call — each checks that the other is on call, then goes off; one
  aborts.
- **Lost update:** two read-increment-write transactions; one aborts.
- **Phantom:** T1 scans a range, T2 inserts into it and commits, and T1's commit aborts. With a
  `LIMIT` that stopped before T2's key, T1 commits.
- **Read-only:** commits without validation, and sees a consistent state.
- **Blind writes:** in commit order, the later writer's value wins.
- **DDL conflict:** a transaction that read table *t*'s descriptor aborts when `CREATE INDEX ON t`
  commits first.

**Done when:** green.

### Step 5 — SQL and session states (`d04/sql`), ~2 days
`feat(sql)`, `feat(api)`: the §2.5 statements, states, limits and `expireIdle`. Tests:
- `txn.slt` — every state transition and code: `25P02`, `25001`, `54000`, and `COMMIT` in
  `FAILED` returning `ROLLBACK`;
- `IdleTimeoutTest` with `ManualClock`.

**Done when:** green.

### Step 6 — the harnesses (`d04/harness`), ~4 days
1. `test(txn)`: `SerialReplayChecker`. For each of 1,000 seeded random schedules of 4 sessions,
   with transactions of 1–5 reads and writes over 20 keys:
   - record every committed transaction's operations, with the values its reads returned;
   - replay the committed transactions in `commitTs` order against a `TreeMap`;
   - assert every read returns what it returned live;
   - and assert `commitTs` order equals the engine sequence order of their batches.
2. `test(txn)`: `BankTransferStressTest` — 8 real threads doing transfers between 50 accounts,
   retrying on `40001`, while a checker thread runs read-only `SUM` transactions. Every sum is
   constant; the final balances match the committed transfers.
3. `test(txn)`: `GroupCommitTest` — the cohort recipe (M5.5) with N committing sessions: fewer
   syncs than commits.
4. `test(txn)`: `TxnCrashTest` (tag `crash`) — a crash at every operation of a 3-statement
   transaction's commit. After recovery the transaction is whole or absent, and `verify()` is
   clean.

**Done when:** all green; `SerialReplayChecker` at 20,000 schedules locally.

### Step 7 — documentation and the tag (`d04/docs`), ~2 days
- `architecture/d4-transactions.md`: the begin/commit sequence diagram; one validation worked
  through; the anomaly table (what SI would allow, what this prevents); why commit order must
  equal engine order.
- Glossary rows (`ReadSet`, `WriteSet`, commit timestamp, watermark, validation).
- README status, changelog, the completion plan's status table.
- `guides/shaledb-sql.md`, a "Transactions" section:
  - `BEGIN`/`COMMIT`/`ROLLBACK`;
  - serializable is the only level;
  - **what `40001` means, and a retry loop to copy**, in Java;
  - why long transactions abort more often;
  - DDL blocking writes.
- The FAQ's isolation answer loses "planned".
- **Reconciliation pass for D5.** Tag `d4-txn`.

## 5. Milestone acceptance gates

- Write skew, lost update and phantoms are prevented (scripted, deterministic).
- Every seeded history is serial in commit order (`SerialReplayChecker`).
- Commit order = engine order.
- Atomic under crash; `verify()` clean.
- Group commit shares syncs across transactions.
- An engine failure makes the database fail fast, never hang.

## 6. Not in D4

Savepoints, `SELECT … FOR UPDATE`, weaker isolation levels, `CREATE INDEX CONCURRENTLY`,
distributed transactions.

## References

Kung & Robinson, "On Optimistic Methods for Concurrency Control" (ACM TODS 1981); Berenson et
al., "A Critique of ANSI SQL Isolation Levels" (SIGMOD 1995); Cahill et al., "Serializable
Isolation for Snapshot Databases" (SIGMOD 2008) — the alternative not chosen; BadgerDB `txn.go`
and `y/watermark.go`; PostgreSQL documentation, "CREATE INDEX" (locking) and "Transaction
Isolation"; Petrov, *Database Internals* ch. 5; CMU 15-445 lectures on OCC.
