"""Fake `claude` and `codex` executables for a tmp PATH, plus a gate double. No real CLI is ever run."""

from __future__ import annotations

import json
import os
import stat
import sys
from decimal import Decimal
from pathlib import Path

from antlab_ai.modelport import Message, ModelRequest, SubscriptionRefusal
from antlab_ai.modelport.subscription import CliConfig

FIXTURES = Path(__file__).parent / "fixtures"

_SCRIPT = r"""#!{python}
import json, os, subprocess, sys, time
here = os.path.dirname(os.path.abspath(__file__))
name = os.path.basename(__file__)
argv = sys.argv[1:]
scenarios = json.load(open(os.path.join(here, "scenario.json")))
sc = scenarios.get(name, {{}})
if argv[:1] in (["auth"], ["login"]):
    sc = scenarios.get(name + ":login", sc)
if argv == ["--version"]:
    with open(os.path.join(here, "calls.jsonl"), "a") as log:
        log.write(json.dumps({{"argv": argv, "env": sorted(os.environ)}}) + "\n")
    print("9.9.9 (fake)")
    sys.exit(0)
stdin = b"" if sc.get("skip_stdin") else sys.stdin.buffer.read()
entry = {{
    "argv": argv, "env": sorted(os.environ), "cwd": os.getcwd(), "stdin": stdin.decode("utf-8"),
    "stdin_tty": sys.stdin.isatty(), "pid": os.getpid(), "cwd_listing": sorted(os.listdir(".")),
    "env_values": dict(os.environ),
}}
with open(os.path.join(here, "calls.jsonl"), "a") as log:
    log.write(json.dumps(entry) + "\n")
if sc.get("child") or sc.get("child_ignore_term") or sc.get("child_detached"):
    code = "import time; time.sleep(120)"
    if sc.get("child_ignore_term"):
        code = "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(120)"
    child = subprocess.Popen([sys.executable, "-c", code], start_new_session=bool(sc.get("child_detached")))
    with open(os.path.join(here, "child.pid"), "w") as f:
        f.write(str(child.pid))
if "-o" in argv:
    target = argv[argv.index("-o") + 1]
    if sc.get("last_message_fifo"):
        os.mkfifo(target)
    elif sc.get("last_message_symlink"):
        real = os.path.join(here, "real_message.txt")
        with open(real, "w") as f:
            f.write("linked reply")
        os.symlink(real, target)
    elif sc.get("last_message_size"):
        with open(target, "w") as f:
            f.write("a" * sc["last_message_size"])
    elif sc.get("last_message") is not None:
        with open(target, "w") as f:
            f.write(sc["last_message"])
if sc.get("flood"):
    sys.stdout.write("x" * sc["flood"])
    sys.stdout.flush()
if sc.get("stdout"):
    sys.stdout.write(sc["stdout"])
    sys.stdout.flush()
if sc.get("stderr"):
    sys.stderr.write(sc["stderr"])
    sys.stderr.flush()
if sc.get("sleep"):
    time.sleep(sc["sleep"])
sys.exit(sc.get("exit", 0))
"""


class FakeClis:
    def __init__(self, root: Path):
        self.dir = root / "bin"
        self.dir.mkdir()
        self.work_root = root / "work"
        for name in ("claude", "codex"):
            path = self.dir / name
            path.write_text(_SCRIPT.format(python=sys.executable))
            path.chmod(stat.S_IRWXU)
        self.scenario("claude")
        self.scenario("codex")
        # The login-status subcommands answer from their own scenario (a subscription login).
        self.scenario("claude:login", stdout=json.dumps({"loggedIn": True, "authMethod": "oauth"}))
        self.scenario("codex:login", stdout="Logged in using ChatGPT\n")

    def scenario(self, name: str, **fields: object) -> None:
        path = self.dir / "scenario.json"
        data = json.loads(path.read_text()) if path.exists() else {}
        data[name] = fields
        path.write_text(json.dumps(data))

    def path(self, name: str) -> str:
        return str(self.dir / name)

    def calls(self) -> list[dict]:
        log = self.dir / "calls.jsonl"
        if not log.exists():
            return []
        return [json.loads(line) for line in log.read_text().splitlines()]

    def config(self, name: str, source_env: dict[str, str] | None = None, **extra) -> CliConfig:
        env = {"PATH": str(self.dir), "HOME": "/home/x"} if source_env is None else source_env
        return CliConfig(self.path(name), "9.9.9", self.work_root, extra_env=extra, source_env=env)


class RecordingGate:
    def __init__(self, refuse_with: str | None = None):
        self.refuse_with = refuse_with
        self.checks: list[tuple[str, str]] = []

    def check_attempt(self, route: str, model_id: str) -> None:
        self.checks.append((route, model_id))
        if self.refuse_with is not None:
            raise SubscriptionRefusal(self.refuse_with)


def request(model_id: str = "claude-x", *, system: bool = True, timeout_ms: int = 30_000, **kw):
    messages = (Message("user", '{"turn":1}'),)
    if system:
        messages = (Message("system", "You are an agent."), *messages)
    return ModelRequest(
        model_id=model_id,
        messages=messages,
        max_tokens=1000,
        temperature=Decimal("0.2"),
        seed=None,
        timeout_ms=timeout_ms,
        **kw,
    )


def claude_envelope(text: str = "REPLY", model: str = "claude-x") -> str:
    return json.dumps(
        {
            "type": "result",
            "subtype": "success",
            "is_error": False,
            "result": text,
            "num_turns": 1,
            "total_cost_usd": 0.5,
            "usage": {"input_tokens": 10, "output_tokens": 4},
            "modelUsage": {model: {"outputTokens": 4}},
        }
    )


def codex_stdout(model: str = "gpt-x", *, tool: bool = False) -> str:
    events = [
        {"type": "thread.started", "model": model},
        {"type": "item.completed", "item": {"type": "agent_message"}},
    ]
    if tool:
        events.append({"type": "item.completed", "item": {"type": "command_execution"}})
    events.append({"type": "turn.completed", "usage": {"input_tokens": 9, "output_tokens": 3}})
    return "\n".join(json.dumps(e) for e in events) + "\n"


def alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True
