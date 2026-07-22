/**
 * Concurrency-contract marker annotations. Every {@code shale-core} type is annotated with exactly
 * one of {@link dev.shale.internal.annotations.ThreadSafe}, {@link
 * dev.shale.internal.annotations.NotThreadSafe}, or {@link
 * dev.shale.internal.annotations.Immutable} (see {@code
 * documentation/conventions/concurrency-and-resources.md} §1).
 *
 * <p><b>Threading:</b> annotations only; no runtime behaviour. <b>Entry point:</b> none — applied
 * to other types.
 */
package dev.shale.internal.annotations;
