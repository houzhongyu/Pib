#!/usr/bin/env python3
"""
Pib × Computer & DSH/Codex Authenticated Bridge (Default Port 18792).

Zero third-party dependencies (Python 3.10+ stdlib; macOS / Linux / Windows).
Wire-compatible with `/health`, `/status`, `/exec` AND adds `/dsh` for asynchronous
delegation to local DSH / Codex / Claude CLI agents in isolated or allowlisted workspaces.

Security boundaries:
- 256-bit Bearer token in ~/.pib_bridge/token (mode 0600, constant-time hmac.compare_digest).
- Strict realpath workspace allowlist (~/Developer, ~/Desktop, ~/Documents, ~/.pib_bridge,
  plus optional PIB_BRIDGE_ALLOW_DIRS separated by os.pathsep).
- `/status` uses fixed argv arrays (shell=False) — zero command injection surface.
- `/exec` and `/dsh` require explicit user confirmation on the Pib phone before dispatch,
  enforce workspace allowlist, block privilege-escalation / destructive patterns, and record
  durable audit & task receipts.
"""

from __future__ import annotations

import argparse
import hmac
import http.server
import json
import os
import platform
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any
from urllib.parse import quote

VERSION = "0.3.0"
DEFAULT_PORT = int(os.environ.get("PIB_BRIDGE_PORT", "18792"))
DEFAULT_BIND = os.environ.get("PIB_BRIDGE_BIND", "0.0.0.0")
STATE_DIR = Path(os.environ.get("PIB_BRIDGE_HOME", str(Path.home() / ".pib_bridge"))).expanduser()

BLOCKED_PATTERNS = (
    "sudo ",
    "rm -rf /",
    "mkfs",
    "shutdown",
    "reboot",
    "launchctl bootout",
    "diskutil erase",
    "csrutil",
    "format c:",
    "del /f /s /q c:\\",
)

EXTENDED_PATH = os.pathsep.join(
    [
        os.environ.get("PATH", ""),
        str(Path.home() / ".local" / "bin"),
        "/opt/homebrew/bin",
        "/usr/local/bin",
    ]
)


def default_allowed_roots(state_dir: Path = STATE_DIR) -> list[str]:
    roots = [
        str(state_dir.resolve()),
        str((Path.home() / "Developer").resolve()),
        str((Path.home() / "Desktop").resolve()),
        str((Path.home() / "Documents").resolve()),
    ]
    extra = os.environ.get("PIB_BRIDGE_ALLOW_DIRS", "").strip()
    if extra:
        for p in extra.split(os.pathsep):
            if p.strip():
                roots.append(str(Path(p.strip()).expanduser().resolve()))
    return roots


def load_or_create_token(state_dir: Path = STATE_DIR) -> str:
    state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    token_file = state_dir / "token"
    if token_file.exists():
        tok = token_file.read_text(encoding="utf-8").strip()
        if len(tok) >= 32:
            _chmod_600(token_file)
            return tok
    tok = secrets.token_hex(32)
    token_file.write_text(tok + "\n", encoding="utf-8")
    _chmod_600(token_file)
    return tok


def _chmod_600(path: Path) -> None:
    if os.name != "nt":
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass


def append_audit(entry: dict[str, Any], state_dir: Path = STATE_DIR) -> None:
    try:
        state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        audit_file = state_dir / "audit.jsonl"
        entry = {**entry, "ts": round(time.time(), 3)}
        with audit_file.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        _chmod_600(audit_file)
    except OSError:
        pass


def resolve_allowed_dir(raw_path: str | None, allowed_roots: list[str], state_dir: Path = STATE_DIR) -> Path | None:
    ws_dir = state_dir / "workspace"
    ws_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    dev_dir = Path.home() / "Developer"
    default_root = dev_dir if dev_dir.is_dir() else ws_dir
    candidate = Path(raw_path).expanduser() if raw_path else default_root
    if not candidate.is_absolute():
        candidate = default_root / candidate
    try:
        real = candidate.resolve()
    except OSError:
        return None
    real_str = str(real)
    for root in allowed_roots:
        if os.path.exists(root):
            root_real = os.path.realpath(root)
            if real_str == root_real or real_str.startswith(root_real + os.sep):
                return real
    return None


def detect_agents() -> dict[str, str]:
    found: dict[str, str] = {}
    custom_dsh = os.environ.get("PIB_DSH_CMD", "").strip()
    if custom_dsh:
        found["dsh"] = custom_dsh
    for name in ("dsh", "codex", "claude"):
        if name not in found:
            path = shutil.which(name, path=EXTENDED_PATH)
            if path:
                found[name] = path
    return found


def estimate_memory_gb() -> tuple[float, float]:
    sys_name = platform.system()
    try:
        if sys_name == "Darwin":
            total_b = int(subprocess.check_output(["/usr/sbin/sysctl", "-n", "hw.memsize"], text=True, timeout=3).strip())
            vm_out = subprocess.check_output(["/usr/bin/vm_stat"], text=True, timeout=3)
            page_size = 16384
            free_pages = 0
            for line in vm_out.splitlines():
                if "page size of" in line:
                    nums = [int(s) for s in line.split() if s.isdigit()]
                    if nums:
                        page_size = nums[0]
                elif any(k in line for k in ("Pages free:", "Pages inactive:", "Pages speculative:")):
                    num = "".join(ch for ch in line.split(":")[-1] if ch.isdigit())
                    if num:
                        free_pages += int(num)
            return round((free_pages * page_size) / (1024**3), 2), round(total_b / (1024**3), 2)
        if sys_name == "Linux" and os.path.exists("/proc/meminfo"):
            info: dict[str, int] = {}
            for line in Path("/proc/meminfo").read_text().splitlines():
                parts = line.split(":")
                if len(parts) == 2:
                    digits = "".join(c for c in parts[1] if c.isdigit())
                    if digits:
                        info[parts[0].strip()] = int(digits)
            total_kb = info.get("MemTotal", 0)
            avail_kb = info.get("MemAvailable", info.get("MemFree", 0))
            return round(avail_kb / (1024**2), 2), round(total_kb / (1024**2), 2)
    except Exception:
        pass
    return 0.0, 0.0


def build_overview(target_dir: Path) -> dict[str, Any]:
    usage = shutil.disk_usage(str(target_dir if target_dir.exists() else Path.home()))
    free_disk_gb = round(usage.free / (1024**3), 1)
    avail_ram_gb, total_ram_gb = estimate_memory_gb()
    agents = detect_agents()

    git_summary = ""
    git_bin = shutil.which("git", path=EXTENDED_PATH)
    if git_bin and ((target_dir / ".git").exists() or (target_dir.parent / ".git").exists()):
        try:
            st = subprocess.check_output([git_bin, "-C", str(target_dir), "status", "--short", "-b"], text=True, timeout=4, stderr=subprocess.DEVNULL).splitlines()
            lg = subprocess.check_output([git_bin, "-C", str(target_dir), "log", "-n", "3", "--oneline"], text=True, timeout=4, stderr=subprocess.DEVNULL).splitlines()
            git_summary = "branch: " + (st[0] if st else "") + f" (dirty={max(0, len(st) - 1)}) | recent: " + " ; ".join(lg)
        except Exception:
            git_summary = ""

    return {
        "ok": True,
        "bridge_version": VERSION,
        "hostname": socket.gethostname(),
        "platform": f"{platform.system()} {platform.release()}",
        "cwd": str(target_dir),
        "free_disk_gb": free_disk_gb,
        "available_ram_gb": avail_ram_gb,
        "total_ram_gb": total_ram_gb,
        "agents": list(agents.keys()),
        "git": git_summary,
    }


def handle_readonly_status(payload: dict[str, Any], allowed_roots: list[str], state_dir: Path = STATE_DIR) -> dict[str, Any]:
    kind = str(payload.get("kind") or "overview").strip().lower()
    raw_dir = payload.get("cwd")
    target = resolve_allowed_dir(raw_path=raw_dir if isinstance(raw_dir, str) else None, allowed_roots=allowed_roots, state_dir=state_dir)
    if target is None or not target.exists():
        return {"ok": False, "error": "路径不在允许的工作区范围内或不存在"}

    if kind == "git":
        git_bin = shutil.which("git", path=EXTENDED_PATH)
        if not git_bin:
            return {"ok": False, "error": "电脑未安装 git"}
        try:
            st = subprocess.check_output([git_bin, "-C", str(target), "status", "--short", "-b"], text=True, timeout=5, stderr=subprocess.STDOUT)
            lg = subprocess.check_output([git_bin, "-C", str(target), "log", "-n", "6", "--oneline"], text=True, timeout=5, stderr=subprocess.STDOUT)
            return {"ok": True, "cwd": str(target), "status": st[:2500], "recent_commits": lg[:1500]}
        except Exception as exc:
            return {"ok": False, "error": f"Git 状态读取失败: {exc}"}

    if kind == "files":
        try:
            entries = []
            for item in sorted(target.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))[:60]:
                suffix = "/" if item.is_dir() else f" ({item.stat().st_size} B)"
                entries.append(item.name + suffix)
            return {"ok": True, "cwd": str(target), "entries": entries}
        except Exception as exc:
            return {"ok": False, "error": f"目录读取失败: {exc}"}

    if kind == "tail_log":
        rel = str(payload.get("file") or "").strip()
        file_path = resolve_allowed_dir(str(target / rel), allowed_roots, state_dir) if rel else None
        if file_path is None or not file_path.is_file():
            return {"ok": False, "error": "日志文件不存在或不在允许目录"}
        try:
            lines = file_path.read_text(encoding="utf-8", errors="replace").splitlines()[-80:]
            return {"ok": True, "file": str(file_path), "tail": "\n".join(lines)[-4000:]}
        except Exception as exc:
            return {"ok": False, "error": f"日志读取失败: {exc}"}

    return build_overview(target)


def check_blocked_code(code: str) -> str | None:
    lower_code = code.lower()
    for pat in BLOCKED_PATTERNS:
        if pat in lower_code:
            return pat
    return None


def handle_approved_exec(payload: dict[str, Any], allowed_roots: list[str], state_dir: Path = STATE_DIR) -> dict[str, Any]:
    mode = str(payload.get("mode") or "python").strip().lower()
    code = str(payload.get("code") or "").strip()
    raw_dir = payload.get("cwd")
    timeout_sec = min(60, max(2, int(payload.get("timeout") or 25)))

    if not code:
        return {"ok": False, "error": "没有要执行的代码或命令"}
    if len(code) > 24000:
        return {"ok": False, "error": "代码长度超过安全上限 (24000 字符)"}

    hit = check_blocked_code(code)
    if hit:
        append_audit({"action": "exec_blocked", "mode": mode, "reason": hit}, state_dir)
        return {"ok": False, "error": f"安全策略拦截了高危指令片段: {hit}"}

    target = resolve_allowed_dir(raw_dir if isinstance(raw_dir, str) else None, allowed_roots, state_dir)
    if target is None or not target.is_dir():
        return {"ok": False, "error": "工作目录不在允许范围内或不存在"}

    started = time.time()
    tmp_script: str | None = None
    env = {**os.environ, "PATH": EXTENDED_PATH}
    try:
        if mode == "python":
            with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False, encoding="utf-8") as tf:
                tf.write(code)
                tmp_script = tf.name
            proc = subprocess.run([sys.executable, tmp_script], cwd=str(target), env=env, capture_output=True, text=True, timeout=timeout_sec)
        elif mode == "node":
            node_bin = shutil.which("node", path=EXTENDED_PATH)
            if not node_bin:
                return {"ok": False, "error": "电脑未找到 node 运行时"}
            with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as tf:
                tf.write(code)
                tmp_script = tf.name
            proc = subprocess.run([node_bin, tmp_script], cwd=str(target), env=env, capture_output=True, text=True, timeout=timeout_sec)
        elif mode == "command":
            shell_cmd = ["cmd.exe", "/c", code] if os.name == "nt" else ["/bin/sh", "-lc", code]
            proc = subprocess.run(shell_cmd, cwd=str(target), env=env, capture_output=True, text=True, timeout=timeout_sec)
        else:
            return {"ok": False, "error": f"不支持的执行模式: {mode}"}

        elapsed = round(time.time() - started, 2)
        stdout = (proc.stdout or "")[-6000:]
        stderr = (proc.stderr or "")[-3000:]
        append_audit({"action": "exec_done", "mode": mode, "cwd": str(target), "exit_code": proc.returncode, "elapsed_sec": elapsed}, state_dir)
        return {"ok": proc.returncode == 0, "exit_code": proc.returncode, "elapsed_sec": elapsed, "cwd": str(target), "stdout": stdout, "stderr": stderr}
    except subprocess.TimeoutExpired:
        append_audit({"action": "exec_timeout", "mode": mode, "cwd": str(target), "timeout": timeout_sec}, state_dir)
        return {"ok": False, "error": f"执行超时（超过 {timeout_sec} 秒已自动终止）"}
    except Exception as exc:
        return {"ok": False, "error": f"执行异常: {exc}"}
    finally:
        if tmp_script and os.path.exists(tmp_script):
            try:
                os.remove(tmp_script)
            except OSError:
                pass


class DshTaskManager:
    """Asynchronous DSH / Codex / Claude task runner with durable JSON receipts."""

    def __init__(self, state_dir: Path, allowed_roots: list[str], runner_override: list[str] | None = None):
        self.state_dir = state_dir
        self.tasks_dir = state_dir / "tasks"
        self.tasks_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.allowed_roots = allowed_roots
        self.runner_override = runner_override
        self._lock = threading.Lock()
        self._procs: dict[str, subprocess.Popen[str]] = {}

    def submit(self, payload: dict[str, Any]) -> dict[str, Any]:
        prompt = str(payload.get("task") or "").strip()
        if not prompt:
            return {"ok": False, "error": "缺少任务描述 task"}
        if len(prompt) > 16000:
            return {"ok": False, "error": "任务描述过长（上限 16000 字符）"}
        hit = check_blocked_code(prompt)
        if hit:
            return {"ok": False, "error": f"任务包含被安全策略禁止的指令片段: {hit}"}

        profile = str(payload.get("profile") or "auto").strip().lower()
        preferred_agent = str(payload.get("agent") or "auto").strip().lower()
        task_id = "dsh-" + secrets.token_hex(6)
        job_dir = (self.tasks_dir / task_id).resolve()
        job_dir.mkdir(mode=0o700, parents=True, exist_ok=True)

        raw_cwd = payload.get("cwd")
        if isinstance(raw_cwd, str) and raw_cwd.strip():
            cwd = resolve_allowed_dir(raw_cwd, self.allowed_roots, self.state_dir)
            if cwd is None or not cwd.is_dir():
                return {"ok": False, "error": "指定的工作目录不在允许范围内或不存在"}
        else:
            cwd = job_dir

        argv, agent_name = self._build_command(preferred_agent, profile, job_dir)
        if argv is None:
            return {"ok": False, "error": "电脑未检测到可用的 DSH / Codex / Claude CLI（可安装 codex 或设置 PIB_DSH_CMD）"}

        meta = {
            "id": task_id,
            "status": "running",
            "agent": agent_name,
            "profile": profile,
            "cwd": str(cwd),
            "task": prompt[:500],
            "started_at": round(time.time(), 2),
            "finished_at": None,
            "summary": "",
        }
        self._write_meta(job_dir, meta)
        append_audit({"action": "dsh_submit", "id": task_id, "agent": agent_name, "cwd": str(cwd)}, self.state_dir)

        t = threading.Thread(target=self._run_worker, args=(task_id, job_dir, cwd, argv, agent_name, prompt), daemon=True)
        t.start()

        wait_sec = min(25.0, max(0.0, float(payload.get("wait_seconds") or 0.0)))
        if wait_sec > 0:
            deadline = time.time() + wait_sec
            while time.time() < deadline:
                cur = self._read_meta(job_dir)
                if cur and cur.get("status") != "running":
                    return {"ok": True, **cur}
                time.sleep(0.25)
        return {"ok": True, **(self._read_meta(job_dir) or meta)}

    def poll(self, task_id: str) -> dict[str, Any]:
        if not re.fullmatch(r"dsh-[a-f0-9]{12}", task_id or ""):
            return {"ok": False, "error": "无效的任务编号"}
        meta = self._read_meta(self.tasks_dir / task_id)
        if not meta:
            return {"ok": False, "error": f"未找到任务 {task_id}"}
        if meta.get("status") == "running":
            meta["elapsed_sec"] = round(time.time() - float(meta.get("started_at") or time.time()), 1)
        return {"ok": True, **meta}

    def list_recent(self, limit: int = 10) -> dict[str, Any]:
        items: list[dict[str, Any]] = []
        for d in sorted(self.tasks_dir.iterdir(), key=lambda p: p.stat().st_mtime, reverse=True):
            if d.is_dir() and d.name.startswith("dsh-"):
                m = self._read_meta(d)
                if m:
                    items.append(m)
                if len(items) >= limit:
                    break
        return {"ok": True, "tasks": items}

    def cancel(self, task_id: str) -> dict[str, Any]:
        if not re.fullmatch(r"dsh-[a-f0-9]{12}", task_id or ""):
            return {"ok": False, "error": "无效的任务编号"}
        with self._lock:
            proc = self._procs.get(task_id)
        if proc and proc.poll() is None:
            try:
                if os.name != "nt":
                    os.killpg(proc.pid, signal.SIGTERM)
                else:
                    proc.terminate()
            except OSError:
                pass
        job_dir = self.tasks_dir / task_id
        meta = self._read_meta(job_dir)
        if not meta:
            return {"ok": False, "error": f"未找到任务 {task_id}"}
        if meta.get("status") == "running":
            meta["status"] = "cancelled"
            meta["finished_at"] = round(time.time(), 2)
            meta["summary"] = "任务已由用户在手机端取消。"
            self._write_meta(job_dir, meta)
        return {"ok": True, **meta}

    def _build_command(self, preferred: str, profile: str, job_dir: Path) -> tuple[list[str] | None, str]:
        if self.runner_override is not None:
            return list(self.runner_override), "custom-test"
        agents = detect_agents()
        order = [preferred] if preferred in agents else ["dsh", "codex", "claude"]
        chosen = next((a for a in order if a in agents), None)
        if not chosen:
            return None, ""
        bin_path = agents[chosen]
        if chosen == "dsh":
            # Supports custom PIB_DSH_CMD (split safely) or `dsh` binary reading prompt from stdin.
            parts = bin_path.split() if " " in bin_path and not os.path.exists(bin_path) else [bin_path]
            return parts, "dsh"
        if chosen == "codex":
            effort = "high" if profile == "pro" else "low"
            out_msg = str(job_dir / "last_message.txt")
            return [
                bin_path,
                "exec",
                "--skip-git-repo-check",
                "--sandbox",
                "workspace-write",
                "-c",
                'approval_policy="never"',
                "-c",
                f'model_reasoning_effort="{effort}"',
                "--color",
                "never",
                "--output-last-message",
                out_msg,
                "-",
            ], f"codex ({effort})"
        return [bin_path, "-p"], "claude"

    def _run_worker(self, task_id: str, job_dir: Path, cwd: Path, argv: list[str], agent_name: str, prompt: str) -> None:
        stdout_file = job_dir / "stdout.log"
        stderr_file = job_dir / "stderr.log"
        env = {**os.environ, "PATH": EXTENDED_PATH}
        env.pop("CODEX_THREAD_ID", None)
        started = time.time()
        try:
            with stdout_file.open("w", encoding="utf-8") as out, stderr_file.open("w", encoding="utf-8") as err:
                kwargs: dict[str, Any] = {
                    "cwd": str(cwd),
                    "stdin": subprocess.PIPE,
                    "stdout": out,
                    "stderr": err,
                    "text": True,
                    "env": env,
                }
                if os.name != "nt":
                    kwargs["start_new_session"] = True
                proc = subprocess.Popen(argv, **kwargs)
                with self._lock:
                    self._procs[task_id] = proc
                try:
                    proc.communicate(input=prompt, timeout=600)
                except subprocess.TimeoutExpired:
                    if os.name != "nt":
                        os.killpg(proc.pid, signal.SIGTERM)
                    else:
                        proc.terminate()
                    proc.wait(timeout=5)
                    self._finish(job_dir, "failed", started, "任务执行超过 10 分钟上限，已自动中止。")
                    return
            last_msg_file = job_dir / "last_message.txt"
            summary = ""
            if last_msg_file.is_file():
                summary = last_msg_file.read_text(encoding="utf-8", errors="replace").strip()
            if not summary and stdout_file.is_file():
                summary = stdout_file.read_text(encoding="utf-8", errors="replace").strip()[-4000:]
            if proc.returncode != 0 and not summary and stderr_file.is_file():
                summary = stderr_file.read_text(encoding="utf-8", errors="replace").strip()[-2000:]
            status = "completed" if proc.returncode == 0 else "failed"
            self._finish(job_dir, status, started, summary[:4000] or f"{agent_name} 退出码 {proc.returncode}")
        except Exception as exc:
            self._finish(job_dir, "failed", started, f"启动失败: {exc}")
        finally:
            with self._lock:
                self._procs.pop(task_id, None)

    def _finish(self, job_dir: Path, status: str, started: float, summary: str) -> None:
        meta = self._read_meta(job_dir) or {}
        if meta.get("status") == "cancelled":
            return
        meta["status"] = status
        meta["finished_at"] = round(time.time(), 2)
        meta["elapsed_sec"] = round(time.time() - started, 2)
        meta["summary"] = summary
        self._write_meta(job_dir, meta)

    @staticmethod
    def _write_meta(job_dir: Path, meta: dict[str, Any]) -> None:
        tmp = job_dir / "meta.json.tmp"
        tmp.write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
        tmp.replace(job_dir / "meta.json")

    @staticmethod
    def _read_meta(job_dir: Path) -> dict[str, Any] | None:
        f = job_dir / "meta.json"
        if not f.is_file():
            return None
        try:
            return json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            return None


class BridgeApp:
    def __init__(self, state_dir: Path = STATE_DIR, allowed_roots: list[str] | None = None, runner_override: list[str] | None = None):
        self.state_dir = state_dir
        self.token = load_or_create_token(state_dir)
        self.allowed_roots = allowed_roots or default_allowed_roots(state_dir)
        self.dsh = DshTaskManager(state_dir, self.allowed_roots, runner_override=runner_override)


def make_handler(app: BridgeApp) -> type[http.server.BaseHTTPRequestHandler]:
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = f"PibBridge/{VERSION}"

        def log_message(self, fmt: str, *args: Any) -> None:
            return

        def _send_json(self, code: int, obj: dict[str, Any]) -> None:
            raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _check_auth(self) -> bool:
            auth = self.headers.get("Authorization") or ""
            if not auth.startswith("Bearer "):
                self._send_json(401, {"ok": False, "error": "缺少 Bearer 鉴权令牌"})
                return False
            if not hmac.compare_digest(auth[7:].strip(), app.token):
                self._send_json(401, {"ok": False, "error": "电脑桥接令牌不匹配"})
                return False
            return True

        def _read_body(self) -> dict[str, Any] | None:
            length = int(self.headers.get("Content-Length") or "0")
            if length <= 0:
                return {}
            if length > 256 * 1024:
                self._send_json(413, {"ok": False, "error": "请求体过大"})
                return None
            raw = self.rfile.read(length)
            try:
                parsed = json.loads(raw.decode("utf-8"))
                return parsed if isinstance(parsed, dict) else {}
            except Exception:
                self._send_json(400, {"ok": False, "error": "无效 JSON"})
                return None

        def do_GET(self) -> None:
            if not self._check_auth():
                return
            if self.path.startswith("/health"):
                default_dir = resolve_allowed_dir(None, app.allowed_roots, app.state_dir) or Path.home()
                self._send_json(200, build_overview(default_dir))
                return
            self._send_json(404, {"ok": False, "error": "Not found"})

        def do_POST(self) -> None:
            if not self._check_auth():
                return
            body = self._read_body()
            if body is None:
                return
            if self.path == "/status":
                self._send_json(200, handle_readonly_status(body, app.allowed_roots, app.state_dir))
                return
            if self.path == "/exec":
                self._send_json(200, handle_approved_exec(body, app.allowed_roots, app.state_dir))
                return
            if self.path == "/dsh":
                action = str(body.get("action") or "submit").strip().lower()
                if action == "submit":
                    self._send_json(200, app.dsh.submit(body))
                elif action == "poll":
                    self._send_json(200, app.dsh.poll(str(body.get("task_id") or "")))
                elif action == "list":
                    self._send_json(200, app.dsh.list_recent())
                elif action == "cancel":
                    self._send_json(200, app.dsh.cancel(str(body.get("task_id") or "")))
                else:
                    self._send_json(400, {"ok": False, "error": f"未知 action: {action}"})
                return
            self._send_json(404, {"ok": False, "error": "Not found"})

    return Handler


def detect_lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("223.5.5.5", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def build_pairing_uri(url: str, token: str, fallback: str = "") -> str:
    q = f"url={quote(url, safe='')}&token={quote(token, safe='')}"
    if fallback:
        q += f"&fallback={quote(fallback, safe='')}"
    return f"pib://pair-bridge?{q}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Pib Computer & DSH/Codex Bridge")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--bind", default=DEFAULT_BIND)
    parser.add_argument("--print-pair", action="store_true", help="Print pairing link and exit")
    args = parser.parse_args()

    app = BridgeApp()
    lan_url = f"http://{detect_lan_ip()}:{args.port}"
    pair_uri = build_pairing_uri(lan_url, app.token)

    if args.print_pair:
        print(pair_uri)
        return

    server = http.server.ThreadingHTTPServer((args.bind, args.port), make_handler(app))
    append_audit({"action": "bridge_start", "bind": args.bind, "port": args.port}, app.state_dir)
    print(f"Pib Bridge v{VERSION} listening on {lan_url}", flush=True)
    print(f"Detected local agents: {', '.join(detect_agents().keys()) or 'none (python/node/command still active)'}", flush=True)
    print("\nCopy this pairing link into Pib [更多 → 远程电脑与 DSH 桥 → 粘贴配对链接]:", flush=True)
    print(pair_uri, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
