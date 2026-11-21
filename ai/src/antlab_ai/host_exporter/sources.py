"""Run the few host commands and parse their output. Parsers are pure so tests feed them fixtures."""

from __future__ import annotations

import json
import os
import re
import subprocess
from collections.abc import Callable, Sequence
from dataclasses import dataclass


class SourceError(Exception):
    """A command is missing, failed or timed out; the source then reports ok=0 and no series."""


Runner = Callable[[Sequence[str], float], str]

_SAFE_ENV = {"LC_ALL": "C", "GIT_OPTIONAL_LOCKS": "0", "GIT_TERMINAL_PROMPT": "0"}


def run_command(argv: Sequence[str], timeout: float) -> str:
    try:
        done = subprocess.run(
            list(argv),
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
            stdin=subprocess.DEVNULL,
            env={**os.environ, **_SAFE_ENV},
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise SourceError(type(exc).__name__) from exc
    if done.returncode != 0:
        raise SourceError(f"exit {done.returncode}")
    return done.stdout


# ---- docker ------------------------------------------------------------------------------------

SERVICE_LABEL = re.compile(r"[a-z0-9][a-z0-9_.-]{0,40}")
OTHER_LEFTOVER = "other_leftover_running"
_UNITS = {
    "b": 1,
    "kb": 1000,
    "mb": 1000**2,
    "gb": 1000**3,
    "tb": 1000**4,
    "kib": 1024,
    "mib": 1024**2,
    "gib": 1024**3,
    "tib": 1024**4,
}
_UP = re.compile(r"^Up (.+?)(?: \(.*\))?$")
_SPAN = {"second": 1, "minute": 60, "hour": 3600, "day": 86400, "week": 604800, "month": 2592000}


@dataclass(frozen=True)
class Container:
    name: str
    running: bool
    project: str
    service: str
    testcontainers: bool
    up_seconds: int


def _label(labels: str, key: str) -> str:
    m = re.search(r"(?:^|,)" + re.escape(key) + r"=([^,]*)", labels)
    return m.group(1) if m else ""


def parse_up_seconds(status: str) -> int:
    """Approximate: Docker prints 'Up 3 hours', 'Up About an hour', 'Up Less than a second'."""
    m = _UP.match(status.strip())
    if not m:
        return 0
    text = m.group(1).lower()
    if text.startswith("less than"):
        return 0
    number = re.match(r"(?:about )?(a|an|\d+) ([a-z]+?)s?$", text)
    if not number or number.group(2) not in _SPAN:
        return 0
    count = 1 if number.group(1) in ("a", "an") else int(number.group(1))
    return count * _SPAN[number.group(2)]


def parse_docker_ps(text: str) -> list[Container]:
    out: list[Container] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError as exc:
            raise SourceError("docker ps output") from exc
        if not isinstance(row, dict):
            raise SourceError("docker ps output")
        labels = str(row.get("Labels") or "")
        running = row.get("State") == "running"
        out.append(
            Container(
                name=str(row.get("Names") or ""),
                running=running,
                project=_label(labels, "com.docker.compose.project"),
                service=_label(labels, "com.docker.compose.service"),
                testcontainers=_label(labels, "org.testcontainers") == "true",
                up_seconds=parse_up_seconds(str(row.get("Status") or "")) if running else 0,
            )
        )
    return out


def parse_size(text: str) -> int:
    m = re.fullmatch(r"\s*([\d.]+)\s*([A-Za-z]+)\s*", text)
    if not m or m.group(2).lower() not in _UNITS:
        raise SourceError("size")
    return int(float(m.group(1)) * _UNITS[m.group(2).lower()])


def parse_docker_stats(text: str) -> dict[str, tuple[float, int, int]]:
    """Container name to (cpu percent, memory bytes, memory limit bytes)."""
    out: dict[str, tuple[float, int, int]] = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            if str(row["CPUPerc"]).strip() == "--" or "--" in str(row["MemUsage"]):
                continue  # a container that is starting or restarting has no numbers yet
            used, limit = str(row["MemUsage"]).split("/")
            cpu = float(str(row["CPUPerc"]).strip().rstrip("%"))
            out[str(row["Name"])] = (cpu, parse_size(used), parse_size(limit))
        except (ValueError, KeyError, TypeError) as exc:
            raise SourceError("docker stats output") from exc
    return out


@dataclass
class ServiceUse:
    running: int = 0
    stopped: int = 0
    cpu_percent: float = 0.0
    mem_bytes: int = 0
    mem_limit_bytes: int = 0
    up_seconds: int = 0


def summarize_containers(
    containers: list[Container],
    stats: dict[str, tuple[float, int, int]] | None,
    project: str,
) -> tuple[dict[str, ServiceUse], int]:
    """Per compose service of the project; everything else that runs is one leftover bucket."""
    services: dict[str, ServiceUse] = {}
    running_testcontainers = 0
    for c in containers:
        if c.testcontainers and c.running:
            running_testcontainers += 1
        if c.project == project and SERVICE_LABEL.fullmatch(c.service):
            key = c.service
        elif c.running:
            key = OTHER_LEFTOVER
        else:
            continue
        use = services.setdefault(key, ServiceUse())
        if c.running:
            use.running += 1
            use.up_seconds = max(use.up_seconds, c.up_seconds)
            if stats is not None and c.name in stats:
                cpu, mem, limit = stats[c.name]
                use.cpu_percent += cpu
                use.mem_bytes += mem
                use.mem_limit_bytes += limit
        else:
            use.stopped += 1
    services.setdefault(OTHER_LEFTOVER, ServiceUse())
    return services, running_testcontainers


# ---- git ---------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Worktree:
    path: str
    head: str
    branch: str


def parse_worktree_list(text: str) -> list[Worktree]:
    out: list[Worktree] = []
    for block in text.strip().split("\n\n"):
        fields: dict[str, str] = {}
        for line in block.splitlines():
            key, _, value = line.partition(" ")
            fields[key] = value
        if "worktree" in fields:
            branch = fields.get("branch", "").removeprefix("refs/heads/")
            out.append(Worktree(fields["worktree"], fields.get("HEAD", ""), branch))
    return out


def parse_branch_names(text: str) -> set[str]:
    return {line.strip() for line in text.splitlines() if line.strip()}


def parse_status_count(text: str) -> int:
    return sum(1 for line in text.splitlines() if line.strip())


def parse_commit_time(text: str) -> int:
    try:
        return int(text.strip())
    except ValueError as exc:
        raise SourceError("commit time") from exc


def parse_du(text: str) -> dict[str, int]:
    """`du -sk` lines: kilobytes, tab, path."""
    out: dict[str, int] = {}
    for line in text.splitlines():
        size, _, path = line.partition("\t")
        if size.isdigit() and path:
            out[path] = int(size) * 1024
    return out


# ---- slots -------------------------------------------------------------------------------------


def slot_counts(slots_dir: str, pid_alive: Callable[[int], bool]) -> tuple[int, int]:
    """(held, stale): slot directories whose recorded pid is alive, and those whose pid is gone."""
    held = stale = 0
    try:
        entries = sorted(os.scandir(slots_dir), key=lambda e: e.name)
    except OSError as exc:
        raise SourceError("slots dir") from exc
    for entry in entries:
        if not entry.is_dir(follow_symlinks=False):
            continue
        try:
            with open(os.path.join(entry.path, "pid"), encoding="ascii") as handle:
                pid = int(handle.read().strip())
        except (OSError, ValueError):
            stale += 1
            continue
        if pid_alive(pid):
            held += 1
        else:
            stale += 1
    return held, stale
