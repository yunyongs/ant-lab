"""Private local files for the batch ledger and lock, anchored to verified directory descriptors.

Every path component is opened with O_NOFOLLOW | O_DIRECTORY relative to its parent descriptor, so
a symlinked ancestor is refused and a swap between the check and the open cannot redirect a write.
Ancestors must belong to the current user or root and must not be group- or world-writable (a
sticky directory such as /tmp is accepted); the leaf directory must be private to the current user.
"""

from __future__ import annotations

import os
import stat
from pathlib import Path

DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


class UnsafePath(Exception):
    """A path, directory or file failed a safety check. The text is a fixed code."""

    def __init__(self) -> None:
        super().__init__("LEDGER_UNSAFE")


def _check_ancestor(fd: int) -> None:
    info = os.fstat(fd)
    if info.st_uid not in (os.geteuid(), 0):
        raise UnsafePath
    if info.st_mode & 0o022 and not info.st_mode & stat.S_ISVTX:
        raise UnsafePath


def open_private_dir(path: Path) -> int:
    """A descriptor for the directory at `path`, created (mode 0700) when missing."""
    absolute = Path(os.path.abspath(path))
    try:
        fd = os.open("/", DIR_FLAGS)
        parts = absolute.parts[1:]
        for index, part in enumerate(parts):
            last = index == len(parts) - 1
            try:
                child = os.open(part, DIR_FLAGS, dir_fd=fd)
            except FileNotFoundError:
                os.mkdir(part, 0o700, dir_fd=fd)
                # The new entry is durable only once its parent directory is flushed.
                os.fsync(fd)
                child = os.open(part, DIR_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = child
            if not last:
                _check_ancestor(fd)
        info = os.fstat(fd)
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise UnsafePath
        return fd
    except UnsafePath:
        _close_quietly(locals().get("fd"))
        raise
    except OSError:
        _close_quietly(locals().get("fd"))
        raise UnsafePath from None


def _close_quietly(fd: int | None) -> None:
    if fd is not None:
        try:
            os.close(fd)
        except OSError:
            pass


def open_private_file(dir_fd: int, name: str, flags: int) -> int:
    """A regular, single-link, owner-only file inside the verified directory (0600 when created, then flushed with its directory entry)."""
    if "/" in name or name in ("", ".", ".."):
        raise UnsafePath
    try:
        try:
            fd = os.open(name, flags | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=dir_fd)
            # A file created now must survive a crash before anything depends on it.
            os.fsync(fd)
            os.fsync(dir_fd)
        except FileExistsError:
            fd = os.open(name, flags | os.O_NOFOLLOW, dir_fd=dir_fd)
    except OSError:
        raise UnsafePath from None
    info = os.fstat(fd)
    if (
        not stat.S_ISREG(info.st_mode)
        or info.st_mode & 0o077
        or info.st_uid != os.geteuid()
        or info.st_nlink != 1
    ):
        os.close(fd)
        raise UnsafePath
    return fd


def write_all(fd: int, data: bytes) -> None:
    view = memoryview(data)
    while view:
        written = os.write(fd, view)
        if written <= 0:
            raise OSError("short write")
        view = view[written:]
