# Sprint 03 — Role profiles and a remote provider (M2)

Status: Planned. The spec is approved; the plan is awaiting owner review and a choice of execution method.

Milestone: [M2](../plans/roadmap.md#milestones)

Spec: [Role profiles and a remote provider](../superpowers/specs/2026-10-18-role-profiles-and-remote-provider-design.md)

Plan: [M2 implementation plan](../superpowers/plans/2026-10-18-role-profiles-and-remote-provider.md)

Sprints group work and carry no dates.

## Objective

The existing coding loop runs as a committed `developer` role on a local Ollama model or on Claude Haiku 5.5 through the Anthropic API. Each run accounts for its spend and pauses with `COST_LIMIT` before a model call could exceed its buffered cost limit. The API key never leaves the host process and is never committed or published.

## Work packages

Mark a row done only after its PR has merged. The plan's own checkboxes track progress within a task.

| # | Work | Plan task | PR | Done |
|---|---|---|---|---|
| 1 | Sealed Ollama and Anthropic profile types | 1 | — | [ ] |
| 2 | Shared tool catalog and per-call tool sets | 2 | — | [ ] |
| 3 | Anthropic adapter, file credentials, leak tests | 3 | — | [ ] |
| 4 | Create Anthropic profiles; key storage script | 4 | — | [ ] |
| 5 | Committed roles, frozen run terms, remote opt-in | 5 | — | [ ] |
| 6 | Role tool sets and secret-path deny list | 6 | — | [ ] |
| 7 | Cost ledger, buffered limit, `COST_LIMIT` pause | 7 | — | [ ] |
| 8 | CLI and console: role, model, cost, cost resume | 8 | — | [ ] |
| 9 | Pinned gitleaks scan in CI | 9 | — | [ ] |
| 10 | Full build, live acceptance, evidence, handoff | 10 | — | [ ] |

## Manual steps (owner)

These live only on the owner's machine. Each is documented in `docs/development.md` by Task 4.

- Store the Anthropic key once: `python3 scripts/store-anthropic-key.py` (writes `.secrets/models/anthropic_api_key`).
- Create the Haiku 5.5 profile with `harness.py probe-anthropic`.
- Bind `harness.roles.developer.profile` in local application config.

CI needs no key.

## Completion evidence

- `./gradlew build` passes on the head commit.
- The gitleaks CI step passes on the PR.
- A live, browser-driven repository task runs as `developer` on `claude-haiku-5-5` with a $0.10 limit and a 10% buffer. It pauses at `COST_LIMIT` with spend at or below $0.09, is resumed with +$0.50, and reaches Ready for review.
- Record the run ID, commit, final spend and estimated part, with no key material.

Scripted or stubbed results do not complete this sprint.

## Out of scope

More than one role per run, roles other than `developer`, the mission workflow (M3), an OpenAI-compatible adapter, and remote profile creation in the console.
