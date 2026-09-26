# Archived milestone plans

Plans kept for the historical record but no longer the working reference.

| Plan | Why archived |
|---|---|
| [`m9-single-raft-group.md`](m9-single-raft-group.md), [`m10-multi-raft-sharding.md`](m10-multi-raft-sharding.md) | Flotilla dropped from scope by [ADR-0014](../../adr/0014-drop-flotilla-from-scope.md). Kept so a separate distributed follow-on project starts from a worked plan. |
| [`m0-skeleton-and-interfaces.md`](m0-skeleton-and-interfaces.md) | 2,061 lines with 112 code fences — the M0 source pasted into a task script rather than a plan a human reads. Its decisions live on in ADRs 0004–0006 and the M0 release note; M1–M4 plans (71–90 lines) are the format to follow. |

Milestone plans should state goal, scope, deferrals, task order and the invariants
tests must hold — and cap at roughly 150 lines. The code belongs in the commit.
