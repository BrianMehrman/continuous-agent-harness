# Task 9 recovery evidence

Historical record: the test classes and scripts named below were removed during the subsequent main-feature test cleanup. These commands document what passed on the stated commit; they are no longer runnable at current HEAD.

Tested on 2026-10-11 and 2026-10-12 with PostgreSQL 17, Temporal server 1.32.0, Temporal CLI 1.9.0, and the local pinned runner image. The task-owned Compose project was `harness-tryout`; its PostgreSQL and Temporal ports used the defaults in `compose.yaml`. The runner image ID came from the local `runner/image.lock` and is intentionally not committed as part of this task.

## Commands and results

```sh
docker compose -p harness-tryout up -d --wait postgres temporal
docker compose -p harness-tryout run --rm --no-deps temporal-namespace
HARNESS_COMPOSE_PROJECT=harness-tryout HARNESS_DB_URL=jdbc:postgresql://127.0.0.1:55432/harness HARNESS_TEMPORAL_TARGET=127.0.0.1:7233 HARNESS_RUNNER_IMAGE=<local pinned image ID> python3 scripts/recovery_test.py --all
HARNESS_COMPOSE_PROJECT=harness-tryout HARNESS_DB_URL=jdbc:postgresql://127.0.0.1:55432/harness HARNESS_TEMPORAL_TARGET=127.0.0.1:7233 python3 scripts/deployment_test.py --forward-and-rollback
```

Both Python commands exited successfully on 2026-10-12 against commit `bef2658`, after the final code changes. The recovery command passed in 7m 13s, and the separate deployment command passed in 43s. The former includes worker death during a model call, Temporal outage past the original deadline, cancellation while a worker is absent, runner crash boundaries, credential scans, and the disconnected exporter test. The latter routes pinned workflow versions forward and back, restarts an old worker, and restores the previous namespace route. A prior broad run failed after disabling the model Activity retry; receipt reconciliation and a persisted-state/deadline guard restored the worker-death case before the final passing run. The Java, test, script, build, and config trees were identical on rebased commit `d837b70`.

The exporter test intentionally logs a loopback connection failure while the scripted coding workflow reaches `SUCCEEDED`; Temporal history remains queryable. The observed metrics failure may occur at application shutdown, so it does not prove a collector outage during a real model call. The attempt unit test checks that run and invocation IDs are span attributes while counter labels remain bounded. On 2026-10-12 against `d837b70`, `./gradlew test bootJar --offline` and `./gradlew integrationTest --offline --tests 'com.brianmehrman.harness.api.ApiWorkflowIT' --tests 'com.brianmehrman.harness.execution.CodingWorkflowSmokeIT'` passed.

## Limits of this proof

The model in process recovery tests is scripted; a live Ollama benchmark remains Task 10. The workspace, command, and projection boundary tests kill separate JVMs and verify durable replay, but they are focused subsystem tests rather than interruptions inside one full model-written coding run. Runner boundary tests kill the runner process and reconcile Docker state. A stopped runner container must be inspected before normal cleanup removes it.

Cancellation delivery through a typed Temporal update can still wait while the pinned worker is absent. The API accepts the command durably, and the existing recovery test shows delivery after that worker returns; this evidence does not show that one blocked cancellation leaves the single scheduled dispatcher free to deliver starts for other runs. The scheduled API pump test uses an isolated PostgreSQL schema so stale commands from earlier local tests cannot starve its new run.
