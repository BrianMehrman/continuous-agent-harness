# Local operator API and CLI

Task 8 adds a loopback HTTP API and a Python 3 standard-library client. Start the services and build the application as described in [local development](development.md). In separate terminals, run the API, worker, and runner roles:

```sh
# Terminal 1
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=api

# Terminal 2
export HARNESS_WORKER_BUILD_ID="sha256:$(shasum -a 256 build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar | awk '{print $1}')"
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=worker

# Terminal 3
HARNESS_RUNNER_ENABLED=true HARNESS_RUNNER_IMAGE="$(sed -n 's/^RUNNER_IMAGE=//p' runner/image.lock)" java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=runner
```

Run these from the repository root so the application reads `.secrets/app/`. The worker build ID is the packaged JAR's immutable digest and is required for worker startup. Select it as the current `coding-harness` version with the tested commands in [operations](operations.md) before submitting runs. The API binds `127.0.0.1:8080` by default. The API accepts a command durably before the background dispatcher delivers it to Temporal. A `202` response means accepted, not applied. Inspect `deliveryStatus` and `applicationOutcome` in a command response or `commands[]` in run detail to see delivery and cancellation results. `projection.stale` signals a gap between authoritative workflow state and contiguous semantic events.

## Operator walkthrough

The following uses the API with a selected installed local Ollama model. The workflow is a real coding run; the model may or may not pass the benchmark. No model is downloaded automatically. For a deterministic scripted run through HTTP, the outbox dispatcher, real Temporal, event paging, and artifact retrieval, run `./gradlew integrationTest --tests '*ApiWorkflowIT'`; it uses a test-only scripted model response and ends in `FAILED` without calling Ollama.

```sh
python3 scripts/harness.py probe qwen3.8:latest
python3 scripts/harness.py profiles
python3 scripts/harness.py start --profile PROFILE_ID --command-id local-attempt-1
python3 scripts/harness.py start --profile REMOTE_PROFILE_ID --role developer --allow-remote --cost-limit 0.10 --cost-buffer 10
python3 scripts/harness.py status RUN_ID
python3 scripts/harness.py events RUN_ID --after 0 --follow
```

Replace `qwen3.8:latest` with an installed local tag, and copy `PROFILE_ID` and `RUN_ID` from the prior responses. Keep the `--command-id` value when retrying a start after a lost HTTP response: the same command and payload return the same run. To cancel, read `projection.version` from `status` and send:

```sh
python3 scripts/harness.py cancel RUN_ID --expected-version VERSION --command-id local-cancel-1
python3 scripts/harness.py status RUN_ID
```

The cancellation response also means accepted. The run detail later shows the workflow's application outcome. If the supplied version is already stale, the API responds `409`; if a delivered workflow cannot answer the authoritative version query, it responds `503` without accepting the cancellation. A race after acceptance may still produce `REJECTED_VERSION` in `applicationOutcome`.

Event responses contain `payloadBlobId` SHA-256 digests. The event cursor returns only contiguous events, so a missing sequence cannot be skipped by a reconnecting client. Download one with:

```sh
python3 scripts/harness.py download RUN_ID DIGEST --output event.json
```

The downloaded file is output data; inspect it with an editor or JSON viewer. Runner reports, evaluation artifacts, model attempt blobs, semantic event payloads, and terminal result blobs use the same route when associated with that run. Unrelated digests return `404`. The CLI never executes downloaded content.

## Pause and resume

A run that stops making progress or reaches a limit pauses instead of failing. Events `NUDGED`, `PAUSED`, `RESUMED`, and `STOPPED` record the ladder. Pause reasons are `STALLED`, `TURN_LIMIT`, `TOOL_LIMIT`, `TIME_LIMIT`, and `COST_LIMIT`; a run that is stopped, or whose 7-day waiting limit passes, ends with its pause reason: `STALLED` as `STALLED`, `TURN_LIMIT`, `TOOL_LIMIT`, and `COST_LIMIT` as `LIMIT_EXHAUSTED`, and `TIME_LIMIT` as `EXPIRED`. A resume must extend the limit that was reached (`extraTurns`, `extraToolCalls`, `extraMinutes`, or `extraCostMicros` above 0 for the matching limit); the API answers `400` otherwise. A `STALLED` pause accepts any resume. Run and work detail views add `pause` (only while `PAUSED`), `resumes`, `nudges`, and `label`: `assisted` if any resume carried a hint, `extended` if resumes only added limits, otherwise null; an assisted pass is never an unassisted completion. The work list adds `paused` and `pausedRuns`.

Read `projection.version` from `status`, then resume with extra allowance or stop:

```sh
python3 scripts/harness.py resume RUN_ID --expected-version VERSION --turns 20 --hint "Check the failing test first"
python3 scripts/harness.py resume RUN_ID --expected-version VERSION --cost 0.50
python3 scripts/harness.py stop RUN_ID --expected-version VERSION
```

`--tools N` and `--minutes N` grant extra tool calls and minutes. `--cost DOLLARS` raises the frozen cost limit; a `COST_LIMIT` resume must add enough to cover the next call's worst case after the buffer. Ceilings are per resume 40 turns, 100 tool calls, and 30 minutes; per run 3 resumes, 120 extra turns, 300 extra tool calls, and 90 extra minutes. A hint is at most 2,000 characters and reaches only the conversation. A run with no resumes left ends with its pause reason at its next pause.

## HTTP contract

| Method and path | Purpose |
|---|---|
| `POST /api/runs` | Accept `{commandId, benchmarkVersion, limits}` plus optional `profileRevision`, `role`, `allowRemote`, `costLimitMicros`, and `costBufferPercent`; return `202` with run and command IDs. |
| `POST /api/work` | Accept `{commandId, title, instructions}` plus the same optional run terms; return `202` with the work and command IDs. |
| `GET /api/runs` | List the latest 100 run projections. |
| `GET /api/runs/{id}` | Return projection and command delivery/application outcomes. |
| `GET /api/runs/{id}/events?after=N` | Return up to 200 ordered events with sequence greater than `N`; repeat with the last seen sequence. |
| `POST /api/runs/{id}/cancel` | Accept `{commandId, expectedVersion}`; return `202`. |
| `POST /api/runs/{id}/resume` | Accept `{commandId, expectedVersion, extraTurns?, extraToolCalls?, extraMinutes?, extraCostMicros?, hint?}` for a `PAUSED` run; return `202`. `409` if the run is not paused, the version is stale, or the command ID was used with different input; `400` above a per-resume or per-run ceiling, for a cost extension too small to cover the next call, or for malformed input. |
| `POST /api/runs/{id}/stop` | Accept `{commandId, expectedVersion}` for a `PAUSED` run; return `202`. The run ends with its pause reason. Same `409` rules. |
| `POST /api/work/{id}/resume`, `POST /api/work/{id}/stop` | Same bodies and responses for repository work items. |
| `GET /api/profiles` | List frozen local and remote profile settings, including `modelName`, `remote`, and `boundToDeveloper`, without credential references. |
| `POST /api/profiles/probe` | Probe `{modelTag}` against installed local Ollama inventory and save a verified profile. |
| `GET /api/runs/{id}/artifacts/{digest}` | Download a run-associated SHA-256 blob as an attachment. |

All mutations require `Content-Type: application/json` and exact fields. `400` means malformed or unsupported input, including `REMOTE_NOT_ALLOWED`, `COST_LIMIT_REQUIRED`, `INVALID_COST_BUFFER`, `ROLE_UNKNOWN`, or `ROLE_UNBOUND`; `404` means unknown run or artifact, `409` means a conflicting command identity or currently stale cancellation version, and `503` means required local authority or model probing is unavailable. A supplied command ID is global and must not be reused for a different operation. The initial API is for a trusted local operator on loopback; it requires a loopback `Host` for every API request, rejects foreign browser Origin and Fetch-Site mutation requests, and does not enable CORS. It is not a hosted multi-user authentication boundary.

The role defaults to `developer`. If `profileRevision` is omitted, configure `harness.roles.developer.profile` with a saved profile ID. A remote profile requires `allowRemote: true`; a priced profile also requires a positive `costLimitMicros`. `costBufferPercent` defaults to 10 and accepts 0–50. The accepted run stores the role content and these terms; changing the local role file or binding later does not change it.

Run and work detail include the frozen `role` and `model`, plus `cost` with `spentMicros`, `estimatedMicros`, `effectiveLimitMicros`, `limitMicros`, and `bufferPercent`. `cost` is null for a free profile without a limit. A priced run pauses with reason `COST_LIMIT` before its next model call could exceed the effective limit; resume it with enough `extraCostMicros` to cover that call. The CLI accepts dollar amounts with up to six decimal places and converts them to integer micros. The CLI and console show amounts exactly, with at least two and up to six decimals, for example `$0.002759`. The console shows the remote opt-in and cost fields for a remote model, cost in run detail, and an extra budget field for a cost pause.

The default limits are encoded in `scripts/harness.py`. Supply `--limits limits.json` to start with all ten explicit bounds. `events --follow` resumes from its last printed sequence after a transient connection failure or temporary `5xx` response. The CLI's `--url` option accepts only a loopback HTTP origin.
