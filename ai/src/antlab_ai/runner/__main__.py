"""Command line of the agent runner: register, start, run, status, league, memory.

Console output holds counts, turn numbers and reason codes only; never prompts, replies, keys or tokens.
The one exception is ``memory show``, which prints the agent's own filtered memory on purpose.
Without ``--live`` a run uses a scripted fake model and makes no model call at all.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections.abc import Callable, Mapping, Sequence
from contextlib import ExitStack
from dataclasses import dataclass, replace
from decimal import Decimal
from pathlib import Path
from typing import Final
from uuid import uuid4

from antlab_ai import __version__
from antlab_ai.memory.reflect import (
    NOT_OFFERED,
    PENDING,
    MemoryStep,
    StepResult,
)
from antlab_ai.memory.reflect import (
    STOPPED as MEMORY_STOPPED,
)
from antlab_ai.memory.report import render_comparison
from antlab_ai.modelport.port import AdapterDisabled, ModelPort, SubscriptionRefusal
from antlab_ai.modelport.registry import SubscriptionWiring, build_registry
from antlab_ai.modelport.settings import ModelPortSettings, is_loopback
from antlab_ai.modelport.subscription.cli_check import check_login, read_version, resolve_binary
from antlab_ai.modelport.subscription.common import CliConfig
from antlab_ai.modelport.subscription.gate import (
    CONTAINER_MARKERS,
    BatchGate,
    LedgeredPort,
    verify_owner,
)
from antlab_ai.modelport.subscription.ledger import BatchLedger, BatchLock, check_batch_id
from antlab_ai.modelport.subscription.settings import (
    SubscriptionSettings,
    SubscriptionSettingsError,
)
from antlab_ai.modelport.subscription.usage import CommandUsageReader
from antlab_ai.openrouter.client import OpenRouterClient, SendCap
from antlab_ai.openrouter.ledger import Ledger
from antlab_ai.openrouter.limiter import Limiter
from antlab_ai.runner.api_client import (
    ApiClient,
    ApiProblem,
    ApiUnavailable,
    Transport,
    urllib_transport,
)
from antlab_ai.runner.config import (
    CLAUDE_CLI,
    CODEX_CLI,
    FLAT_FEE,
    KNOWN,
    KNOWN_ROUTES,
    OLLAMA,
    OPENROUTER,
    SUBSCRIPTION_ROUTES,
    RunnerConfig,
    ScriptedPort,
    ScriptError,
    default_timeout_ms,
    load_script,
    season_config,
)
from antlab_ai.runner.contract import ContractError
from antlab_ai.runner.journal import Journal, JournalError, JournalLocked
from antlab_ai.runner.loop import FINISHED, FlatFeeGate, RunnerAbort, SeasonRunner, StillPaused
from antlab_ai.runner.persona import Persona, PersonaError, load_persona
from antlab_ai.runner.prompt import TEMPLATES, PromptError
from antlab_ai.runner.records import RunnerDefect
from antlab_ai.runner.roster import RosterError, load_roster
from antlab_ai.runner.settings import RunnerSettings, SettingsError
from antlab_ai.settings import Settings

EXIT_OK: Final = 0
EXIT_ERROR: Final = 1
EXIT_USAGE: Final = 2
EXIT_PAUSED: Final = 3
DEFAULT_PATH_LENGTH: Final = 12
DEFAULT_MAX_SEASONS: Final = 5
ROUTES: Final = KNOWN_ROUTES


class UsageError(Exception):
    """A refused option or configuration. The text is fixed and holds no value."""


@dataclass(frozen=True)
class Interaction:
    """The owner's terminal; tests replace it. A subscription batch must be started from a terminal."""

    is_terminal: Callable[[], bool] = lambda: sys.stdin.isatty()  # noqa: E731
    ask: Callable[[str], str] = input
    say: Callable[[str], None] = print
    container_markers: tuple[Path, ...] = CONTAINER_MARKERS


@dataclass(frozen=True)
class LiveContext:
    persona: Persona
    api: ApiClient
    state_dir: Path
    stack: ExitStack
    io: Interaction


# Errors whose text is a fixed sentence or code, safe to print.
_USAGE_ERRORS = (
    UsageError,
    SettingsError,
    SubscriptionSettingsError,
    SubscriptionRefusal,
    ScriptError,
    PersonaError,
    RosterError,
    StillPaused,
    JournalLocked,
)
_RUN_ERRORS = (
    ApiProblem,
    ApiUnavailable,
    ContractError,
    JournalError,
    PromptError,
    RunnerAbort,
    RunnerDefect,
)


def _memory_option(value: str) -> str:
    if value in ("latest", "none") or (value.isascii() and value.isdigit() and int(value) >= 1):
        return value
    raise argparse.ArgumentTypeError("use latest, none or a version number of at least 1")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="antlab_ai.runner")
    sub = parser.add_subparsers(dest="command", required=True)

    def agent_args(p: argparse.ArgumentParser) -> None:
        p.add_argument("--persona", required=True)
        p.add_argument("--model-id", required=True)
        p.add_argument("--route", choices=ROUTES, default=OPENROUTER)

    register = sub.add_parser("register")
    agent_args(register)
    start = sub.add_parser("start")
    start.add_argument("--agent-id", type=int, required=True)
    start.add_argument("--path-length", type=int)
    start.add_argument("--league", type=int)
    start.add_argument(
        "--memory",
        type=_memory_option,
        default="latest",
        help="memory version to pin: latest (default), none, or a version number",
    )
    agent_args(start)
    run = sub.add_parser("run")
    run.add_argument("--agent-id", type=int, required=True)
    run.add_argument("--season-id", type=int, required=True)
    agent_args(run)
    run.add_argument("--model-script", type=Path)
    run.add_argument("--live", action="store_true")
    run.add_argument("--max-calls", type=int)
    run.add_argument("--resume", action="store_true")
    run.add_argument("--batch-id")
    run.add_argument("--reconcile", action="store_true")
    run.add_argument("--env-file")
    status = sub.add_parser("status")
    status.add_argument("--agent-id", type=int, required=True)
    status.add_argument("--season-id", type=int, required=True)
    league = sub.add_parser("league")
    league_sub = league.add_subparsers(dest="league_command", required=True)
    create = league_sub.add_parser("create")
    create.add_argument("--name", required=True)
    create.add_argument("--path-length", type=int)
    create.add_argument("--max-entries", type=int)
    create.add_argument(
        "--same-seed-as", type=int, help="root league of an earlier same-seed group"
    )
    enter = league_sub.add_parser("enter")
    enter.add_argument("--league", type=int, required=True)
    enter.add_argument("--roster", type=Path, required=True)
    for name in ("show", "close"):
        league_sub.add_parser(name).add_argument("--league", type=int, required=True)
    memory = sub.add_parser("memory")
    memory_sub = memory.add_subparsers(dest="memory_command", required=True)
    reflect = memory_sub.add_parser("reflect")
    agent_args(reflect)
    reflect.add_argument("--agent-id", type=int, required=True)
    which = reflect.add_mutually_exclusive_group()
    which.add_argument("--season-id", type=int)
    which.add_argument("--all-pending", action="store_true")
    reflect.add_argument("--max-seasons", type=int, default=DEFAULT_MAX_SEASONS)
    reflect.add_argument("--model-script", type=Path)
    reflect.add_argument("--live", action="store_true")
    reflect.add_argument("--max-calls", type=int)
    reflect.add_argument("--env-file")
    reflect.add_argument("--discard-on-budget", action="store_true")
    for name in ("pending", "show"):
        command = memory_sub.add_parser(name)
        command.add_argument("--agent-id", type=int, required=True)
        if name == "show":
            command.add_argument("--version", type=int)
    compare = memory_sub.add_parser("compare")
    compare.add_argument("--agent-id", type=int, required=True)
    compare.add_argument("--version-a", type=int, required=True)
    compare.add_argument("--version-b", type=int, required=True)
    compare.add_argument("--out", type=Path)
    return parser


def _formation(persona: Persona) -> dict[str, int]:
    goalkeeper, defender, midfielder, forward = persona.default_formation
    return {
        "goalkeeper": goalkeeper,
        "defender": defender,
        "midfielder": midfielder,
        "forward": forward,
    }


def _config(
    args: argparse.Namespace, persona: Persona, path_length: int, template_id: str
) -> RunnerConfig:
    return RunnerConfig(
        model_requested=args.model_id,
        route=args.route,
        persona=persona,
        path_length=path_length,
        turn_timeout_ms=default_timeout_ms(args.route),
        template_id=template_id,
    )


def _subscription_port(
    args: argparse.Namespace,
    environ: Mapping[str, str],
    config: RunnerConfig,
    *,
    persona: Persona,
    api: ApiClient,
    state_dir: Path,
    stack: ExitStack,
    io: Interaction,
) -> tuple[ModelPort, RunnerConfig, FlatFeeGate]:
    """A subscription route: no OpenRouter key or ledger; every start check runs before any spawn."""
    if args.env_file is not None:
        raise UsageError("--env-file is not used on a subscription route")
    try:
        settings = SubscriptionSettings.from_env(environ)
        port_settings = ModelPortSettings.from_env(environ)
        batch_id = check_batch_id(args.batch_id or uuid4().hex)
    except ValueError as error:
        raise UsageError(str(error)) from None
    route = args.route
    if not settings.is_on(route):
        raise UsageError(f"{AdapterDisabled.__name__} {route}")
    directory = state_dir / "subscription"
    ledger = BatchLedger(directory / "calls.jsonl")
    gate = BatchGate(
        route=route,
        model_id=args.model_id,
        batch_id=batch_id,
        max_calls=min(args.max_calls, settings.max_calls),
        usage=CommandUsageReader(settings.usage_cmd, max_age_s=settings.usage_max_age_s),
        ledger=ledger,
        lock=BatchLock(directory / "batch.lock"),
        environ=environ,
        container_markers=io.container_markers,
    )
    stack.callback(gate.close)
    gate.begin(is_terminal=io.is_terminal, reconcile=args.reconcile)
    try:
        proof = verify_owner(
            api,
            agent_id=args.agent_id,
            persona_key=persona.key,
            display_name=persona.display_name,
            route=route,
            model_id=args.model_id,
            device_owner_id=settings.owner_member_id or 0,
        )
    except ApiProblem as problem:
        # The API answers 404 for an agent the runner may not act for.
        if problem.status in (403, 404):
            raise SubscriptionRefusal("NOT_OWNER_AGENT") from None
        raise
    gate.attach_owner(proof)
    gate.finish_start(ask=io.ask, say=io.say)
    extra = settings.extra_env(route)
    try:
        binary = resolve_binary(route, environ)
        version = read_version(binary, settings.work_root, environ, extra)
        check_login(route, binary, settings.work_root, environ, extra)
    except SubscriptionRefusal:
        gate.invalidate()
        raise
    gate.activate()
    cli = CliConfig(binary, version, settings.work_root, extra_env=extra, source_env=environ)
    wiring = SubscriptionWiring(
        gate,
        claude=cli if route == CLAUDE_CLI else None,
        codex=cli if route == CODEX_CLI else None,
    )
    adapter = build_registry(port_settings, subscription=wiring).get(route)
    return LedgeredPort(adapter, gate, ledger), replace(config, cost_mode=FLAT_FEE), gate


def _live_port(
    args: argparse.Namespace,
    environ: Mapping[str, str],
    config: RunnerConfig,
    context: LiveContext,
) -> tuple[ModelPort, RunnerConfig, FlatFeeGate | None]:
    if args.max_calls is None:
        raise UsageError("--live needs --max-calls")
    if args.route in SUBSCRIPTION_ROUTES:
        return _subscription_port(
            args,
            environ,
            config,
            persona=context.persona,
            api=context.api,
            state_dir=context.state_dir,
            stack=context.stack,
            io=context.io,
        )
    try:
        settings = Settings.from_env(args.env_file, live=True)
        port_settings = ModelPortSettings.from_env(environ)
    except (ValueError, OSError) as error:
        # The texts of these two readers are fixed names of settings, never values.
        raise UsageError(
            str(error) if isinstance(error, ValueError) else "env file cannot be read"
        ) from None
    if (
        args.route == OLLAMA
        and port_settings.ollama_base_url is not None
        and not is_loopback(port_settings.ollama_base_url)
    ):
        raise UsageError("a remote Ollama address is refused: its cost cannot be counted")
    if settings.ledger_path is None:
        raise UsageError("ANTLAB_OPENROUTER_LEDGER is required")
    client = OpenRouterClient(
        settings.api_key or "",
        Limiter(Ledger(settings.ledger_path), settings.tier),
        send_cap=SendCap(args.max_calls),
    )
    registry = build_registry(port_settings, openrouter=client)
    try:
        port = registry.get(args.route)
    except AdapterDisabled as error:
        raise UsageError(f"{type(error).__name__} {args.route}") from None
    if args.route == OLLAMA and port_settings.ollama_base_url is not None:
        if not is_loopback(port_settings.ollama_base_url):
            raise UsageError("a remote Ollama address is refused: its cost cannot be counted")
        config = replace(config, cost_mode=KNOWN)
    return port, config, None


def _port(
    args: argparse.Namespace,
    environ: Mapping[str, str],
    config: RunnerConfig,
    context: LiveContext,
) -> tuple[ModelPort, RunnerConfig, FlatFeeGate | None]:
    """The scripted fake, or the configured route when ``--live``; never both, never neither."""
    if args.live == (args.model_script is not None):
        raise UsageError("run needs exactly one of --model-script and --live")
    if args.live:
        return _live_port(args, environ, config, context)
    return ScriptedPort(load_script(args.model_script), args.route), config, None


def _register(api: ApiClient, args: argparse.Namespace) -> int:
    persona = load_persona(args.persona)
    agent, created = api.register_agent(
        persona.key, args.route, args.model_id, persona.display_name
    )
    print(f"agent_id={agent['agentId']} created={str(created).lower()}")
    return EXIT_OK


def _start_season(
    api: ApiClient,
    persona: Persona,
    route: str,
    model_id: str,
    agent_id: int,
    *,
    length: int,
    league_id: int | None = None,
    memory_version: int | None = None,
) -> dict:
    """Start one season; with a league the league's path length is used and not sent."""
    config = RunnerConfig(
        model_requested=model_id,
        route=route,
        persona=persona,
        path_length=length,
        turn_timeout_ms=default_timeout_ms(route),
        memory_version=memory_version,
    )
    return api.start_season(
        agent_id,
        persona.default_risk_limit,
        _formation(persona),
        None if league_id is not None else length,
        season_config(config),
        league_id=league_id,
    )


def _start(api: ApiClient, args: argparse.Namespace) -> int:
    persona = load_persona(args.persona)
    if args.league is None:
        length = DEFAULT_PATH_LENGTH if args.path_length is None else args.path_length
    else:
        length = api.get_league(args.league)["pathLength"]
        if args.path_length is not None and args.path_length != length:
            raise UsageError("--path-length differs from the league's path length")
    waiting = None
    if args.memory == "latest":
        state = api.get_memory(args.agent_id)
        pin, waiting = state["latestVersion"], len(state["pendingSeasonIds"])
    else:
        pin = None if args.memory == "none" else int(args.memory)
    season = _start_season(
        api,
        persona,
        args.route,
        args.model_id,
        args.agent_id,
        length=length,
        league_id=args.league,
        memory_version=pin,
    )
    print(
        f"season_id={season['id']} path_length={season['pathLength']} "
        f"memory_version={'none' if pin is None else pin}"
        + (f" memory_pending={waiting}" if waiting else "")
    )
    return EXIT_OK


def _league_line(league: dict) -> str:
    entries = league["entries"]
    return (
        f"league_id={league['id']} status={league['status']} path_length={league['pathLength']} "
        f"entries={len(entries)} max_entries={league['maxEntries']}"
    )


def _league_show(league: dict) -> None:
    print(_league_line(league))
    for entry in league["entries"]:
        print(
            f"seat season_id={entry['seasonId']} kind={entry['memberKind']} "
            f"status={entry['status']} turn={entry['currentTurnNo']}"
        )


def _league_enter(api: ApiClient, args: argparse.Namespace) -> int:
    entries = load_roster(args.roster)
    personas = [load_persona(entry.persona) for entry in entries]  # refuse the whole roster first
    # One read before any registration: a missing or closed league is refused early, and a seat that
    # exists is skipped without a start (the start would answer SEASON_LIMIT or a persona pin first).
    league = api.get_league(args.league)
    if league["status"] != "OPEN":
        raise UsageError("the league is closed")
    seated = {e["memberId"] for e in league["entries"] if e["memberKind"] == "AGENT"}
    taken = 0
    for number, (entry, persona) in enumerate(zip(entries, personas, strict=True), start=1):
        agent, _created = api.register_agent(
            persona.key, entry.route, entry.model_id, persona.display_name
        )
        agent_id = agent["agentId"]
        if agent_id in seated:
            taken += 1
            print(f"entry={number} agent_id={agent_id} seat=taken")
            continue
        latest = api.get_memory(agent_id)["latestVersion"]
        try:
            season = _start_season(
                api,
                persona,
                entry.route,
                entry.model_id,
                agent_id,
                length=league["pathLength"],
                league_id=args.league,
                memory_version=latest,
            )
        except ApiProblem as problem:
            # A seat taken between the read and the start (a second runner).
            if problem.status != 409 or problem.code != "LEAGUE_SEAT_TAKEN":
                raise
            taken += 1
            print(f"entry={number} agent_id={agent_id} seat=taken")
            continue
        print(f"entry={number} agent_id={agent_id} season_id={season['id']} seat=started")
    print(f"entered={len(entries) - taken} already_seated={taken}")
    return EXIT_OK


def _league(api: ApiClient, args: argparse.Namespace) -> int:
    command = args.league_command
    if command == "create":
        league = api.create_league(args.name, args.path_length, args.max_entries, args.same_seed_as)
        print(_league_line(league))
    elif command == "enter":
        return _league_enter(api, args)
    elif command == "show":
        _league_show(api.get_league(args.league))
    else:
        print(_league_line(api.close_league(args.league)))
    return EXIT_OK


def _check_bound(path: Path, wanted: dict[str, str]) -> None:
    try:
        recorded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise UsageError("the season binding file cannot be read") from None
    # The runner version may change between runs; the persona, model and route may not.
    if any(recorded.get(k) != v for k, v in wanted.items() if k != "runnerVersion"):
        raise UsageError("persona, model or route differ from this season's first run")


def _check_recorded(
    api: ApiClient,
    agent_id: int,
    season_id: int,
    args: argparse.Namespace,
    persona: Persona,
    *,
    identity_only: bool = False,
) -> dict:
    """Compare the season's recorded config with the local values before any model call.

    The runner version may differ, and so may the prompt template as long as it is a known one: a
    season keeps the template it was started with. A difference ends the run (exit 2) and prints
    no value. Returns the recorded config. With ``identity_only`` (the memory reflection) the persona
    text may have been edited since the season: only persona key, route and model are compared.
    """
    recorded = api.get_season_config(agent_id, season_id)
    local = {
        "personaKey": persona.key,
        "personaVersion": persona.version,
        "personaSha256": persona.sha256,
        "modelRoute": args.route,
        "modelRequested": args.model_id,
    }
    if identity_only:
        for name in ("personaVersion", "personaSha256"):
            del local[name]
    if (
        any(recorded.get(name) != value for name, value in local.items())
        or recorded.get("promptTemplateVersion") not in TEMPLATES
    ):
        raise UsageError(
            "persona, model, route or prompt template differ from the season's recorded config"
        )
    return recorded


def _check_pinned_memory(recorded: dict, view: dict) -> None:
    """The turn screen must carry the memory version the season was started with, and nothing else."""
    pinned = recorded["memoryVersion"]
    memory = view["agent"]["memory"]
    if (memory is None) != (pinned is None) or (memory is not None and memory["version"] != pinned):
        raise UsageError(
            "the turn screen's memory differs from the version the season was started with"
        )


def _bind(journal_path: Path, config: RunnerConfig) -> None:
    """Pin the season to the persona, model and route of its first run; a later mismatch is refused."""
    wanted = {
        "personaSha256": config.persona.sha256,
        "modelRequested": config.model_requested,
        "route": config.route,
        "runnerVersion": __version__,
    }
    path = journal_path.with_suffix(".bind")
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        _check_bound(path, wanted)
        return
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(wanted, handle)


def _run(
    api: ApiClient,
    settings: RunnerSettings,
    args: argparse.Namespace,
    environ: Mapping[str, str],
    sleep: Callable[[float], None],
    io: Interaction,
) -> int:
    persona = load_persona(args.persona)
    if args.route not in SUBSCRIPTION_ROUTES and (args.batch_id is not None or args.reconcile):
        raise UsageError("--batch-id and --reconcile are for a subscription route")
    if args.max_calls is not None and args.max_calls < 1:
        raise UsageError("--max-calls must be at least 1")
    with (
        ExitStack() as stack,
        Journal.open(settings.state_dir, args.agent_id, args.season_id) as journal,
    ):
        recorded = _check_recorded(api, args.agent_id, args.season_id, args, persona)
        view = api.get_turn_view(args.agent_id, args.season_id)
        _check_pinned_memory(recorded, view)
        config = _config(
            args, persona, view["season"]["pathLength"], recorded["promptTemplateVersion"]
        )
        _bind(journal.path, config)
        state = journal.state
        if len(state.pauses) > state.resumes and not args.resume:
            raise StillPaused(state.pauses[-1][1])
        context = LiveContext(persona, api, settings.state_dir, stack, io)
        port, config, gate = _port(args, environ, config, context)
        runner = SeasonRunner(
            api=api,
            journal=journal,
            port=port,
            config=config,
            agent_id=args.agent_id,
            season_id=args.season_id,
            sleep=sleep,
            send_cap=None if args.max_calls is None else SendCap(args.max_calls),
            gate=gate,
        )
        result = runner.run(resume=args.resume)
    reason = f" reason={result.reason}" if result.reason else ""
    print(f"state={result.state}{reason} decisions={result.decisions} sends={result.sends}")
    return EXIT_OK if result.state == FINISHED else EXIT_PAUSED


def _status(api: ApiClient, settings: RunnerSettings, args: argparse.Namespace) -> int:
    with Journal.open(settings.state_dir, args.agent_id, args.season_id) as journal:
        state = journal.state
        decided = sum(1 for t in state.turns.values() if t.decided is not None)
        advanced = sum(1 for t in state.turns.values() if t.advanced)
        paused = len(state.pauses) > state.resumes
        reason = state.pauses[-1][1] if state.pauses else "-"
        print(
            f"attempts={state.attempt_total} decided_turns={decided} advanced_turns={advanced} "
            f"no_response_streak={state.no_response_streak} paused={str(paused).lower()} "
            f"last_pause={reason} resumes={state.resumes}"
        )
    try:
        season = api.get_turn_view(args.agent_id, args.season_id)["season"]
    except (ApiProblem, ApiUnavailable, ContractError):
        print("season=unavailable")
    else:
        print(f"season_status={season['status']} current_turn={season['currentTurnNo']}")
    return EXIT_OK


# ---- memory commands (AG-05) ------------------------------------------------------------


def _memory_pending(api: ApiClient, args: argparse.Namespace) -> int:
    state = api.get_memory(args.agent_id)
    latest = state["latestVersion"]
    print(
        f"latest_version={'none' if latest is None else latest} "
        f"pending={len(state['pendingSeasonIds'])} held={state['heldSeasonCount']}"
    )
    for season_id in state["pendingSeasonIds"]:
        print(f"pending season_id={season_id}")
    return EXIT_OK


def _memory_show(api: ApiClient, args: argparse.Namespace) -> int:
    version = args.version
    if version is None:
        version = api.get_memory(args.agent_id)["latestVersion"]
        if version is None:
            print("latest_version=none")
            return EXIT_OK
    shown = api.get_memory_version(args.agent_id, version)
    memory = shown["memory"]
    print(f"version={memory['version']} carried={str(shown['playbookCarried']).lower()}")
    for number, rule in enumerate(memory["playbook"], start=1):
        print(f"rule {number}: {rule}")
    for body in memory["reflections"]:
        print("reflection:")
        for line in body["good"]:
            print(f"  good: {line}")
        for item in body["mistakes"]:
            print(f"  mistake {item['type']}: {item['text']}")
        for line in body["ruleCandidates"]:
            print(f"  candidate: {line}")
    return EXIT_OK


def _memory_compare(api: ApiClient, args: argparse.Namespace) -> int:
    report = render_comparison(
        api.get_memory_comparison(args.agent_id, args.version_a, args.version_b)
    )
    if args.out is None:
        print(report, end="")
    else:
        args.out.write_text(report, encoding="utf-8")
        print(f"written={args.out.name}")
    return EXIT_OK


def _memory_line(season_id: int, result: StepResult) -> str:
    parts = [f"season_id={season_id}", f"state={result.state}"]
    if result.version is not None:
        parts.append(f"version={result.version}")
    if result.reason:
        parts.append(f"reason={result.reason}")
    parts.append(f"sends={result.sends}")
    if result.dropped:
        parts.append(f"dropped={result.dropped}")
    return " ".join(parts)


def _memory_reflect(
    api: ApiClient,
    settings: RunnerSettings,
    args: argparse.Namespace,
    environ: Mapping[str, str],
    sleep: Callable[[float], None],
    io: Interaction,
) -> int:
    if args.route in SUBSCRIPTION_ROUTES:
        raise UsageError("the memory step does not run on a subscription route")
    if args.season_id is None and not args.all_pending:
        raise UsageError("memory reflect needs --season-id or --all-pending")
    if args.max_calls is not None and args.max_calls < 1:
        raise UsageError("--max-calls must be at least 1")
    if args.max_seasons < 1:
        raise UsageError("--max-seasons must be at least 1")
    persona = load_persona(args.persona)
    base = RunnerConfig(
        model_requested=args.model_id,
        route=args.route,
        persona=persona,
        path_length=DEFAULT_PATH_LENGTH,
        turn_timeout_ms=default_timeout_ms(args.route),
    )
    with ExitStack() as stack:
        context = LiveContext(persona, api, settings.state_dir, stack, io)
        port, base, _gate = _port(args, environ, base, context)
        send_cap = None if args.max_calls is None else SendCap(args.max_calls)
        if args.season_id is not None:
            seasons = [args.season_id]
        else:
            seasons = api.get_memory(args.agent_id)["pendingSeasonIds"][: args.max_seasons]
        waiting = False
        for season_id in seasons:
            recorded = _check_recorded(
                api, args.agent_id, season_id, args, persona, identity_only=True
            )
            config = replace(
                base,
                budget_calls=recorded["budgetCalls"],
                budget_usd=Decimal(recorded["budgetUsd"]),
                memory_reserve=0,
            )
            with Journal.open(settings.state_dir, args.agent_id, season_id) as journal:
                step = MemoryStep(
                    api=api,
                    journal=journal,
                    port=port,
                    config=config,
                    agent_id=args.agent_id,
                    season_id=season_id,
                    sleep=sleep,
                    send_cap=send_cap,
                    discard_on_budget=args.discard_on_budget,
                )
                result = step.run()
            print(_memory_line(season_id, result))
            if result.state == NOT_OFFERED and result.reason == "MEMORY_NOT_ELIGIBLE":
                continue  # a VOID or abandoned season never gets a reflection; it does not hold the rest back
            if result.state in (PENDING, MEMORY_STOPPED, NOT_OFFERED):
                # Later seasons would be refused (MEMORY_ORDER) until this one has an outcome.
                waiting = True
                break
        if not seasons:
            print("pending=0")
    return EXIT_PAUSED if waiting else EXIT_OK


def _memory(
    api: ApiClient,
    settings: RunnerSettings,
    args: argparse.Namespace,
    environ: Mapping[str, str],
    sleep: Callable[[float], None],
    io: Interaction,
) -> int:
    command = args.memory_command
    if command == "reflect":
        return _memory_reflect(api, settings, args, environ, sleep, io)
    if command == "pending":
        return _memory_pending(api, args)
    if command == "show":
        return _memory_show(api, args)
    return _memory_compare(api, args)


def main(
    argv: Sequence[str] | None = None,
    *,
    environ: Mapping[str, str] | None = None,
    transport: Transport = urllib_transport,
    sleep: Callable[[float], None] = time.sleep,
    io: Interaction | None = None,
) -> int:
    args = _parser().parse_args(argv)
    env: Mapping[str, str] = os.environ if environ is None else environ
    try:
        settings = RunnerSettings.from_env(env)
        api = ApiClient(settings, transport=transport, sleep=sleep)
        if args.command == "register":
            return _register(api, args)
        if args.command == "start":
            return _start(api, args)
        if args.command == "league":
            return _league(api, args)
        if args.command == "memory":
            return _memory(api, settings, args, env, sleep, io or Interaction())
        if args.command == "run":
            return _run(api, settings, args, env, sleep, io or Interaction())
        return _status(api, settings, args)
    except _USAGE_ERRORS as error:
        print(f"error={type(error).__name__} {error}", file=sys.stderr)
        return EXIT_USAGE
    except _RUN_ERRORS as error:
        print(f"error={type(error).__name__} {error}", file=sys.stderr)
        return EXIT_ERROR
    except Exception as error:  # noqa: BLE001 - last resort: the class name only, never a message
        print(f"error={type(error).__name__}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
