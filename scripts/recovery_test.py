#!/usr/bin/env python3
"""Run the harness recovery integration suite against the configured local services."""
import argparse
import os
import subprocess
import sys


def gradle(*tests: str) -> None:
    command = ["./gradlew", "integrationTest"]
    for test in tests:
        command.extend(["--tests", test])
    command.append("--offline")
    subprocess.run(command, check=True, env=os.environ.copy())


def get_run(run_id: str) -> dict:
    """Fetch one run from the loopback operator API (used by process scenarios)."""
    import json
    import urllib.request

    base = os.environ.get("HARNESS_API_URL", "http://127.0.0.1:8080")
    with urllib.request.urlopen(f"{base}/api/runs/{run_id}", timeout=5) as response:
        return json.loads(response.read())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="run recovery and deployment integration tests")
    parser.add_argument("--recovery-only", action="store_true")
    args = parser.parse_args()
    if not (args.all or args.recovery_only):
        parser.error("choose --all or --recovery-only")
    # DeploymentIT exercises actual separately owned worker JVMs and restores
    # the namespace's prior routing before it exits.
    if args.all:
        gradle("com.brianmehrman.harness.recovery.RecoveryIT",
               "com.brianmehrman.harness.recovery.DeploymentIT")
    else:
        gradle("com.brianmehrman.harness.recovery.RecoveryIT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
