# Gradle Migration Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the primary checkout. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace Maven with Gradle throughout the harness build and active benchmark/runner specifications, preserving the verified Task 1 behavior.

**Architecture:** One Java application, one readable Groovy DSL build, and the Gradle wrapper. Use native Gradle BOM support and ordinary Java/Test tasks; no buildSrc, convention-plugin project, multi-project conversion, or version catalog is needed for this small build.

**Tech Stack:** Java 21; Gradle 9.8.0 verified baseline; Spring Boot 4.1.1; Spring AI 2.0.1; Temporal SDK 1.36.1; existing PostgreSQL and Temporal services.

**Spec:** Brian's explicit Gradle selection on 2026-09-26 supersedes Maven choices in the [original implementation plan](2026-09-20-local-task-tracker-implementation.md), [stack specification](../specs/implementation-stack.md), and [benchmark specification](../specs/local-task-tracker-benchmark.md).

**Status:** Implemented on 2026-09-26; see [validation evidence](../development.md). The original 8.14.5 candidate lacked the planned test-discovery API; implementation selected and verified 9.8.0. Task 1 PR #4 remained open and conflict-free, so the migration updates that feature branch. Benchmark starter and runner remain future tasks.

## Global constraints

- Work directly in `/Users/brianmehrman/projects/continuous-agent-harness`. Do not create a worktree or stage project files elsewhere.
- Inspect local changes and current PR/main state before switching branches. Preserve Brian's work. Fetch and rebase only when implementation starts; never merge another branch.
- Keep this plan review separate from implementation. Do not rewrite or push the existing Task 1 PR merely to publish this plan.
- Keep Java 21, application dependencies, package names, process roles, service images, and database schemas stable. Do not rename or edit the applied V1 migration.
- Gradle is the build tool for both the harness and the future Java task-tracker target. The target remains a plain Java application, not Spring Boot.
- Existing sandbox boundaries still apply: no network during candidate execution, no agent-editable build scripts, 2 CPU / 1 GiB memory / 128 processes, and existing time/output limits.
- Integrate implementation through a PR. After any required rebase push, verify GitHub reports the exact local head SHA and no conflicts; a stale PR response is not verification.

## Task 1: Replace the harness build and verify behavioral parity

**Files:**
- Create `settings.gradle`, `build.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.lockfile`.
- Modify `.gitignore`, `.gitattributes`.
- Remove `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` after Gradle verification succeeds.
- Exercise existing `src/test/java/com/brianmehrman/harness/config/RoleConfigurationTest.java` and `InfrastructureIT.java` without changing their behavioral assertions.

**Interface:** `test` runs unit tests without services; `integrationTest` runs real-service tests; `check` depends on both; `build` performs verification and creates the executable Boot JAR. Outputs live under `build/`.

- [x] Record current branch, local diff, HEAD, PR state, and dependency baseline before changes. Use the current repository state, not an old branch or this plan's historical assumptions. If Task 1 has merged, start the migration branch from latest main in the primary checkout.
- [x] Capture the existing dependency tree and six test names/results while Maven still exists. Store temporary evidence inside the project's ignored output directory. This establishes a comparison, not acceptance of Maven as the future build.
- [x] Verify Gradle 9.8.0's official distribution checksum and Java 21/Boot compatibility. Generate the wrapper with that exact version and `bin` distribution, record `distributionSha256Sum`, and verify the wrapper JAR against the official checksum. Do not invent checksums or select a floating version. If the candidate fails compatibility checks, record the reason and select an explicitly pinned supported release before proceeding.
- [x] Set `settings.gradle` to:

```groovy
rootProject.name = 'continuous-agent-harness'
```

- [x] Implement the root build using the following configuration. Verify resolved dependency parity before committing the lockfile; Gradle and Maven may resolve competing constraints differently.

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.1'
}

group = 'com.brianmehrman'
version = '0.1.0-SNAPSHOT'

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType(JavaCompile).configureEach {
    options.release = 21
    options.compilerArgs.add('-parameters')
    options.encoding = 'UTF-8'
}

repositories { mavenCentral() }

dependencies {
    implementation platform('org.springframework.boot:spring-boot-dependencies:4.1.1')
    implementation platform('org.springframework.ai:spring-ai-bom:2.0.1')
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.flywaydb:flyway-database-postgresql'
    implementation 'org.springframework.ai:spring-ai-ollama'
    implementation 'io.temporal:temporal-sdk:1.36.1'
    runtimeOnly 'org.postgresql:postgresql'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'io.temporal:temporal-testing:1.36.1'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyLocking { lockAllConfigurations() }

tasks.withType(Test).configureEach {
    useJUnitPlatform()
    failOnNoDiscoveredTests = true
    filter { failOnNoMatchingTests = true }
}

tasks.named('test', Test) {
    exclude '**/*IT.class'
}

def integrationTest = tasks.register('integrationTest', Test) {
    description = 'Runs integration tests against PostgreSQL and Temporal.'
    group = 'verification'
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
    include '**/*IT.class'
    shouldRunAfter tasks.named('test')
}

tasks.named('check') { dependsOn integrationTest }
tasks.named('jar') { enabled = false }
```

`mavenCentral()` names the dependency repository; it does not run Maven. Keep source files in their existing standard Java directories. Do not add abstractions merely to translate Maven lifecycle concepts.

- [x] Ignore `.gradle/` and `build/`; retain the existing `target/` ignore temporarily so old local evidence does not become untracked noise. Replace wrapper line-ending rules with `/gradlew text eol=lf`, `/gradlew.bat text eol=crlf`, and `/gradle/wrapper/gradle-wrapper.jar binary`. Preserve executable permission on `gradlew`.
- [x] Generate dependency locks with `./gradlew dependencies --write-locks`. Compare compile, runtime, and test resolutions with the captured baseline, especially both Jackson major versions, Flyway, PostgreSQL JDBC, JUnit, and Temporal. Explicitly constrain only differences that must be corrected to preserve compatibility; document any accepted differences.
- [x] Run the following acceptance commands:

```sh
./gradlew --version
./gradlew clean test
./gradlew test --tests '*DefinitelyMissingTest' --rerun-tasks
docker compose up -d --wait postgres temporal
docker compose run --rm --no-deps temporal-namespace
./gradlew clean build
./gradlew integrationTest --tests '*InfrastructureIT' --rerun-tasks
./gradlew integrationTest --tests '*DefinitelyMissingIT' --rerun-tasks
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar
```

Both deliberately missing-test commands must fail. `test` must discover the three role tests and require no live service connection. `build` must execute those plus all three infrastructure tests exactly once, with zero skipped tests. Inspect XML under `build/test-results/test/` and `build/test-results/integrationTest/`, not just the exit code. A task reported NO-SOURCE or skipped is not evidence of passing tests.

- [x] Check `http://127.0.0.1:8080/actuator/health` returns UP from the Gradle-built JAR, then stop that smoke-test process. Confirm packaged worker/runner startup still follows the existing non-web role configuration. Preserve developer database volumes.
- [x] Remove the Maven build and wrapper files. Rerun `./gradlew clean build` from the resulting tree. Commit build migration and its lockfile only after acceptance succeeds.

## Task 2: Correct the active documentation and downstream implementation plan

**Files:** `README.md`, `docs/development.md`, `docs/specs/implementation-stack.md`, `docs/specs/local-task-tracker-benchmark.md`, `docs/plans/2026-09-20-local-task-tracker-implementation.md`. Inspect other active docs/scripts for references introduced since this plan was written.

**Interface:** All future implementation tasks use Gradle. No later task should restore a POM, Maven invocation, Surefire/Failsafe reports, or Maven-only runner cache.

- [x] Record Gradle as the accepted build choice in the stack specification. Update developer setup, executable JAR paths, wrapper filenames, resolved version evidence, test filtering, and report locations. Clearly retain old Maven test evidence as historical; do not relabel it as Gradle evidence.
- [x] Apply the command mapping consistently:

| Previous operation | Gradle command |
|---|---|
| Unit tests | `./gradlew test` |
| Full verification and packaging | `./gradlew build` |
| Integration tests only | `./gradlew integrationTest` |
| One unit test class | `./gradlew test --tests '*OllamaAdapterTest'` |
| Two integration classes | `./gradlew integrationTest --tests '*RecoveryIT' --tests '*DeploymentIT'` |
| Unit and integration selection | `./gradlew test --tests '*WorkspacePathPolicyTest' integrationTest --tests '*WorkspaceStoreIT'` |
| Dependencies | `./gradlew dependencies` |
| API development startup | `./gradlew bootRun` |

- [x] Rewrite original plan Task 1's file list and checks to reflect the Gradle foundation. Update Task 2's future trusted starter files to `build.gradle`, `settings.gradle`, wrapper files, and dependency locks. Keep `example.tasktracker.TaskTracker`, Java 21, pinned JUnit, and JAR name `task-tracker.jar`. Do not implement the starter or benchmark solution during this migration.
- [x] Rewrite Task 3's future runner contract: use a trusted, pinned Gradle distribution and preloaded dependency/plugin caches in the runner image. BUILD is `gradle --offline --no-daemon --console=plain jar`; TEST is `gradle --offline --no-daemon --console=plain test`. These apply to the plain-Java target, not the harness build. Runner owns working directory, arguments, build files, settings, wrapper, lockfiles, and `GRADLE_USER_HOME`; candidate edits remain restricted to allowed source/test/README paths.
- [x] Specify image validation using actual offline `jar` and `test` invocations with fixture source/tests under the real non-root, network-disabled, read-only-root limits. Merely resolving dependencies does not prove the offline cache is complete. Bake toolchain and Gradle distribution into the image; wrapper downloads and automatic JDK downloads cannot occur during execution. Provide bounded writable Gradle user-home/project-cache/output locations for each invocation; do not mount host caches or credentials. Account for possible single-use JVM startup even with `--no-daemon` inside the invocation's resource and deadline limits.
- [x] Update future artifact/report paths to `build/libs/task-tracker.jar` and `build/test-results/test/`. Preserve independent evaluator verdicts; candidate-generated XML remains untrusted. Update future live inference from a Maven profile to an explicit `liveLocalTest` task that is excluded from normal `check` and fails on missing prerequisites when requested. Do not create that task before its actual tests exist.
- [x] Search tracked content for Maven/POM/wrapper/report references. Remove operational references; retain only clearly labeled history or this migration explanation. Do not blindly replace the word Maven in repository URLs or historical observations.
- [x] Review the complete original Task 1–10 plan for executable commands, file allowlists, cache layouts, packaging, and report-consumer assumptions. Commit documentation corrections with the migration before opening the PR.

## Completion gate

- [x] A clean checkout needs Java 21, Docker/Compose, and `./gradlew`; no installed Maven or globally installed Gradle is required after wrapper creation.
- [x] `./gradlew clean build` passes the same six behavioral tests and produces the working Boot JAR; test filters cannot silently pass with no matches.
- [x] Dependency/version changes are accounted for and wrapper checksums and dependency locks are committed.
- [x] Only the Gradle build remains; active benchmark and runner instructions consistently use it.
- [x] Java code, the applied migration, service images, and durable data remain unchanged by this build migration.
- [ ] Review the diff, push the migration branch, and create/update its PR without merging it. Confirm GitHub's head SHA matches local HEAD and its conflict status has finished recalculating before claiming readiness. If status remains unknown or conflicting, investigate and report that accurately.

## References checked during planning

- [Spring Boot Gradle plugin support](https://docs.spring.io/spring-boot/gradle-plugin/): supports Gradle 8.14+ and 9.x.
- [Spring Boot Gradle setup](https://docs.spring.io/spring-boot/gradle-plugin/getting-started.html): supports native Gradle BOM management.
- [Gradle Test API](https://docs.gradle.org/9.8.0/dsl/org.gradle.api.tasks.testing.Test.html): built-in test discovery failure handling in the selected version.
- [Gradle wrapper integrity](https://docs.gradle.org/current/userguide/gradle_wrapper.html).
- [Gradle JVM testing](https://docs.gradle.org/current/userguide/java_testing.html).
