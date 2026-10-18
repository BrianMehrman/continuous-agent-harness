# Role Profiles and a Remote Provider Implementation Plan (M2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the existing coding loop as a committed `developer` role on a local or an Anthropic model. Account for each run's spend, and pause the run before a model call could exceed its buffered cost limit.

**Architecture:**
- **Profiles.** `ProfileRevision` becomes a sealed interface (`OllamaProfile`, `AnthropicProfile`). Profiles stay stored as JSON in `model_profile_revision`.
- **Frozen run terms.** At acceptance, a resolver freezes a `RunTerms` record into `RunSpec`: role name, digest and blob, tool set, model name, remote opt-in, and `CostTerms`.
- **Workflow cost check.** The workflow computes each attempt's charge deterministically from the frozen `CostTerms` and the reply's usage. It pauses through M1's `pause(...)` with reason `COST_LIMIT`.
- **Ledger.** The ledger is new columns on `model_attempt`.
- **Adapter.** The Anthropic adapter uses the official Java SDK with retries off. It reads the key from a file outside the Spring configtree on every call.

**Tech Stack:** Java 21, Spring Boot, Temporal Java SDK, Jackson 3 (`tools.jackson`), PostgreSQL/Flyway, `com.anthropic:anthropic-java`, JUnit 5, Python 3 CLI (`scripts/harness.py`), React/TypeScript (`web/`), GitHub Actions.

**Spec:** [`docs/superpowers/specs/2026-10-18-role-profiles-and-remote-provider-design.md`](../specs/2026-10-18-role-profiles-and-remote-provider-design.md). Read its **Plan amendments** section first; it records where this plan departs from the original text and why.

## Global Constraints

- The repository is public. No key, password or token may be committed, printed, logged, stored in a blob, put in a Temporal payload, or placed in a runner container. Only the reference name travels.
- The Anthropic key file lives at `${HARNESS_MODEL_SECRETS_DIR:./.secrets/models/}<credentialReference>`, never under `.secrets/app/`. Spring imports every file in `.secrets/app/` as a property (`application.yaml` `configtree`).
- `credentialReference` matches `[a-z0-9_]{1,64}`.
- `AnthropicProfile.endpoint` is exactly `https://api.anthropic.com`.
- Requests to Anthropic send no `temperature`, `top_p` or `top_k` (Haiku 5.5 rejects any non-default value). They send `thinking: {type: "disabled"}` and `output_config.effort: "medium"`, so no thinking blocks need replaying and output stays within `max_tokens`.
- SDK retries are off (`maxRetries(0)`). The harness owns attempts and their receipts.
- Money is a `long` in micro-dollars. Prices are micro-dollars per million tokens.
  - Attempt charge: `ceil((in×pIn + out×pOut + cw×pCw + cr×pCr) / 1_000_000)`.
  - Worst-case charge: `ceil(((contextTokens − outputTokens)×pIn + outputTokens×pOut) / 1_000_000)`, flagged estimated.
  - Effective limit: `floor(limit × (100 − buffer) / 100)`.
- `costBufferPercent` is an integer from 0 to 50, default 10. `costLimitMicros` is required when any frozen price is above 0, and there is no default.
- Haiku 5.5 acceptance profile prices (first-party, prompts ≤ 100K tokens):
  - input `100000`, output `500000`, cache write (5-minute) `125000`, cache read `10000` micro-dollars per MTok;
  - `priceTierInputTokens` `100000`.
- Deny list for every model tool: any path segment `.secrets`; file names `.env`, `.env.*`, `*.pem`, `*.key`, `*.p12`, `id_rsa*`, `id_ed25519*`.
- New error codes (exact strings): `TOOL_NOT_ALLOWED`, `PATH_DENIED`, `REMOTE_NOT_ALLOWED`, `COST_LIMIT_REQUIRED`, `INVALID_COST_BUFFER`, `ROLE_UNKNOWN`, `ROLE_UNBOUND`, `CREDENTIAL_UNAVAILABLE`.
- A `RunSpec` or stored profile written before M2 must deserialize, pass its integrity check, and run exactly as before.
- Tests: extend existing test classes. A new test class only where noted. No crash, race or deployment suites (`AGENTS.md`). Report the net new test count in the PR.
- Dependency changes update `gradle.lockfile` and `gradle/verification-metadata.xml`. Check the new coordinates and hashes against Maven Central first.
- Migration: `V10__record_model_attempt_cost.sql`. Verify the name against `src/main/resources/db/migration/` and Flyway history before committing.

## Review Focus

1. **Stored profiles from before M2.** A `model_profile_revision` row written by today's code must still read back and pass `JdbcProfileRevisionStore`'s sha256 re-serialization check. If `OllamaProfile`'s JSON changes by a single field or its order, every existing profile fails. *Test: Task 1, `storedOllamaJsonRoundTripsByteForByte`.*
2. **Key exposure through Spring.** A key file placed in `.secrets/app/` becomes a Spring property, and the actuator or an error message could reveal it. *Test: Task 3, `defaultModelSecretsDirectoryIsOutsideConfigtree`.*
3. **Output-capped or usage-less Anthropic replies.** `stop_reason: "max_tokens"` must map to `finishReason` `"length"`, which `WorkingMemory` already treats as output-capped. A priced reply without usage must be charged worst case, flagged estimated. *Tests: Task 3, `maxTokensStopMapsToLength`; Task 7, `pricedReplyWithoutUsageIsChargedWorstCase`.*
4. **A resume that cannot afford the next call.** A `COST_LIMIT` resume with `extraCostMicros > 0` whose new effective limit still can't cover `spent + worstCase` would re-pause straight away and use up a resume. It must be rejected with `REJECTED_LIMIT`, both at the API and in the workflow. *Test: Task 7, inside `costLimitPausesAtEffectiveLimitThenResumesWithExtraBudget`.*
5. **Unbound role.** A start or work request with no `profileRevision` and no local binding for its role must be refused with `ROLE_UNBOUND` before anything is written. *Test: Task 5, `unboundRoleWithoutProfileIsRejected`.*

---

### Task 1: Sealed profile types

**Files:**
- Modify: `src/main/java/com/brianmehrman/harness/model/ProfileRevision.java` (becomes a sealed interface)
- Create: `src/main/java/com/brianmehrman/harness/model/OllamaProfile.java`, `src/main/java/com/brianmehrman/harness/model/AnthropicProfile.java`, `src/main/java/com/brianmehrman/harness/model/Prices.java`
- Modify callers: `LocalProfileProbe.java`, `OllamaModelAdapter.java`, `config/ModelConfiguration.java`, `api/ProfileController.java`, `runs/RunCommandService.java`, `runs/RepositoryWorkService.java`, `execution/RunActivitiesImpl.java`
- Test: `src/test/java/com/brianmehrman/harness/model/ProfileProbeTest.java` (extend)

**Interfaces:**
- Produces:
  - `sealed interface ProfileRevision permits OllamaProfile, AnthropicProfile`, with `String id()`, `String adapter()`, `String modelName()`, `int contextTokens()`, `int outputTokens()`, `Prices prices()`, `boolean remote()`. Annotated `@JsonTypeInfo(use = NAME, include = EXISTING_PROPERTY, property = "adapter", visible = true)` and `@JsonSubTypes({ollama → OllamaProfile, anthropic → AnthropicProfile})`.
  - `record OllamaProfile(String id, String adapter, String endpoint, String modelTag, String fullDigest, int contextTokens, int outputTokens, double temperature, boolean toolRoundTripVerified, String credentialReference)`. These are today's components in today's order with today's validation. `modelName()` returns `modelTag`, `prices()` returns `Prices.FREE`, `remote()` returns false.
  - `record AnthropicProfile(String id, String adapter, String endpoint, String modelId, String credentialReference, int contextTokens, int outputTokens, int priceTierInputTokens, Prices prices, boolean toolRoundTripVerified)`. `modelName()` returns `modelId`, `remote()` returns true.
  - `record Prices(long inputMicrosPerMTok, long outputMicrosPerMTok, long cacheWriteMicrosPerMTok, long cacheReadMicrosPerMTok)`, with `static final Prices FREE` (all 0) and `boolean free()`.

- [ ] **Step 1: Write the failing tests in `ProfileProbeTest`**

```java
@Test void storedOllamaJsonRoundTripsByteForByte() {
    String stored = "{\"id\":\"ollama-x\",\"adapter\":\"ollama\",\"endpoint\":\"http://127.0.0.1:11434\"," +
        "\"modelTag\":\"qwen3.5:9b\",\"fullDigest\":\"" + "a".repeat(64) + "\",\"contextTokens\":16384," +
        "\"outputTokens\":4096,\"temperature\":0.0,\"toolRoundTripVerified\":true,\"credentialReference\":null}";
    ProfileRevision read = JSON.readValue(stored, ProfileRevision.class);
    assertInstanceOf(OllamaProfile.class, read);
    assertEquals(stored, JSON.writeValueAsString(read));
}

@Test void anthropicProfileValidatesEndpointCredentialPricesAndTier() {
    var ok = anthropic("https://api.anthropic.com", "anthropic_api_key", 16384, 100_000, HAIKU);
    assertEquals("claude-haiku-5-5", ok.modelName());
    assertTrue(ok.remote());
    assertThrows(IllegalArgumentException.class, () -> anthropic("http://api.anthropic.com", "anthropic_api_key", 16384, 100_000, HAIKU));
    assertThrows(IllegalArgumentException.class, () -> anthropic("https://api.anthropic.com", "../app/key", 16384, 100_000, HAIKU));
    assertThrows(IllegalArgumentException.class, () -> anthropic("https://api.anthropic.com", "anthropic_api_key", 16384, 100_000, Prices.FREE));
    assertThrows(IllegalArgumentException.class, () -> anthropic("https://api.anthropic.com", "anthropic_api_key", 200_000, 100_000, HAIKU));
    assertEquals(ok, JSON.readValue(JSON.writeValueAsString(ok), ProfileRevision.class));
}
```

`HAIKU` is `new Prices(100_000, 500_000, 125_000, 10_000)`. `anthropic(...)` builds an `AnthropicProfile` with `id = "anthropic-test"`, `adapter = "anthropic"`, `modelId = "claude-haiku-5-5"`, `outputTokens = 4096`, and `toolRoundTripVerified = true`.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.model.ProfileProbeTest'`
Expected: compilation failure (`OllamaProfile`, `AnthropicProfile`, `Prices` undefined).

- [ ] **Step 3: Implement the sealed interface and records**

`AnthropicProfile` rejects each of the following with `IllegalArgumentException`:
- a blank id, an `adapter` other than `"anthropic"`, or an endpoint other than exactly `https://api.anthropic.com`;
- a blank `modelId`, or a `credentialReference` that doesn't match `[a-z0-9_]{1,64}`;
- `outputTokens ≤ 0` or `outputTokens ≥ contextTokens`, or `contextTokens > priceTierInputTokens`;
- any negative price, or `prices.free()`;
- `toolRoundTripVerified == false`.

Then switch every caller that used Ollama-only accessors (`endpoint()`, `modelTag()`, `fullDigest()`, `temperature()`) to `OllamaProfile`. Use a pattern-matching `switch`, or keep the parameter typed as `OllamaProfile` where the code is Ollama-only. `RunSpec.modelDigest` gets `fullDigest` for Ollama and `modelId` for Anthropic. `ProfileView` gains `String modelName` and `boolean remote`, and keeps its existing fields; they are null for Anthropic.

- [ ] **Step 4: Run them and confirm they pass**

Run: `./gradlew test --tests 'com.brianmehrman.harness.model.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/brianmehrman/harness src/test/java/com/brianmehrman/harness/model/ProfileProbeTest.java
git commit -m "feat: split model profiles into Ollama and Anthropic types"
```

### Task 2: Shared tool catalog, per-call tool sets, cache usage

**Files:**
- Create: `src/main/java/com/brianmehrman/harness/model/ToolCatalog.java`
- Modify: `model/ModelCall.java`, `model/ModelReply.java`, `model/OllamaModelAdapter.java`, `model/ScriptedModelAdapter.java` (constructor callers only), `execution/CodingWorkflowImpl.java` (pass tools), `execution/RunActivitiesImpl.java`
- Test: `src/test/java/com/brianmehrman/harness/model/OllamaAdapterTest.java` (extend)

**Interfaces:**
- Produces:
  - `final class ToolCatalog` with `record Definition(String name, String description, String inputSchemaJson)`, `static final List<String> ALL` (the six names, in today's order), and `static List<Definition> select(List<String> names)`. `null` means all six; an unknown name throws `IllegalArgumentException`.
  - `ModelCall(String runId, String invocationId, int attempt, String profileRevision, String conversationBlobId, long deadlineEpochMillis, List<String> tools)`. Keep a 6-argument constructor that passes `null`.
  - `ModelReply(String text, List<ToolRequest> tools, Long inputTokens, Long outputTokens, String finishReason, Long cacheWriteTokens, Long cacheReadTokens)`. Keep a 5-argument constructor that passes `null, null`.

- [ ] **Step 1: Write the failing test in `OllamaAdapterTest`**

`requestCarriesOnlyTheCallsToolSet`: call the adapter against the existing stubbed Ollama server with `tools = List.of("list_files", "read_file")`. Assert the captured request's `tools[*].function.name` equals exactly `["list_files", "read_file"]`. With `tools = null`, assert all six in `ToolCatalog.ALL` order.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew test --tests 'com.brianmehrman.harness.model.OllamaAdapterTest'`
Expected: compilation failure (7-argument `ModelCall`).

- [ ] **Step 3: Implement**

Move the six descriptions and schemas out of `OllamaModelAdapter.TOOLS` into `ToolCatalog`, without changing a byte. `OllamaModelAdapter` builds its `ToolCallback`s from `ToolCatalog.select(request.tools())`. `CodingWorkflowImpl` passes `input.terms() == null ? null : input.terms().tools()`; until Task 5 adds `terms`, pass `null`.

- [ ] **Step 4: Run it and confirm it passes**

Run: `./gradlew test --tests 'com.brianmehrman.harness.model.*' --tests 'com.brianmehrman.harness.execution.CodingWorkflowTest'`
Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "refactor: share tool definitions and pass a tool set per model call"`

### Task 3: Anthropic adapter with file credentials

**Files:**
- Modify: `build.gradle` (add `implementation 'com.anthropic:anthropic-java:<version>'`), `gradle.lockfile`, `gradle/verification-metadata.xml`
- Create: `src/main/java/com/brianmehrman/harness/model/CredentialFiles.java`, `src/main/java/com/brianmehrman/harness/model/AnthropicModelAdapter.java`
- Modify: `config/ModelConfiguration.java` (the factory picks the adapter by profile type), `src/main/resources/application.yaml` (`harness.model.secrets-directory: ${HARNESS_MODEL_SECRETS_DIR:./.secrets/models/}`)
- Test: create `src/test/java/com/brianmehrman/harness/model/AnthropicAdapterTest.java` (new class; no existing test covers this adapter). Extend `src/test/java/com/brianmehrman/harness/config/ModelConfigurationTest.java`.

**Interfaces:**
- Consumes: `AnthropicProfile` (Task 1), `ToolCatalog`, the 7-argument `ModelCall`, and the 7-argument `ModelReply` (Task 2).
- Produces:
  - `final class CredentialFiles` with `CredentialFiles(Path directory)` and `String read(String reference)`. It rejects a reference outside `[a-z0-9_]{1,64}`, a symlinked directory or file, and a file readable by group or others. A missing or unreadable file throws `IllegalStateException("CREDENTIAL_UNAVAILABLE: " + reference)`; the message never includes contents.
  - `final class AnthropicModelAdapter implements ModelAdapter`, with `AnthropicModelAdapter(BlobStore blobs, AnthropicProfile profile, CredentialFiles credentials)` and a package-private `AnthropicModelAdapter(BlobStore, AnthropicProfile, CredentialFiles, String baseUrl)` for tests.
  - The factory: `ModelAdapterFactory` bean `profile -> switch (profile) { case OllamaProfile o -> new OllamaModelAdapter(blobs, o); case AnthropicProfile a -> new AnthropicModelAdapter(blobs, a, credentials); }`.

- [ ] **Step 1: Add the dependency with verified hashes**

Find the newest `com.anthropic:anthropic-java` release on Maven Central that is at least two weeks old (the skill reference names `2.34.0`). Check the `.pom` and `.jar` sha256 values against Central's published checksums. Then run `./gradlew dependencies --write-locks` and `./gradlew --write-verification-metadata sha256 help`, and review the diff: only the SDK and its transitive artifacts may be added.

Run: `./gradlew dependencies --configuration runtimeClasspath | grep anthropic`
Expected: the chosen version resolves; the build passes verification.

- [ ] **Step 2: Write the failing tests in `AnthropicAdapterTest`**

Use a JDK `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0` as the stub Messages API. Write a fake key `"sk-ant-api03-TESTONLY" + "x".repeat(40)` to a `0600` file in a `@TempDir` directory with `0700` permissions.

- `toolUseReplyMapsToToolRequestsAndUsage`: the stub returns `content` with one `text` and two `tool_use` blocks, `stop_reason: "tool_use"`, and usage `input_tokens 1200`, `output_tokens 80`, `cache_creation_input_tokens 600`, `cache_read_input_tokens 0`. Assert two `ToolRequest`s with the stub's ids, names and argument JSON, and `ModelReply` tokens `1200 / 80 / 600 / 0`.
- `requestShapeHasCachedSystemAndNoSampling`: capture the request body. Assert:
  - the `x-api-key` header equals the file contents;
  - `model` is `"claude-haiku-5-5"` and `max_tokens` is `4096`;
  - `thinking.type` is `"disabled"` and `output_config.effort` is `"medium"`;
  - `temperature`, `top_p` and `top_k` are absent;
  - the system block carries `cache_control.type` `"ephemeral"`;
  - the `tools` names match the call's tool set.
- `maxTokensStopMapsToLength`: `stop_reason: "max_tokens"` gives `finishReason` `"length"`.
- `httpErrorCarriesOnlyStatusAndType`: the stub returns 429 with body `{"type":"error","error":{"type":"rate_limit_error","message":"echo sk-ant-api03-TESTONLY..."}}`. Assert the thrown message is exactly `MODEL_HTTP_429 rate_limit_error`.
- `keyNeverLeavesTheAdapter`: run the three cases above, collecting every `BlobStore.put` payload, every returned `ModelReply` serialized with Jackson, and every exception's `toString()` and cause chain. Assert none contains the substring `sk-ant-`.
- `missingCredentialNamesReferenceOnly`: delete the file, call, and assert the message is `CREDENTIAL_UNAVAILABLE: anthropic_api_key`.

In `ModelConfigurationTest`, add `defaultModelSecretsDirectoryIsOutsideConfigtree`: the resolved `harness.model.secrets-directory` normalizes to `.secrets/models`, and doesn't start with the `spring.config.import` configtree path `.secrets/app`.

- [ ] **Step 3: Run them and confirm they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.model.AnthropicAdapterTest' --tests 'com.brianmehrman.harness.config.ModelConfigurationTest'`
Expected: compilation failure.

- [ ] **Step 4: Implement `AnthropicModelAdapter.call(ModelCall)`**

1. Validate the profile ID and deadline the same way `OllamaModelAdapter` does.
2. Read the conversation blob and convert its `system`/`user`/`assistant`/`tool` messages to Messages API params. Use the same pending-tool-call checks as `OllamaModelAdapter.messages(...)`. Tool results go back as `tool_result` blocks in one user message per turn.
3. Read the key with `credentials.read(profile.credentialReference())` and build an `AnthropicOkHttpClient` with that key, `baseUrl`, `maxRetries(0)`, and a `timeout` equal to the time left before the deadline. Build it inside the method and keep no field or log line holding the key.
4. Map the outcome:
   - `stop_reason`: `tool_use` → `"tool_calls"`, `end_turn` → `"stop"`, `max_tokens` → `"length"`, anything else → its own name;
   - a timeout → `IllegalStateException("MODEL_DEADLINE_EXCEEDED")`;
   - a transport failure → `"MODEL_TRANSPORT"`;
   - an HTTP status error → `"MODEL_HTTP_" + status + " " + errorType`, with no cause attached. The SDK exception's message can contain the response body, so don't chain it.

- [ ] **Step 5: Run them and confirm they pass**

Run: the command from Step 3.
Expected: PASS.

- [ ] **Step 6: Commit** — `git commit -m "feat: add Anthropic model adapter with file-based credentials"`

### Task 4: Create Anthropic profiles and store the key

**Files:**
- Create: `src/main/java/com/brianmehrman/harness/model/AnthropicProfileProbe.java`, `scripts/store-anthropic-key.py`, `scripts/test_store_anthropic_key.py`
- Modify: `config/ModelConfiguration.java` (`selectAnthropicProfile`), `api/ProfileController.java` (`POST /api/profiles/anthropic`), `scripts/harness.py` (`probe-anthropic`), `scripts/test_harness.py`, `.gitignore`, `docs/development.md`, `config/local-profile.example.yaml`
- Test: extend `src/test/java/com/brianmehrman/harness/model/AnthropicAdapterTest.java`

**Interfaces:**
- Consumes: `AnthropicModelAdapter` and `CredentialFiles` (Task 3).
- Produces:
  - `AnthropicProfileProbe.probe(String modelId, String credentialReference, int contextTokens, int outputTokens, int priceTierInputTokens, Prices prices) -> AnthropicProfile`.
  - The probe runs the same read-only `list_files` round trip as `LocalProfileProbe`, through `AnthropicModelAdapter` against a throwaway in-memory conversation; it never writes a run blob.
  - Profile ID: `"anthropic-" + sha256(endpoint\nmodelId\ncredentialReference\ncontext\noutput\ntier\npIn\npOut\npCw\npCr)`.
  - API request body keys: `modelId`, `credentialReference`, `contextTokens`, `outputTokens`, `priceTierInputTokens`, `prices{inputMicrosPerMTok, outputMicrosPerMTok, cacheWriteMicrosPerMTok, cacheReadMicrosPerMTok}`. A failure returns 400 with the error code, or 503 if the API can't be reached.
  - CLI: `harness.py probe-anthropic MODEL_ID --credential NAME --prices IN,OUT,CW,CR --context N --output N --price-tier N`. Prices are dollars per MTok, converted with `dollars_to_micros(text: str) -> int` (exact decimal; refuses more than 6 decimal places or a negative value).

- [ ] **Step 1: Write the failing tests**
  - `AnthropicAdapterTest.probeRoundTripCreatesVerifiedProfile`: the stub answers the `tool_use` and then the marker. Assert the returned profile is `toolRoundTripVerified` and its ID is stable across two calls.
  - `test_harness.py`: `test_dollars_to_micros` (`"0.10"` → `100000`, `"0.0000001"` → `ValueError`, `"-1"` → `ValueError`), and `test_probe_anthropic_payload` (it builds the exact body above from the flags).
  - `scripts/test_store_anthropic_key.py`, modelled on `test_local_secrets.py`:
    - with `getpass` patched, the file is created with mode `0600` in a `0700` directory;
    - a second run refuses and leaves the file unchanged;
    - a symlinked directory is refused;
    - stdout and stderr never contain the key.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.model.AnthropicAdapterTest' && python3 -m unittest scripts/test_harness.py scripts/test_store_anthropic_key.py`
Expected: FAIL.

- [ ] **Step 3: Implement**
  - The probe, the controller route, and the CLI subcommand.
  - `store-anthropic-key.py [--name anthropic_api_key] [--directory .secrets/models]`: `os.umask(0o077)`; refuse an existing path, a symlink, or an empty input; read with `getpass.getpass("Anthropic API key: ")`; write with `open(path, "x")`, then `chmod 0600`; print only `Stored key reference <name>`.
  - `.gitignore`: replace `/.env` with `.env*`, and add `*.pem`, `*.key`, `*.p12`.
  - `docs/development.md`: a "Remote model (Anthropic)" section covering storing the key, `probe-anthropic` with the Haiku 5.5 prices from Global Constraints, and binding the role (Task 5).

- [ ] **Step 4: Run them and confirm they pass**

Run: the command from Step 2.
Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: create Anthropic profiles and store keys outside the repository"`

### Task 5: Roles, run terms and the remote opt-in

**Files:**
- Create: `src/main/resources/roles/developer.yaml`, `src/main/java/com/brianmehrman/harness/execution/AgentRole.java`, `src/main/java/com/brianmehrman/harness/execution/AgentRoleCatalog.java`, `src/main/java/com/brianmehrman/harness/execution/RunTerms.java`, `src/main/java/com/brianmehrman/harness/execution/CostTerms.java` (fields only here; the arithmetic comes in Task 7), `src/main/java/com/brianmehrman/harness/runs/RunTermsResolver.java`
- Modify: `execution/RunSpec.java`, `runs/StartCommand.java`, `runs/RunCommandService.java`, `runs/RepositoryWorkService.java` (`CreateWorkRequest`), `execution/RunActivitiesImpl.java` (`startConversation`), `execution/CodingWorkflowImpl.java` (pass `terms().tools()`), `api/RunController.java`, `api/WorkController.java`, `config/local-profile.example.yaml`
- Test: create `src/test/java/com/brianmehrman/harness/execution/AgentRoleCatalogTest.java` (new; the catalog is new). Extend `CodingWorkflowTest` (prompt assembly) and `src/test/java/com/brianmehrman/harness/api/ApiSecurityTest.java` or the nearest unit test of request parsing for the rejections.

**Interfaces:**
- Consumes: `ProfileRevision.remote()` and `prices()` (Task 1).
- Produces:
  - `record AgentRole(String name, int version, String prompt, List<String> tools)`, with `String canonicalJson()`: keys in the order `name, version, prompt, tools`, tools sorted.
  - `AgentRoleCatalog`, a Spring `@Component`, has `AgentRole get(String name)` (throws `IllegalArgumentException("ROLE_UNKNOWN")`). It also has a static `AgentRoleCatalog load(ResourceLoader)` that reads `classpath:roles/*.yaml` and throws at startup on a malformed file, a duplicate name, a name that doesn't match the file name, or a tool missing from `ToolCatalog.ALL`.
  - `record RunTerms(String roleName, String roleDigest, String roleBlobId, List<String> tools, String modelName, boolean allowRemote, CostTerms cost)`.
  - `record CostTerms(Prices prices, long worstCaseInputTokens, long worstCaseOutputTokens, Long limitMicros, int bufferPercent)`.
  - `RunSpec` gains a trailing component, `RunTerms terms`, which is null for runs from before M2. The existing constructors pass `null`.
  - `record RunOptions(String role, String profileRevision, boolean allowRemote, Long costLimitMicros, Integer costBufferPercent)`.
  - `RunTermsResolver.resolve(RunOptions options) -> Resolved(ProfileRevision profile, RunTerms terms)`:
    - role defaults to `"developer"`; the binding comes from `harness.roles.<role>.profile`;
    - no profile and no binding throws `ROLE_UNBOUND`;
    - a remote profile without `allowRemote` throws `REMOTE_NOT_ALLOWED`;
    - a priced profile without a limit throws `COST_LIMIT_REQUIRED`;
    - a buffer outside 0–50 throws `INVALID_COST_BUFFER`;
    - it stores the role's `canonicalJson()` as blob type `application/vnd.harness.role+json`, with `roleDigest` set to the blob's sha256.
  - `StartCommand(String commandId, String benchmarkVersion, String profileRevision, Limits limits, String role, Boolean allowRemote, Long costLimitMicros, Integer costBufferPercent)`, with `profileRevision` now optional. `CreateWorkRequest` gains the same four fields.
  - Request JSON keys (optional on both `/api/runs` and `/api/work`): `role`, `allowRemote`, `costLimitMicros`, `costBufferPercent`.

- [ ] **Step 1: Write the failing tests**
  - `AgentRoleCatalogTest`:
    - `loadsDeveloperWithAllSixTools`;
    - `rejectsUnknownToolDuplicateAndMismatchedName`, using three temp YAML files through a `DefaultResourceLoader` on a temp directory;
    - `canonicalJsonIsStable`: the same role gives the same digest, and reordering the tools doesn't change it.
  - `CodingWorkflowTest`, extending the fake activities:
    - `systemPromptIsRolePromptThenTaskFramingThenProtocol`: call `RunActivitiesImpl.startConversation` on a spec with `terms`, using the test's blob store holding the role. Assert the stored conversation's `system` starts with the role prompt, contains the repository framing, and ends with `PROTOCOL`.
    - `preM2SpecRendersTodaysPrompt`: a spec with `terms == null` gives exactly today's system text.
  - Resolver rejections, as a unit test of `RunTermsResolver` with a fake `ProfileRevisionStore` and `Environment` (a new `RunTermsResolverTest`, or folded into `AgentRoleCatalogTest` if that avoids a new class):
    - `unboundRoleWithoutProfileIsRejected` → `ROLE_UNBOUND`;
    - a remote profile without the opt-in → `REMOTE_NOT_ALLOWED`;
    - a priced profile without a limit → `COST_LIMIT_REQUIRED`;
    - a buffer of 60 → `INVALID_COST_BUFFER`;
    - a free profile without a limit → accepted, with `cost.limitMicros() == null`.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.*' --tests 'com.brianmehrman.harness.runs.*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`developer.yaml` prompt: "You implement one focused change: read the relevant files, edit, build and test, then submit." Tools: all six.

`startConversation` builds `system` as `role.prompt() + "\n\n" + framing + PROTOCOL` when `terms != null`. The role prompt is read from `blobs.get(terms.roleBlobId())`, never the classpath. `framing` is the existing repository or benchmark text. Both `RunCommandService.start` and `RepositoryWorkService.create` call the resolver before their transaction writes.

`CostTerms` worst-case tokens: `contextTokens − outputTokens` input and `outputTokens` output, from the frozen profile.

- [ ] **Step 4: Run them and confirm they pass**

Run: the command from Step 2, then `./gradlew test`.
Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: freeze a committed role, model and remote opt-in into each run"`

### Task 6: Role tool set and path deny list in the router

**Files:**
- Create: `src/main/java/com/brianmehrman/harness/execution/ModelPathPolicy.java`
- Modify: `execution/ToolInvocation.java`, `execution/ToolRouter.java`, `execution/CodingWorkflowImpl.java`
- Test: `src/test/java/com/brianmehrman/harness/execution/ToolRouterTest.java` (extend)

**Interfaces:**
- Consumes: `RunTerms.tools()` (Task 5).
- Produces:
  - `ToolInvocation` gains a trailing `List<String> allowedTools`. `null` means all six; the existing constructor passes `null`.
  - `final class ModelPathPolicy` with `static boolean denied(String path)`, applying the deny list from Global Constraints.

- [ ] **Step 1: Write the failing tests in `ToolRouterTest`**
  - `toolOutsideRoleSetIsRefused`: `allowedTools = ["list_files", "read_file"]` and a `write_file` request. Assert the outcome JSON has `code` `"TOOL_NOT_ALLOWED"`, the snapshot is unchanged, and `failureCode` is null (not a run failure).
  - `deniedPathsAreHiddenAndRefused`: a snapshot with `src/A.java`, `.env`, `config/.env.local`, `deploy/server.pem`, `.secrets/x` and `keys/id_ed25519`. Assert:
    - `list_files` returns only `src/A.java`;
    - `read_file` and `write_file` on each denied path return `code` `"PATH_DENIED"`;
    - `ModelPathPolicy.denied("src/environment.java")` is false.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.ToolRouterTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

In `route`, after the `UNKNOWN_TOOL` check: `allowedTools != null && !allowedTools.contains(name)` → `error(call, "TOOL_NOT_ALLOWED")`. `list` filters with `ModelPathPolicy.denied`; `read` and `write` check it before touching the workspace. The workflow passes `input.terms() == null ? null : input.terms().tools()`.

- [ ] **Step 4: Run them and confirm they pass**

Run: the command from Step 2.
Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: enforce role tool sets and a secret-path deny list for model tools"`

### Task 7: Cost ledger, buffered limit and the COST_LIMIT pause

**Files:**
- Create: `src/main/resources/db/migration/V10__record_model_attempt_cost.sql`, `src/main/java/com/brianmehrman/harness/runs/RunCost.java`
- Modify: `execution/CostTerms.java` (arithmetic), `execution/ModelAttemptStore.java`, `execution/RunActivitiesImpl.java` (`callModel`), `execution/CodingWorkflowImpl.java`, `execution/ResumeCommand.java`, `runs/RunCommandService.java` (resume validation), `runs/RunQueryService.java` and `runs/RepositoryWorkService.java` (detail adds `cost`), `api/RunController.java` (`extraCostMicros` in `RESUME_OPTIONAL`)
- Test: create `src/test/java/com/brianmehrman/harness/execution/CostTermsTest.java` (new; it covers pure arithmetic and a priced reply without usage). Extend `CodingWorkflowTest`.

**Interfaces:**
- Consumes: `CostTerms`, `RunTerms` (Task 5), `ModelReply` cache tokens (Task 2).
- Produces:
  - `record Charge(long micros, boolean estimated)`, nested in `CostTerms`.
  - `CostTerms.charge(ModelReply reply) -> Charge`: exact cost when `inputTokens` and `outputTokens` are present; otherwise `worstCase()`. Free prices always give `Charge(0, false)`.
  - `CostTerms.worstCase() -> Charge`: estimated, using the Global Constraints formula.
  - `CostTerms.effectiveLimit(long limitMicros) -> long`.
  - `ModelAttemptStore`:
    - `complete(AttemptLease, String responseBlobId, Charge charge)` records the charge;
    - `abandon(AttemptLease, Charge worstCase)` records the worst case;
    - `begin(..., Charge worstCase)` records the worst case when it converts an `IN_FLIGHT` row to `UNKNOWN`.
  - `ResumeCommand` gains a trailing `long extraCostMicros ≥ 0`. The 6-argument constructor passes 0.
  - `RunCost.read(JdbcTemplate jdbc, JsonMapper json, String runId) -> Map<String, Object>` returns `{"cost": {spentMicros, estimatedMicros, effectiveLimitMicros, limitMicros, bufferPercent}}`, or `{"cost": null}` when the run has no limit and free prices. `limitMicros` is the frozen limit plus the `extraCostMicros` of every applied resume.
  - The `PAUSED` event detail gains a `cost` map, with the same keys plus `nextCallWorstCaseMicros`.
  - Pause reason string: `"COST_LIMIT"`.

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE model_attempt
    ADD COLUMN cost_micros BIGINT CHECK (cost_micros >= 0),
    ADD COLUMN cost_estimated BOOLEAN,
    ADD COLUMN input_tokens BIGINT,
    ADD COLUMN output_tokens BIGINT,
    ADD COLUMN cache_write_tokens BIGINT,
    ADD COLUMN cache_read_tokens BIGINT;
```

Rows from before M2 keep nulls, and `RunCost` treats a null as 0.

- [ ] **Step 2: Write the failing tests**

`CostTermsTest`, using `HAIKU` prices and `16384 / 4096` tokens:
- `exactChargeRoundsUp`: in `1201`, out `80`, cache write `600`, cache read `0` → `ceil((1201×100000 + 80×500000 + 600×125000) / 1e6)` = `ceil(235.1)` = `236`, not estimated.
- `pricedReplyWithoutUsageIsChargedWorstCase`: null tokens → `ceil((12288×100000 + 4096×500000) / 1e6)` = `3277`, estimated.
- `freeProfileChargesNothing`.
- `effectiveLimitAppliesBuffer`: limit `100000`, buffer 10 → `90000`; buffer 0 → `100000`.

`CodingWorkflowTest.costLimitPausesAtEffectiveLimitThenResumesWithExtraBudget`:
- `CostTerms` prices are `$1` input and `$5` output per MTok (`1000000` / `5000000` micros). The worst case is `20000` input and `2000` output tokens, so `worstCase()` = `30000`. Each scripted reply reports usage of `20000` input and `2000` output tokens, so it also charges `30000`. The limit is `100000` with buffer 10, so the effective limit is `90000`.
- Calls 1–3 run (`0`, `30000`, `60000` + `30000` ≤ `90000`), leaving spent at `90000`. Before call 4, `90000 + 30000 > 90000`, so the run pauses with reason `COST_LIMIT`, and the `PAUSED` detail's `cost.effectiveLimitMicros == 90000`.
- A resume with `extraCostMicros = 0` → `REJECTED_LIMIT`.
- A resume with `extraCostMicros = 10000` (new effective `99000 < 120000`) → `REJECTED_LIMIT` (Review Focus 4).
- A resume with `extraCostMicros = 50000` (new effective `135000` ≥ `120000`) → `APPLIED`, and the next scripted reply submits successfully.
- `RESUMED` detail includes `extraCostMicros`.

- [ ] **Step 3: Run them and confirm they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.CostTermsTest' --tests 'com.brianmehrman.harness.execution.CodingWorkflowTest'`
Expected: FAIL.

- [ ] **Step 4: Implement**

**Activity.** `RunActivitiesImpl.callModel` loads `RunSpec.terms().cost()` from `run_request.spec_json` for the run, and treats a null as free. It passes `charge(reply)` to `complete`, and `worstCase()` to `abandon` and `begin`.

**Workflow state.** `CodingWorkflowImpl` keeps `long spentMicros`, `long estimatedMicros` and `Long limitMicros` (initialised from terms).

**Workflow check.** Before every model dispatch, including a retry within a turn: if `limitMicros != null` and `spentMicros + worstCase > effectiveLimit(limitMicros)`, call `pause("COST_LIMIT", null, conversation, turn, toolCount)`. Return its terminal if it has one; otherwise continue with the resumed conversation and the same attempt.

**Charges.** After a reply, add `charge(reply)`. After a `MODEL_ATTEMPT_UNKNOWN` failure, add `worstCase()` and count it toward `estimatedMicros`.

**Workflow resume rule.** `resume(...)` rejects with `REJECTED_LIMIT` when the pause reason is `COST_LIMIT` and `extraCostMicros ≤ 0`, or when `spentMicros + worstCase > effectiveLimit(limitMicros + extraCostMicros)`. When applied: `limitMicros += extraCostMicros`.

**API resume rule.** `RunCommandService.resume` applies the same two checks. It reads spend from `RunCost.read` and throws `IllegalArgumentException("Resume must raise the cost limit above the next call's worst case")`.

**Detail views.** `RunQueryService.detail` and `RepositoryWorkService.detail` call `result.putAll(RunCost.read(...))`.

- [ ] **Step 5: Run them and confirm they pass**

Run: the command from Step 3, then `./gradlew test`.
Expected: PASS.

- [ ] **Step 6: Verify the migration name and history**

Run: `ls src/main/resources/db/migration/ | tail -2`, then `./gradlew integrationTest --tests 'com.brianmehrman.harness.api.WorkApiIT'`, with the services from `docs/development.md` running.
Expected: `V9__…` and `V10__record_model_attempt_cost.sql` are listed; Flyway applies V10; the IT passes.

- [ ] **Step 7: Commit** — `git commit -m "feat: account run cost and pause before a call could exceed the buffered limit"`

### Task 8: Operator surfaces: CLI and console

**Files:**
- Modify: `scripts/harness.py`, `scripts/test_harness.py`, `web/src/api.ts`, `web/src/App.tsx`, `execution/CodingWorkflowImpl.java` (Model stage summary), `docs/api.md`
- Test: extend `scripts/test_harness.py`. The console is checked live in Task 10.

**Interfaces:**
- Consumes: the request keys from Task 5, `cost` from Task 7, and `ProfileView.modelName` and `remote` from Task 1.
- Produces:
  - CLI `start` gains `--role`, `--allow-remote`, `--cost-limit DOLLARS` and `--cost-buffer PERCENT`. `resume` gains `--cost DOLLARS`.
  - `status` prints the line `role <role> · <model> · $<spent> (+$<estimated> est) of $<effective> / $<limit>`, via `format_cost(cost: dict | None, role: str, model: str) -> str | None`.
  - The workflow's repository `MODEL_RECORDED` summary becomes `"Model response recorded · " + roleName + " · " + modelName` when `terms != null`.
  - `api.ts`: `type Cost = { spentMicros: number; estimatedMicros: number; effectiveLimitMicros: number; limitMicros: number; bufferPercent: number }`. `cost: Cost | null` goes on `WorkDetail` and `RunDetail`, and `cost?: Cost & { nextCallWorstCaseMicros: number }` on `Pause`. `ResumeBody` gains `extraCostMicros?: number`; `Profile` gains `modelName: string; remote: boolean`.

- [ ] **Step 1: Write the failing CLI tests**
  - `test_start_payload_with_remote_cost_flags`: `--allow-remote --cost-limit 0.10 --cost-buffer 10` gives `allowRemote: true, costLimitMicros: 100000, costBufferPercent: 10`.
  - `test_resume_cost_flag`: `--cost 0.50` → `extraCostMicros: 500000`.
  - `test_format_cost`: `{"spentMicros":1120000,"estimatedMicros":40000,"effectiveLimitMicros":4500000,"limitMicros":5000000,"bufferPercent":10}` → `"role developer · claude-haiku-5-5 · $1.12 (+$0.04 est) of $4.50 / $5.00"`; `None` → `None`.

- [ ] **Step 2: Run them and confirm they fail**

Run: `python3 -m unittest scripts/test_harness.py`
Expected: FAIL.

- [ ] **Step 3: Implement the CLI**

Then the console (`App.tsx`):
- **Submit form:** a role select (only `developer`). The profile list shows every verified profile, labelled `modelName` and marked "remote" when `remote` is true. When a remote profile is selected:
  - a required checkbox appears, labelled "Send repository content to Anthropic";
  - a cost limit field ($, blank by default) and a buffer field (%, default 10) appear;
  - Submit stays disabled until all three are valid;
  - switching back to a local profile clears all three.
- **Run header:** show the cost line when `cost` is set.
- **Pause panel:** for `COST_LIMIT`, show "$<spent> spent (incl. $<estimated> estimated) of $<effective> effective / $<limit> limit", plus an "Extra budget ($)" field. Resume stays disabled until that field is above 0. `ResumeHistory` shows `+$N` when `extraCostMicros > 0`.
- **Docs:** `docs/api.md` documents the new keys and error codes.

- [ ] **Step 4: Run the checks and confirm they pass**

Run: `python3 -m unittest scripts/test_harness.py && ./gradlew test`. `processResources` builds the console, so a TypeScript error fails here.
Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: show role, model and cost, and resume cost pauses from the CLI and console"`

### Task 9: Secret scan in CI

**Files:**
- Modify: `.github/workflows/unit-tests.yml`
- Create: `.gitleaks.toml` (only if the test fixture key needs an allowlist)

- [ ] **Step 1: Add the scan job**

Add a `secret-scan` job on `ubuntu-24.04`:
- `actions/checkout` with `fetch-depth: 0` and `persist-credentials: false`.
- Download the gitleaks release tarball for a version at least two weeks old. Verify it with `sha256sum -c` against a checksum copied into the workflow from the release's `checksums.txt`; record the version and the source of the checksum in a comment.
- Run `gitleaks git --redact --exit-code 1 --log-opts="${{ github.event.pull_request.base.sha || github.event.before }}..${{ github.sha }}"`.
- No third-party action and no token beyond `contents: read`.

If the Task 3 fake key (`sk-ant-api03-TESTONLY…`) is flagged, allowlist only that exact test path and regex in `.gitleaks.toml`.

- [ ] **Step 2: Check it locally on the branch**

Run: `gitleaks git --redact --log-opts="origin/main..HEAD"`, using the same pinned binary.
Expected: `no leaks found`.

- [ ] **Step 3: Commit** — `git commit -m "ci: scan pull requests for leaked secrets with pinned gitleaks"`

### Task 10: Verification, live acceptance, handoff

**Files:**
- Modify: `docs/evidence/local-task-tracker.md` or a new `docs/evidence/m2-remote-developer.md`, `docs/STATUS.md`

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`, with services and `HARNESS_RUNNER_IMAGE` / `HARNESS_REPOSITORY_RUNNER_IMAGE` set as in `docs/development.md`.
Expected: BUILD SUCCESSFUL. Record the unit and integration counts and the commit.

- [ ] **Step 2: Run the live acceptance on the head commit**

Follow the spec's Acceptance section. The owner stores the key once, using the `! python3 scripts/store-anthropic-key.py` prompt in this session. Then:
1. `probe-anthropic claude-haiku-5-5 --credential anthropic_api_key --prices 0.10,0.50,0.125,0.01 --context 16384 --output 4096 --price-tier 100000`.
2. Bind `harness.roles.developer.profile`.
3. In the browser, submit a small repository task with the opt-in, a `$0.10` limit and a `10%` buffer.
4. Watch the cost line and the `developer · claude-haiku-5-5` labels, and reload once.
5. Confirm the `COST_LIMIT` pause shows spend at or below `$0.09`.
6. Resume with `+$0.50` and reach Ready for review.

- [ ] **Step 3: Record the evidence**

Record the run ID, commit, final spend, estimated part, pause and resume, and build counts. Before committing, run `git diff --cached | grep -E 'sk-ant-[A-Za-z0-9_-]{8,}'` and expect no output. Update `docs/STATUS.md`.

- [ ] **Step 4: Inspect and push**

Inspect `git status` and the final diff. Run the pinned gitleaks over the branch. Push, then open the PR, listing:
- the net new test count;
- every manual step: storing the key, the profile probe, the role binding, and that CI needs no key;
- confirmation that the PR body contains no secrets.
