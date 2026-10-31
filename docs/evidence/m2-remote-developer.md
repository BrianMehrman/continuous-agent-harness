# M2 evidence: role profiles and a remote provider

Design: [M2 spec](../superpowers/specs/2026-10-18-role-profiles-and-remote-provider-design.md). Plan: [M2 Task 10](../superpowers/plans/2026-10-18-role-profiles-and-remote-provider.md#task-10-verification-live-acceptance-handoff).

**M2 acceptance has not been run.** The spec's live acceptance needs a real Anthropic key and spends real money. The owner will provide a key at the end of the sprint (decision of 2026-10-31). Until then, acceptance items 1–6 are outstanding. Everything below is a local check, not acceptance.

| Spec acceptance item | State |
|---|---|
| 1. Store the key, probe `claude-haiku-5-5`, bind the developer role | Not run (no key) |
| 2. Submit a repository task as `developer` with the opt-in, a $0.10 limit and a 10% buffer | Not run. The form gating was checked against a remote fixture profile without submitting; see below. |
| 3. Watch role, model and cost update in the browser, including after a reload | Role and model checked on a free local run. Cost not shown (free profile). |
| 4. Pause at `COST_LIMIT` with spend at or below $0.09 | Not run. Covered only by `CodingWorkflowTest` and `WorkApiIT`. |
| 5. Resume with +$0.50 and reach Ready for review | Not run |
| 6. Record run ID, final spend, estimated part and commit | Not run |
| 7. The gitleaks CI step passes on the PR | Passed on [PR #41](https://github.com/BrianMehrman/continuous-agent-harness/pull/41) (2 commits scanned, no leaks) |

## Full build (2026-10-31)

- `./gradlew build --rerun-tasks` passed on `921a716` (`main` after PR #41) and again on `30c3293` (the check-build fix below), each in about 3 minutes.
- Each run had 94 unit tests and 20 integration tests, with 0 failures. The opt-in `LocalOllamaProbeIT` was skipped.
- Services: the default Compose project, with `HARNESS_RUNNER_IMAGE` and `HARNESS_REPOSITORY_RUNNER_IMAGE` taken from the lock files.
- Each build left two evaluator containers behind (`harness-eval-holder-…` running, `harness-eval-command-…` exited), plus two `harness-eval-*` volumes. `EvaluationRuntimeSupervisor` kills a running container on one pass and removes it on a later one, and the test JVM exits before the later pass. They were removed by hand. This is an open test-cleanup gap.

## Local console journey (2026-10-31)

### Setup

- JAR built from `921a716`, SHA-256 `03496b25ad95bb1a6b31bcab055c2ca67863ecd48e3e255e00f58a103d8bba45`.
- Separate `api`, `worker` and `runner` processes on the default Compose project, default schema and the `harness` namespace. That worker build was set as the current `coding-harness` version.
- Profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` (`qwen3.5:9b`), verified with `harness.py probe`. The API process bound it as the developer role with `HARNESS_ROLES_DEVELOPER_PROFILE`; no config file was written.
- Headless Chromium, driven by Python Playwright, used only the page.

### What was checked

- **Profile list.** The bound profile was preselected and labelled `qwen3.5:9b · developer binding · 6488c96fa5fa`. The role select offered only Developer.
- **Remote form, never submitted.** The shared database holds remote `claude-haiku-5-5` fixture profiles written by `RunApiIT`, whose credential reference has no key file. Selecting one showed `claude-haiku-5-5 · remote`, the "Send repository content to Anthropic" checkbox, a blank cost limit and a buffer of 10. Submit stayed disabled with:
  - no opt-in;
  - the opt-in but no limit;
  - a buffer of 51.

  It was enabled with the opt-in, `0.10` and `10`. Switching to the local profile and back cleared the opt-in and the limit, and reset the buffer to 10.
- **Submit.** The local task request carried `commandId`, `title`, `instructions` and `role`. It had no `profileRevision`, because the bound profile resolves on the server. The API answered `202`.
- **Run.** Two runs, both on base commit `921a716`:
  - `run-0cc58e003a34015a0d8f06cb1bf57a5468754f0f557759dc17814828eab6fa85`, accepted at 18:17:22 UTC with the old check image;
  - `run-ba0265ea91693d68cf6444dedcbb9aec31b43063423325f53fd82a8ae6b96fc7`, accepted at 18:21:58 UTC with the rebuilt image.

  The page was reloaded once mid-run. Each run reached `READY_FOR_REVIEW` with 12 events, shown once each and in order, matching `/api/work/{id}/events?after=0`. The event sequence was model → `read_file` → model → `write_file` → model → `build` → model → `run_tests` → model → `submit` → review.
- **Role and model.** Every `MODEL_RECORDED` summary was `Model response recorded · developer · qwen3.5:9b`. Work detail returned `role: developer`, `model: qwen3.5:9b` and `cost: null`. The page showed no cost line, as expected for a free profile without a limit.
- **Review diff.** The diff added the requested line to `README.md` only. It dropped the file's final newline. The live checkout was unchanged.

### Findings

1. **The check build lacked the Anthropic SDK (fixed in `30c3293`).** M2 Task 3 added `com.anthropic:anthropic-java:2.71.0` to `build.gradle` but not to the trusted `runner/repository-build.gradle`. The pinned check image predated it. Run 1's host `BUILD` failed with 62 `cannot find symbol` errors in `AnthropicModelAdapter`. The fix adds the dependency and rebuilds the image, giving `runner/repository-image.lock` the value `sha256:110814462406fbbddbcad02fecf2996d8965a8e3c6c43b106a58b69a8ee430a1`. Run 2 had no Anthropic errors. `RepositoryTaskIT` passed with the new image.
2. **This repository cannot pass its own console checks (open).** Since `e6f7d99` (PR #20 review fixes), repository snapshots exclude the host-side `benchmark` package so the model never sees the private evaluator. But `ToolRouter`, `RunActivitiesImpl` and `RunCommandService` import it. Run 2's host `BUILD` and `TEST` still failed with exit 1: 10 compile errors, all from the missing package. The October 12 console trial passed its build on `64a0439`, before that exclusion. Fixing this needs a design choice, for example excluding only the private evaluator classes, so it is left as a follow-up.
3. **Title-cased summaries (cosmetic, open).** The console's CSS title-cases event summaries, so `developer · qwen3.5:9b` displays as "Developer · Qwen3.5:9b". The stored and API values are correct; only the displayed model tag is altered.

### Limits

- A free local model ran this journey, so no cost was charged, no cost line was shown, and no `COST_LIMIT` pause or cost resume happened.
- No request reached Anthropic.
- The browser was headless Chromium, not a person at a screen.
- The model's README change was not applied.
- Both runs ended with failed host checks. A model submission becomes Ready for review whatever the check result, by design.

The roles and Compose services were stopped afterwards. The database volume was kept.
