#!/usr/bin/env python3
"""Local operator client for the harness API. Uses only Python's standard library."""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from decimal import Decimal, InvalidOperation
from pathlib import Path

DEFAULT_LIMITS = {
    "modelTurns": 40, "toolCalls": 100, "modelAttempts": 80,
    "perTurnModelAttempts": 2, "runnerAttempts": 2,
    "modelAttemptMillis": 180000, "buildTestMillis": 120000,
    "evaluationMillis": 180000, "fileOperationMillis": 10000,
    "runMillis": 1800000,
}


class ApiHttpError(RuntimeError):
    def __init__(self, status, detail):
        self.status = status
        super().__init__(f"HTTP {status}: {detail}")


class ApiClient:
    def __init__(self, base_url):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1") or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise ValueError("--url must be a loopback HTTP origin")
        self.base_url = base_url.rstrip("/")

    def request(self, method, path, payload=None, binary=False):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
                return data if binary else json.loads(data)
        except urllib.error.HTTPError as error:
            try:
                detail = json.loads(error.read()).get("error", error.reason)
            except (ValueError, AttributeError):
                detail = error.reason
            raise ApiHttpError(error.code, detail) from error


def event_stream(client, run_id, after=0, follow=False, interval=1.0):
    """Yield each sequence once; reconnect after transient transport failures."""
    cursor = after
    while True:
        try:
            events = client.request("GET", f"/api/runs/{quote(run_id)}/events?after={cursor}")
        except ApiHttpError as error:
            if not follow or not (error.status in (408, 429) or 500 <= error.status < 600):
                raise
            time.sleep(interval)
            continue
        except (urllib.error.URLError, TimeoutError):
            if not follow:
                raise
            time.sleep(interval)
            continue
        for event in events:
            sequence = event["sequence"]
            if sequence > cursor:
                yield event
                cursor = sequence
        if not follow:
            return
        time.sleep(interval)


def quote(value):
    return urllib.parse.quote(value, safe="")


def dollars_to_micros(text):
    try:
        amount = Decimal(text)
    except InvalidOperation as error:
        raise ValueError("Invalid dollar amount") from error
    if not amount.is_finite() or amount < 0 or amount.as_tuple().exponent < -6:
        raise ValueError("Dollar amount must be nonnegative with at most six decimal places")
    return int(amount * 1_000_000)


def money(micros):
    # Exact micro-dollars: at least two decimals, trailing zeros dropped, never rounded.
    whole, fraction = f"{Decimal(micros) / 1_000_000:.6f}".split(".")
    return f"${whole}.{fraction.rstrip('0').ljust(2, '0')}"


def format_cost(cost, role, model):
    if cost is None:
        return None
    spent = money(cost["spentMicros"])
    estimated = money(cost["estimatedMicros"])
    effective = money(cost["effectiveLimitMicros"])
    limit = money(cost["limitMicros"])
    return f"role {role} · {model} · {spent} (+{estimated} est) of {effective} / {limit}"


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--url", default="http://127.0.0.1:8080")
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("profiles")
    probe = commands.add_parser("probe")
    probe.add_argument("model_tag")
    remote_probe = commands.add_parser("probe-anthropic")
    remote_probe.add_argument("model_id")
    remote_probe.add_argument("--credential", required=True)
    remote_probe.add_argument("--prices", required=True, help="input,output,cache-write,cache-read dollars per MTok")
    remote_probe.add_argument("--context", type=int, required=True)
    remote_probe.add_argument("--output", type=int, required=True)
    remote_probe.add_argument("--price-tier", type=int, required=True)
    start = commands.add_parser("start")
    start.add_argument("--profile")
    start.add_argument("--role", default="developer")
    start.add_argument("--allow-remote", action="store_true")
    start.add_argument("--cost-limit")
    start.add_argument("--cost-buffer", type=int)
    start.add_argument("--benchmark", default="task-tracker-v1")
    start.add_argument("--limits", type=Path, help="JSON file containing all run limits")
    start.add_argument("--command-id", default=None)
    status = commands.add_parser("status")
    status.add_argument("run_id", nargs="?")
    events = commands.add_parser("events")
    events.add_argument("run_id")
    events.add_argument("--after", type=int, default=0)
    events.add_argument("--follow", action="store_true")
    cancel = commands.add_parser("cancel")
    cancel.add_argument("run_id")
    cancel.add_argument("--expected-version", type=int, required=True)
    cancel.add_argument("--command-id", default=None)
    resume = commands.add_parser("resume")
    resume.add_argument("run_id")
    resume.add_argument("--expected-version", type=int, required=True)
    resume.add_argument("--command-id", default=None)
    resume.add_argument("--turns", type=int, default=0)
    resume.add_argument("--tools", type=int, default=0)
    resume.add_argument("--minutes", type=int, default=0)
    resume.add_argument("--cost")
    resume.add_argument("--hint", default=None)
    stop = commands.add_parser("stop")
    stop.add_argument("run_id")
    stop.add_argument("--expected-version", type=int, required=True)
    stop.add_argument("--command-id", default=None)
    download = commands.add_parser("download")
    download.add_argument("run_id")
    download.add_argument("digest")
    download.add_argument("--output", type=Path, required=True)
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    client = ApiClient(args.url)
    if args.command == "profiles":
        result = client.request("GET", "/api/profiles")
    elif args.command == "probe":
        result = client.request("POST", "/api/profiles/probe", {"modelTag": args.model_tag})
    elif args.command == "probe-anthropic":
        parts = args.prices.split(",")
        if len(parts) != 4:
            raise ValueError("--prices requires input,output,cache-write,cache-read")
        amounts = [dollars_to_micros(part) for part in parts]
        result = client.request("POST", "/api/profiles/anthropic", {
            "modelId": args.model_id, "credentialReference": args.credential,
            "contextTokens": args.context, "outputTokens": args.output,
            "priceTierInputTokens": args.price_tier,
            "prices": dict(zip(("inputMicrosPerMTok", "outputMicrosPerMTok",
                                "cacheWriteMicrosPerMTok", "cacheReadMicrosPerMTok"), amounts)),
        })
    elif args.command == "start":
        limits = json.loads(args.limits.read_text()) if args.limits else DEFAULT_LIMITS
        body = {
            "commandId": args.command_id or str(uuid.uuid4()),
            "benchmarkVersion": args.benchmark,
            "role": args.role,
            "allowRemote": args.allow_remote,
            "limits": limits,
        }
        if args.profile is not None:
            body["profileRevision"] = args.profile
        if args.cost_limit is not None:
            body["costLimitMicros"] = dollars_to_micros(args.cost_limit)
        if args.cost_buffer is not None:
            body["costBufferPercent"] = args.cost_buffer
        result = client.request("POST", "/api/runs", body)
        print(f"runId={result['runId']} commandId={result['commandId']}")
        return 0
    elif args.command == "status":
        result = client.request("GET", "/api/runs" + ("/" + quote(args.run_id) if args.run_id else ""))
        if args.run_id:
            line = format_cost(result.get("cost"), result.get("role"), result.get("model"))
            if line:
                print(line)
    elif args.command == "events":
        if args.after < 0:
            raise ValueError("--after must be nonnegative")
        for event in event_stream(client, args.run_id, args.after, args.follow):
            print(json.dumps(event, sort_keys=True), flush=True)
        return 0
    elif args.command == "cancel":
        result = client.request("POST", f"/api/runs/{quote(args.run_id)}/cancel", {
            "commandId": args.command_id or str(uuid.uuid4()),
            "expectedVersion": args.expected_version,
        })
    elif args.command == "resume":
        body = {"commandId": args.command_id or str(uuid.uuid4()), "expectedVersion": args.expected_version,
                "extraTurns": args.turns, "extraToolCalls": args.tools, "extraMinutes": args.minutes}
        if args.hint is not None:
            body["hint"] = args.hint
        if args.cost is not None:
            body["extraCostMicros"] = dollars_to_micros(args.cost)
        result = client.request("POST", f"/api/runs/{quote(args.run_id)}/resume", body)
    elif args.command == "stop":
        result = client.request("POST", f"/api/runs/{quote(args.run_id)}/stop", {
            "commandId": args.command_id or str(uuid.uuid4()),
            "expectedVersion": args.expected_version,
        })
    elif args.command == "download":
        if len(args.digest) != 64 or any(c not in "0123456789abcdef" for c in args.digest):
            raise ValueError("digest must be a lowercase SHA-256 hex string")
        data = client.request("GET", f"/api/runs/{quote(args.run_id)}/artifacts/{args.digest}", binary=True)
        args.output.write_bytes(data)
        print(f"wrote {len(data)} bytes to {args.output}")
        return 0
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
