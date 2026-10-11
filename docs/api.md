# Local operator API and CLI

Task 8 adds a loopback HTTP API and a Python 3 standard-library client. Start the services and build the application as described in [local development](development.md). In separate terminals, run the API, worker, and runner roles:

```sh
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=api
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=worker
HARNESS_RUNNER_ENABLED=true HARNESS_RUNNER_IMAGE="$(sed -n 's/^RUNNER_IMAGE=//p' runner/image.lock)" java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=runner
```

Run these from the repository root so the application reads `.secrets/app/`. The API binds `127.0.0.1:8080` by default. The API accepts a command durably before the background dispatcher delivers it to Temporal. A `202` response means accepted, not applied. Inspect `deliveryStatus` and `applicationOutcome` in a command response or `commands[]` in run detail to see delivery and cancellation results. `projection.stale` signals a gap between authoritative workflow state and contiguous semantic events.

## Operator walkthrough

The following uses the API with a selected installed local Ollama model. The workflow is a real coding run; the model may or may not pass the benchmark. No model is downloaded automatically. For a deterministic scripted run through HTTP, the outbox dispatcher, real Temporal, event paging, and artifact retrieval, run `./gradlew integrationTest --tests '*ApiWorkflowIT'`; it uses a test-only scripted model response and ends in `FAILED` without calling Ollama.

```sh
python3 scripts/harness.py probe qwen3.8:latest
python3 scripts/harness.py profiles
python3 scripts/harness.py start --profile PROFILE_ID --command-id local-attempt-1
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

## HTTP contract

| Method and path | Purpose |
|---|---|
| `POST /api/runs` | Accept `{commandId, benchmarkVersion, profileRevision, limits}`; return `202` with run and command IDs. |
| `GET /api/runs` | List the latest 100 run projections. |
| `GET /api/runs/{id}` | Return projection and command delivery/application outcomes. |
| `GET /api/runs/{id}/events?after=N` | Return up to 200 ordered events with sequence greater than `N`; repeat with the last seen sequence. |
| `POST /api/runs/{id}/cancel` | Accept `{commandId, expectedVersion}`; return `202`. |
| `GET /api/profiles` | List frozen local profile settings without credential references. |
| `POST /api/profiles/probe` | Probe `{modelTag}` against installed local Ollama inventory and save a verified profile. |
| `GET /api/runs/{id}/artifacts/{digest}` | Download a run-associated SHA-256 blob as an attachment. |

All mutations require `Content-Type: application/json` and exact fields. `400` means malformed or unsupported input, `404` means unknown run or artifact, `409` means a conflicting command identity or currently stale cancellation version, and `503` means required local authority or model probing is unavailable. A supplied command ID is global and must not be reused for a different operation. The initial API is for a trusted local operator on loopback; it requires a loopback `Host` for every API request, rejects foreign browser Origin and Fetch-Site mutation requests, and does not enable CORS. It is not a hosted multi-user authentication boundary.

The default limits are encoded in `scripts/harness.py`. Supply `--limits limits.json` to start with all ten explicit bounds. `events --follow` resumes from its last printed sequence after a transient connection failure or temporary `5xx` response. The CLI's `--url` option accepts only a loopback HTTP origin.
