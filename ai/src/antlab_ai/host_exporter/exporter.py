"""Collect host resource numbers for ant-lab and render them in the Prometheus text format.

Runs on the host (a container cannot see host processes). Stdlib only. Nothing here writes or
kills anything; git runs with optional locks off so it never blocks the owner's own git commands.
One scrape costs one `ps`, one `docker ps -a`, one `docker stats --no-stream`, one
`git worktree list` and one `git log -1` per worktree; the whole result is cached for 15 s, and
the heavier git answers (merged branches, uncommitted counts) for 10 minutes.
"""

from __future__ import annotations

import os
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path

from antlab_ai.ledger_exporter.exporter import Series, render

from .processes import (
    MAX_WORKTREE_LABELS,
    Roots,
    group_processes,
    parse_ps,
)
from .processes import (
    WORKTREE_NAME as _WORKTREE_NAME,
)
from .sources import (
    Runner,
    SourceError,
    parse_branch_names,
    parse_commit_time,
    parse_docker_ps,
    parse_docker_stats,
    parse_du,
    parse_status_count,
    parse_worktree_list,
    run_command,
    slot_counts,
    summarize_containers,
)

CACHE_SECONDS = 15.0
SLOW_CACHE_SECONDS = 600.0
SIZE_CACHE_SECONDS = 3600.0
# Per command, kept under Prometheus' 20 s scrape timeout even when two commands of one source are slow.
PS_TIMEOUT = 5.0
DOCKER_PS_TIMEOUT = 5.0
DOCKER_STATS_TIMEOUT = 8.0
GIT_TIMEOUT = 5.0
SOURCES = ("ps", "docker", "docker_stats", "git", "slots")


@dataclass(frozen=True)
class Config:
    repo: str
    worktrees: str
    job_tmp: str
    slots_dir: str
    compose_project: str = "antlab"
    sizes: bool = False

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> Config:
        repo = env.get("ANTLAB_HOST_REPO") or str(Path(__file__).resolve().parents[4])
        home = Path(env.get("HOME") or Path.home())
        config = cls(
            repo=repo,
            worktrees=env.get("ANTLAB_HOST_WORKTREES") or str(home / "orca/workspaces/ant-lab"),
            job_tmp=env.get("ANTLAB_HOST_JOB_TMP", ""),
            slots_dir=env.get("ANTLAB_HOST_SLOTS_DIR", ""),
            compose_project=env.get("ANTLAB_HOST_COMPOSE_PROJECT") or "antlab",
            sizes=env.get("ANTLAB_HOST_SIZES") == "1",
        )
        for name in ("repo", "worktrees", "job_tmp", "slots_dir"):
            value = getattr(config, name)
            # job_tmp and slots_dir are optional: unset turns that part off.
            if value and not os.path.isabs(value):
                raise ValueError(f"{name} must be an absolute path")
        return config


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


class HostCollector:
    def __init__(
        self,
        config: Config,
        run: Runner = run_command,
        clock: Callable[[], float] = time.monotonic,
        wall: Callable[[], float] = time.time,
        pid_alive: Callable[[int], bool] = _pid_alive,
        own_pid: int | None = None,
    ) -> None:
        self.config = config
        self._run = run
        self._clock = clock
        self._wall = wall
        self._pid_alive = pid_alive
        self._own_pid = os.getpid() if own_pid is None else own_pid
        self._lock = threading.Lock()
        self._cached: tuple[float, str] | None = None
        self._slow: dict[str, tuple[float, object]] = {}

    def exposition(self) -> str:
        with self._lock:
            now = self._clock()
            if self._cached is not None and now - self._cached[0] < CACHE_SECONDS:
                return self._cached[1]
            text = render(self.collect())
            self._cached = (self._clock(), text)
            return text

    def _slow_get(self, key: str, ttl: float, fn: Callable[[], object]) -> object:
        now = self._clock()
        hit = self._slow.get(key)
        if hit is not None and now - hit[0] < ttl:
            return hit[1]
        value = fn()
        self._slow[key] = (now, value)
        return value

    def _timed(self, name: str, meta: Series, fn: Callable[[], object]) -> object | None:
        start = self._clock()
        try:
            value: object | None = fn()
            ok = 1
        except (SourceError, OSError, ValueError):
            value, ok = None, 0
        meta.append(("antlab_host_source_ok", {"source": name}, ok))
        meta.append(
            ("antlab_host_collect_seconds", {"source": name}, round(self._clock() - start, 3))
        )
        return value

    def collect(self) -> Series:
        cfg = self.config
        meta: Series = []
        body: Series = []
        roots = Roots(cfg.repo, cfg.worktrees, cfg.job_tmp)

        procs = self._timed("ps", meta, lambda: parse_ps(self._ps()))
        if procs is not None:
            body += self._process_series(procs, roots)

        containers = self._timed("docker", meta, lambda: parse_docker_ps(self._docker_ps()))
        stats = None
        if containers is not None:
            stats = self._timed(
                "docker_stats", meta, lambda: parse_docker_stats(self._docker_stats())
            )
            body += self._container_series(containers, stats)
        else:
            meta.append(("antlab_host_source_ok", {"source": "docker_stats"}, 0))

        git_body = self._timed("git", meta, self._git_series)
        if git_body is not None:
            body += git_body  # type: ignore[operator]

        slots = None
        if cfg.slots_dir:
            slots = self._timed("slots", meta, lambda: slot_counts(cfg.slots_dir, self._pid_alive))
        if slots is not None:
            held, stale = slots  # type: ignore[misc]
            body += [
                ("antlab_host_slots_held", {}, held),
                ("antlab_host_slots_stale", {}, stale),
            ]
        return body + meta

    # ---- processes ----

    def _ps(self) -> str:
        return self._run(["ps", "-axwwo", "pid=,etime=,pcpu=,rss=,command="], PS_TIMEOUT)

    def _process_series(self, procs: list, roots: Roots) -> Series:
        out: Series = []
        for (kind, worktree), g in sorted(group_processes(procs, roots, self._own_pid).items()):
            labels = {"kind": kind, "worktree": worktree}
            out += [
                ("antlab_host_process_count", labels, g.count),
                ("antlab_host_process_cpu_percent", labels, round(g.cpu_percent, 1)),
                ("antlab_host_process_rss_bytes", labels, g.rss_bytes),
                ("antlab_host_process_max_age_seconds", labels, g.max_age_seconds),
                ("antlab_host_process_stale", labels, g.stale),
                ("antlab_host_process_idle", labels, g.idle),
            ]
        return out

    # ---- containers ----

    def _docker_ps(self) -> str:
        return self._run(["docker", "ps", "-a", "--format", "{{json .}}"], DOCKER_PS_TIMEOUT)

    def _docker_stats(self) -> str:
        return self._run(
            ["docker", "stats", "--no-stream", "--format", "{{json .}}"], DOCKER_STATS_TIMEOUT
        )

    def _container_series(self, containers: list, stats: object | None) -> Series:
        services, testcontainers = summarize_containers(
            containers,
            stats,  # type: ignore[arg-type]
            self.config.compose_project,
        )
        out: Series = [("antlab_host_testcontainers_running", {}, testcontainers)]
        for service, use in sorted(services.items()):
            labels = {"service": service}
            out += [
                ("antlab_host_container_running", labels, use.running),
                ("antlab_host_container_stopped", labels, use.stopped),
                ("antlab_host_container_up_seconds", labels, use.up_seconds),
            ]
            if stats is not None:
                out += [
                    ("antlab_host_container_cpu_percent", labels, round(use.cpu_percent, 2)),
                    ("antlab_host_container_mem_bytes", labels, use.mem_bytes),
                    ("antlab_host_container_mem_limit_bytes", labels, use.mem_limit_bytes),
                ]
        return out

    # ---- worktrees ----

    def _git(self, *args: str, cwd: str) -> str:
        return self._run(["git", "--no-optional-locks", "-C", cwd, *args], GIT_TIMEOUT)

    def _worktree_name(self, path: str) -> str | None:
        base = self.config.worktrees.rstrip("/") + "/"
        if not path.startswith(base):
            return None
        return path[len(base) :].split("/", 1)[0] or None

    def _git_series(self) -> Series:
        cfg = self.config
        listed = parse_worktree_list(self._git("worktree", "list", "--porcelain", cwd=cfg.repo))
        merged, first_parent = self._merged_state()
        now = self._wall()
        out: Series = []
        names: list[str] = []
        for wt in listed:
            name = self._worktree_name(wt.path)
            if (
                name is None
                or not _WORKTREE_NAME.fullmatch(name)
                or len(names) >= MAX_WORKTREE_LABELS
                or not os.path.isdir(wt.path)
            ):
                continue
            names.append(name)
            labels = {"worktree": name}
            stamp = parse_commit_time(self._git("log", "-1", "--format=%ct", cwd=wt.path))
            out.append(
                ("antlab_host_worktree_last_commit_age_seconds", labels, max(0, int(now - stamp)))
            )
            if merged is not None:
                # Merge commits mean a merged feature tip is never on main's first-parent line, while a
                # branch with no commits of its own always is, however far main has moved on.
                is_merged = bool(wt.branch) and wt.branch in merged and wt.head not in first_parent
                out.append(("antlab_host_worktree_merged", labels, int(is_merged)))
            changes = self._slow_get(
                f"status:{wt.path}",
                SLOW_CACHE_SECONDS,
                lambda p=wt.path: parse_status_count(self._git("status", "--porcelain", cwd=p)),
            )
            out.append(("antlab_host_worktree_uncommitted_files", labels, changes))
        out.append(("antlab_host_worktrees", {}, len(names)))
        if cfg.sizes:
            out += self._size_series(names)
        return out

    def _merged_state(self) -> tuple[set[str] | None, set[str]]:
        repo = self.config.repo

        def fetch() -> tuple[set[str] | None, set[str]]:
            try:
                merged = parse_branch_names(
                    self._git(
                        "branch", "--merged", "origin/main", "--format=%(refname:short)", cwd=repo
                    )
                )
                first_parent = parse_branch_names(
                    self._git("rev-list", "--first-parent", "origin/main", cwd=repo)
                )
            except SourceError:
                return None, set()
            return merged, first_parent

        state = self._slow_get("merged", SLOW_CACHE_SECONDS, fetch)
        if state[0] is None:  # type: ignore[index]
            self._slow.pop("merged", None)  # a failure is retried on the next scrape
        return state  # type: ignore[return-value]

    def _size_series(self, names: list[str]) -> Series:
        cfg = self.config

        def fetch() -> Series:
            raw = Path(cfg.repo) / "data/raw"
            builds = [Path(cfg.worktrees) / n / "build" for n in names]
            paths = [str(p) for p in [raw, *builds] if os.path.isdir(p)]
            sizes = parse_du(self._run(["du", "-sk", *paths], 120.0)) if paths else {}
            return [
                ("antlab_host_dir_bytes", {"dir": "data_raw"}, sizes.get(str(raw), 0)),
                (
                    "antlab_host_dir_bytes",
                    {"dir": "worktree_build"},
                    sum(sizes.get(str(b), 0) for b in builds),
                ),
            ]

        return self._slow_get("sizes", SIZE_CACHE_SECONDS, fetch)  # type: ignore[return-value]


__all__ = ["Config", "HostCollector"]
