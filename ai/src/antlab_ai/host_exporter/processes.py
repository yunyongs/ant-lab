"""Parse `ps` output and sort ant-lab processes into a fixed set of kinds.

Only the kind and the worktree name leave this module. The command line is matched, never kept:
no argv, no path beyond the worktree directory name, no environment.
"""

from __future__ import annotations

import os
import re
from collections import defaultdict
from dataclasses import dataclass

KINDS = (
    "gradle_daemon",
    "gradle_worker",
    "java_api_local",
    "pytest",
    "uv_or_python_ai",
    "engine_uvicorn",
    "postgres_temp",
    "node_web",
    "other_antlab",
)
# other_antlab is a catch-all (editor helpers and the like), so it never counts as a stale or idle daemon.
WATCHED = frozenset(KINDS) - {"other_antlab"}

STALE_AGE_SECONDS = 2 * 3600
STALE_CPU_PERCENT = 50.0
IDLE_AGE_SECONDS = 3600
IDLE_CPU_PERCENT = 1.0

NO_WORKTREE = "none"
SHARED = "shared"
MAIN = "main"
JOB_TMP = "jobtmp"
OTHER_WORKTREE = "other"
MAX_WORKTREE_LABELS = 60

WORKTREE_NAME = re.compile(r"[A-Za-z0-9._-]{1,64}")
_PS_LINE = re.compile(
    r"^\s*(\d+)\s+(?:(\d+)-)?(?:(\d+):)?(\d+):(\d+)\s+([\d.]+)\s+(\d+)\s+(.+?)\s*$"
)
_PYTEST_NAMES = ("pytest", "py.test")
_SELF = "antlab_ai.host_exporter"


@dataclass(frozen=True)
class Roots:
    repo: str
    worktrees: str
    job_tmp: str


@dataclass(frozen=True)
class Proc:
    pid: int
    age_seconds: int
    cpu_percent: float
    rss_bytes: int
    command: str


@dataclass
class Group:
    count: int = 0
    cpu_percent: float = 0.0
    rss_bytes: int = 0
    max_age_seconds: int = 0
    stale: int = 0
    idle: int = 0


def parse_ps(text: str) -> list[Proc]:
    """Lines of `ps -axo pid=,etime=,pcpu=,rss=,command=`; lines that do not fit are skipped."""
    out: list[Proc] = []
    for line in text.splitlines():
        m = _PS_LINE.match(line)
        if not m:
            continue
        pid, days, hours, minutes, seconds, cpu, rss, command = m.groups()
        age = int(days or 0) * 86400 + int(hours or 0) * 3600 + int(minutes) * 60 + int(seconds)
        out.append(Proc(int(pid), age, float(cpu), int(rss) * 1024, command))
    return out


def _under(root: str, command: str) -> re.Match[str] | None:
    if not root.rstrip("/"):
        return None
    return re.search(re.escape(root.rstrip("/")) + r"(?=[/\s:=\"']|$)", command)


def _worktree(command: str, roots: Roots) -> str | None:
    base = roots.worktrees.rstrip("/")
    named = re.search(re.escape(base) + r"/([^/\s:=\"']+)", command)
    if named:
        return named.group(1) if WORKTREE_NAME.fullmatch(named.group(1)) else OTHER_WORKTREE
    if _under(roots.repo, command):
        return MAIN
    if _under(roots.job_tmp, command):
        return JOB_TMP
    return None


def _runs_module(exe: str, tokens: list[str], names: tuple[str, ...]) -> bool:
    """True when the executable itself is the tool, or is python running it as a module or script."""
    if exe in names:
        return True
    if not exe.startswith("python"):
        return False
    if "-m" in tokens:
        i = tokens.index("-m")
        if tokens[i + 1 : i + 2] and tokens[i + 1] in names:
            return True
    return len(tokens) > 1 and os.path.basename(tokens[1]) in names


def classify(command: str, roots: Roots) -> tuple[str, str] | None:
    """Kind and worktree label of an ant-lab process, or None for any other process."""
    if _SELF in command:
        return None
    tokens = command.split()
    exe = os.path.basename(tokens[0]) if tokens else ""
    where = _worktree(command, roots)
    # The Gradle daemon is shared across checkouts, so it has no worktree of its own.
    if exe == "java" and "org.gradle.launcher.daemon.bootstrap.GradleDaemon" in command:
        return "gradle_daemon", where or SHARED
    if exe == "java" and ("GradleWorkerMain" in command or "Gradle Test Executor" in command):
        return "gradle_worker", where or SHARED
    if where is None:
        return None
    if exe in ("postgres", "pg_ctl", "initdb") and _under(roots.job_tmp, command):
        return "postgres_temp", where
    # Decided by the executable, not by a word anywhere in argv: shell wrappers (`zsh -c ... pytest`),
    # `tail` and node CLIs that merely mention a path fall through to other_antlab.
    if _runs_module(exe, tokens, _PYTEST_NAMES):
        return "pytest", where
    if _runs_module(exe, tokens, ("uvicorn",)) and (
        "antlab_engine" in command or "/engine/" in command
    ):
        return "engine_uvicorn", where
    if exe == "java":
        return "java_api_local", where
    if exe == "node" and "/web/" in command:
        return "node_web", where
    if exe == "uv" or (exe.startswith("python") and ("antlab_ai" in command or "/ai/" in command)):
        return "uv_or_python_ai", where
    return "other_antlab", where


def group_processes(procs: list[Proc], roots: Roots, own_pid: int) -> dict[tuple[str, str], Group]:
    groups: dict[tuple[str, str], Group] = defaultdict(Group)
    names: set[str] = set()
    for proc in procs:
        if proc.pid == own_pid:
            continue
        hit = classify(proc.command, roots)
        if hit is None:
            continue
        kind, worktree = hit
        if worktree not in (SHARED, MAIN, JOB_TMP, OTHER_WORKTREE):
            if worktree not in names and len(names) >= MAX_WORKTREE_LABELS:
                worktree = OTHER_WORKTREE
            else:
                names.add(worktree)
        g = groups[(kind, worktree)]
        g.count += 1
        g.cpu_percent += proc.cpu_percent
        g.rss_bytes += proc.rss_bytes
        g.max_age_seconds = max(g.max_age_seconds, proc.age_seconds)
        if kind in WATCHED:
            if proc.age_seconds > STALE_AGE_SECONDS and proc.cpu_percent > STALE_CPU_PERCENT:
                g.stale += 1
            if proc.age_seconds > IDLE_AGE_SECONDS and proc.cpu_percent < IDLE_CPU_PERCENT:
                g.idle += 1
    # A kind with no process still gets a row, so "none running" reads as 0 and not as missing data.
    for kind in KINDS:
        if not any(k == kind for k, _ in groups):
            groups[(kind, NO_WORKTREE)]
    return dict(groups)
