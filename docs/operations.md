# Recovery and worker deployment operations

## Worker build identity

Every worker process must use an immutable build ID in the form `sha256:<64 lowercase hex digits>`. Compute it from the built application artifact and start the worker with the same value for every process running that artifact:

```sh
HARNESS_WORKER_BUILD_ID="sha256:$(shasum -a 256 build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar | awk '{print $1}')" \
  SPRING_PROFILES_ACTIVE=worker java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar
```

Workflows use Temporal's `PINNED` behavior and the deployment name `coding-harness`. A workflow stays assigned to its original build while that run remains open. Keep that build available until its runs complete. If no worker for the assigned build is polling, its run waits; bringing that build back resumes it. Do not assume a newer build can take over pinned history.

## Forward deployment and rollback

The pinned server and CLI are Temporal 1.32.0 and CLI 1.9.0. Verify the namespace and current route before changing it:

```sh
docker compose run --rm --no-deps --entrypoint temporal temporal-namespace \
  --address temporal:7233 --namespace harness worker deployment describe \
  --name coding-harness --output json
```

Start the new worker build first. After its version is registered, route new workflows to it:

```sh
docker compose run --rm --no-deps --entrypoint temporal temporal-namespace \
  --address temporal:7233 --namespace harness worker deployment set-current-version \
  --deployment-name coding-harness --build-id sha256:<64-hex-build-digest> --yes
```

Rollback new workflows by setting the previous build as current. Existing pinned workflows remain on their assigned build; retain workers for both builds until their workflows finish.

## Local acceptance commands

With the local PostgreSQL and Temporal services already initialized, run:

```sh
export HARNESS_RUNNER_IMAGE="$(sed -n 's/^RUNNER_IMAGE=//p' runner/image.lock)"
python3 scripts/recovery_test.py --all
python3 scripts/deployment_test.py --forward-and-rollback
```

Set `HARNESS_COMPOSE_PROJECT`, `HARNESS_DB_URL`, and `HARNESS_TEMPORAL_TARGET` when using a nondefault task-owned Compose project. `HARNESS_RUNNER_IMAGE` must refer to the locally built pinned image before running the recovery suite.

The deployment test starts labeled-by-test worker JVMs, uses the current Temporal CLI deployment API, checks v1/v2 assignment through a forward change and rollback, restarts v1, and restores the namespace's original current route. It leaves task output under `build/task9-deployment-*` for inspection. The Python recovery command runs the Java integration suites, including a coding run whose deadline passes during a Temporal outage. That test stops and restarts the Compose project's Temporal service while retaining PostgreSQL and its volume, so run it only against the task-owned local project. The command does not provision or stop the database.

The coding-run suite also queues cancellation while a pinned worker is absent, restarts that worker, and checks that cancellation stops further model retries. `DurableBoundaryRecoveryIT` kills child JVMs after a committed workspace write receipt, after Temporal accepts START but before outbox acknowledgment, and inside an uncommitted event projection transaction. It then replays each operation and checks receipt/event uniqueness. These focused boundary tests do not exercise every interruption inside one complete model-written coding run.

The `--all` recovery command checks that a synthetic credential reference is absent from loopback run, profile, event, and artifact responses and captured API logs. Its coding recovery case injects a synthetic credential into the worker environment and profile, then scans stopped worker logs and raw Temporal history. The focused `RunnerRecoveryIT.candidateTestsRunOfflineWithIsolationAndBoundedLogs` case checks that a host sentinel environment variable is absent from the candidate and runner log; it requires `HARNESS_RUNNER_IMAGE` to name the locally built pinned image.

## Attempt telemetry

Model and tool attempts log `runId` and `invocationId` as key-value text and emit Micrometer observations. Span attributes carry those IDs; metric tags are limited to operation, a fixed tool name, and outcome. The worker records a transition counter after appending a durable state event. Export is off by default. Set `HARNESS_OTLP_METRICS_ENABLED=true` and/or `HARNESS_OTLP_TRACES_ENABLED=true`, plus `HARNESS_OTLP_METRICS_URL` and `HARNESS_OTLP_TRACES_ENDPOINT`, when a trusted local collector is available. Spring's default trace sampling is 10%; set `management.tracing.sampling.probability` explicitly if another rate is required. Neither prompt/source content nor tool output is attached to these observations.

`TelemetryOutageIT` enables both exporters with an unreachable loopback receiver, gives the metrics exporter a one-second push step, completes the scripted coding workflow, and checks that Temporal history remains queryable. The observed metrics connection failure can occur during application shutdown; this test does not establish collector failure during a real `RunActivitiesImpl` model call. Collector connection failures do not decide a run outcome.

`config/recovery-test.yaml` is a JSON subset of YAML so the standard-library script can read it without another dependency. It lists the suites and terminal poll timeout used by `scripts/recovery_test.py`. Its helper functions can query the loopback API, stop only worker processes they started, and compare expected invocation IDs against durable model receipts. They query the task-owned Compose PostgreSQL container using its local socket; use `HARNESS_COMPOSE_PROJECT` to identify that project. Docker container counts use the stored invocation input digest and the runner's ownership labels. Check a stopped container before runner cleanup removes it.
