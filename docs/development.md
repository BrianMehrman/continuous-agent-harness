# Local development

Run commands from the primary `continuous-agent-harness` checkout. Use Java 21 and Docker with Compose v2. The checksum-pinned Gradle wrapper downloads Gradle 9.8.0 on first use; no globally installed Gradle is required. Initial dependency and image downloads require network access.

## Start and verify

For a new database, create local credentials first:

```sh
python3 scripts/create-local-secrets.py
docker compose up -d --wait postgres temporal
docker compose run --rm --no-deps temporal-namespace
./gradlew build
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar
```

In another terminal:

```sh
curl --fail http://127.0.0.1:8080/actuator/health
```

Expect HTTP 200 and `status: UP`. This is application/database health; the integration suite independently verifies Temporal connectivity and workflow execution. The namespace setup command is safe to repeat and creates `harness` with seven-day retention. It is a separate setup job so Compose readiness waits only for persistent services. PostgreSQL readiness uses TCP to avoid accepting its temporary initialization server.

`./gradlew test` runs the three role configuration tests without Docker. `./gradlew build` additionally runs three integration tests against real PostgreSQL and Temporal: migration and profile persistence through an independent connection, database access isolation, and a workflow payload round trip. Reports are in `build/test-results/test/` and `build/test-results/integrationTest/`.

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

`api` is the default and exposes loopback HTTP with a Temporal client. `worker` creates a Temporal client and worker factory. The factory remains idle until Task 7 registers workflows and starts polling. `runner` creates no Temporal client or factory. Worker and runner are non-web processes kept alive by Spring Boot; their actual workloads arrive in later tasks. All three currently configure the application database and run Flyway at startup.

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
