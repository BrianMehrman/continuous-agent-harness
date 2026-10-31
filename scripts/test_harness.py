import importlib.util
import pathlib
import unittest
import urllib.error
import io
import json
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("harness", pathlib.Path(__file__).with_name("harness.py"))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class EventStreamTest(unittest.TestCase):
    def test_reconnect_keeps_last_acknowledged_sequence(self):
        class Client:
            calls = []
            def request(self, method, path):
                self.calls.append(path)
                if len(self.calls) == 1:
                    return [{"sequence": 1}, {"sequence": 2}]
                if len(self.calls) == 2:
                    raise urllib.error.URLError("temporary disconnect")
                return [{"sequence": 2}, {"sequence": 3}]

        client = Client()
        stream = harness.event_stream(client, "run-1", follow=True, interval=0)
        self.assertEqual([1, 2, 3], [next(stream)["sequence"] for _ in range(3)])
        self.assertEqual(["/api/runs/run-1/events?after=0",
                          "/api/runs/run-1/events?after=2",
                          "/api/runs/run-1/events?after=2"], client.calls)

    def test_rejects_non_loopback_url(self):
        with self.assertRaises(ValueError):
            harness.ApiClient("https://example.com")

    def test_follow_retries_transient_http_failure(self):
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                pass

            def read(self):
                return json.dumps([{"sequence": 1}]).encode()

        unavailable = urllib.error.HTTPError("http://127.0.0.1:8080", 503, "Unavailable", {},
                                               io.BytesIO(b'{"error":"busy"}'))
        client = harness.ApiClient("http://127.0.0.1:8080")
        with patch("urllib.request.urlopen", side_effect=[unavailable, Response()]):
            event = next(harness.event_stream(client, "run-1", follow=True, interval=0))
        self.assertEqual(1, event["sequence"])


class AnthropicProfileCliTest(unittest.TestCase):
    def test_dollars_to_micros(self):
        self.assertEqual(100000, harness.dollars_to_micros("0.10"))
        self.assertEqual(1, harness.dollars_to_micros("0.000001"))
        for invalid in ("0.0000001", "0.1000000", "-1", "not-money"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                harness.dollars_to_micros(invalid)

    def test_probe_anthropic_payload(self):
        with patch.object(harness.ApiClient, "request", return_value={"id": "profile-1"}) as request:
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(0, harness.main([
                    "probe-anthropic", "claude-haiku-5-5", "--credential", "anthropic_api_key",
                    "--prices", "0.10,0.50,0.125,0.01", "--context", "16384",
                    "--output", "4096", "--price-tier", "100000",
                ]))
        request.assert_called_once_with("POST", "/api/profiles/anthropic", {
            "modelId": "claude-haiku-5-5", "credentialReference": "anthropic_api_key",
            "contextTokens": 16384, "outputTokens": 4096, "priceTierInputTokens": 100000,
            "prices": {"inputMicrosPerMTok": 100000, "outputMicrosPerMTok": 500000,
                       "cacheWriteMicrosPerMTok": 125000, "cacheReadMicrosPerMTok": 10000},
        })


class CostCliTest(unittest.TestCase):
    def test_start_uses_role_binding_without_profile_override(self):
        with patch.object(harness.ApiClient, "request", return_value={"runId": "run-1", "commandId": "cmd-1"}) as request:
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(0, harness.main(["start", "--command-id", "cmd-1"]))
        self.assertNotIn("profileRevision", request.call_args.args[2])

    def test_start_payload_with_remote_cost_flags(self):
        with patch.object(harness.ApiClient, "request", return_value={"runId": "run-1", "commandId": "cmd-1"}) as request:
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(0, harness.main(["start", "--command-id", "cmd-1", "--role", "developer",
                    "--profile", "remote-1", "--allow-remote", "--cost-limit", "0.10", "--cost-buffer", "10"]))
        payload = request.call_args.args[2]
        self.assertEqual("developer", payload["role"])
        self.assertEqual(True, payload["allowRemote"])
        self.assertEqual(100000, payload["costLimitMicros"])
        self.assertEqual(10, payload["costBufferPercent"])

    def test_resume_cost_flag(self):
        with patch.object(harness.ApiClient, "request", return_value={"commandId": "cmd-2"}) as request:
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(0, harness.main(["resume", "run-1", "--expected-version", "4", "--cost", "0.50"]))
        self.assertEqual(500000, request.call_args.args[2]["extraCostMicros"])

    def test_format_cost(self):
        cost = {"spentMicros": 1120000, "estimatedMicros": 40000,
                "effectiveLimitMicros": 4500000, "limitMicros": 5000000, "bufferPercent": 10}
        self.assertEqual("role developer · claude-haiku-5-5 · $1.12 (+$0.04 est) of $4.50 / $5.00",
                         harness.format_cost(cost, "developer", "claude-haiku-5-5"))
        self.assertIsNone(harness.format_cost(None, "developer", "claude-haiku-5-5"))
        sub_cent = {"spentMicros": 1270, "estimatedMicros": 0,
                    "effectiveLimitMicros": 4500, "limitMicros": 5000, "bufferPercent": 10}
        self.assertEqual("role developer · claude-haiku-5-5 · $0.00127 (+$0.00 est) of $0.0045 / $0.005",
                         harness.format_cost(sub_cent, "developer", "claude-haiku-5-5"))
        self.assertEqual("$0.505", harness.money(505000))

    def test_status_prints_cost_line(self):
        detail = {"role": "developer", "model": "claude-haiku-5-5", "cost": {
            "spentMicros": 1120000, "estimatedMicros": 40000,
            "effectiveLimitMicros": 4500000, "limitMicros": 5000000, "bufferPercent": 10}}
        with patch.object(harness.ApiClient, "request", return_value=detail):
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                self.assertEqual(0, harness.main(["status", "run-1"]))
        self.assertIn("role developer · claude-haiku-5-5 · $1.12 (+$0.04 est) of $4.50 / $5.00", output.getvalue())



if __name__ == "__main__":
    unittest.main()
