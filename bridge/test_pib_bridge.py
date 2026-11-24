import http.server
import json
import secrets
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pib_bridge import (
    BridgeApp,
    check_blocked_code,
    compute_hmac_signature,
    is_private_or_tailscale_ip,
    make_handler,
    resolve_bind_hosts,
)


class PibBridgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
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

    def _req(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        token: str | None = "DEFAULT",
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, dict]:
        url = f"http://127.0.0.1:{self.port}{path}"
        headers = {"Content-Type": "application/json"}
        if token == "DEFAULT":
            headers["Authorization"] = f"Bearer {self.app.token}"
        elif token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if extra_headers:
            headers.update(extra_headers)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as err:
            with err:
                return err.code, json.loads(err.read().decode("utf-8"))

    def test_rejects_anonymous_wrong_token_and_throttles_bruteforce(self) -> None:
        code1, _ = self._req("GET", "/health", token=None)
        code2, _ = self._req("GET", "/health", token="wrong-token")
        self.assertEqual(code1, 401)
        self.assertEqual(code2, 401)
        for _ in range(11):
            self._req("GET", "/health", token="wrong-token")
        code_throttled, body = self._req("GET", "/health", token="wrong-token")
        self.assertEqual(code_throttled, 429)
        self.assertFalse(body["ok"])

    def test_hmac_signature_auth_without_cleartext_token_and_replay_block(self) -> None:
        payload = {"kind": "overview"}
        raw = json.dumps(payload).encode("utf-8")
        ts = str(int(time.time()))
        nonce = secrets.token_hex(16)
        sig = compute_hmac_signature(self.app.token, ts, nonce, "POST", "/status", raw)
        headers = {
            "X-Pib-Timestamp": ts,
            "X-Pib-Nonce": nonce,
            "X-Pib-Signature": sig,
        }
        code, res = self._req("POST", "/status", payload, token=None, extra_headers=headers)
        self.assertEqual(code, 200)
        self.assertTrue(res["ok"])

        # Replaying exact same signed request must be rejected!
        replay_code, _ = self._req("POST", "/status", payload, token=None, extra_headers=headers)
        self.assertEqual(replay_code, 401)

        # Stale timestamp outside 300s window must be rejected!
        old_ts = str(int(time.time()) - 900)
        old_nonce = secrets.token_hex(16)
        old_sig = compute_hmac_signature(self.app.token, old_ts, old_nonce, "POST", "/status", raw)
        stale_code, _ = self._req(
            "POST",
            "/status",
            payload,
            token=None,
            extra_headers={"X-Pib-Timestamp": old_ts, "X-Pib-Nonce": old_nonce, "X-Pib-Signature": old_sig},
        )
        self.assertEqual(stale_code, 401)

    def test_default_bind_and_source_ip_policy(self) -> None:
        self.assertEqual(resolve_bind_hosts("", False, None), ["127.0.0.1"])
        self.assertEqual(resolve_bind_hosts("", False, "100.88.1.2"), ["127.0.0.1", "100.88.1.2"])
        self.assertEqual(resolve_bind_hosts("", True, "100.88.1.2"), ["0.0.0.0"])
        self.assertTrue(is_private_or_tailscale_ip("127.0.0.1"))
        self.assertTrue(is_private_or_tailscale_ip("192.168.1.15"))
        self.assertTrue(is_private_or_tailscale_ip("100.101.2.3"))
        self.assertFalse(is_private_or_tailscale_ip("8.8.8.8"))
        self.assertFalse(is_private_or_tailscale_ip("100.128.0.1"))

    def test_health_and_status_files(self) -> None:
        sample = self.root / "hello.txt"
        sample.write_text("line1\nline2\n", encoding="utf-8")
        code, h = self._req("GET", "/health")
        self.assertEqual(code, 200)
        self.assertTrue(h["ok"])
        self.assertFalse(h["shell_enabled"])

        code, f = self._req("POST", "/status", {"kind": "files", "cwd": str(self.root)})
        self.assertEqual(code, 200)
        self.assertTrue(any("hello.txt" in x for x in f["entries"]))

        code, denied = self._req("POST", "/status", {"kind": "files", "cwd": "/etc"})
        self.assertEqual(code, 200)
        self.assertFalse(denied["ok"])

    def test_exec_python_shell_default_off_and_regex_command_guards(self) -> None:
        code, res = self._req("POST", "/exec", {"mode": "python", "cwd": str(self.root), "code": "print(6 * 7)"})
        self.assertEqual(code, 200)
        self.assertTrue(res["ok"])
        self.assertEqual(res["stdout"].strip(), "42")

        # Arbitrary shell mode is disabled by default unless --allow-shell is passed
        code, sh_off = self._req("POST", "/exec", {"mode": "command", "cwd": str(self.root), "code": "echo hi"})
        self.assertEqual(code, 200)
        self.assertFalse(sh_off["ok"])
        self.assertIn("--allow-shell", sh_off["error"])

        # Blocked patterns catch whitespace/path variants like sudo\tid, /usr/bin/sudo, rm -fr /
        for bad in ("sudo ls", "sudo\tid", "/usr/bin/sudo whoami", "rm -fr /", "diskutil eraseDisk APFS X disk9"):
            self.assertIsNotNone(check_blocked_code(bad), msg=f"Expected {bad!r} to be blocked")
        code, blocked = self._req("POST", "/exec", {"mode": "command", "cwd": str(self.root), "code": "sudo\tls"})
        self.assertFalse(blocked["ok"])
        self.assertIn("sudo", blocked["error"])

    def test_idempotency_key_prevents_duplicate_exec_and_dsh_submit(self) -> None:
        counter_file = self.root / "count.txt"
        py_code = (
            f"from pathlib import Path\n"
            f"p = Path({str(counter_file)!r})\n"
            f"n = int(p.read_text()) if p.exists() else 0\n"
            f"p.write_text(str(n + 1))\n"
            f"print(n + 1)\n"
        )
        idem = "idem-exec-0001"
        _, r1 = self._req("POST", "/exec", {"mode": "python", "cwd": str(self.root), "code": py_code}, extra_headers={"X-Idempotency-Key": idem})
        _, r2 = self._req("POST", "/exec", {"mode": "python", "cwd": str(self.root), "code": py_code}, extra_headers={"X-Idempotency-Key": idem})
        self.assertTrue(r1["ok"])
        self.assertEqual(r1["stdout"].strip(), "1")
        self.assertEqual(r2["stdout"].strip(), "1")
        self.assertEqual(counter_file.read_text(encoding="utf-8"), "1")

        dsh_idem = "idem-dsh-0001"
        _, sub1 = self._req("POST", "/dsh", {"action": "submit", "task": "只跑一次", "wait_seconds": 5}, extra_headers={"X-Idempotency-Key": dsh_idem})
        _, sub2 = self._req("POST", "/dsh", {"action": "submit", "task": "只跑一次", "wait_seconds": 5}, extra_headers={"X-Idempotency-Key": dsh_idem})
        self.assertEqual(sub1["id"], sub2["id"])

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
