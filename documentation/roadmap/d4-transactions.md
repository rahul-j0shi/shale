# D4 — Serializable transactions: implementation plan

**Status:** planned 2026-09-26; starts after D3 is tagged. **Depends on:** M7 (`Snapshot`, `writeAsync`,
`WriteBatch`), M5.5 (group commit — concurrent committers should share an fsync), D1–D3.

**Goal:** `BEGIN … COMMIT | ROLLBACK` across many statements, with **serializable** isolation,
using optimistic concurrency control over snapshots. Autocommit statements become one-statement
transactions, and D1's single writer lock is retired for DML.

## Design (decided — ADR-0020, "Concurrency control and isolation", records it)

1. **Isolation level.** Serializable, by backward-validation OCC (Kung & Robinson).
   Snapshot isolation alone permits write skew. The ADR records why SI was rejected as the
   default: it would be simpler, but the project wants a guarantee a test can falsify.
2. **Timestamps (the Badger oracle design).** The DB layer keeps its own logical commit counter.
   - **Begin:** `readTs` = the last assigned commit timestamp. Wait until every commit
     ≤ `readTs` is applied to the engine (a watermark), then take an engine `Snapshot`. The
     snapshot may also contain a few later commits. That is safe: validation checks against
     *every* commit after `readTs`, so the worst case is a spurious abort, never an anomaly.
   - **Commit:** under a short commit lock:
     1. check the transaction's read set against the write sets of every transaction committed
        after its `readTs`;
     2. on success, assign `commitTs` and record the write set;
     3. **while still holding the lock, call `engine.writeAsync(batch, durability)`.** The call
        only enqueues, and it fixes the batch's engine order at call time (M7).
   - **Apply:** release the lock, then wait for the future. Concurrent committers still share one
     group-commit fsync, and commits reach the engine exactly in `commitTs` order. Mark `commitTs`
     applied when the future completes.
   - **Why order matters** (a bug found reviewing an earlier draft of this plan): if commits
     could enter the engine in a different order from `commitTs`, two blind writes to the same
     key would end with the older transaction's value, and the history would not be serializable
     in commit order. BadgerDB holds a lock across timestamp assignment and hand-off to its write
     channel for the same reason.
3. **Read set.** Encoded point keys read, and encoded key ranges scanned (table, index and
   catalog). Scanned ranges catch phantoms: a concurrent insert into a range this transaction
   scanned is a conflict. Catalog descriptor reads are in the read set too, so a concurrent DDL
   conflicts naturally.
4. **Write set and read-your-own-writes.** Writes buffer in an ordered map (key → value or
   tombstone). Reads within the transaction merge that buffer over the snapshot cursor; the
   merging logic reuses the engine's reconciliation rules, re-implemented over the DB layer's
   keys.
5. **Pruning.** The oracle keeps committed write sets newer than the oldest active `readTs`, and
   drops the rest.
6. **Aborts.** A conflict raises `TransactionConflictException`, retryable, SQLSTATE `40001`.
   Retry is the client's job: the server reports SQLSTATE `40001` exactly as PostgreSQL does,
   and the demo retries.
7. **Limits and lifetime.** An idle transaction times out on the injected `Clock` (N8) and
   aborts, releasing its snapshot (long snapshots pin versions in compaction, M7). There is a
   write-set byte limit. DDL is autocommit-only; `CREATE`/`DROP` inside `BEGIN` is an error.
8. **Durability.** `COMMIT` acknowledges only after its batch is durable under the session's
   `Durability` (default `SYNC`), stated per N3 with a `// DURABILITY:` line.

## Scope

**In D4:** `dev.shale.db.txn` — `Transaction`, `Oracle`, `ReadSet`, `WriteSet`, and the merging
read view; parser and session support for `BEGIN`, `COMMIT`, `ROLLBACK`; the metrics
`txn.commits`, `txn.aborts.conflict`, `txn.aborts.timeout`, `txn.retries`.

**Deferred:** savepoints; `SELECT … FOR UPDATE`; pessimistic locking; weaker isolation levels
as options (stretch: SI as a measured option, to show write skew happening).

## Task order (TDD; each task one commit, gate green)

1. ADR, this plan, ADR index.
2. Deterministic transaction scheduler for tests: sessions as step functions interleaved by a
   seeded schedule. No threads or sleeps are needed to test interleavings (N8).
3. `Oracle`: timestamps, applied watermark, validation, pruning.
4. `Transaction`: buffered writes, read-your-own-writes view, read-set capture for point and
   range reads.
5. Session and SQL: `BEGIN`/`COMMIT`/`ROLLBACK`, autocommit as one-statement transactions; retire
   D1's writer lock for DML.
6. Idle timeout on the `Clock`; write-set limit; DDL rules.
7. Anomaly tests (next section) and the serial-replay checker.
8. Concurrency stress on real threads: N sessions doing bank transfers; the total is constant
   at every snapshot.
9. Docs: `architecture/d4-transactions.md` (begin/commit sequence diagram, validation example,
   the anomaly table), glossary, README status, changelog, tag `d4-txn`.

## Acceptance gates

- **Write skew is prevented:** the classic two-doctors-on-call schedule aborts one transaction.
- **Lost update is prevented:** two read-increment-write transactions never both commit.
- **Phantoms are prevented:** T1 scans a range, T2 inserts into it and commits, and T1's commit
  aborts.
- **Commit order is engine order:** for every seeded schedule, `commitTs` order equals the engine
  sequence order of the committed batches.
- **Serial-replay checker:** for every seeded schedule, replaying the committed transactions in
  `commitTs` order against the model reproduces every value each transaction read. That makes
  the history serializable in commit order.
- **Atomic under crash:** kill at every file operation of a commit; recovery shows all of the
  transaction's writes or none, and `verify()` is clean.
- **Group commit is used:** N concurrent committers under `SYNC` produce fewer fsyncs than
  commits, measured with the M5.5 metric.

## References

Kung & Robinson, "On Optimistic Methods for Concurrency Control" (ACM TODS 1981); Berenson et
al., "A Critique of ANSI SQL Isolation Levels" (SIGMOD 1995); Fekete et al., "Making Snapshot
Isolation Serializable" (TODS 2005); BadgerDB `txn.go` and its oracle; Cahill et al.,
"Serializable Isolation for Snapshot Databases" (SIGMOD 2008) — the alternative not chosen;
Petrov, *Database Internals* ch. 5; CMU 15-445 lectures on OCC and MVCC.
