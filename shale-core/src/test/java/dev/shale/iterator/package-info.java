/**
 * Tests for merge-iteration and reconciliation, plus {@link dev.shale.iterator.Drain} — the
 * test-only helper that collects an {@link dev.shale.iterator.InternalIterator} into a list.
 *
 * <p>{@code Drain} is shared across the memtable and SSTable suites because M4 removed the
 * materialising {@code entries()} accessors from both (ADR-0011), and assertions about whole-source
 * ordering read better against a list than against an advance loop. It stays in the test tree
 * deliberately: as production API it would be exactly the unbounded read the ADR set out to delete.
 */
package dev.shale.iterator;
