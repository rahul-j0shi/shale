# Project status and next steps — 2026-09-10

**Reviewed revision:** `812f88c`, branch `analysis-and-planning`. Source and local history
reviewed; no implementation changes. This supersedes the September 7 action list while
preserving that assessment as history.

## Where we are

Shale is a Java 25, JDK-only, hand-written LSM project. WAL, skiplist memtables, synchronous
SSTable flush and streaming merged reads are built through M4. The intended deliverable is a
measured engine, followed by a bounded B+Tree comparison or Flotilla Raft/sharding work.
The three other modules are documented build shells.

The source confirms the README's built/planned split. Shale.open discovers tables by filename,
scans for the highest sequence and flushes replayed WAL data. Writes/flush I/O serialize under
writeLock. Table references close channels at zero but do not delete obsolete files. Manifest,
compaction, snapshots, filters/cache, background flush, group commit and benchmarks are unbuilt.

W0 documentation/build work is present, including CI and the runtime dependency guard. Both
prerequisite tests from the older assessment have landed:

- `9780bfd`: WAL truncation checks exactly the complete surviving records and values.
- `812f88c`: four writers/four readers, overwrites, scans and forced flushes across five seeds.

The concurrency test is stress coverage, not deterministic schedule replay. It does not prove
visibility after individual acknowledgements during concurrent execution, reclamation, delete
races, durable concurrent writes or close/error behavior. Five seeds exist, not the proposed
fifty; seeded reader choices do not control scheduling. The truncation test uses five small
records, not a full filesystem fault simulation. These are follow-up gates, not reasons to
repeat the completed prerequisite work.

**Validation limit:** Java is absent from PATH, /usr/lib/jvm is absent and this checkout has no
.tools JDK. Build/crash suites were not rerun. Existing green statements are historical;
remote CI, publication and tag state were not independently checked.

## Is the plan correct?

**Yes at milestone level:** M5 → M5.5 → M6 → M7. File membership and recovery must be explicit
before compaction removes inputs, and background work needs explicit write coordination.
No pivot or additional module is needed now.

The [M5 plan](../roadmap/m5-manifest-and-recovery.md) supplies the missing recovery boundaries,
installation protocol, legacy-data policy, safe Version acquisition, obsolete-file condition,
counter recovery and failure contracts. Irreversible choices remain for proposed ADR-0012.

Corrections to earlier advice:

- The charter's opening sequence put the manifest after snapshots and included Percolator.
  It now agrees with the milestone list and non-goals.
- Delete-on-zero requires obsolescence: normal close must not delete committed data.
- CURRENT selects the manifest; individual edits need not replace CURRENT.
- M4's claim that exact internal-key ties are unreachable is unsafe in the crash window between
  table rename and WAL deletion. M5 must handle new recovery and legacy duplicates explicitly.
- Recovery may include unacknowledged writes that reached storage. Gates must preserve durable
  acknowledgements without forbidding such writes.
- Truncation alone does not prove installation crash safety. Add operation injection and an
  explicit persistence model; current source has no directory-force protocol.
- Move snapshot acceptance to M7. Before dropping tombstones, prove no older value survives
  in unselected files. Compaction also invalidates newest-file-first point lookup assumptions.
- Add a small benchmark baseline before M5.5/M6; retain the full suite at M8. Subcompactions
  and advanced bloom allocation should not block the first correct engine.
- Keep documented module shells now; deleting them is not a prerequisite and would need a
  decision amending the four-module ADR.

## Ordered next steps

1. Use JDK 25 and establish a fresh `build crashTest` baseline before implementation.
2. Write/review ADR-0012 using the M5 checklist; settle persistence, migration, ownership and
   failure behavior before implementing its format.
3. Implement M5 in the plan's order, ending with fault/model tests and release documentation.
4. Capture the benchmark baseline, then M5.5 and M6, followed by M7 in explicit slices.
5. After the engine and measurements ship, choose the B+Tree capstone or Flotilla.

Planning is complete. The next engineering task is M5's decision record after baseline
validation; no prerequisite requires repeating the September 7 test work.
