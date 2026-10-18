# Role Profiles and a Remote Provider Design (M2)

Date: 2026-10-18
Status: Approved by owner 2026-10-18; amended by the implementation plan (see Plan amendments)

## Purpose

Milestone M2 of the [revised roadmap](../../plans/roadmap.md). Later milestones run several roles (architect, project manager, plan reviewer, developer, QA) on local and remote models. Today the harness has none of the pieces for that. `ProfileRevision` accepts only a loopback Ollama endpoint, the system prompt and the six-tool set are hard-coded in `RunActivitiesImpl` and `ToolRouter`, and nothing limits what a run spends.

M2 adds three things to the existing single coding loop:

1. **Roles.** A role is a committed prompt and allowed tool set, bound locally to a model profile and frozen into each run.
2. **An Anthropic adapter** behind the existing `ModelAdapter` interface, with file-based credentials that never leave the host process.
3. **Cost accounting** per run, with a cost limit that pauses the run through M1's durable pause.

**Owner decisions (2026-10-18):** the Anthropic API is the first remote provider. Acceptance is a developer role on Claude Haiku 5.5. Prices are frozen in the profile. The cost limit pauses before a call that could exceed it, and resuming raises the limit. A call with an uncertain outcome is charged at worst case and flagged as estimated. Roles are committed files, and the role-to-profile binding is local config. Sending repository content to a remote model needs an explicit opt-in on each run. Prompt caching covers the system prompt and tool definitions. Each run has a cost buffer, 10% by default. Secrets are never committed or published, and CI runs a gitleaks scan.

The cost limit is a guard against runaway spend while a run is unattended. It is not a billing system. The harness promises that a run stays below its own conservative estimate of spend; it does not reconcile against Anthropic's invoice.

## Plan amendments (2026-10-18)

Writing the [implementation plan](../plans/2026-10-18-role-profiles-and-remote-provider.md) against the code changed these decisions. Where the sections below disagree with this one, this one wins.

1. **Key location.** `application.yaml` imports `.secrets/app/` as a Spring configtree, which turns every file there into an application property. The key therefore lives in `${HARNESS_MODEL_SECRETS_DIR:./.secrets/models/}<credentialReference>`, never in `.secrets/app/`.
2. **One migration.** Profiles are already stored as JSON in `model_profile_revision.profile_json`, so they need no migration. The only migration is `V10__record_model_attempt_cost.sql`.
3. **Every uncertain attempt is charged worst case.** The workflow can't tell whether a `MODEL_ATTEMPT_UNKNOWN` attempt was sent. To keep the workflow's spend and the ledger identical, every `UNKNOWN` attempt is charged worst case, including one abandoned before dispatch. The worst-case input is the full window, `contextTokens − outputTokens`.
4. **Anthropic request shape.** Claude Haiku 5.5 rejects non-default sampling parameters, so `AnthropicProfile` has no temperature and requests send none. Requests send `thinking: {type: "disabled"}` with effort `medium`, so working memory's rewritten history never has to replay thinking blocks. A system-prompt `cache_control` breakpoint caches both the tools and the system prompt, because tools render before the system prompt.
5. **Price tier.** Haiku 5.5's prices rise fivefold above 100K prompt tokens, so `AnthropicProfile` records `priceTierInputTokens` and requires `contextTokens ≤ priceTierInputTokens`.
6. **Naming.** Model roles are `AgentRole` and `AgentRoleCatalog`, to avoid confusion with the existing process roles (`api`, `worker`, `runner`).
7. **SDK.** The adapter uses the official `com.anthropic:anthropic-java` SDK with `maxRetries(0)`. It is added with lockfile and checksum verification.
8. **CLI scope.** `scripts/harness.py` has no work-creation command, so the new flags go on `start` and `resume` only.
9. **A resume must be able to afford the next call.** A `COST_LIMIT` resume is rejected with `REJECTED_LIMIT` unless the new effective limit covers spent plus the next call's worst case.
10. **Where cost appears.** The cost block appears on the run and work detail responses, not on `RunView`. `RunView` is the Temporal query projection and stays unchanged.

## Scope

In scope:

- Sealed profile types: `OllamaProfile` (today's rules) and `AnthropicProfile`.
- `AnthropicModelAdapter` (Messages API, tool use, prompt caching on stable prefixes).
- A role catalog with one role, `developer`, used by both repository tasks and the benchmark.
- Freezing the role and remote-content opt-in into each run.
- A path deny list for every model tool.
- A cost ledger on model attempts, a cost limit with a buffer, and a `COST_LIMIT` pause reason.
- API, CLI, and console changes for role, model, cost, and the opt-in.
- Credential storage, leak tests, `.gitignore` hardening, and a gitleaks CI scan.

Out of scope: more than one role per run, switching roles within a run, roles other than `developer`, the mission workflow (M3), an OpenAI-compatible adapter, creating remote profiles in the console, reconciling against provider invoices, and GitHub Actions secrets.

## Profiles

`ProfileRevision` becomes a sealed interface with two records. Each record checks its own invariants, so neither adapter weakens the other's rules.

| Field | `OllamaProfile` | `AnthropicProfile` |
|---|---|---|
| Endpoint | loopback HTTP only (unchanged) | exactly `https://api.anthropic.com` |
| Model | installed tag plus full digest (unchanged) | model ID, e.g. `claude-haiku-5-5`; no digest exists, so the frozen identity is the ID plus the profile revision |
| Credential | none | `credentialReference` matching `[a-z0-9_]{1,64}`: a file name in `.secrets/app/`, never a path |
| Context, output, temperature | unchanged | same rules |
| Prices | all zero | micro-dollars per million tokens for input, output, cache write, cache read; each is ≥ 0 and at least one is > 0 |
| Tool round trip | must be verified (unchanged) | must be verified by a live probe |

Profile revisions are immutable. Changing a price creates a new revision, and runs that already started keep the old one.

Migration `V10__add_remote_model_profiles.sql` adds an adapter type (existing rows become `ollama`) and the price and credential-reference columns. The planning step verifies the current table name and the Flyway history before naming the columns.

`ModelReply` gains `cacheWriteTokens` and `cacheReadTokens` (null for Ollama). `ModelAdapterFactory.forProfile` picks the adapter from the profile type.

### Anthropic adapter

- Sends the rendered working-memory conversation and the role's tool definitions to the Messages API, and maps `tool_use` blocks to `ToolRequest`s and `stop_reason` to `finishReason`.
- Marks the system prompt and tool definitions with `cache_control`. The conversation itself is not cached, because working memory rewrites it every turn.
- Reads usage (`input_tokens`, `output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`) into `ModelReply`.
- Respects the `ModelCall` deadline. A timeout, or a lost connection after the request was sent, is an uncertain outcome (see [Cost](#cost)).
- Turns HTTP errors into adapter failures that carry only the status and Anthropic's error `type`.

## Roles

### Role files

Each role is a committed file at `src/main/resources/roles/<name>.yaml` and is packaged in the Boot JAR:

```yaml
name: developer
version: 1
prompt: |
  You implement one focused change: read the relevant files, edit, build and test, then submit.
tools: [list_files, read_file, write_file, run_tests, build, submit]
```

On startup, `RoleCatalog` loads every role file. The application refuses to start if a file is malformed, a name is duplicated, a name doesn't match its file name, or a tool name is unknown. M2 ships only `developer`, with all six tools.

### System prompt and tools

The system prompt is built from three parts: `role.prompt`, then the task framing, then `PROTOCOL`. The task framing is the text that differs between `repository-v1` and the benchmark. It stays in code because the host owns the benchmark prompt, and it must never carry evaluator details.

`ToolRouter` gets the role's tool set. The model sees definitions only for that set. A call to a tool outside it gets `TOOL_NOT_ALLOWED` back as a tool error, not a run failure.

### Binding and freezing

Local application config binds a role to a profile revision ID and is never committed:

```yaml
harness:
  roles:
    developer:
      profile: <profile-revision-id>
```

A start or work request names a `role` (default `developer`) and may override the profile with `profileRevision`, so today's requests stay valid. The request is refused if it has no profile override and the role has no binding.

When a run is accepted, the role's canonical content (name, version, prompt, and the sorted tool list, serialised in a fixed order) is hashed with SHA-256 and stored as a blob. `RunSpec` gains `roleName`, `roleDigest`, `allowRemote`, `costLimitMicros`, and `costBufferPercent`, so the run's `inputSha256` identity covers them. Workers read the role from the blob, never the classpath, so a deployment that edits `developer.yaml` cannot change a run already in flight. A `RunSpec` written before M2, with no role, renders today's prompt and six tools unchanged.

### Remote opt-in and deny list

If the frozen profile is an `AnthropicProfile`, the request must carry `allowRemote: true`, or it is rejected with `REMOTE_NOT_ALLOWED`. The run records the flag.

For every role and profile, `ToolRouter` hides denied paths from `list_files` and refuses them in `read_file` and `write_file` with `PATH_DENIED`. Denied paths are any path segment named `.secrets`, and file names matching `.env`, `.env.*`, `*.pem`, `*.key`, `id_rsa*`, `id_ed25519*`, or `*.p12`. The list is a constant in code, not configuration.

## Cost

### Units and ledger

Money is stored as whole micro-dollars (`long`), never floating point. The cost of an attempt is:

`ceil((input × pIn + output × pOut + cacheWrite × pCw + cacheRead × pCr) / 1_000_000)`

The prices are the frozen profile's micro-dollars per million tokens.

Migration `V11__record_model_attempt_cost.sql` adds `cost_micros`, the four token counts, and `cost_estimated` to the model-attempt rows. There is no separate ledger table.

- **Finished attempt.** When `ModelAttemptStore.complete` records a reply, it computes the cost from the reply's usage, with `cost_estimated = false`.
- **Uncertain attempt.** An attempt that is abandoned after dispatch, or found `UNKNOWN`, is charged the worst case: the run's frozen working-memory input budget plus the profile's `outputTokens`, all priced as plain input and output, with `cost_estimated = true`. An attempt abandoned before dispatch costs 0.
- **Run spend** is the sum over the run's attempts, reported as `spentMicros` and the estimated part `estimatedMicros`. Each retry is its own attempt.

### Limit and buffer

- `costLimitMicros` is required when any frozen price is greater than 0. It has no default, so a priced run cannot start without a budget. For free profiles it is optional, and absent means no limit.
- `costBufferPercent` is an integer from 0 to 50, default 10.
- The effective limit is `floor(costLimitMicros × (100 − costBufferPercent) / 100)`.

The buffer covers what the harness cannot measure exactly: prices that change after they were frozen, differences in token counting, rounding, and charges the API does not report.

### Check before each call

Before each `callModel`, the workflow pauses with reason `COST_LIMIT` when:

`spentMicros + worstCaseNextCall > effectiveLimitMicros`

`worstCaseNextCall` is the uncertain-attempt charge above. To keep the workflow deterministic, `callModel` returns the attempt's cost and the workflow keeps the running total. The workflow never reads spend from the database. The pause uses M1's `pause(...)` path and records evidence: spend, the estimated part, the effective limit, the raw limit, the buffer, and the next call's worst case.

### Resume and stop

`ResumeCommand` gains `extraCostMicros ≥ 0`. A resume from a `COST_LIMIT` pause must have `extraCostMicros > 0`, or it is rejected with `REJECTED_LIMIT`. A resume adds to `costLimitMicros`, and the effective limit is recalculated from it. The buffer is frozen and a resume cannot change it. M1's `maxResumes` caps all resumes, so cost gets no separate ceiling. If the run is stopped, or no resume can be accepted, it ends `LIMIT_EXHAUSTED` with pause reason `COST_LIMIT`. Pause history records each `+extraCostMicros`.

## API, CLI, and UI

### API

- `POST /api/profiles/anthropic` takes the model ID, `credentialReference`, context and output tokens, temperature, and the four prices. It runs the live tool round-trip probe and stores the revision only if the probe passes. It never accepts or returns key material.
- Run start and work creation gain `role`, `allowRemote`, `costLimitMicros`, and `costBufferPercent`. Rejections: `REMOTE_NOT_ALLOWED`, `COST_LIMIT_REQUIRED`, `INVALID_COST_BUFFER`, `ROLE_UNKNOWN`, `ROLE_UNBOUND`.
- `RunView` and the work detail gain `role`, the `model` on each Model stage event, and `cost: {spentMicros, estimatedMicros, effectiveLimitMicros, limitMicros, bufferPercent}`. `cost` is null for free profiles without a limit.
- Resume gains `extraCostMicros`.

### CLI (`scripts/harness.py`)

- `probe-anthropic <model-id> --credential <name> --prices in,out,cw,cr --context N --output N`. Prices are given in dollars per million tokens and converted to micros.
- `start` and work creation gain `--role`, `--allow-remote`, `--cost-limit <dollars>`, and `--cost-buffer <percent>`.
- `status` prints `role developer · claude-haiku-5-5 · $1.12 (+$0.04 est) of $4.50 / $5.00`. `events` names the role and model on each turn.
- `resume --cost <dollars>`.

### Console

- **Submit form:** a role picker (only `developer` in M2) that shows the bound profile, with an optional profile override. When a remote profile is selected, a required "Send repository content to Anthropic" checkbox appears, with cost limit (blank) and buffer (10%) fields. Submit stays disabled until all three are filled in.
- **Run header:** the cost line, as in the CLI. Free profiles show none.
- **Model stage events:** a small `role · model` label.
- **Pause panel:** for `COST_LIMIT`, the evidence line (for example, "$4.38 spent (incl. $0.12 estimated) of $4.50 effective / $5.00 limit") and an Extra budget ($) field. Resume stays disabled until that field is above 0. The turn, tool, and minute fields remain. Resume history shows `+$N`.
- Remote profiles are created through the CLI or API only. The console's Verify model stays Ollama-only.

## Credentials and secrets

The repository is public. No key, password, token, or private key may be committed, printed, logged, stored in a blob, sent to Temporal, or placed in a runner container.

### Storage

- The key lives only in `.secrets/app/<credentialReference>`, with file mode `0600` in a `0700` directory.
- `scripts/store-anthropic-key.py [--name anthropic_api_key]` reads the key from a hidden prompt (`getpass`), never from an argument or environment variable. It refuses to overwrite an existing file or follow a symlink, and never echoes the value.
- Profiles, `RunSpec`, the API, the CLI, events, and Temporal payloads carry only the reference name.

### Use

- `AnthropicModelAdapter` reads the file on each call. The key exists only as a local variable and on the outgoing `x-api-key` header.
- It never appears in `ModelCall`, request or reply blobs, model-attempt rows, telemetry, events, or exception messages.
- HTTP client request and response logging stays off for this adapter.
- Errors carry the HTTP status and Anthropic's error `type` only. They never carry headers or the raw response body.
- A missing or unreadable credential fails with `CREDENTIAL_UNAVAILABLE` and names the reference, not the contents.
- The adapter runs in the host worker process. Runner and evaluator containers get no mount and no environment variable for the key.

### Repository and CI

- `.gitignore` widens `/.env` to `.env*` and adds `*.pem`, `*.key`, and `*.p12`.
- `unit-tests.yml` gains a gitleaks scan over the full PR and push history range. The action or binary is pinned to an exact version and checksum, and is checked against trusted release evidence, following the repository's dependency-pinning rule. The step fails the workflow on any finding.
- GitHub secret scanning and push protection stay on. They were verified enabled on 2026-10-18.
- CI needs no Anthropic key. Every adapter test uses a stubbed HTTP server, and the opt-in live probe is skipped when no credential file exists.
- Evidence docs and PR bodies record run IDs, model IDs, and dollar amounts only. Before each commit and PR, check the staged files and the PR body for secrets, as `AGENTS.md` requires.

### Reaching each environment

| Environment | Needs | How it gets it |
|---|---|---|
| Owner machine | Anthropic key file | Manual, once: `python3 scripts/store-anthropic-key.py`, documented in `docs/development.md` |
| Owner machine | Role binding | Local application config, documented next to `config/local-profile.example.yaml` |
| CI | Nothing secret | Stubbed tests. Live probe skipped |
| Database | Migrations V10, V11 | Flyway on startup |

## Durability and compatibility

- Runs accepted before M2 have no role, cost, or opt-in fields. They render today's prompt and six tools, have no cost limit, and keep their frozen profile.
- Role content is frozen as a blob, so edits to role files don't affect runs in flight.
- An uncertain model attempt is never retried silently. It stays `UNKNOWN` as today and now also carries an estimated cost.
- Profile revisions stay immutable. A price or model change is a new revision.

## Testing

Following the main-feature test scope in `AGENTS.md`, existing tests are extended where possible. No crash, race, or deployment suites are added.

- **Profiles:** extend the profile tests for `AnthropicProfile` validation (endpoint, credential reference pattern, prices).
- **Adapter:** extend the adapter tests with a stubbed Messages API: tool use mapping, usage and cache tokens, error mapping, and deadline handling. One **leak test** uses a fake `sk-ant-` key and asserts it is absent from every stored blob, the reply, the attempt row, events, and every thrown exception's text.
- **Roles:** one `RoleCatalog` test for malformed and invalid files. Extend the `startConversation` test for the three-part prompt and role freezing.
- **Router:** extend the router tests for `TOOL_NOT_ALLOWED` and the deny list (hidden from listing, refused on read and write).
- **Cost:** extend the attempt-store tests for finished, uncertain, and cache-priced costs. Extend the workflow pause tests with one scripted priced run: it pauses at the effective limit (not the raw limit), rejects a resume without extra cost, and continues after a resume with extra cost.
- **API and CLI:** extend the controller tests for the new rejections. Extend `test_harness.py` for the new flags and dollar-to-micro conversion. Add a `store-anthropic-key.py` test next to `test_local_secrets.py`.
- **CI:** the gitleaks step runs on the PR that adds it.

## Acceptance

On the final head commit, after `./gradlew build` passes:

1. Store the key with `store-anthropic-key.py`, then `probe-anthropic claude-haiku-5-5` creates a verified profile, and the developer role is bound to it.
2. From the console, submit a small repository task as `developer` with the remote opt-in, a $0.10 limit, and a 10% buffer.
3. In the browser, watch the role, model, and cost update on each turn, including after a reload.
4. The run pauses at `COST_LIMIT` with spend at or below the $0.09 effective limit.
5. Resume from the console with +$0.50. The run reaches Ready for review.
6. Record the run ID, final spend, any estimated entries, and the commit in the evidence doc, with no key material.
7. The gitleaks CI step passes on the PR.

## Risks

- **Price drift.** Frozen prices can go out of date. The buffer and new revisions handle this, and the harness does not fetch prices.
- **Conservative worst case.** Charging the full input budget for each uncertain attempt or pre-call check can pause a run earlier than real spend requires. This is accepted, because the limit exists to guard unattended runs.
- **Cache accounting.** Cache writes cost more than plain input. If the stable prefix changes every turn, caching costs more than it saves. Only the system prompt and tool definitions are cached, and both are fixed for the life of a run.
- **Remote data exposure.** Opted-in runs send repository content to Anthropic. The deny list blocks known secret file shapes, but it cannot recognise secrets inside ordinary source files.
