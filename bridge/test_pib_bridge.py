import http.server
import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pib_bridge import BridgeApp, make_handler


class PibBridgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        # Fake fast DSH/Codex agent CLI using Python itself so tests don't hit network
        self.app = BridgeApp(
            state_dir=self.root / "state",
            allowed_roots=[str(self.root)],
            runner_override=[sys.executable, "-c", "import sys; t=sys.stdin.read(); print('DSH_DONE:' + t.strip())"],
        )
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.app))
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def _req(self, method: str, path: str, body: dict | None = None, token: str | None = "DEFAULT") -> tuple[int, dict]:
        url = f"http://127.0.0.1:{self.port}{path}"
        headers = {"Content-Type": "application/json"}
        if token == "DEFAULT":
            headers["Authorization"] = f"Bearer {self.app.token}"
        elif token is not None:
            headers["Authorization"] = f"Bearer {token}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as err:
            return err.code, json.loads(err.read().decode("utf-8"))

    def test_rejects_anonymous_and_wrong_token(self) -> None:
        code1, _ = self._req("GET", "/health", token=None)
        code2, _ = self._req("GET", "/health", token="wrong-token")
        self.assertEqual(code1, 401)
        self.assertEqual(code2, 401)

    def test_health_and_status_files(self) -> None:
        sample = self.root / "hello.txt"
        sample.write_text("line1\nline2\n", encoding="utf-8")
        code, h = self._req("GET", "/health")
        self.assertEqual(code, 200)
        self.assertTrue(h["ok"])

        code, f = self._req("POST", "/status", {"kind": "files", "cwd": str(self.root)})
        self.assertEqual(code, 200)
        self.assertTrue(any("hello.txt" in x for x in f["entries"]))

        # Outside allowlist must fail closed
        code, denied = self._req("POST", "/status", {"kind": "files", "cwd": "/etc"})
        self.assertEqual(code, 200)
        self.assertFalse(denied["ok"])

    def test_approved_exec_and_dangerous_command_block(self) -> None:
        code, res = self._req("POST", "/exec", {"mode": "python", "cwd": str(self.root), "code": "print(6 * 7)"})
        self.assertEqual(code, 200)
        self.assertTrue(res["ok"])
        self.assertEqual(res["stdout"].strip(), "42")

        code, blocked = self._req("POST", "/exec", {"mode": "command", "cwd": str(self.root), "code": "sudo ls"})
        self.assertFalse(blocked["ok"])
        self.assertIn("sudo", blocked["error"])

    def test_dsh_async_submit_poll_and_list(self) -> None:
        code, sub = self._req("POST", "/dsh", {"action": "submit", "task": "重构模块A", "profile": "flash", "wait_seconds": 5})
        self.assertEqual(code, 200)
        self.assertTrue(sub["ok"])
        self.assertEqual(sub["status"], "completed")
        self.assertIn("DSH_DONE:重构模块A", sub["summary"])

        code, polled = self._req("POST", "/dsh", {"action": "poll", "task_id": sub["id"]})
        self.assertEqual(polled["status"], "completed")

        code, listed = self._req("POST", "/dsh", {"action": "list"})
        self.assertEqual(len(listed["tasks"]), 1)


if __name__ == "__main__":
    unittest.main()
