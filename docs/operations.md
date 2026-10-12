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
python3 scripts/recovery_test.py --all
python3 scripts/deployment_test.py --forward-and-rollback
```

The deployment test starts labeled-by-test worker JVMs, uses the current Temporal CLI deployment API, checks v1/v2 assignment through a forward change and rollback, restarts v1, and restores the namespace's original current route. It leaves task output under `build/task9-deployment-*` for inspection. The Python recovery command runs the Java integration suites; it does not provision or stop shared services.

The repository also has focused persistence and fault tests for command redelivery, model-attempt receipts, runner reconciliation, and workflow replay. These checks do not, by themselves, claim that every crash point in the full end-to-end recovery matrix has been exercised.
