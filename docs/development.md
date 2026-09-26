# Local development

Run commands from the primary `continuous-agent-harness` checkout. Use Java 21 and Docker with Compose v2. The checksum-pinned Gradle wrapper downloads Gradle 9.8.0 on first use; no globally installed Gradle is required. Initial dependency and image downloads require network access.

## Start and verify

```sh
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

## Process roles

Choose one role per process:

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
| `HARNESS_DB_PASSWORD` | `harness_dev` |
| `HARNESS_TEMPORAL_TARGET` | `127.0.0.1:7233` |
| `HARNESS_TEMPORAL_NAMESPACE` | `harness` |
| `HARNESS_HTTP_PORT` | `8080` |

Compose accepts `HARNESS_DB_PORT` (55432), `HARNESS_TEMPORAL_PORT` (7233), `POSTGRES_ADMIN_PASSWORD` (`postgres_dev`), `HARNESS_DB_PASSWORD`, `TEMPORAL_DB_PASSWORD` (`temporal_dev`), and `TEMPORAL_VISIBILITY_PASSWORD` (`visibility_dev`). If changing published ports, also update the application's database URL and Temporal target. Export variables for Java: Compose's `.env` file is not automatically loaded by the application. A custom Temporal namespace must be created separately; the setup script creates `harness`.

The default passwords and loopback port bindings are for local development. PostgreSQL initializes three databases with separate owners: `harness`, `temporal`, and `temporal_visibility`. Each revokes public connection access. Flyway owns only the application schema; the pinned Temporal schema tool owns the other two schemas. `V1__create_model_profiles.sql` creates the profile revision storage. Credential references belong in profile JSON; secret values do not. Future application code must enforce revision immutability.

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

Gradle also resolves Guava's additional annotation metadata dependencies and explicitly declares the JUnit Platform launcher 6.0.3. The committed lockfile records the complete graph per configuration. Dependency locking fixes resolved versions; it does not verify artifact contents. Wrapper checksums cover the Gradle distribution and wrapper JAR separately.

Development stays in the primary checkout on a feature branch, rebased onto the latest `origin/main`. Integrate through a pull request.
