import importlib.util
import pathlib
import unittest
import urllib.error

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



if __name__ == "__main__":
    unittest.main()
