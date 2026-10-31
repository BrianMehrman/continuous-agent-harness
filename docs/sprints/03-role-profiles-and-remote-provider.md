# Sprint 03 — Role profiles and a remote provider (M2)

Status: Closed. Task 10 merged in [PR #42](https://github.com/BrianMehrman/continuous-agent-harness/pull/42) as `89bf5f4` on 2026-10-31; the live acceptance passed with a $0.005 limit ([evidence](../evidence/m2-remote-developer.md)).

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
| 1 | Sealed Ollama and Anthropic profile types | 1 | [#32](https://github.com/BrianMehrman/continuous-agent-harness/pull/32) | [x] |
| 2 | Shared tool catalog and per-call tool sets | 2 | [#34](https://github.com/BrianMehrman/continuous-agent-harness/pull/34) | [x] |
| 3 | Anthropic adapter, file credentials, leak tests | 3 | [#35](https://github.com/BrianMehrman/continuous-agent-harness/pull/35) | [x] |
| 4 | Create Anthropic profiles; key storage script | 4 | [#36](https://github.com/BrianMehrman/continuous-agent-harness/pull/36) | [x] |
| 5 | Committed roles, frozen run terms, remote opt-in | 5 | [#37](https://github.com/BrianMehrman/continuous-agent-harness/pull/37) | [x] |
| 6 | Role tool sets and secret-path deny list | 6 | [#38](https://github.com/BrianMehrman/continuous-agent-harness/pull/38) | [x] |
| 7 | Cost ledger, buffered limit, `COST_LIMIT` pause | 7 | [#39](https://github.com/BrianMehrman/continuous-agent-harness/pull/39) | [x] |
| 8 | CLI and console: role, model, cost, cost resume | 8 | [#40](https://github.com/BrianMehrman/continuous-agent-harness/pull/40) | [x] |
| 9 | Pinned gitleaks scan in CI | 9 | [#41](https://github.com/BrianMehrman/continuous-agent-harness/pull/41) | [x] |
| 10 | Full build, live acceptance, evidence, handoff | 10 | [#42](https://github.com/BrianMehrman/continuous-agent-harness/pull/42) | [x] |

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

On 2026-10-31 the owner accepted a $0.005 limit in place of $0.10: a small Haiku 5.5 task spends about $0.003, so it never reaches a $0.10 limit. A $0.01 run completed under budget, and a $0.005 run paused and resumed ([evidence](../evidence/m2-remote-developer.md)).

Scripted or stubbed results do not complete this sprint.

## Out of scope

More than one role per run, roles other than `developer`, the mission workflow (M3), an OpenAI-compatible adapter, and remote profile creation in the console.
