"""Audit round: frozen environment, bounded last-message read, cleanup paths, work-root placement."""

import os
import subprocess
import threading
import time
from pathlib import Path

import pytest
from sub_fakes import alive, claude_envelope, codex_stdout, request

from antlab_ai.modelport import SubscriptionRefusal
from antlab_ai.modelport.subscription import ClaudeCliAdapter, CliConfig, CodexCliAdapter
from antlab_ai.modelport.subscription import codex_cli as codex_module
from antlab_ai.modelport.subscription.common import build_env
from antlab_ai.modelport.subscription.process import run_cli

REPO = Path(__file__).resolve().parents[4]


def codex(fakes, gate):
    return CodexCliAdapter(fakes.config("codex"), gate)


def test_extra_env_is_copied_and_rechecked_at_spawn_time(tmp_path):
    original = {"CODEX_HOME": "/h"}
    config = CliConfig("/bin/x", "1", tmp_path, extra_env=original)
    original["OPENROUTER_API_KEY"] = "leak"
    original["CODEX_HOME"] = "/changed"
    env = build_env(config)
    assert env["CODEX_HOME"] == "/h" and "OPENROUTER_API_KEY" not in env
    with pytest.raises(TypeError):
        config.extra_env["ANTLAB_ENGINE_TOKEN"] = "x"  # type: ignore[index]
    object.__setattr__(config, "extra_env", {"ANTLAB_ENGINE_TOKEN": "x"})
    with pytest.raises(ValueError):
        build_env(config)


def test_source_env_is_copied_too(tmp_path):
    source = {"PATH": "/p"}
    config = CliConfig("/bin/x", "1", tmp_path, source_env=source)
    source["PATH"] = "/other"
    assert build_env(config)["PATH"] == "/p"


@pytest.mark.parametrize(
    "scenario",
    [
        {"last_message_fifo": True},
        {"last_message_symlink": True},
        {"last_message_size": 2 * 1024 * 1024 + 1},
    ],
    ids=["fifo", "symlink", "over_cap"],
)
def test_last_message_that_is_not_a_bounded_regular_file_is_error(fakes, gate, scenario):
    fakes.scenario("codex", stdout=codex_stdout(), **scenario)
    adapter = codex(fakes, gate)
    started = time.monotonic()
    attempt = adapter.attempt(request("gpt-x"))
    assert time.monotonic() - started < 5
    assert attempt.status == "ERROR" and attempt.text is None
    assert adapter.last_meta.pattern_id == "no_last_message"


def test_last_message_at_the_cap_is_read_whole(fakes, gate):
    fakes.scenario("codex", stdout=codex_stdout(), last_message_size=2 * 1024 * 1024)
    attempt = codex(fakes, gate).attempt(request("gpt-x"))
    assert attempt.status == "OK" and len(attempt.text) == 2 * 1024 * 1024


@pytest.mark.parametrize(
    "scenario",
    [{"exit": 1, "stderr": "usage limit reached"}, {"sleep": 30}],
    ids=["failed_exit", "timeout"],
)
def test_the_reply_file_is_not_read_after_a_failure_or_timeout(fakes, gate, monkeypatch, scenario):
    def forbidden(path):
        raise AssertionError("reply file read after a failed process")

    monkeypatch.setattr(codex_module, "read_last_message", forbidden)
    fakes.scenario("codex", stdout=codex_stdout(), last_message="r", **scenario)
    attempt = codex(fakes, gate).attempt(request("gpt-x", timeout_ms=1000))
    assert attempt.status in ("LIMITED", "TIMEOUT")


def test_a_thread_that_cannot_start_kills_the_child(fakes, monkeypatch):
    fakes.scenario("claude", sleep=60, stdout=claude_envelope())
    spawned: list[subprocess.Popen] = []

    class Recording(subprocess.Popen):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            spawned.append(self)

    real_start = threading.Thread.start
    starts = []

    def failing_start(self):
        starts.append(1)
        if len(starts) == 3:
            raise RuntimeError("cannot start new thread")
        real_start(self)

    monkeypatch.setattr(subprocess, "Popen", Recording)
    monkeypatch.setattr(threading.Thread, "start", failing_start)
    with pytest.raises(RuntimeError):
        run_cli(
            [fakes.path("claude")],
            stdin_bytes=b"x",
            env={"PATH": str(fakes.dir)},
            cwd=fakes.dir,
            deadline_s=30,
            stdout_cap=1000,
            stderr_cap=1000,
        )
    (proc,) = spawned
    assert proc.poll() is not None
    assert not alive(proc.pid)


def test_a_term_resistant_helper_is_killed(fakes, gate):
    fakes.scenario("claude", sleep=60, child_ignore_term=True)
    attempt = ClaudeCliAdapter(fakes.config("claude"), gate).attempt(request(timeout_ms=1000))
    assert attempt.status == "TIMEOUT"
    pid = int((fakes.dir / "child.pid").read_text())
    for _ in range(50):
        if not alive(pid):
            break
        time.sleep(0.1)
    assert not alive(pid)


def test_a_detached_helper_holding_the_pipes_is_detected_not_trusted(fakes, gate):
    fakes.scenario("claude", child_detached=True, stdout=claude_envelope())
    adapter = ClaudeCliAdapter(fakes.config("claude"), gate)
    started = time.monotonic()
    try:
        attempt = adapter.attempt(request())
        elapsed = time.monotonic() - started
    finally:
        pid_file = fakes.dir / "child.pid"
        if pid_file.exists():
            try:
                os.kill(int(pid_file.read_text()), 9)
            except ProcessLookupError:
                pass
    assert attempt.status == "ERROR" and attempt.text is None
    assert adapter.last_meta.pattern_id == "escaped_descendant"
    assert elapsed < 6


def test_a_repo_local_or_symlinked_work_root_is_refused(fakes, gate, tmp_path):
    with pytest.raises(ValueError):
        CliConfig(fakes.path("claude"), "1", REPO / "ai" / "work-root-test")
    link = tmp_path / "link"
    link.symlink_to(REPO / "ai")
    with pytest.raises(ValueError):
        CliConfig(fakes.path("claude"), "1", link / "inside")
    assert not (REPO / "ai" / "work-root-test").exists()


@pytest.mark.parametrize("name", ["claude", "codex"])
def test_a_work_root_swapped_for_a_repo_link_after_construction_spawns_nothing(
    fakes, gate, tmp_path, name
):
    config = fakes.config(name)
    config.work_root.symlink_to(REPO / "ai")
    adapter = (ClaudeCliAdapter if name == "claude" else CodexCliAdapter)(config, gate)
    with pytest.raises(ValueError):
        adapter.attempt(request("claude-x" if name == "claude" else "gpt-x"))
    assert fakes.calls() == []
    assert not any(p.name.startswith("tmp") for p in (REPO / "ai").iterdir())


def _open_fds() -> int:
    return len(os.listdir("/dev/fd"))


def _drain_fds(baseline: int, seconds: float = 3.0) -> int:
    end = time.monotonic() + seconds
    while time.monotonic() < end and _open_fds() > baseline:
        time.sleep(0.05)
    return _open_fds()


def test_a_full_stdin_pipe_held_open_by_a_detached_helper_does_not_stall_cleanup(fakes):
    # The leader never reads stdin and a detached helper keeps the read end open, so the 8 MiB
    # prompt can never be written. Cleanup must still return, and the writer must give up its fd.
    fakes.scenario("claude", skip_stdin=True, sleep=60, child_detached=True)
    baseline = _open_fds()
    started = time.monotonic()
    try:
        result = run_cli(
            [fakes.path("claude")],
            stdin_bytes=b"x" * (8 * 1024 * 1024),
            env={"PATH": str(fakes.dir)},
            cwd=fakes.dir,
            deadline_s=0.5,
            stdout_cap=1000,
            stderr_cap=1000,
        )
        elapsed = time.monotonic() - started
        # While the detached helper lives, only the two blocked readers may still hold a descriptor.
        held = _drain_fds(baseline + 2)
    finally:
        pid_file = fakes.dir / "child.pid"
        if pid_file.exists():
            try:
                os.kill(int(pid_file.read_text()), 9)
            except ProcessLookupError:
                pass
    assert result.timed_out and result.pipes_open and elapsed < 6
    assert held <= baseline + 2
    assert _drain_fds(baseline, 5.0) <= baseline


def test_a_reader_thread_that_cannot_be_built_closes_its_duplicate_and_kills_the_child(
    fakes, monkeypatch
):
    fakes.scenario("claude", sleep=60)
    spawned: list[subprocess.Popen] = []

    class Recording(subprocess.Popen):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            spawned.append(self)

    real_thread = threading.Thread
    built = []

    def failing_thread(*args, **kwargs):
        built.append(1)
        if len(built) == 3:
            raise RuntimeError("cannot build thread")
        return real_thread(*args, **kwargs)

    baseline = _open_fds()
    monkeypatch.setattr(subprocess, "Popen", Recording)
    monkeypatch.setattr(threading, "Thread", failing_thread)
    with pytest.raises(RuntimeError):
        run_cli(
            [fakes.path("claude")],
            stdin_bytes=b"x",
            env={"PATH": str(fakes.dir)},
            cwd=fakes.dir,
            deadline_s=30,
            stdout_cap=1000,
            stderr_cap=1000,
        )
    monkeypatch.undo()
    (proc,) = spawned
    assert proc.poll() is not None and not alive(proc.pid)
    assert _drain_fds(baseline) <= baseline


def test_a_failing_first_duplication_still_closes_the_original_stdin(fakes, monkeypatch):
    import errno

    fakes.scenario("claude", sleep=60)
    spawned: list[subprocess.Popen] = []

    class Recording(subprocess.Popen):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            spawned.append(self)

    def no_descriptors(fd):
        raise OSError(errno.EMFILE, "too many open files")

    monkeypatch.setattr(subprocess, "Popen", Recording)
    monkeypatch.setattr(os, "dup", no_descriptors)
    with pytest.raises(OSError):
        run_cli(
            [fakes.path("claude")],
            stdin_bytes=b"x",
            env={"PATH": str(fakes.dir)},
            cwd=fakes.dir,
            deadline_s=30,
            stdout_cap=1000,
            stderr_cap=1000,
        )
    monkeypatch.undo()
    (proc,) = spawned
    assert proc.stdin.closed and proc.stdout.closed and proc.stderr.closed
    assert proc.poll() is not None and not alive(proc.pid)


@pytest.mark.parametrize("name", ["claude", "codex"])
def test_a_raise_never_leaves_stale_metadata(fakes, name):
    from sub_fakes import RecordingGate

    fakes.scenario("claude", stdout=claude_envelope())
    fakes.scenario("codex", stdout=codex_stdout(), last_message="r")
    model = "claude-x" if name == "claude" else "gpt-x"
    cls = ClaudeCliAdapter if name == "claude" else CodexCliAdapter
    gate = RecordingGate()
    adapter = cls(fakes.config(name), gate)
    adapter.attempt(request(model))
    assert adapter.last_meta is not None
    with pytest.raises(ValueError):
        adapter.attempt(request("--bad"))
    assert adapter.last_meta is None
    adapter.attempt(request(model))
    assert adapter.last_meta is not None
    gate.refuse_with = "USAGE_STOP"
    with pytest.raises(SubscriptionRefusal):
        adapter.attempt(request(model))
    assert adapter.last_meta is None
