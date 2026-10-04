# Local development

Run commands from the primary `continuous-agent-harness` checkout. Use Java 21 and Docker with Compose v2. The checksum-pinned Gradle wrapper downloads Gradle 9.8.0 on first use; no globally installed Gradle is required. Initial dependency and image downloads require network access.

## Start and verify

For a new database, create local credentials first:

```sh
python3 scripts/create-local-secrets.py
docker compose up -d --wait postgres temporal
docker compose run --rm --no-deps temporal-namespace
python3 scripts/build-runner-image.py
export HARNESS_RUNNER_IMAGE="$(sed -n 's/^RUNNER_IMAGE=//p' runner/image.lock)"
./gradlew build
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar
```

In another terminal:

```sh
curl --fail http://127.0.0.1:8080/actuator/health
```

Expect HTTP 200 and `status: UP`. This is application/database health; the integration suite independently verifies Temporal connectivity and workflow execution. The namespace setup command is safe to repeat and creates `harness` with seven-day retention. It is a separate setup job so Compose readiness waits only for persistent services. PostgreSQL readiness uses TCP to avoid accepting its temporary initialization server.

`./gradlew test` runs unit tests without Docker. `./gradlew build` additionally runs real PostgreSQL, Temporal, workspace, and runner recovery/isolation integration tests. Build the runner image first and export `HARNESS_RUNNER_IMAGE`; runner integration tests require a working Docker daemon. Reports are in `build/test-results/test/` and `build/test-results/integrationTest/`.

`./gradlew integrationTest` runs only the integration suite. Both `check` and `build` require live services; `build` also creates the executable JAR. For focused checks, use `./gradlew test --tests '*RoleConfigurationTest'` or `./gradlew integrationTest --tests '*InfrastructureIT'`. Unmatched filters fail. HTML reports are under `build/reports/tests/`.

The Groovy DSL build uses Java 21 toolchains, native BOM constraints, and a committed `gradle.lockfile`. To intentionally update dependencies, review the version changes, run `./gradlew dependencies --write-locks`, then verify the full build. Do not refresh locks as part of ordinary builds. For local API development, use `./gradlew bootRun`.

Existing database users must follow [credential rotation](#existing-databases-and-credential-rotation) before switching to the generated credentials. Secret creation does not change database passwords.

## Process roles

Choose exactly one role per process. Startup rejects conflicting roles, an explicit profile selection with no role, and a missing or blank database password before database or Temporal beans are created. Additional non-role profiles may accompany one role:

```sh
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=api
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=worker
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=runner
```

`api` is the default and exposes loopback HTTP with a Temporal client. `worker` creates a Temporal client and worker factory. The factory remains idle until Task 7 registers workflows and starts polling. `runner` creates no Temporal client or factory. Worker and runner are non-web processes kept alive by Spring Boot. Task 3 enables runner polling when `HARNESS_RUNNER_ENABLED=true`; worker workflows arrive in Task 7. All three currently configure the application database and run Flyway at startup.

Spring AI's Ollama library is included without model auto-configuration. No model request or model download occurs in this foundation.

## Configuration

| Application variable | Default |
|---|---|
| `HARNESS_DB_URL` | `jdbc:postgresql://127.0.0.1:55432/harness` |
| `HARNESS_DB_USER` | `harness` |
| `HARNESS_SECRETS_DIR` | `./.secrets/app/` (directory with trailing slash) |
| `HARNESS_TEMPORAL_TARGET` | `127.0.0.1:7233` |
| `HARNESS_TEMPORAL_NAMESPACE` | `harness` |
| `HARNESS_HTTP_PORT` | `8080` |

Compose accepts `HARNESS_DB_PORT` (55432), `HARNESS_TEMPORAL_PORT` (7233), and `HARNESS_SECRETS_ROOT` (`./.secrets`). Changing published ports also requires updating the application's database URL and Temporal target. Export variables for Java: Compose's `.env` file is not automatically loaded by the application. A custom Temporal namespace must be created separately; the setup script creates `harness`.

The local secret generator creates four independent random passwords without printing them or overwriting existing files. Secret directories are enforced to mode 0700, and files use mode 0444 so non-root container users can read bind-mounted secrets on native Linux. Other host users cannot traverse the private parent directories. Do not copy these files into public directories or relax the directory permissions; `.secrets/` is Git-ignored. Keep secret files outside backups, artifacts, logs, and version control unless the storage is explicitly protected. Compose local secrets are read-only file mounts, not an encrypted secret manager.

| Local file under `.secrets/` | Consumers |
|---|---|
| `postgres_admin_password` | PostgreSQL bootstrap |
| `app/spring.datasource.password` | PostgreSQL bootstrap and Spring configuration tree |
| `temporal_db_password` | PostgreSQL bootstrap, Temporal schema tool, Temporal server |
| `temporal_visibility_password` | PostgreSQL bootstrap, Temporal schema tool, Temporal server |

Spring imports only the application secret directory. The other credentials are not application configuration. Standard external Spring configuration can supply `spring.datasource.password` instead; there is no password default. Avoid command-line password arguments, which can appear in process listings. Missing files fail service startup; missing/blank application passwords fail validation. Temporal's image and schema tool require process environment credentials, so their startup scripts read the mounted files and export them only inside the container. Docker's configured environment and Compose output contain no password values. Administrators with host/container access can still inspect secrets in files or process memory.

PostgreSQL initializes three databases with separate owners: `harness`, `temporal`, and `temporal_visibility`. Each revokes public connection access. Flyway owns only the application schema; the pinned Temporal schema tool owns the other two schemas. `V1__create_model_profiles.sql` creates profile revision storage and has not been changed. Credential references belong in profile JSON; secret values do not. The application still owns its database; runtime/migration role separation and Temporal client authentication remain separate hardening work. All service ports remain loopback-bound.

### Existing databases and credential rotation

An existing volume retains its passwords. Do not delete the volume to apply this change, and do not expect `POSTGRES_PASSWORD_FILE` to rotate an existing account.

1. Stop application API/worker/runner processes and the Temporal service/schema jobs. Keep PostgreSQL running. Preserve the existing working configuration until the rotation completes.
2. Run `python3 scripts/create-local-secrets.py --directory .secrets/next` to stage new credentials without replacing existing ones.
3. Open a trusted local administrative session: `docker compose exec postgres psql -U postgres -d postgres`. In that interactive session, run `\password harness`, `\password temporal`, `\password temporal_visibility`, and finally `\password postgres`. For each prompt, enter the corresponding staged file's value twice using a trusted local editor/password manager. The prompts do not echo input. Do not put passwords in SQL literals, shell arguments, shell history, or pasted logs. If local socket authentication was hardened separately, authenticate using the existing administrator credential.
4. Set `HARNESS_SECRETS_ROOT=./.secrets/next` for Compose and `HARNESS_SECRETS_DIR=./.secrets/next/app/` for Java. Recreate services using `docker compose up -d --wait postgres temporal`, then run the namespace setup command. Start the application and verify health plus `./gradlew integrationTest`.
5. Confirm authentication with the new credentials before retiring old secret files. If interrupted, keep the administrative session available and reconcile each role with its matching staged file; do not restore stale password files blindly.

These are operator instructions; this PR does not rotate existing developer data. A newly initialized test-only database is used for verification.

## Persistence and shutdown

```sh
docker compose down
```

The named `postgres-data` volume preserves application data and Temporal history across service recreation. Avoid `down -v` unless intentionally discarding all local data. Initialization scripts and initial passwords apply only to an empty volume; changing environment passwords does not rotate existing database credentials. Application shutdown closes Temporal client resources and the worker factory.

This task verifies durable storage, not agent continuation. Coding workflow replay, crash recovery, and deployment compatibility are later acceptance gates.

## Tested dependency baseline

| Component | Resolved version |
|---|---|
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| Temporal Java SDK and test library | 1.36.1 |
| Temporal server and admin tools | 1.32.0 |
| PostgreSQL image | 17.11 |
| Flyway | 12.4.0 |
| PostgreSQL JDBC | 42.7.13 |
| JUnit | 6.0.3 |
| Gradle | 9.8.0 |
| Pinned Temurin JDK image | 21.0.12+8 |

OCI digests are recorded in [`config/images.lock`](../config/images.lock); Compose repeats the service digests explicitly. Both files must be updated together. Images were exercised on Linux arm64. The selected Temporal release uses `server` plus `admin-tools` for explicit schema initialization; no `auto-setup:1.32.0` image was available during verification.

The resolved tree includes Jackson 3.1.5 for Spring and Jackson 2.21.5 for Temporal. The real service payload test passed with their default configuration. To inspect the resolved dependencies:

```sh
./gradlew dependencies
```

Historical Task 1 validation with Maven passed six tests against both the normal development services and a separate fresh PostgreSQL volume. A packaged API smoke test on the pinned JDK image returned health `UP`. A persisted profile row and the Temporal namespace UUID survived restarting PostgreSQL and Temporal. Maven tests ran on host Java 21.0.2; use an updated Java 21 installation for ongoing development. No live Ollama quality or crash-resume claim follows from these checks.

Gradle migration validation (2026-09-26): the checksum-verified Gradle 9.8.0 wrapper ran `clean build`, discovering three unit and three real-service integration tests with zero failures or skips. Both missing-class filter probes failed as required. The Gradle-built JAR started in API, worker, and runner roles, and the API health endpoint returned `UP`. Application Java, Flyway migration, and service image definitions were unchanged.

The initial Gradle 8.14.5 candidate could not configure `failOnNoDiscoveredTests`; 9.8.0 supplies this built-in check. The wrapper uses a 60-second network read timeout after the default 10-second timeout interrupted the distribution download. Distribution and wrapper JAR hashes were checked against Gradle's official published checksums.

Compared with the captured Maven tree, Gradle selected the following transitive versions through native dependency constraints and variant selection. These are accepted and locked after the real-service and packaged startup checks; direct application/framework versions did not change.

| Dependency | Previous resolution | Gradle resolution |
|---|---|---|
| Gson | 2.13.2 | 2.13.2 compile; 2.14.0 runtime |
| Guava | 33.4.8-android | 33.6.0-android compile; 33.6.0-jre runtime |
| Error Prone annotations | 2.41.0 | 2.50.0 |
| J2ObjC annotations | 3.0.0 | 3.1 |

Gradle also resolves Guava's additional annotation metadata dependencies and explicitly declares the JUnit Platform launcher 6.0.3. The committed lockfile records the complete graph per configuration. Dependency locking fixes resolved versions. `gradle/verification-metadata.xml` additionally pins SHA-256 hashes for dependency/plugin artifacts and metadata; Gradle enforces verification by default when this file is present. Wrapper checksums separately cover the Gradle distribution and wrapper JAR.

For intentional dependency updates, regenerate verification metadata only in a trusted environment with `./gradlew --write-verification-metadata sha256 test bootJar dependencies`, inspect new coordinates and hashes against publisher/repository evidence, and run the full build with verification enabled. Never regenerate metadata to silence an unexplained mismatch or use `--dependency-verification off` as a workaround. Initial checksum bootstrapping trusts downloaded bytes; it is not a vulnerability scan or proof that publishers are uncompromised.

Development stays in the primary checkout on a feature branch, rebased onto the latest `origin/main`. Integrate through a pull request.

## Security follow-up verification

On 2026-09-26, a fresh Compose project initialized PostgreSQL and Temporal from generated secret files. The full build passed 13 unit tests and 3 real-service integration tests; two Python tests verified secret permissions, distinct values, no printed credentials, overwrite refusal, and symlink refusal. Run those tests with `python3 -m unittest discover -s scripts -p 'test_*.py'`.

The packaged API returned health UP with file credentials. Packaged startup rejected an absent credential and conflicting roles before database pool startup. A deliberately altered dependency checksum failed verification; the reviewed metadata was restored before the successful final build. Nine critical JAR hashes were independently matched to fresh Maven Central downloads, and metadata contains no trusted-artifact bypasses. This establishes a reviewed checksum baseline, not an independent publisher signature audit. Existing developer database credentials were not rotated.

## Immutable workspaces (Task 2)

`WorkspaceStore` seeds the `task-tracker-v1` benchmark, returns sorted immutable file maps scoped to a run, and writes new snapshots with an expected file digest (`absent` for a new file). Snapshot IDs are SHA-256 of versioned, length-prefixed UTF-8 paths and contents in Java string lexical order. A write's input hash also covers run/invocation identity, parent, path, expected digest, and content.

`V2__create_workspace_snapshots.sql` adds snapshots, run ownership links, immutable per-run starter selection, and write receipts. The per-run starter record preserves the original seed across retries/deployments. Receipts record parent/result lineage, allowing identical file content to be deduplicated across multiple parents and runs. Snapshot insertion, ownership, and receipt commit share a transaction; conflicting concurrent invocation reuse rolls back speculative inserts. There is no mutable workspace head and no delete operation. Only the future Workflow adopts a returned snapshot.

Writes permit Java production/test files and `README.md`, with 64 files, 64 KiB per file, and 1 MiB total UTF-8 content including readable starter assets. Validation rejects traversal, non-normalized paths, control characters, invalid UTF-8, protected build edits, and file/directory collisions. Filesystem import checks reject symlinks and nonregular files. Future tar staging must independently reject links/nonregular entry types and enforce these checks inside its isolated staging root; the source store does not extract archives.

The starter lives in `benchmarks/task-tracker-v1/starter/` and is packaged with the application. Changes to trusted starter behavior/build inputs require a new benchmark version. It contains the complete public behavior contract, a pinned plain-Java Gradle build and JUnit 6.0.3, and an entry point that throws `UnsupportedOperationException`. It deliberately contains no solution or agent-authored tests. `./gradlew -p benchmarks/task-tracker-v1/starter jar` produces its unimplemented JAR. The binary wrapper JAR stays a trusted runner asset; snapshots expose its checksum, and seeding verifies that checksum against the packaged binary. The runner must supply that exact binary. Generated build/cache directories and evaluator fixtures are not imported.

After starting local services and configuring secret files, run:

```sh
./gradlew test --tests '*WorkspacePathPolicyTest' integrationTest --tests '*WorkspaceStoreIT'
```

Task 2 verification on 2026-09-27 passed 49 harness tests (37 unit, 12 real-service integration), including concurrent matching/conflicting invocations, ownership, stale hashes, quotas, and both orders of path collision. A test-only PostgreSQL trigger paused a writer after snapshot insertion; terminating that backend proved rollback of the snapshot and receipt, followed by a successful retry. Run these integration tests against a development/test database where the application role can create its test-only trigger and terminate its own backend sessions. Test rows use fresh run IDs and remain in the test database; no production cleanup/retention policy is implied.

A separate, non-agent-visible JUnit smoke fixture proved the starter compiles/tests offline and still has no implementation. All 21 starter dependency artifact/metadata hashes matched the foundation's reviewed verification metadata. This does not yet prove the Task 3 runner's network/resource isolation or any live model capability.


## Isolated build/test runner (Task 3)

Build the trusted image once (network access required while building), then select its immutable local image ID:

```sh
python3 scripts/build-runner-image.py
export HARNESS_RUNNER_IMAGE="$(sed -n 's/^RUNNER_IMAGE=//p' runner/image.lock)"
./gradlew bootJar
HARNESS_RUNNER_ENABLED=true java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar --spring.profiles.active=runner
```

The builder sends an explicit allowlist of runner/build assets as the Docker context. It never sends the checkout, `.secrets`, host caches, or candidate solution files. The base JDK and Gradle distribution are checksum-pinned; dependencies are locked and verified. `runner/image.lock` records the actual local image ID, which is platform/build specific. Rebuild on another machine and select its generated ID; no image has been published to a registry. Admission rejects mutable tags. Existing invocations retain their original image ID across deployments; keep those images until their invocations finish.

`Runner.ensureStarted` durably admits BUILD or TEST and returns without waiting for Docker. EVALUATE remains unsupported until Task 4. Identical invocation IDs reuse the stored request/result; changed input is rejected. Deadlines are absolute and at most 120 seconds from admission. There is no HTTP submission endpoint yet; Task 5 will connect tool calls to this Java interface. All submitters must configure the image ID, while only the runner role with polling enabled needs Docker access.

`V3__create_runner_invocations.sql` stores invocation state, input/snapshot/image provenance, attempts, cancellation, lease ownership and bounded result blobs. The supervisor polls every second. It holds a PostgreSQL row lock through each bounded reconciliation step, with `SKIP LOCKED` for competing supervisors; the lock fences ownership in addition to recording the lease. Names and ownership/input labels make Docker creation recoverable. Docker operations use fixed argument lists and validated source tar streams. Mismatched container ownership fails visibly without stopping or removing that container.

Each candidate runs as UID/GID 1000 with no network, no host mounts, a read-only root, no capabilities, no-new-privileges, two CPUs, 1 GiB memory (no swap), 128 PIDs, and bounded scratch tmpfs. It receives no database credentials or Docker socket. The trusted stage helper runs as root before candidate execution, extracts only supervisor-generated regular-file archives, and protects source/build/control files with root ownership. A root-owned readiness marker and stage lock prevent restaging once execution begins.

Two implementation details are required by the actual Docker/Gradle behavior:

- A tmpfs cannot be populated in a stopped container and disappears when it exits. The container therefore starts a waiting launcher; the supervisor stages source and releases the protected readiness marker. The logical `AFTER_START` boundary means candidate execution has been released.
- Gradle requires a writable project directory. `/work/project` is root-owned with the sticky bit, and its fixed build/settings/properties/lock/verification files are root-owned and read-only. Candidate code cannot replace them. Gradle caches, output and reports remain inside the bounded container scratch space.

The launcher runs fixed offline Gradle `jar`/`test` commands, drains stdout/stderr while retaining at most 256 KiB, and caps JARs at 16 MiB. Candidate XML reports are retained as a ZIP capped at 4 MiB; they are untrusted diagnostic output, not benchmark verdicts. A strict one-line envelope carries logs, truncation, artifact and reports into bounded Docker logs (32 MiB, one file, compression disabled). This preserves evidence after container exit/restart without an unbounded writable volume. The supervisor uses Docker's exit state, validates the envelope and persists blobs/result in one transaction before cleanup. Independent semantic evaluation is Task 4.

Cancellation/deadline receipts require confirmation that the owned execution is stopped. Docker outages defer reconciliation instead of pretending termination succeeded. A completed build retains its result if the supervisor returns after its deadline. Missing container evidence permits one fresh attempt and marks the final receipt uncertain; a second loss is terminal UNCERTAIN. Compilation/test failures do not consume an infrastructure retry. Containers are removed only after durable receipt commit. Retention of database blobs/test rows is not yet automated.

Run the recovery suite against development/test services:

```sh
python3 scripts/runner_fault_test.py --all-boundaries
```

It starts actual runner JVMs, kills them at five boundaries, restarts them, checks durable receipts and Docker creation events, and exercises cancellation, downtime deadlines, evidence loss, competing supervisors, compilation failure, network/filesystem isolation, cgroup limits and stdout truncation. Fault injection is implemented only in test sources under the `recovery-test` profile; it is absent from the packaged application. Logs/markers stay under ignored `target/runner-tests/`. Normal `integrationTest` includes these tests and therefore requires the runner image.

Task 3 also supplies the starter's previously missing POM checksums. Fresh image resolution fetched POM metadata that the warm local Gradle cache had not needed; checksums were verified against Maven Central without disabling verification. Build behavior and dependency versions in the starter are unchanged.

Security verification: 30 newly added dependency artifact/metadata SHA-256 values matched fresh Maven Central downloads. The publishable tree contained no matches for the eight local credential values (including base64 encodings) or common token/private-key patterns. The packaged application contains no test fault injector or local secret entries. Independent review found an oversized-output retry loop; overflow is now terminal FAILED, with both unit and actual-container regression coverage.

Task 3 verification on 2026-09-27: the full Gradle build passed 74 Java tests (42 unit, 32 integration), including 18 real-process recovery/isolation cases, with no failures or skips. Two Python secret-permission tests also passed. The final snapshot-reuse admission assertion passed in a targeted rerun. The image was built and tested on Docker Desktop Linux/arm64; other runtime/platform combinations require their own image build and integration run.

## Independent task-tracker evaluator (Task 4)

Task 4 is being implemented on top of the merged runner. Its acceptance contract is the model-visible `benchmarks/task-tracker-v1/starter/REQUIREMENTS.md`; the evaluator must not invent additional CLI requirements. The reference solution and intentionally broken variants belong only to harness tests, never the starter, model prompt, or runner image context.

Evaluation binds a submitted immutable snapshot to a built JAR digest, evaluator version, deterministic seed and absolute deadline. Build/test execution remains delegated to the durable runner. Candidate stdout, success messages and JUnit XML cannot establish the independent behavioral verdict. The trusted controller compares observed CLI output and exit status to its own seeded expectations. Each case derives its independent Java `Random` stream from the recorded seed and case name, so a completed case can replay after a controller crash. The XML count is named `reportedAgentTests`: candidate code can inflate it, so it is diagnostic only. The agent-test gate also requires real fixed-Gradle test execution and its no-discovered-tests failure check.

Runtime design uses a fresh candidate container per CLI invocation to prevent surviving child processes from faking persistence. Bounded per-case data is shared across those container lifetimes and discarded between cases/submissions. JAR storage is read-only to candidates, and neither evaluator assertions nor verdict storage is mounted into them. Docker's local driver supports bounded tmpfs-backed volumes on Linux and Docker Desktop; see [Docker volume creation](https://docs.docker.com/reference/cli/docker/volume/create/). A noncandidate holder preserves the mount during a case. Its artifact volume is 34 MiB so a maximum-size 16 MiB JAR and its replacement can coexist during crash-safe staging.

The durable receipt distinguishes `PASSED`, candidate `REJECTED`, and `INFRASTRUCTURE_FAILED`. A command's start intent commits before a candidate container can execute; missing evidence then becomes uncertain rather than replaying a possible mutation. Per-case results commit before cleanup. A name collision leaves foreign Docker objects untouched while the supervisor removes its own resources. A deployment must retain the pinned evaluator version and runner image for an in-progress evaluation; a new version refuses to silently resume old work.

The public contract forbids NUL in descriptions, but operating-system argv cannot carry a NUL byte. Evaluation covers representable controls such as tabs, CR and LF; it does not invent an undocumented escape syntax for NUL.

During Task 4 verification, Gradle requested 20 previously unpinned POM/module metadata files. Each cached file's SHA-256 matched a fresh download from Maven Central before its checksum was added to `gradle/verification-metadata.xml`; strict dependency verification remains enabled.

Task 4 verification on 2026-10-03 used the task-owned PostgreSQL/Temporal services and pinned local Docker image. `./gradlew build` passed 92 Java tests (42 unit, 50 integration), with zero failures or skips. The 14 evaluator integration tests accepted the private good fixture and rejected the starter, nonpersistence, daemon-only persistence, hardcoded output, unknown-ID mutation, hangs, fake success text, forged report attempts, compilation failure, missing README, and missing/discovered-test failures. Separate real-Docker tests covered maximum-size JAR restaging, durable start-intent evidence loss, and foreign-name collision cleanup; a persisted runner receipt proved the distinct infrastructure outcome. The existing 18 real-process runner recovery tests also passed in the full suite.

The packaged application contains the evaluator version/resource manifests and `V4__create_evaluations.sql`, while private reference/mutant fixtures and local secret files are absent. Flyway recorded the descriptive V4 migration in the task-owned database. A scan of all 23 publishable changed/new files found none of the eight local credential values (including base64 encodings) or common private-key/token patterns. These Docker checks ran on Docker Desktop Linux/arm64; other platforms need their own pinned image build and integration run. Live Ollama inference and end-to-end model completion remain Task 5 and later work.
