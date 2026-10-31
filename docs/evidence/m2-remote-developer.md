# M2 evidence: role profiles and a remote provider

Design: [M2 spec](../superpowers/specs/2026-10-18-role-profiles-and-remote-provider-design.md). Plan: [M2 Task 10](../superpowers/plans/2026-10-18-role-profiles-and-remote-provider.md#task-10-verification-live-acceptance-handoff).

**The M2 live acceptance passed on 2026-10-31, with one departure from the spec: the cost limit was $0.005, not $0.10.** The harness pauses when actual spend plus one worst-case call would pass the effective limit. Real Haiku 5.5 calls cost about $0.0001–$0.0012 each, so a small task never gets near a $0.10 limit: a $0.01 run finished under budget. The owner chose a smaller limit over a longer, costlier task. The pause, resume and accounting code paths are the same at any limit.

| Spec acceptance item | Result |
|---|---|
| 1. Store the key, probe `claude-haiku-5-5`, bind the developer role | Passed. Owner stored the key; the probe saved a verified profile; the role was bound with `HARNESS_ROLES_DEVELOPER_PROFILE`. |
| 2. Submit a repository task as `developer` with the opt-in, a $0.10 limit and a 10% buffer | Passed with a **$0.005** limit and a 10% buffer (see above). A separate $0.01 run is also recorded. |
| 3. Watch role, model and cost update in the browser, including after a reload | Passed. The cost line showed role, model and cost before and after a reload. |
| 4. Pause at `COST_LIMIT` with spend at or below the effective limit | Passed. Paused at $0.00127 spent, effective limit $0.0045. |
| 5. Resume with +$0.50 and reach Ready for review | Passed. Resumed from the console pause panel; reached `READY_FOR_REVIEW`. |
| 6. Record run ID, final spend, estimated part and commit, with no key material | This document. |
| 7. The gitleaks CI step passes on the PR | Passed on [PR #41](https://github.com/BrianMehrman/continuous-agent-harness/pull/41) and runs on every later PR. |

## Live acceptance (2026-10-31)

### Setup

- **Harness code:** JAR built from `30c3293`, SHA-256 `03496b25ad95bb1a6b31bcab055c2ca67863ecd48e3e255e00f58a103d8bba45` (the worker build ID). `30c3293` changed only files under `runner/`, which are not in the JAR, so this is byte-identical to the `921a716` JAR used in the local journey. The cost display fix (`320de72`) came after the runs and was checked against their stored data.
- **Roles:** separate `api`, `worker` and `runner` processes on the default Compose project, default schema and `harness` namespace. That worker build was set as the current `coding-harness` version.
- **Check image:** `sha256:110814462406fbbddbcad02fecf2996d8965a8e3c6c43b106a58b69a8ee430a1`.
- **Profile:** `anthropic-ac9dc600b6ff44bfbb0c5bdf924375bb8eb6519065f33909e4a5611eeff4fe3c`, created at 19:49 UTC with `harness.py probe-anthropic claude-haiku-5-5 --credential anthropic_api_key --prices 0.10,0.50,0.125,0.01 --context 16384 --output 4096 --price-tier 100000`. Endpoint `https://api.anthropic.com`, `toolRoundTripVerified: true`.
- **Key:** stored by the owner in `.secrets/models/anthropic_api_key` (mode 0600, directory 0700, git-ignored). It was never read into the session, printed or logged; the API log held no key-shaped strings.
- **Browser:** headless Chromium, driven by Python Playwright, used only the page. The API was read only to check results.

### Probe

- The first probe attempt returned `HTTP 503: Anthropic profile probe unavailable`. A diagnostic that sent the adapter's exact request once showed Anthropic's real answer: `400 invalid_request_error`, "Your credit balance is too low to access the Anthropic API" (request `req_011CgatSrjJoddpNYa8p8NE8`).
- The request itself was valid, and nothing was billed.
- After the owner added credits, the probe passed through the harness API.

### Run A: pause at `COST_LIMIT` and resume

- `run-e7e7fdf19425fca711fcab5b924443f5e17772930e5bf6bbbfb05640992adc57`, accepted 19:52:16 UTC on base commit `921a716`.
- **Submit.** The profile list preselected `claude-haiku-5-5 · developer binding · remote`. The opt-in was checked, the limit set to `0.005` and the buffer left at `10`. The request carried `role: developer`, `allowRemote: true`, `costLimitMicros: 5000` and `costBufferPercent: 10`, with no `profileRevision`. It was accepted with `202`.
- **Before the pause.** Two model calls were charged 73 and 1,197 micro-dollars. The model read `README.md` and wrote the new line.
- **Pause.** At 19:52:24 the run paused with reason `COST_LIMIT`:
  - spent 1,270 micro-dollars, 0 estimated;
  - effective limit 4,500 (limit 5,000, buffer 10%);
  - next call's worst case 3,277, which would have reached 4,547.

  The pause panel showed "Paused · cost limit". Resume stayed disabled until an extra budget was entered.
- **Resume.** The console sent `extraCostMicros: 500000`, along with the panel's default extra turns, tool calls and minutes, and got `202`. The run requeued and ran two more calls, charged 488 and 1,001.
- **Result.** `READY_FOR_REVIEW` at 19:52:47:
  - final spend **2,759 micro-dollars ($0.002759), 0 estimated**;
  - limit 505,000, effective 454,500;
  - label `extended`; resume history `+20 turns · +50 tool calls · +15 min · +$0.50`.

  16 events. Every `MODEL_RECORDED` summary was `Model response recorded · developer · claude-haiku-5-5`.

### Run B: under budget at $0.01

- `run-bb026f367f03039c10ab7fd5d051338c10d78d86340de50ce0fcc99d2783c4f3`, accepted 19:51:16 UTC, same task, `costLimitMicros: 10000`.
- Four calls (201, 1,214, 500 and 1,016 micro-dollars) reached `READY_FOR_REVIEW` with **2,931 micro-dollars ($0.002931), 0 estimated**, without pausing.
- The cost line was visible before and after a reload.

Spend across the probe and both runs was well under one cent.

### Host checks

Both runs' host `BUILD` and `TEST` checks failed with exit 1. The cause is finding 2 below, not the model's change: the review diff added the requested line to `README.md` only. Ready for review does not depend on check results, by design.

## Full build (2026-10-31)

- `./gradlew build --rerun-tasks` passed on `921a716` (`main` after PR #41), on `30c3293` (the check-build fix) and on `320de72` (the cost display fix). Each took about 3 minutes.
- Each run had 94 unit tests and 20 integration tests, with 0 failures. The opt-in `LocalOllamaProbeIT` was skipped.
- Services: the default Compose project, with `HARNESS_RUNNER_IMAGE` and `HARNESS_REPOSITORY_RUNNER_IMAGE` taken from the lock files.
- Each build left two evaluator containers (`harness-eval-holder-…` running, `harness-eval-command-…` exited) and two `harness-eval-*` volumes. They were removed by hand (finding 4).

## Local console journey (2026-10-31)

Before the key was available, the same console was checked with a free local model. This used a JAR built from `921a716` (SHA-256 `03496b25ad95bb1a6b31bcab055c2ca67863ecd48e3e255e00f58a103d8bba45`) and profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` (`qwen3.5:9b`), bound as the developer role.

- **Profile list.** The bound profile was preselected and labelled `qwen3.5:9b · developer binding · 6488c96fa5fa`. The role select offered only Developer.
- **Remote form, on a `claude-haiku-5-5` fixture profile from `RunApiIT`, never submitted.** Submit stayed disabled with:
  - no opt-in;
  - the opt-in but no limit;
  - a buffer of 51.

  It was enabled with the opt-in, a limit and a buffer of 10. Switching to the local profile and back cleared all three fields.
- **Runs.** `run-0cc58e003a34015a0d8f06cb1bf57a5468754f0f557759dc17814828eab6fa85` ran with the old check image and `run-ba0265ea91693d68cf6444dedcbb9aec31b43063423325f53fd82a8ae6b96fc7` with the rebuilt one. Each reached `READY_FOR_REVIEW` with 12 events, shown once each and in order across a mid-run reload. Every model event read `developer · qwen3.5:9b`, and detail returned `cost: null`.

## Findings

1. **The check build lacked the Anthropic SDK (fixed in `30c3293`).** M2 Task 3 added `com.anthropic:anthropic-java:2.71.0` to `build.gradle` but not to the trusted `runner/repository-build.gradle`. The fix adds it, rebuilds the check image and updates `runner/repository-image.lock`. Before the fix, `BUILD` failed with 62 `cannot find symbol` errors in `AnthropicModelAdapter`; afterwards there were none. `RepositoryTaskIT` passed with the new image.
2. **This repository cannot pass its own console checks (open).** Since `e6f7d99` (PR #20 review fixes), snapshots exclude the host-side `benchmark` package so the model never sees the private evaluator. But `ToolRouter`, `RunActivitiesImpl` and `RunCommandService` import it, so the check fails with 10 compile errors from that package. Fixing this needs a design choice, for example excluding only the private evaluator classes.
3. **Cost amounts were rounded to cents (fixed in `320de72`).** At Haiku prices the console and CLI showed "$0.00 spent … of $0.00 effective / $0.01 limit". JavaScript and Python also rounded half cents differently: the console showed $0.505 as `$0.51` and the CLI as `$0.50`. Both now show exact amounts with at least two and up to six decimals, for example `$0.002759 (+$0.00 est) of $0.4545 / $0.505`. The console and CLI were checked against Run A and Run B and printed identical lines.
4. **Evaluator integration tests leave containers (open).** `EvaluationRuntimeSupervisor` kills a running container on one pass and removes it on a later one, and the test JVM exits in between.
5. **The probe hides Anthropic's error (open).** The adapter deliberately discards provider error bodies, and the API maps unknown probe failures to a generic `503`, with no log line. A low credit balance was therefore indistinguishable from an outage. Reporting the HTTP status and error type, which carry no body, would make this diagnosable.
6. **Title-cased summaries (cosmetic, open).** The console's CSS title-cases event summaries, so a model tag displays as `Qwen3.5:9b`. Stored and API values are correct.

## Limits

- The cost limit was $0.005, not the spec's $0.10 (see the top of this document).
- The browser was headless Chromium, not a person at a screen.
- The model's README changes were not applied.
- No benchmark evaluator judged these repository tasks, and the task-tracker benchmark (M7) remains open.

The roles and Compose services were stopped after the runs. The database volume, which holds the profiles and runs, was kept.
