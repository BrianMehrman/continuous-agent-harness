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

## Current feature checks

With the local PostgreSQL and Temporal services initialized and the pinned runner image selected, run `./gradlew build`. It includes workspace, runner isolation, trusted evaluator, scripted coding workflow, and operator API checks. The former Task 9 process-recovery and deployment test scripts were removed to keep the default suite focused; their [dated evidence](evidence/task9-recovery.md) remains available for interpreting the original proof. A live Ollama benchmark still requires Task 10 acceptance.

## Attempt telemetry

Model and tool attempts log `runId` and `invocationId` as key-value text and emit Micrometer observations. Span attributes carry those IDs; metric tags are limited to operation, a fixed tool name, and outcome. The worker records a transition counter after appending a durable state event. Export is off by default. Set `HARNESS_OTLP_METRICS_ENABLED=true` and/or `HARNESS_OTLP_TRACES_ENABLED=true`, plus `HARNESS_OTLP_METRICS_URL` and `HARNESS_OTLP_TRACES_ENDPOINT`, when a trusted local collector is available. Spring's default trace sampling is 10%; set `management.tracing.sampling.probability` explicitly if another rate is required. Neither prompt/source content nor tool output is attached to these observations.
