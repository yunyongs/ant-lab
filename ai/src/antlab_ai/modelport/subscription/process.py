"""Run one CLI as one process group with a hard deadline and bounded output.

Containment limit, stated plainly: the deadline and the cleanup act on the CLI's process group
(SIGTERM, a grace period, then SIGKILL, also after a normal exit). A descendant that leaves the
group with setsid() cannot be killed from here. It can only be detected when it keeps a stdout or
stderr pipe open: then `CliResult.pipes_open` is true and the mappers turn the attempt into ERROR.
A detached descendant that closes its pipes is neither killed nor detected.
"""

from __future__ import annotations

import os
import select
import signal
import subprocess
import threading
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

KILL_GRACE_S = 5.0
_POLL_S = 0.01
_JOIN_S = 2.0
_WRITER_JOIN_S = 0.2
_REAP_S = 1.0
# Longest run_cli can take beyond deadline_s with the default grace: SIGTERM grace, the bounded
# reap after SIGKILL, the shared reader join and the writer join (5 + 1 + 2 + 0.2).
WORST_CASE_OVERRUN_S = KILL_GRACE_S + _REAP_S + _JOIN_S + _WRITER_JOIN_S


@dataclass(frozen=True)
class CliResult:
    exit_code: int | None
    stdout: bytes
    stderr: bytes
    timed_out: bool
    over_cap: bool
    latency_ms: int
    # A reader was still blocked after cleanup: some descendant outside the group holds a pipe.
    pipes_open: bool = False

    def __repr__(self) -> str:
        # stderr may hold an account address; only sizes are ever shown.
        return (
            f"CliResult(exit_code={self.exit_code}, stdout={len(self.stdout)}B, "
            f"stderr={len(self.stderr)}B, timed_out={self.timed_out}, over_cap={self.over_cap})"
        )


def _read(fd: int, cap: int, sink: bytearray, over: threading.Event) -> None:
    # The thread owns this descriptor (a dup), so closing the Popen pipe cannot alias it.
    try:
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                return
            room = cap - len(sink)
            if len(chunk) > room:
                sink += chunk[:room]
                over.set()
                return
            sink += chunk
    except OSError:
        return
    finally:
        os.close(fd)


def _write(fd: int, data: bytes, cancel: threading.Event) -> None:
    """Feed stdin without ever blocking on a full pipe; the thread owns fd and closes it."""
    try:
        os.set_blocking(fd, False)
        view = memoryview(data)
        while view and not cancel.is_set():
            try:
                view = view[os.write(fd, view) :]
            except BlockingIOError:
                select.select([], [fd], [], 0.05)
            except OSError:
                return
    finally:
        os.close(fd)


def _signal_group(pgid: int, sig: int) -> None:
    try:
        os.killpg(pgid, sig)
    except (ProcessLookupError, PermissionError):
        pass


def _kill_group(proc: subprocess.Popen, grace_s: float) -> None:
    pgid = proc.pid
    _signal_group(pgid, signal.SIGTERM)
    deadline = time.monotonic() + grace_s
    while proc.poll() is None and time.monotonic() < deadline:
        time.sleep(_POLL_S)
    # SIGKILL also reaches helpers that ignore SIGTERM or outlived the leader.
    _signal_group(pgid, signal.SIGKILL)
    try:
        proc.wait(timeout=_REAP_S)
    except subprocess.TimeoutExpired:
        pass


def run_cli(
    argv: Sequence[str],
    *,
    stdin_bytes: bytes,
    env: Mapping[str, str],
    cwd: Path,
    deadline_s: float,
    stdout_cap: int,
    stderr_cap: int,
    kill_grace_s: float = KILL_GRACE_S,
) -> CliResult:
    """Spawn argv (no shell) in its own session; the prompt goes to stdin, which is then closed."""
    started = time.monotonic()
    over = threading.Event()
    cancel = threading.Event()
    out, err = bytearray(), bytearray()
    readers: list[threading.Thread] = []
    writer: threading.Thread | None = None
    timed_out = False
    proc = subprocess.Popen(  # noqa: S603 - argv list, shell=False, absolute binary
        list(argv),
        shell=False,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(cwd),
        env=dict(env),
        start_new_session=True,
        close_fds=True,
        bufsize=0,
    )
    try:
        # Cleanup is armed from here on: any failure below kills the group.
        # Each thread owns a duplicated descriptor from the moment it has started; until then
        # this block closes it.
        wfd = os.dup(proc.stdin.fileno())
        proc.stdin.close()
        try:
            writer = threading.Thread(target=_write, args=(wfd, stdin_bytes, cancel), daemon=True)
            writer.start()
        except BaseException:
            os.close(wfd)
            writer = None
            raise
        for stream, cap, sink in ((proc.stdout, stdout_cap, out), (proc.stderr, stderr_cap, err)):
            fd = os.dup(stream.fileno())
            try:
                thread = threading.Thread(target=_read, args=(fd, cap, sink, over), daemon=True)
                thread.start()
            except BaseException:
                os.close(fd)
                raise
            readers.append(thread)
        while True:
            if over.is_set():
                _kill_group(proc, kill_grace_s)
                break
            if proc.poll() is not None:
                _signal_group(proc.pid, signal.SIGKILL)
                break
            if time.monotonic() - started >= deadline_s:
                timed_out = True
                _kill_group(proc, kill_grace_s)
                break
            time.sleep(_POLL_S)
    except BaseException:
        _kill_group(proc, 0.0)
        raise
    finally:
        cancel.set()
        end = time.monotonic() + _JOIN_S
        for thread in readers:
            thread.join(max(0.0, end - time.monotonic()))
        if writer is not None:
            writer.join(_WRITER_JOIN_S)
        for stream in (proc.stdin, proc.stdout, proc.stderr):
            try:
                stream.close()
            except OSError:
                pass
    return CliResult(
        exit_code=proc.returncode,
        stdout=bytes(out),
        stderr=bytes(err),
        timed_out=timed_out,
        over_cap=over.is_set(),
        latency_ms=int((time.monotonic() - started) * 1000),
        pipes_open=any(t.is_alive() for t in readers),
    )
