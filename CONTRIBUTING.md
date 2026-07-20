# Contributing

## Setup

```bash
git clone <repo> && cd shale
git config commit.template .gitmessage    # do this first
./gradlew build
```

Requires JDK 25. No other local tooling — the Gradle wrapper brings its own.

## The loop

1. Read the current milestone in `documentation/roadmap/`. Work belongs to exactly one
   milestone, and milestones are strictly ordered.
2. Branch: `m06/leveled-compaction`.
3. If the change touches an on-disk format, a public API, the module graph, or anything
   the roadmap marks hard-to-reverse — write the ADR first and stop there until it is
   accepted.
4. Write the failing test.
5. Implement.
6. `./gradlew build && ./gradlew crashTest`
7. Commit per `documentation/conventions/commits.md`, with the required trailers.
8. Rebase onto `main`, do not merge.

## Before you push

- [ ] `./gradlew build` green, `crashTest` green
- [ ] New public types have Javadoc with **Threading**, **Ownership**, and a citation
- [ ] New mutable fields declare a concurrency contract
- [ ] Format changes: version bumped, `format.md` updated, golden file added,
      `Format-Change:` trailer present
- [ ] New durability claims have a crash test
- [ ] No new dependency (or: ADR merged)
- [ ] No `Thread.sleep`, no unseeded `Random`, no `System.out`
- [ ] Commit has `Milestone:`, and `Benchmark:` if it is a `perf` commit

## Where the rules live

`CLAUDE.md` is the summary and the source of authority for agents. The detail sits in
`documentation/conventions/`. If you find a rule ambiguous, that is a bug in the rule —
fix the document in its own `docs` commit rather than guessing, so the next person
does not face the same ambiguity.

## Working with Claude Code

`CLAUDE.md` is loaded automatically at session start. Beyond that:

- Point it at the specific convention file for the task rather than restating rules in
  the prompt — the prompt gets forgotten across a long session, the file does not.
- Name the milestone in the prompt. The most common failure mode is an agent
  helpfully building three milestones ahead, which produces code with no tests behind
  it and breaks the "each milestone ends in a working artifact" discipline.
- Ask for the ADR before the implementation on anything hard to reverse.
- Review format-touching diffs by hand, byte by byte. This is the one area where a
  plausible-looking wrong answer is both easy to generate and expensive to discover.
- Add the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer where it applies.
  This project's value is in demonstrating understanding; being straightforward about
  what was generated and what was hand-written costs nothing and protects that claim.
