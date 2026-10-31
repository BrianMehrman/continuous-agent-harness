# Delivery roadmap

Status: Revised 2026-10-17 by owner direction: long-running, plan-driven, multi-role harness first; benchmark acceptance through it

Date: 2026-09-20; revised 2026-10-17

This is a high-level roadmap. Project status lives in [STATUS.md](../STATUS.md). The [durable foundation](../specs/spring-boot-durable-foundation.md) governs execution semantics. Each milestone below gets its own spec, implementation plan, and pull request.

## Goal

A durable harness that takes a coding goal and carries it through spec, plan, and implemented, verified steps, using role agents on local and remote models. It runs in two modes, and an operator can watch it live in a browser UI:

- **Human in the loop:** the operator approves the spec and the plan, and is called on every hard stop.
- **Agent loop:** the plan reviewer approves the spec and plan instead, and a stalled developer goes back to the project manager to split its step. Every agent approval is recorded and visible. Hard stops still pause for the operator: cost limit, a budget the agents cannot split their way out of, and drift QA cannot resolve.

Roles and their intended model class:

| Role | Responsibility | Model class |
|---|---|---|
| Architect | Turn a goal into a design and spec | Strongest available, usually remote |
| Project manager | Turn a spec into a plan of right-sized steps; split steps that are too large | Strong |
| Plan reviewer | Check that the plan covers the spec and that each step fits a run budget; approve in agent-loop mode | Strong, independent of the project manager |
| Developer | Implement one step: edit, build, test | Fast; local is acceptable |
| QA | Verify a step against its acceptance criteria; detect drift between plan and implementation | Strong, independent of the developer |

The task-tracker benchmark becomes an acceptance test of this harness: run as a mission, it must end with a trusted evaluator pass.

## Architecture direction

Temporal runs the role graph. A parent mission workflow holds the plan and moves between roles; each role step is a child workflow generalized from today's coding loop. A role is a model profile, an allowed tool set, and a prompt. Gates and pauses are durable signal waits, whether an operator or an agent resolves them. The plan is a durable, versioned document: steps with acceptance criteria, allowed files, and a size estimate. Agents never hold the plan only in memory; splitting a step creates a new plan version, and QA's drift check compares a step's criteria and allowed files against its diff and check receipts. The UI's graph and plan views read the same durable records.

## Milestones

Each milestone ships something usable and adds its own UI slice.

| # | Milestone | Delivers | UI slice |
|---|---|---|---|
| M1 | Stall detection and pause-at-limit | The coding run detects no-progress loops from recorded facts and pauses at its limits instead of ending; the operator resumes with extra budget or a hint, or stops it. [Spec](../superpowers/specs/2026-10-17-stall-detection-and-pause-design.md) | Paused state, reason and evidence, resume/stop, in-UI notification |
| M2 | Role profiles and a remote provider | Role = model profile + tools + prompt; a remote API adapter with file-based credentials; per-run cost accounting and a cost hard stop | Role and model per turn; cost so far |
| M3 | Plan document and mission workflow | Versioned plan with per-step acceptance criteria and budgets; a mission workflow runs one developer step at a time; human gates for spec and plan | Mission graph, plan view, approve/reject at gates |
| M4 | Project manager and plan reviewer | Plan sizing and splitting into a new version; agent-loop mode with recorded agent approvals; stall escalation from developer to project manager | Mode switch, plan version history, agent decisions |
| M5 | QA and drift detection | Each step checked against its criteria and allowed files; drift fixed or escalated as a hard stop | Drift findings per step |
| M6 | Architect | Goal to spec, feeding M3's spec gate | Spec view |
| M7 | Benchmark acceptance | The task tracker, run as a mission, ends with a trusted evaluator pass. Unassisted (no operator hints) is the proposed criterion; budget extensions are allowed and reported | — |

Notifications outside the UI (email, push, chat) are deferred; the first UI pass shows an in-page notification.

## Sprints

A sprint is a group of work with no dates. Each sprint file holds its objective, work packages and completion evidence; its work-package table records which pieces have merged. [STATUS.md](../STATUS.md) remains the handoff.

| Sprint | Scope | State |
|---|---|---|
| [01](../sprints/01-observable-loop.md) | Durable local coding loop, workspace, runner, evaluator, operator API/CLI, browser console (original Tasks 1–9) | Closed; the Task 10 benchmark pass moved to M7 |
| [02](../sprints/02-stall-detection-and-pause.md) | M1 stall detection and pause-at-limit | Closed ([PR #28](https://github.com/BrianMehrman/continuous-agent-harness/pull/28)) |
| [03](../sprints/03-role-profiles-and-remote-provider.md) | M2 role profiles and a remote provider | In progress; Tasks 1–8 merged |
| — | M3–M7 | Not yet scoped; each gets a sprint when its spec is written |

## After M7

These earlier Phase 1 items remain and follow M7: the local acceptance script and `liveLocalTest`, crash and deployment-continuity scenarios against real services, and the local-versus-remote comparison of the same coding task. The original Phases 3 (coded graphs and workflows) and 4 (delegation, mixed backends) are largely absorbed into M3–M6; recurring execution and checkpoint forks still need separate specifications.

## History

The 2026-09-20 roadmap ran four phases: a durable observable loop, interactive continuity, coded graphs, and fleets. The first slice delivered the durable coding loop, workspace, runner, evaluator, operator API/CLI, and loop console. Five live `qwen3.5:9b` runs on 2026-10-17 bounded context successfully but never passed the benchmark; each fixed failure exposed another, including a fixed-point loop a turn budget alone cannot recognize ([evidence](../evidence/local-task-tracker.md)). That led to this revision.

## Delivery discipline

Implementation plans map reviewed specifications to exact files, interfaces, and test steps. Never integrate directly into the default branch. Name database migrations for their schema purpose and verify filenames/history entries before committing.
