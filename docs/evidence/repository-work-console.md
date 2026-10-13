# Repository work console: local proof

Date: 2026-10-12 CDT (2026-10-13 UTC). This is a repository-task run, **not** a live task-tracker benchmark acceptance result.

## Setup

- Isolated PostgreSQL schema `console_run` and Temporal namespace `console-trial` on the task-owned `harness-tryout` Compose project; named database volumes were preserved.
- Boot JAR built with `./gradlew bootJar --offline`, serving `/` and its same-origin assets from loopback port 8080. `/actuator/health` returned `UP` and `/api/work` returned the queue projection.
- Locally installed Ollama `qwen3.5:9b`, verified for tool use as immutable profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129`, full model digest `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`, 16K context and 4K output tokens.
- Trusted repository runner image ID `sha256:f9729d6ec9dd99f334f16fb1d7a7d7b48d1b480f1e42ec92166fc2b64e32fb72`; candidate containers used `--network none` and a fixed host-owned Gradle build file. A disposable offline runner diagnostic completed `jar` with nested migration resources after correcting root-owned input directory permissions.

## Observed run

Run `run-0f1b5bd9e54335f64a49c0915990a302cd6d251aa0eca45bc85a69b53518fc07` accepted at `03:55:49.456815 UTC` and reached `READY_FOR_REVIEW` at `03:56:26.317680 UTC` (about 37 seconds). It froze `main` at `076699ef63290e3d17a8ed02719163a6627877aa` with seed snapshot `1d938e1eebd3fa0a8b9a0625c2f3b5564ae3878431d3b66496ec27231276e0bd`. The model read and edited `README.md`, called `build`, and submitted a summary. Ordered events advanced through model, tool, check, submit, and review stages.

The host-owned `BUILD` receipt was `SUCCEEDED`, exit code 0, against final snapshot `4207ac059a0c27e5beae10e7fdb9fc111755eafbc0157f5ba764f201822fef0b`. The downloadable diff blob was `a96ddf12b5dae7b01b445870dc3ed2f1bfa1c596209ee12f4db2a6836044917a`; it contained only `README.md` and added the requested sentence. The live checkout had no model-created `Console note` section. The runner supervisor left no task-owned containers after completion.

While that run held the slot, `run-e3dce59b3fbce78551fa679016e3c9d1ffee48d85c07b8bb02320f4751daed2c` remained queued at version 0 after START delivery. Its cancellation command returned `APPLIED`; it reached `CANCELLED` without a runner invocation.

Flyway recorded successful versions 7 (`V7__create_repository_work_items.sql`, `create repository work items`) and 8 (`V8__store_repository_review_result.sql`, `store repository review result`). The focused `WorkApiIT` passed three cases, including idempotent intake, FIFO admission, isolated edit, concise host diff, and a failed check retained in review data.

Final review closed gaps in active and queued visibility after long history, shared-slot display for benchmark work, trailing-newline diffs, and unfinished-task review output. The subsequent `./gradlew build --offline` passed 71 unit and integration tests with no failures or errors; the existing opt-in skip remained. The focused review test now exercises a newline-only edit and a failed task's downloadable diff.

## Browser journey and scripted check (2026-10-12)

Run against commit `64a0439` (Boot JAR SHA-256 `81daff3c3dfa7a0623f103411f864dcf77c2ca168793ab9c1e6bf81e7d50adb7`) with separate `api`, `worker`, and `runner` processes, schema `console_journey`, Temporal namespace `console-trial`, and the same runner images. A headless Chromium session driven by Python Playwright used only the page:

- Verified `qwen3.5:9b` through **Verify model** (same profile and digest as above).
- Submitted the first task from the keyboard (Tab to **Add to queue →**, Enter). Run `run-e322e30c2b4ba08a92a80781b7af4ad9eebeaa44efbc8ae73c0ab987dd137c5a` took the slot; a second task, `run-1bff3442a18c752cde5129f68bbf83ec63d0390593f78f4d56af9ad42a4f34b5`, showed `#1 in queue`.
- **Cancel task** on the queued item reached `CANCELLED` with outcome `APPLIED` and no check receipts; it never gained the slot.
- The page was reloaded mid-run at event 3. After completion the activity list held events 1–10 exactly once, in order, matching `/api/work/{id}/events?after=0`: model → `read_file` → model → `write_file` → model → `build` → model → `submit` → review.
- The first task reached `READY_FOR_REVIEW` about 41 s after acceptance. Its host `BUILD` receipt was `SUCCEEDED`, exit 0. **Download review diff** returned 200 `text/x-diff` with `nosniff` and an attachment disposition. The diff touched only `README.md`, which was unchanged in the live checkout. The loop highlighted **06 Review**, and every stage had a text label.
- Tab order reached Task title, Instructions, Verified local model, and Installed Ollama model tag. Disabled buttons are skipped until usable; work rows are buttons.

`RepositoryTaskIT` drives a scripted model through the real workflow, tool router, Docker runner, and review packaging. It writes a syntax-broken Java file, the real `BUILD` fails, and the model claims success on submit. The task ends `READY_FOR_REVIEW` with the `FAILED` receipt visible, a diff containing only that file, contiguous events whose stages match the tool calls, an `after` cursor that returns only later events, and cleaned runner containers. A deliberate mutation (repository submit reporting `SUCCEEDED`) made it fail. On `64a0439`, `./gradlew integrationTest --offline --tests 'com.brianmehrman.harness.execution.RepositoryTaskIT' --tests 'com.brianmehrman.harness.api.WorkApiIT'` and `./gradlew test --offline` (55 tests) passed.

## Limits of this proof

The browser journey was headless Chromium driven by Playwright, not a person at a screen; screen-reader output was not checked. The model's proposed README change was not applied. The review diff is one prefix/suffix hunk per file, so when the model dropped README's final newline, unchanged lines appeared removed and re-added. A benchmark evaluator did not judge this arbitrary repository task, and the original task-tracker live benchmark remains open.

Two earlier 4B trials ended `FAILED` when the model exhausted a 1,024-token response before another tool call. The first 9B trial reached review but had no check receipts because the worker lacked the pinned repository image. A subsequent check recorded a real `FAILED` build caused by Gradle resource-copy permissions in the trusted runner; that receipt remained visible in `READY_FOR_REVIEW`. The observed API-submitted run used the corrected image and recorded a successful host check.
