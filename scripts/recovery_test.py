#!/usr/bin/env python3
"""Run Task 9 recovery suites and provide process/API assertion helpers."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "LIMIT_EXHAUSTED",
            "INFRASTRUCTURE_FAILED", "CONTEXT_OVERFLOW"}
_workers: dict[str, tuple[subprocess.Popen, object]] = {}


def gradle(*tests: str) -> None:
    command = ["./gradlew", "integrationTest"]
    for test in tests:
        command.extend(["--tests", test])
    subprocess.run(command + ["--offline"], check=True, env=os.environ.copy())


def _api(path: str) -> object:
    base = os.environ.get("HARNESS_API_URL", "http://127.0.0.1:8080").rstrip("/")
    with urllib.request.urlopen(base + path, timeout=5) as response:
        return json.loads(response.read())


def get_run(run_id: str) -> dict:
    """Fetch the durable projection through the operator API."""
    return _api("/api/runs/" + urllib.parse.quote(run_id, safe=""))["projection"]


def get_events(run_id: str) -> list[dict]:
    """Fetch durable semantic event metadata through the operator API."""
    path = "/api/runs/" + urllib.parse.quote(run_id, safe="") + "/events"
    result = []
    while True:
        page = _api(path + "?after=" + str(result[-1]["sequence"] if result else 0))
        if not page:
            return result
        result.extend(page)


def start_worker(build: str, command: list[str], log_path: Path) -> int:
    """Start only the explicitly supplied worker argv and retain its process handle."""
    if build in _workers:
        raise ValueError(f"worker {build} is already owned by this script")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = log_path.open("ab")
    try:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    except BaseException:
        log.close()
        raise
    _workers[build] = (process, log)
    return process.pid


def kill_worker(build: str) -> None:
    """Kill only a worker process started by this script; never search by name."""
    process, log = _workers.pop(build)
    try:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=10)
    finally:
        log.close()


def wait_for_terminal(run_id: str, timeout_seconds: float | None = None) -> dict:
    if timeout_seconds is None:
        timeout_seconds = json.loads(Path("config/recovery-test.yaml").read_text())[
            "terminalTimeoutSeconds"]
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        run = get_run(run_id)
        if run["status"] in TERMINAL:
            return run
        time.sleep(0.2)
    raise TimeoutError(f"run {run_id} did not become terminal")


def completed_model_invocation_ids(run_id: str) -> set[str]:
    """Read recorded attempt receipts and verify the event projection is contiguous."""
    run = get_run(run_id)
    events = get_events(run_id)
    if [event["sequence"] for event in events] != list(range(1, run["lastSequence"] + 1)):
        raise AssertionError("durable event projection is not contiguous")
    return set(_sql_rows("select distinct invocation_id from model_attempt where run_id='" +
                         _sql_identity(run_id) + "' and status='RECORDED'"))


def assert_completed_model_invocations(run_id: str, expected_ids: set[str]) -> None:
    actual = completed_model_invocation_ids(run_id)
    if actual != expected_ids:
        raise AssertionError(f"model invocation IDs: expected {expected_ids}, got {actual}")


def _sql_identity(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9:_-]{1,200}", value):
        raise ValueError("invalid invocation identity")
    return value


def _sql_rows(query: str) -> list[str]:
    project = os.environ.get("HARNESS_COMPOSE_PROJECT", "continuous-agent-harness")
    result = subprocess.run(["docker", "compose", "-p", project, "exec", "-T", "-u",
                             "postgres", "postgres", "psql", "-d", "harness", "-At", "-c", query],
                            check=True, capture_output=True, text=True)
    return result.stdout.splitlines()


def container_count_for_invocation(invocation_id: str) -> int:
    """Resolve a durable runner invocation and count preserved Docker label matches."""
    rows = _sql_rows("select input_sha256 from runner_invocation where invocation_id='" +
                     _sql_identity(invocation_id) + "'")
    if len(rows) != 1:
        raise AssertionError("expected one durable runner invocation")
    input_sha256 = rows[0]
    result = subprocess.run(["docker", "ps", "--all", "--filter",
                             "label=harness.owner=continuous-agent-harness-v1", "--filter",
                             f"label=harness.input={input_sha256}", "--format", "{{.ID}}"],
                            check=True, capture_output=True, text=True)
    return len(result.stdout.splitlines())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--recovery-only", action="store_true")
    args = parser.parse_args()
    if not (args.all or args.recovery_only):
        parser.error("choose --all or --recovery-only")
    config = json.loads(Path("config/recovery-test.yaml").read_text())
    tests = config["recoverySuites"]
    if args.all:
        tests += config["allSuites"]
    try:
        gradle(*tests)
    finally:
        for build in list(_workers):
            kill_worker(build)
    return 0


if __name__ == "__main__":
    sys.exit(main())
