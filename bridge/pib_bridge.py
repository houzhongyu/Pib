#!/usr/bin/env python3
"""
Pib Cross-Platform Computer & DSH / Codex Bridge (macOS / Linux / Windows).

Zero third-party dependencies (Python 3.10+ standard library only).
Provides authenticated endpoints for the Pib Android app:
- One-tap pairing via `pib://pair-bridge?url=...&token=...`
- Read-only computer & workspace status (`/health`, `POST /status`)
- Per-call user-confirmed Python / Node / optional shell execution (`POST /exec`)
- Asynchronous delegation to local DSH / Codex / Claude CLI agents (`POST /dsh`)

Security boundaries:
- 256-bit secret token in ~/.pib_bridge/token (mode 0600).
- Supports HMAC-SHA256 request signing (`X-Pib-Timestamp`, `X-Pib-Nonce`, `X-Pib-Signature`)
  with ±300s clock-skew check and nonce replay cache so raw tokens never cross LAN HTTP in
  cleartext, while keeping constant-time Bearer token support for local CLI/HTTPS use.
- Default network bind is loopback (`127.0.0.1`) plus detected Tailscale CGNAT IP (`100.64/10`);
  pass `--lan` to listen on private Wi-Fi LAN interfaces. Public source IPs are rejected (403)
  unless `--allow-public-ip` is explicitly set behind an HTTPS reverse proxy.
- Strict realpath workspace allowlist (~/Developer, ~/Desktop, ~/Documents, ~/.pib_bridge,
  plus optional PIB_BRIDGE_ALLOW_DIRS separated by os.pathsep). Note: allowlist scopes working
  directories and `/status` reads; authorized `/exec` code runs with your user account privileges.
- `/status` uses fixed argv arrays (shell=False) — zero command injection surface.
- `/exec` disables arbitrary shell (`mode="command"`) by default unless `--allow-shell` is passed,
  enforces regex-based high-risk command guards, deduplicates `X-Idempotency-Key` within 10 minutes,
  and writes a local audit log (`~/.pib_bridge/audit.jsonl`).
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import http.server
import ipaddress
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

VERSION = "0.3.1"
DEFAULT_PORT = int(os.environ.get("PIB_BRIDGE_PORT", "18792"))
DEFAULT_BIND = os.environ.get("PIB_BRIDGE_BIND", "").strip()
DEFAULT_LAN = os.environ.get("PIB_BRIDGE_LAN", "").strip().lower() in ("1", "true", "yes")
DEFAULT_ALLOW_SHELL = os.environ.get("PIB_BRIDGE_ALLOW_SHELL", "").strip().lower() in ("1", "true", "yes")
DEFAULT_ALLOW_PUBLIC_IP = os.environ.get("PIB_BRIDGE_ALLOW_PUBLIC_IP", "").strip().lower() in ("1", "true", "yes")
STATE_DIR = Path(os.environ.get("PIB_BRIDGE_HOME", str(Path.home() / ".pib_bridge"))).expanduser()

BLOCKED_REGEXES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("sudo", re.compile(r"(?i)(?:^|[\s;|&(`'/\\])sudo(?:\s|$)")),
    ("rm -rf /", re.compile(r"(?i)\brm\s+(?:-[A-Za-z]*[rf][A-Za-z]*\s+|--no-preserve-root\s+){1,4}/(?:\s|$|\*)")),
    ("mkfs", re.compile(r"(?i)\bmkfs(?:\.[A-Za-z0-9_]+)?\b")),
    ("shutdown/reboot", re.compile(r"(?i)\b(?:shutdown|reboot|poweroff|halt)\b")),
    ("launchctl bootout", re.compile(r"(?i)\blaunchctl\s+bootout\b")),
    ("diskutil erase", re.compile(r"(?i)\bdiskutil\s+erase\w*\b")),
    ("csrutil", re.compile(r"(?i)\bcsrutil\b")),
    ("format drive", re.compile(r"(?i)\bformat\s+[a-z]:")),
    ("del system drive", re.compile(r"(?i)\bdel\s+(?:/[a-z]\s+){1,4}[a-z]:\\")),
)

EXTENDED_PATH = os.pathsep.join(
    [
        os.environ.get("PATH", ""),
        str(Path.home() / ".local" / "bin"),
        "/opt/homebrew/bin",
        "/usr/local/bin",
    ]
)

TAILSCALE_NET = ipaddress.ip_network("100.64.0.0/10")


def default_allowed_roots(state_dir: Path = STATE_DIR) -> list[str]:
    roots = [
        str((Path.home() / "Developer").resolve()),
        str((Path.home() / "Desktop").resolve()),
        str((Path.home() / "Documents").resolve()),
        str(state_dir.resolve()),
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
            path.chmod(0o600)
        except OSError:
            pass


def append_audit(entry: dict[str, Any], state_dir: Path = STATE_DIR) -> None:
    try:
        state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        record = {"ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **entry}
        audit_file = state_dir / "audit.jsonl"
        with audit_file.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
        _chmod_600(audit_file)
    except OSError:
        pass


def resolve_allowed_dir(raw_path: str | None, allowed_roots: list[str], state_dir: Path = STATE_DIR) -> Path | None:
    ws_dir = state_dir / "workspace"
    ws_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    dev_dir = Path.home() / "Developer"
    existing_roots = [Path(r) for r in allowed_roots if os.path.isdir(r)]
    if dev_dir.is_dir() and any(_is_within(dev_dir, r) for r in existing_roots):
        default_root = dev_dir
    elif any(_is_within(ws_dir, r) for r in existing_roots):
        default_root = ws_dir
    elif existing_roots:
        default_root = existing_roots[0]
    else:
        default_root = ws_dir
    candidate = Path(raw_path).expanduser() if raw_path else default_root
    if not candidate.is_absolute():
        candidate = default_root / candidate
    try:
        real = candidate.resolve(strict=False)
    except OSError:
        return None
    for root in existing_roots:
        if _is_within(real, root):
            return real
    return None


def _is_within(target: Path, root: Path) -> bool:
    target_real = os.path.realpath(target)
    root_real = os.path.realpath(root)
    return target_real == root_real or target_real.startswith(root_real + os.sep)


def is_private_or_tailscale_ip(raw_ip: str) -> bool:
    cleaned = raw_ip.strip()
    if cleaned.startswith("::ffff:"):
        cleaned = cleaned[7:]
    if "%" in cleaned:
        cleaned = cleaned.split("%", 1)[0]
    try:
        addr = ipaddress.ip_address(cleaned)
    except ValueError:
        return False
    if addr.is_loopback or addr.is_private or addr.is_link_local:
        return True
    if isinstance(addr, ipaddress.IPv4Address) and addr in TAILSCALE_NET:
        return True
    return False


def detect_agents() -> dict[str, str]:
    found: dict[str, str] = {}
    custom_dsh = os.environ.get("PIB_DSH_CMD", "").strip()
    if custom_dsh:
        found["dsh"] = custom_dsh
    else:
        dsh_bin = shutil.which("dsh", path=EXTENDED_PATH)
        if dsh_bin:
            found["dsh"] = dsh_bin
    for name in ("codex", "claude"):
        b = shutil.which(name, path=EXTENDED_PATH)
        if b:
            found[name] = b
    return found


def estimate_memory_gb() -> tuple[float, float]:
    sysname = platform.system()
    try:
        if sysname == "Darwin":
            total = int(subprocess.check_output(["/usr/sbin/sysctl", "-n", "hw.memsize"], text=True, timeout=3).strip())
            mp = subprocess.check_output(["/usr/bin/memory_pressure", "-Q"], text=True, timeout=4)
            m = re.search(r"free percentage:\s*(\d+)%", mp)
            pct = int(m.group(1)) if m else 50
            total_gb = round(total / (1024**3), 2)
            return round(total_gb * pct / 100.0, 2), total_gb
        if sysname == "Linux" and os.path.exists("/proc/meminfo"):
            info: dict[str, int] = {}
            with open("/proc/meminfo", encoding="utf-8") as f:
                for line in f:
                    parts = line.split(":")
                    if len(parts) == 2:
                        info[parts[0].strip()] = int(parts[1].strip().split()[0]) * 1024
            tot = info.get("MemTotal", 0)
            avail = info.get("MemAvailable", info.get("MemFree", 0))
            return round(avail / (1024**3), 2), round(tot / (1024**3), 2)
        if sysname == "Windows":
            import ctypes

            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("sullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            stat = MEMORYSTATUSEX()
            stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat))  # type: ignore[attr-defined]
            return round(stat.ullAvailPhys / (1024**3), 2), round(stat.ullTotalPhys / (1024**3), 2)
    except Exception:
        pass
    return 0.0, 0.0


def build_overview(target_dir: Path, allow_shell: bool = False) -> dict[str, Any]:
    avail_gb, total_gb = estimate_memory_gb()
    try:
        disk = shutil.disk_usage(str(target_dir if target_dir.exists() else Path.home()))
        free_disk_gb = round(disk.free / (1024**3), 2)
    except OSError:
        free_disk_gb = 0.0
    git_summary = None
    git_bin = shutil.which("git", path=EXTENDED_PATH)
    if git_bin and (target_dir / ".git").exists():
        try:
            branch = subprocess.check_output([git_bin, "-C", str(target_dir), "rev-parse", "--abbrev-ref", "HEAD"], text=True, timeout=4).strip()
            dirty = len(subprocess.check_output([git_bin, "-C", str(target_dir), "status", "--porcelain"], text=True, timeout=4).splitlines())
            git_summary = f"{branch} ({dirty} dirty)"
        except Exception:
            git_summary = None
    return {
        "ok": True,
        "bridge": "pib-bridge",
        "version": VERSION,
        "hostname": socket.gethostname(),
        "platform": f"{platform.system()} {platform.machine()}",
        "available_ram_gb": avail_gb,
        "total_ram_gb": total_gb,
        "free_disk_gb": free_disk_gb,
        "cwd": str(target_dir),
        "git": git_summary,
        "agents": list(detect_agents().keys()),
        "shell_enabled": allow_shell,
    }


def handle_readonly_status(payload: dict[str, Any], allowed_roots: list[str], state_dir: Path = STATE_DIR, allow_shell: bool = False) -> dict[str, Any]:
    kind = str(payload.get("kind") or "overview").strip().lower()
    raw_dir = payload.get("cwd")
    target = resolve_allowed_dir(raw_path=raw_dir if isinstance(raw_dir, str) else None, allowed_roots=allowed_roots, state_dir=state_dir)
    if target is None or not target.exists():
        return {"ok": False, "error": "目录不在允许的白名单内或不存在"}

    if kind == "overview":
        return build_overview(target, allow_shell=allow_shell)

    if kind == "git":
        git_bin = shutil.which("git", path=EXTENDED_PATH)
        if not git_bin:
            return {"ok": False, "error": "电脑未安装 git"}
        try:
            st = subprocess.check_output([git_bin, "-C", str(target), "status", "-sb"], text=True, stderr=subprocess.STDOUT, timeout=6)
            lg = subprocess.check_output([git_bin, "-C", str(target), "log", "-n", "5", "--oneline"], text=True, stderr=subprocess.STDOUT, timeout=6)
            return {"ok": True, "cwd": str(target), "status": st.strip()[:2000], "recent_commits": lg.strip()[:1500]}
        except subprocess.CalledProcessError as exc:
            return {"ok": False, "error": (exc.output or str(exc)).strip()[:600]}

    if kind == "files":
        entries: list[str] = []
        try:
            for item in sorted(target.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))[:60]:
                suffix = "/" if item.is_dir() else f" ({item.stat().st_size} B)"
                entries.append(item.name + suffix)
            return {"ok": True, "cwd": str(target), "entries": entries}
        except OSError as exc:
            return {"ok": False, "error": str(exc)}

    if kind == "tail_log":
        rel = str(payload.get("file") or "").strip()
        file_path = resolve_allowed_dir(str(target / rel), allowed_roots, state_dir) if rel else None
        if file_path is None or not file_path.is_file():
            return {"ok": False, "error": "日志文件不存在或不在允许目录内"}
        try:
            lines = file_path.read_text(encoding="utf-8", errors="replace").splitlines()[-40:]
            return {"ok": True, "file": str(file_path), "tail": "\n".join(lines)[-4000:]}
        except OSError as exc:
            return {"ok": False, "error": str(exc)}

    return {"ok": False, "error": f"未知的状态查询类型: {kind}"}


def check_blocked_code(code: str) -> str | None:
    for label, pattern in BLOCKED_REGEXES:
        if pattern.search(code):
            return label
    return None


def handle_approved_exec(
    payload: dict[str, Any],
    allowed_roots: list[str],
    state_dir: Path = STATE_DIR,
    allow_shell: bool = False,
) -> dict[str, Any]:
    mode = str(payload.get("mode") or "python").strip().lower()
    code = str(payload.get("code") or "").strip()
    raw_dir = payload.get("cwd")
    try:
        timeout_sec = min(60, max(2, int(payload.get("timeout") or 25)))
    except (TypeError, ValueError):
        timeout_sec = 25

    if not code:
        return {"ok": False, "error": "没有要执行的代码或命令"}
    if len(code) > 24000:
        return {"ok": False, "error": "代码长度超过安全上限 (24000 字符)"}

    hit = check_blocked_code(code)
    if hit:
        append_audit({"action": "exec_blocked", "mode": mode, "reason": hit}, state_dir)
        return {"ok": False, "error": f"安全策略拦截了高危指令片段: {hit}"}

    if mode == "command" and not allow_shell:
        append_audit({"action": "exec_shell_disabled", "mode": mode}, state_dir)
        return {
            "ok": False,
            "error": "电脑桥默认关闭终端 Shell 模式（仍可用 python / node 模式）。若确需开启终端命令，请在电脑端以 python3 bridge/pib_bridge.py --allow-shell 启动。",
        }

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


class IdempotencyCache:
    """Single-flight + 10-minute TTL response deduplicator for non-idempotent POSTs."""

    def __init__(self, ttl_sec: float = 600.0, max_entries: int = 512):
        self.ttl_sec = ttl_sec
        self.max_entries = max_entries
        self._lock = threading.Lock()
        self._done: dict[str, tuple[float, dict[str, Any]]] = {}
        self._inflight: dict[str, threading.Event] = {}

    def run(self, scope: str, raw_key: str | None, fn: Any) -> dict[str, Any]:
        key = (raw_key or "").strip()
        if not key or len(key) > 128 or not re.fullmatch(r"[A-Za-z0-9_\-:.]+", key):
            return fn()
        full_key = f"{scope}:{key}"
        now = time.time()
        wait_event: threading.Event | None = None
        with self._lock:
            self._prune(now)
            cached = self._done.get(full_key)
            if cached is not None:
                return dict(cached[1])
            existing = self._inflight.get(full_key)
            if existing is not None:
                wait_event = existing
            else:
                ev = threading.Event()
                self._inflight[full_key] = ev

        if wait_event is not None:
            wait_event.wait(timeout=35.0)
            with self._lock:
                cached = self._done.get(full_key)
                if cached is not None:
                    return dict(cached[1])
            return {"ok": False, "error": "相同请求正在电脑端执行中，请稍后查询状态"}

        try:
            result = fn()
            with self._lock:
                self._done[full_key] = (time.time(), dict(result))
            return result
        finally:
            with self._lock:
                ev = self._inflight.pop(full_key, None)
                if ev is not None:
                    ev.set()

    def _prune(self, now: float) -> None:
        expired = [k for k, (ts, _) in self._done.items() if now - ts > self.ttl_sec]
        for k in expired:
            self._done.pop(k, None)
        while len(self._done) > self.max_entries:
            oldest = next(iter(self._done))
            self._done.pop(oldest, None)


class ReplayGuard:
    """Rejects replayed HMAC nonces within the ±300s timestamp window and throttles 401 bursts."""

    def __init__(self, window_sec: float = 300.0):
        self.window_sec = window_sec
        self._lock = threading.Lock()
        self._nonces: dict[str, float] = {}
        self._failures: dict[str, list[float]] = {}

    def is_throttled(self, ip: str) -> bool:
        now = time.time()
        with self._lock:
            recent = [t for t in self._failures.get(ip, []) if now - t < 60.0]
            self._failures[ip] = recent
            return len(recent) >= 12

    def record_failure(self, ip: str) -> None:
        now = time.time()
        with self._lock:
            recent = [t for t in self._failures.get(ip, []) if now - t < 60.0]
            recent.append(now)
            self._failures[ip] = recent

    def accept_nonce(self, nonce: str, ts_val: int) -> bool:
        now = time.time()
        if abs(now - ts_val) > self.window_sec:
            return False
        if not re.fullmatch(r"[A-Za-z0-9_\-]{12,80}", nonce):
            return False
        with self._lock:
            cutoff = now - (self.window_sec * 2 + 30)
            stale = [n for n, seen in self._nonces.items() if seen < cutoff]
            for n in stale:
                self._nonces.pop(n, None)
            if nonce in self._nonces:
                return False
            self._nonces[nonce] = now
            return True


def compute_hmac_signature(token: str, timestamp: str, nonce: str, method: str, path: str, body: bytes) -> str:
    body_sha = hashlib.sha256(body).hexdigest()
    canonical = f"{timestamp}\n{nonce}\n{method.upper()}\n{path}\n{body_sha}"
    digest = hmac.new(token.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"sha256={digest}"


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

        try:
            wait_sec = min(25.0, max(0.0, float(payload.get("wait_seconds") or 0.0)))
        except (TypeError, ValueError):
            wait_sec = 0.0
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
    def __init__(
        self,
        state_dir: Path = STATE_DIR,
        allowed_roots: list[str] | None = None,
        runner_override: list[str] | None = None,
        allow_shell: bool = DEFAULT_ALLOW_SHELL,
        allow_public_ip: bool = DEFAULT_ALLOW_PUBLIC_IP,
    ):
        self.state_dir = state_dir
        self.token = load_or_create_token(state_dir)
        self.allowed_roots = allowed_roots or default_allowed_roots(state_dir)
        self.allow_shell = allow_shell
        self.allow_public_ip = allow_public_ip
        self.dsh = DshTaskManager(state_dir, self.allowed_roots, runner_override=runner_override)
        self.idempotency = IdempotencyCache()
        self.replay = ReplayGuard()


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

        def _check_source_ip(self) -> bool:
            client_ip = self.client_address[0] if self.client_address else ""
            if app.allow_public_ip or is_private_or_tailscale_ip(client_ip):
                return True
            append_audit({"action": "ip_rejected", "ip": client_ip}, app.state_dir)
            self._send_json(403, {"ok": False, "error": "安全拦截：拒绝公网源 IP 直连"})
            return False

        def _read_raw_body(self) -> bytes | None:
            raw_len = self.headers.get("Content-Length")
            if not raw_len:
                return b""
            try:
                length = int(raw_len)
            except ValueError:
                self._send_json(400, {"ok": False, "error": "无效的 Content-Length"})
                return None
            if length < 0:
                self._send_json(400, {"ok": False, "error": "无效的 Content-Length"})
                return None
            if length > 256 * 1024:
                self._send_json(413, {"ok": False, "error": "请求体过大"})
                return None
            return self.rfile.read(length) if length > 0 else b""

        def _check_auth(self, body_bytes: bytes) -> bool:
            client_ip = self.client_address[0] if self.client_address else "unknown"
            if app.replay.is_throttled(client_ip):
                self._send_json(429, {"ok": False, "error": "鉴权失败次数过多，请 1 分钟后再试"})
                return False

            sig = (self.headers.get("X-Pib-Signature") or "").strip()
            ts_str = (self.headers.get("X-Pib-Timestamp") or "").strip()
            nonce = (self.headers.get("X-Pib-Nonce") or "").strip()
            auth = self.headers.get("Authorization") or ""
            if sig or ts_str or nonce:
                try:
                    ts_val = int(ts_str)
                except ValueError:
                    app.replay.record_failure(client_ip)
                    self._send_json(401, {"ok": False, "error": "无效的签名时间戳"})
                    return False
                expected = compute_hmac_signature(app.token, ts_str, nonce, self.command, self.path, body_bytes)
                if hmac.compare_digest(sig, expected):
                    if not app.replay.accept_nonce(nonce, ts_val):
                        app.replay.record_failure(client_ip)
                        self._send_json(401, {"ok": False, "error": "请求已过期或重复发送（防重放拦截）"})
                        return False
                    return True
                # Allow fallback to Bearer ONLY when an HTTPS reverse proxy rewrote request path
                if not (auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), app.token)):
                    app.replay.record_failure(client_ip)
                    self._send_json(401, {"ok": False, "error": "电脑桥接签名不匹配"})
                    return False
                return True
            if not auth.startswith("Bearer "):
                app.replay.record_failure(client_ip)
                self._send_json(401, {"ok": False, "error": "缺少鉴权签名或 Bearer 令牌"})
                return False
            if not hmac.compare_digest(auth[7:].strip(), app.token):
                app.replay.record_failure(client_ip)
                self._send_json(401, {"ok": False, "error": "电脑桥接令牌不匹配"})
                return False
            return True

        def do_GET(self) -> None:
            if not self._check_source_ip():
                return
            if not self._check_auth(b""):
                return
            if self.path.startswith("/health"):
                default_dir = resolve_allowed_dir(None, app.allowed_roots, app.state_dir) or Path.home()
                self._send_json(200, build_overview(default_dir, allow_shell=app.allow_shell))
                return
            self._send_json(404, {"ok": False, "error": "Not found"})

        def do_POST(self) -> None:
            if not self._check_source_ip():
                return
            raw_body = self._read_raw_body()
            if raw_body is None:
                return
            if not self._check_auth(raw_body):
                return
            if raw_body:
                try:
                    parsed = json.loads(raw_body.decode("utf-8"))
                    body = parsed if isinstance(parsed, dict) else {}
                except Exception:
                    self._send_json(400, {"ok": False, "error": "无效 JSON"})
                    return
            else:
                body = {}

            idem_key = self.headers.get("X-Idempotency-Key")
            if self.path == "/status":
                self._send_json(200, handle_readonly_status(body, app.allowed_roots, app.state_dir, allow_shell=app.allow_shell))
                return
            if self.path == "/exec":
                res = app.idempotency.run(
                    "/exec",
                    idem_key,
                    lambda: handle_approved_exec(body, app.allowed_roots, app.state_dir, allow_shell=app.allow_shell),
                )
                self._send_json(200, res)
                return
            if self.path == "/dsh":
                action = str(body.get("action") or "submit").strip().lower()
                if action == "submit":
                    res = app.idempotency.run("/dsh:submit", idem_key, lambda: app.dsh.submit(body))
                    self._send_json(200, res)
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
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127."):
                return ip
    except Exception:
        pass
    return "127.0.0.1"


def detect_tailscale_ip() -> str | None:
    # 1. Try Tailscale MagicDNS / quad-100 route table lookup without sending packets
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("100.100.100.100", 80))
            ip = s.getsockname()[0]
            if ipaddress.ip_address(ip) in TAILSCALE_NET:
                return ip
    except Exception:
        pass
    # 2. Fallback to `tailscale ip -4` CLI if installed
    ts_bin = shutil.which("tailscale", path=EXTENDED_PATH) or "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
    if ts_bin and os.path.exists(ts_bin):
        try:
            out = subprocess.check_output([ts_bin, "ip", "-4"], text=True, stderr=subprocess.DEVNULL, timeout=2)
            for line in out.splitlines():
                cand = line.strip()
                if cand and ipaddress.ip_address(cand) in TAILSCALE_NET:
                    return cand
        except Exception:
            pass
    return None


def resolve_bind_hosts(explicit_bind: str, lan_enabled: bool, tailscale_ip: str | None) -> list[str]:
    if explicit_bind:
        return [explicit_bind]
    if lan_enabled:
        return ["0.0.0.0"]
    hosts = ["127.0.0.1"]
    if tailscale_ip and tailscale_ip not in hosts:
        hosts.append(tailscale_ip)
    return hosts


def build_pairing_uri(url: str, token: str, fallback: str = "") -> str:
    q = f"url={quote(url, safe='')}&token={quote(token, safe='')}"
    if fallback:
        q += f"&fallback={quote(fallback, safe='')}"
    return f"pib://pair-bridge?{q}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Pib Computer & DSH/Codex Bridge")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--bind", default=DEFAULT_BIND, help="Explicit bind IP (overrides default loopback+Tailscale)")
    parser.add_argument("--lan", action="store_true", default=DEFAULT_LAN, help="Also listen on private Wi-Fi LAN interfaces (0.0.0.0)")
    parser.add_argument("--allow-shell", action="store_true", default=DEFAULT_ALLOW_SHELL, help="Enable /exec mode=command shell execution")
    parser.add_argument("--allow-public-ip", action="store_true", default=DEFAULT_ALLOW_PUBLIC_IP, help="Allow non-private client IPs (only behind HTTPS proxy)")
    parser.add_argument("--print-pair", action="store_true", help="Print pairing link and exit")
    args = parser.parse_args()

    app = BridgeApp(allow_shell=args.allow_shell, allow_public_ip=args.allow_public_ip)
    ts_ip = detect_tailscale_ip()
    lan_ip = detect_lan_ip()
    bind_hosts = resolve_bind_hosts(args.bind, args.lan, ts_ip)

    if args.lan or (args.bind and args.bind not in ("127.0.0.1", "localhost")):
        primary_ip = lan_ip if args.bind in ("", "0.0.0.0") else args.bind
        primary_url = f"http://{primary_ip}:{args.port}"
        fallback_url = f"http://{ts_ip}:{args.port}" if ts_ip and ts_ip != primary_ip else ""
    elif ts_ip:
        primary_url = f"http://{ts_ip}:{args.port}"
        fallback_url = ""
    else:
        primary_url = f"http://127.0.0.1:{args.port}"
        fallback_url = ""

    pair_uri = build_pairing_uri(primary_url, app.token, fallback=fallback_url)

    if args.print_pair:
        print(pair_uri)
        return

    handler_cls = make_handler(app)
    servers: list[http.server.ThreadingHTTPServer] = []
    for host in bind_hosts:
        try:
            srv = http.server.ThreadingHTTPServer((host, args.port), handler_cls)
            servers.append(srv)
        except OSError as exc:
            if host == "127.0.0.1":
                raise
            print(f"Warning: could not bind Tailscale address {host}:{args.port} ({exc})", file=sys.stderr)

    for srv in servers[1:]:
        threading.Thread(target=srv.serve_forever, daemon=True).start()

    bound_desc = ", ".join(f"{s.server_address[0]}:{s.server_address[1]}" for s in servers)
    append_audit({"action": "bridge_start", "bound": bound_desc, "allow_shell": args.allow_shell}, app.state_dir)
    print(f"Pib Bridge v{VERSION} listening on [{bound_desc}]", flush=True)
    print(f"Detected local agents: {', '.join(detect_agents().keys()) or 'none (python/node active)'} | shell={'on' if args.allow_shell else 'off'}", flush=True)
    if not args.lan and not ts_ip and not args.bind:
        print("Note: bound to 127.0.0.1 only. For same-Wi-Fi phone access without Tailscale, restart with: python3 bridge/pib_bridge.py --lan", flush=True)
    print("\nCopy this pairing link into Pib [更多 → 远程电脑与 DSH 桥 → 粘贴配对链接]:", flush=True)
    print(pair_uri, flush=True)
    try:
        servers[0].serve_forever()
    except KeyboardInterrupt:
        for srv in servers:
            srv.shutdown()


if __name__ == "__main__":
    main()
