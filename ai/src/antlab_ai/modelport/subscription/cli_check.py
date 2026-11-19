"""Batch-start checks that need the CLI itself: where it is, its version and its login kind.

None of these is a model call. The wording of the login commands is a draft until the probe; an
output that is not recognised counts as "not logged in" (fail closed). Output is never stored.
"""

from __future__ import annotations

import json
import os
import re
import shutil
from collections.abc import Mapping
from pathlib import Path
from typing import Final

from ..port import SubscriptionRefusal
from .common import CliConfig, build_env, ensure_outside_repo
from .process import CliResult, run_cli

CLI_NAMES: Final = {"CLAUDE_CLI": "claude", "CODEX_CLI": "codex"}
CHECK_DEADLINE_S: Final = 30
CHECK_STDOUT_CAP: Final = 16 * 1024
CHECK_STDERR_CAP: Final = 16 * 1024
_VERSION_TOKEN: Final = re.compile(r"\d[\x21-\x7e]{0,79}")


def resolve_binary(route: str, environ: Mapping[str, str]) -> str:
    """The absolute path of the CLI, resolved once so a later PATH change cannot swap the binary."""
    name = CLI_NAMES.get(route)
    found = shutil.which(name, path=environ.get("PATH")) if name else None
    if found is None:
        raise SubscriptionRefusal("CLI_MISSING")
    path = Path(found).absolute()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise SubscriptionRefusal("CLI_MISSING")
    return str(path)


def _run(binary: str, args: list[str], config: CliConfig) -> CliResult:
    root = ensure_outside_repo(config.work_root)
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        return run_cli(
            [binary, *args],
            stdin_bytes=b"",
            env=build_env(config),
            cwd=root,
            deadline_s=CHECK_DEADLINE_S,
            stdout_cap=CHECK_STDOUT_CAP,
            stderr_cap=CHECK_STDERR_CAP,
        )
    except OSError:
        raise SubscriptionRefusal("CLI_MISSING") from None


def _probe_config(
    binary: str, work_root: Path, environ: Mapping[str, str], extra: Mapping[str, str]
):
    return CliConfig(binary, "check", work_root, extra_env=extra, source_env=environ)


def read_version(
    binary: str, work_root: Path, environ: Mapping[str, str], extra: Mapping[str, str]
) -> str:
    """The first version-looking token of `--version`; anything else refuses with CLI_MISSING."""
    result = _run(binary, ["--version"], _probe_config(binary, work_root, environ, extra))
    if result.exit_code != 0 or result.timed_out or result.over_cap or result.pipes_open:
        raise SubscriptionRefusal("CLI_MISSING")
    for token in result.stdout.decode("utf-8", "replace").split():
        if _VERSION_TOKEN.fullmatch(token):
            return token
    raise SubscriptionRefusal("CLI_MISSING")


# Provisional shapes until the probe: only these count as a subscription login. Anything else,
# including a missing or unknown method, is refused.
CLAUDE_AUTH_METHODS: Final = frozenset({"oauth", "claude.ai", "claude_ai", "subscription"})
CODEX_LOGIN_TEXT: Final = re.compile(r"logged in (?:using|with) (?:a )?chatgpt(?: account)?\.?")


def _claude_logged_in(result: CliResult) -> bool:
    try:
        status = json.loads(result.stdout.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return False
    if not isinstance(status, dict) or status.get("loggedIn") is not True:
        return False
    method = status.get("authMethod")
    if not isinstance(method, str) or method.strip().lower() not in CLAUDE_AUTH_METHODS:
        return False
    return status.get("apiKeySource") in (None, "", "none")


def _codex_logged_in(result: CliResult) -> bool:
    lines = [
        line.strip().lower()
        for line in (result.stdout + b"\n" + result.stderr).decode("utf-8", "replace").splitlines()
        if line.strip()
    ]
    return len(lines) == 1 and CODEX_LOGIN_TEXT.fullmatch(lines[0]) is not None


def check_login(
    route: str,
    binary: str,
    work_root: Path,
    environ: Mapping[str, str],
    extra: Mapping[str, str],
) -> None:
    """Refuse with NOT_LOGGED_IN unless the CLI reports a subscription login, not an API key."""
    if route == "CLAUDE_CLI":
        args, judge = ["auth", "status", "--json"], _claude_logged_in
    else:
        args, judge = ["login", "status"], _codex_logged_in
    result = _run(binary, args, _probe_config(binary, work_root, environ, extra))
    ok = result.exit_code == 0 and not (result.timed_out or result.over_cap or result.pipes_open)
    if not ok or not judge(result):
        raise SubscriptionRefusal("NOT_LOGGED_IN")
