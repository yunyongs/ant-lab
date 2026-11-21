import re
from pathlib import Path

import pytest

from antlab_ai.host_exporter.__main__ import BIND_ADDRESS, respond
from antlab_ai.host_exporter.exporter import CACHE_SECONDS, Config, HostCollector
from antlab_ai.host_exporter.sources import SourceError

from .fixtures import DOCKER_PS, DOCKER_STATS, PS, ROOTS, SECRET, WORKTREE_LIST

NOW = 1_700_000_000.0
COMMIT_TIMES = {
    "older": NOW - 5 * 86400,
    "old1": NOW - 9 * 86400,
    "fresh": NOW - 60,
    "wip": NOW - 3600,
}


class Env:
    """Fake command runner: records calls and answers from fixtures."""

    def __init__(self, fail=()):
        self.calls = []
        self.fail = set(fail)
        self.clock = 0.0

    def __call__(self, argv, timeout):
        self.calls.append(list(argv))
        head = argv[0]
        if head in self.fail:
            raise SourceError("down")
        if head == "ps":
            return PS
        if head == "docker":
            return DOCKER_PS if argv[1] == "ps" else DOCKER_STATS
        if head == "git":
            args = argv[4:]  # after --no-optional-locks -C <dir>
            if args[0] == "worktree":
                return WORKTREE_LIST
            if args[0] == "branch":
                return "main\nfeat/1-old\nfeat/2-fresh\nfeat/4-older\n"
            if args[0] == "rev-list":
                return "aaaa000\naaaa111\n"
            if args[0] == "status":
                return " M a.py\n?? b.py\n" if argv[3].endswith("wip") else ""
            if args[0] == "log":
                return f"{int(COMMIT_TIMES[argv[3].rsplit('/', 1)[1]])}\n"
        raise AssertionError(argv)

    def count(self, head, sub=None):
        return sum(1 for c in self.calls if c[0] == head and (sub is None or sub in c))


@pytest.fixture
def env():
    return Env()


def make(env, tmp_path, **kw):
    slots = tmp_path / "slots"
    (slots / "1").mkdir(parents=True)
    (slots / "1" / "pid").write_text("42\n")
    config = Config(
        repo=ROOTS.repo,
        worktrees=ROOTS.worktrees,
        job_tmp=ROOTS.job_tmp,
        slots_dir=str(slots),
        **kw,
    )
    ticks = {"t": 0.0}

    def clock():
        return ticks["t"]

    collector = HostCollector(
        config, run=env, clock=clock, wall=lambda: NOW, pid_alive=lambda p: p == 42, own_pid=500
    )
    return collector, ticks


@pytest.fixture(autouse=True)
def existing_worktrees(monkeypatch):
    monkeypatch.setattr("os.path.isdir", lambda p: not str(p).startswith("/elsewhere"))


def samples(text):
    out = {}
    for line in text.splitlines():
        if line.startswith("#") or not line:
            continue
        m = re.fullmatch(r"(\w+)(?:\{(.*)\})? (\S+)", line)
        assert m, line
        labels = dict(re.findall(r'(\w+)="([^"]*)"', m.group(2) or ""))
        out[(m.group(1), tuple(sorted(labels.items())))] = float(m.group(3))
    return out


def get(s, name, **labels):
    return s[(name, tuple(sorted(labels.items())))]


def test_exposition_has_every_family_with_fixed_labels(env, tmp_path):
    collector, _ = make(env, tmp_path)
    text = collector.exposition()
    s = samples(text)
    assert get(s, "antlab_host_process_stale", kind="pytest", worktree="old1") == 2
    assert get(s, "antlab_host_process_idle", kind="gradle_daemon", worktree="shared") == 1
    assert get(s, "antlab_host_process_count", kind="gradle_worker", worktree="shared") == 1
    assert get(s, "antlab_host_container_running", service="db") == 1
    assert get(s, "antlab_host_container_mem_limit_bytes", service="grafana") == 512 * 1024**2
    assert get(s, "antlab_host_container_stopped", service="api") == 1
    assert get(s, "antlab_host_testcontainers_running") == 1
    assert get(s, "antlab_host_worktrees") == 4
    assert get(s, "antlab_host_worktree_last_commit_age_seconds", worktree="old1") == 9 * 86400
    assert get(s, "antlab_host_slots_held") == 1
    for source in ("ps", "docker", "docker_stats", "git", "slots"):
        assert get(s, "antlab_host_source_ok", source=source) == 1
    label_names = {k for (_, labels) in s for k, _ in labels}
    assert label_names <= {"kind", "worktree", "service", "source", "dir"}
    assert SECRET not in text
    assert "/Users" not in text
    assert text.count("# TYPE antlab_host_process_count gauge") == 1


def test_merged_means_behind_origin_main_and_not_a_fresh_branch(env, tmp_path):
    collector, _ = make(env, tmp_path)
    s = samples(collector.exposition())
    assert get(s, "antlab_host_worktree_merged", worktree="old1") == 1
    assert get(s, "antlab_host_worktree_merged", worktree="fresh") == 0  # tip equals origin/main
    assert get(s, "antlab_host_worktree_merged", worktree="wip") == 0
    # no commits of its own, on main's first-parent line: stays 0 after main moves on
    assert get(s, "antlab_host_worktree_merged", worktree="older") == 0
    assert get(s, "antlab_host_worktree_uncommitted_files", worktree="wip") == 2
    assert get(s, "antlab_host_worktree_uncommitted_files", worktree="old1") == 0


def test_a_scrape_inside_the_cache_window_runs_nothing(env, tmp_path):
    collector, ticks = make(env, tmp_path)
    first = collector.exposition()
    calls = len(env.calls)
    ticks["t"] = CACHE_SECONDS - 1
    assert collector.exposition() == first
    assert len(env.calls) == calls
    ticks["t"] = CACHE_SECONDS + 1
    collector.exposition()
    assert len(env.calls) > calls


def test_one_ps_one_docker_pair_and_slow_git_answers_are_cached(env, tmp_path):
    collector, ticks = make(env, tmp_path)
    collector.exposition()
    assert env.count("ps") == 1
    assert env.count("docker", "ps") == 1 and env.count("docker", "stats") == 1
    assert env.count("git", "log") == 4
    assert env.count("git", "status") == 4 and env.count("git", "branch") == 1
    ticks["t"] = 20
    collector.exposition()
    assert env.count("git", "log") == 8  # per scrape
    assert env.count("git", "status") == 4  # cached 10 minutes
    assert env.count("git", "branch") == 1
    ticks["t"] = 700
    collector.exposition()
    assert env.count("git", "status") == 8


def test_git_runs_without_optional_locks(env, tmp_path):
    collector, _ = make(env, tmp_path)
    collector.exposition()
    assert all(c[1] == "--no-optional-locks" for c in env.calls if c[0] == "git")


def test_a_missing_docker_reports_down_and_leaves_other_sources(tmp_path):
    env = Env(fail={"docker"})
    collector, _ = make(env, tmp_path)
    s = samples(collector.exposition())
    assert get(s, "antlab_host_source_ok", source="docker") == 0
    assert get(s, "antlab_host_source_ok", source="docker_stats") == 0
    assert get(s, "antlab_host_source_ok", source="ps") == 1
    assert not any(n.startswith("antlab_host_container") for n, _ in s)
    assert get(s, "antlab_host_process_stale", kind="pytest", worktree="old1") == 2


def test_failing_stats_keeps_container_state_without_numbers(tmp_path):
    class NoStats(Env):
        def __call__(self, argv, timeout):
            if argv[0] == "docker" and argv[1] == "stats":
                raise SourceError("timeout")
            return super().__call__(argv, timeout)

    collector, _ = make(NoStats(), tmp_path)
    s = samples(collector.exposition())
    assert get(s, "antlab_host_source_ok", source="docker_stats") == 0
    assert get(s, "antlab_host_container_running", service="db") == 1
    assert not any(n == "antlab_host_container_mem_bytes" for n, _ in s)


def test_an_unreadable_slots_dir_is_skipped(env, tmp_path):
    collector, _ = make(env, tmp_path)
    collector.config = Config(
        repo=ROOTS.repo, worktrees=ROOTS.worktrees, job_tmp=ROOTS.job_tmp, slots_dir="/nonexistent"
    )
    s = samples(collector.exposition())
    assert get(s, "antlab_host_source_ok", source="slots") == 0
    assert not any(n == "antlab_host_slots_held" for n, _ in s)


def test_sizes_stay_off_unless_the_flag_is_set(env, tmp_path):
    collector, _ = make(env, tmp_path)
    assert "antlab_host_dir_bytes" not in collector.exposition()
    assert env.count("du") == 0


def test_config_from_env_defaults_and_validation():
    cfg = Config.from_env({"HOME": "/home/x", "ANTLAB_HOST_REPO": "/r"})
    assert cfg.worktrees == "/home/x/orca/workspaces/ant-lab"
    assert cfg.job_tmp == "" and cfg.slots_dir == ""  # optional parts stay off unless set
    assert cfg.sizes is False and cfg.compose_project == "antlab"
    with pytest.raises(ValueError):
        Config.from_env({"HOME": "/home/x", "ANTLAB_HOST_REPO": "relative"})


def test_http_surface_serves_metrics_only_and_binds_to_loopback(env, tmp_path):
    collector, _ = make(env, tmp_path)
    status, ctype, body = respond("/metrics", collector)
    assert status == 200 and ctype.startswith("text/plain") and b"antlab_host_" in body
    assert respond("/", collector)[0] == 200
    assert respond("/debug", collector)[0] == 404
    assert BIND_ADDRESS == "127.0.0.1"


def test_a_failed_merged_lookup_drops_the_series_and_is_retried(tmp_path):
    class NoOrigin(Env):
        def __call__(self, argv, timeout):
            if argv[0] == "git" and argv[4] == "rev-list":
                self.calls.append(list(argv))
                raise SourceError("no origin/main")
            return super().__call__(argv, timeout)

    env = NoOrigin()
    collector, ticks = make(env, tmp_path)
    s = samples(collector.exposition())
    assert not any(n == "antlab_host_worktree_merged" for n, _ in s)
    assert get(s, "antlab_host_source_ok", source="git") == 1
    ticks["t"] = 20
    collector.exposition()
    assert env.count("git", "rev-list") == 2


def test_sizes_flag_adds_two_bounded_series(tmp_path):
    class WithDu(Env):
        def __call__(self, argv, timeout):
            if argv[0] == "du":
                self.calls.append(list(argv))
                return "10\t" + ROOTS.repo + "/data/raw\n"
            return super().__call__(argv, timeout)

    env = WithDu()
    collector, ticks = make(env, tmp_path, sizes=True)
    s = samples(collector.exposition())
    assert get(s, "antlab_host_dir_bytes", dir="data_raw") == 10 * 1024
    assert get(s, "antlab_host_dir_bytes", dir="worktree_build") == 0
    ticks["t"] = 30
    collector.exposition()
    assert env.count("du") == 1  # hourly


def test_dashboard_allowlist_names_equal_the_exported_families(tmp_path):
    from pathlib import Path

    class WithDu(Env):
        def __call__(self, argv, timeout):
            if argv[0] == "du":
                return "10\t" + ROOTS.repo + "/data/raw\n"
            return super().__call__(argv, timeout)

    collector, _ = make(WithDu(), tmp_path, sizes=True)
    path = Path(__file__).parents[3] / "src/test/resources/monitoring/metric-names-host.txt"
    listed = {
        line.split("#")[0].strip()
        for line in path.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    }
    assert {n for n, _, _ in collector.collect()} == listed


def test_unset_slots_dir_and_job_tmp_turn_those_parts_off(env, tmp_path):
    config = Config(repo=ROOTS.repo, worktrees=ROOTS.worktrees, job_tmp="", slots_dir="")
    collector = HostCollector(config, run=env, clock=lambda: 0.0, wall=lambda: NOW, own_pid=500)
    s = samples(collector.exposition())
    assert not any(n == "antlab_host_slots_held" for n, _ in s)
    assert ("antlab_host_source_ok", (("source", "slots"),)) not in s
    # an empty job_tmp must not match every command line
    assert get(s, "antlab_host_process_count", kind="pytest", worktree="old1") == 2
    assert not any(dict(lbl).get("worktree") == "jobtmp" for _, lbl in s)


def test_tracked_exporter_files_do_not_mention_the_harness_folder():
    root = Path(__file__).parents[3]
    for folder in (
        root / "ai/src/antlab_ai/host_exporter",
        root / "ai/tests/host_exporter",
        root / "monitoring/host-exporter",
    ):
        for path in folder.rglob("*"):
            if path.is_file() and path.suffix in {".py", ".example"}:
                assert "." + "claude" not in path.read_text(), path.name
