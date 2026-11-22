"""Experiment driver of the score-weight validation (M3-03): roster, cells, profiles and files.

One cell is a set of worlds that share a size (turns, assets, path generator, path count). Every
world of a cell runs the same roster of bots; the components of each bot go to one JSONL line of
`cells/<cell>.jsonl`. A world is the unit of work: workers are chunked by world, so a world's rank
counts are drawn once and the cache is never rebuilt per chunk. Files are written in world order
whatever the worker count, and a finished world is never run twice.

`run` and `analyze` meet in one place: the analysis always reads the written files, never the
in-memory values, so both give the same `result.json`. Nothing here touches a database except
through `bots.world_pg` (DB profiles), and nothing is written to a database.
"""

import itertools
import json
import os
import re
import resource
import sys
import time
from collections import Counter
from collections.abc import Callable, Iterator, Mapping, Sequence
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from antlab_engine import __version__ as engine_version
from antlab_engine.bots import io
from antlab_engine.bots.disciplined_bot import VERSION as DISCIPLINED_VERSION
from antlab_engine.bots.news_bot import VERSION as NEWS_VERSION
from antlab_engine.bots.newsmix_bot import VERSION as NEWSMIX_VERSION
from antlab_engine.bots.random_bot import VERSION as RANDOM_VERSION
from antlab_engine.bots.redeclare_bot import VERSION as REDECLARE_VERSION
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.static_bot import VERSION as STATIC_VERSION
from antlab_engine.bots.world import BotWorld
from antlab_engine.bots.world_feed import default_catalog
from antlab_engine.bots.world_panel import GENERATORS, MIN_ASSETS, panel_world, world_seed
from antlab_engine.engine_errors import EngineError
from antlab_engine.projection import ReturnPanel
from antlab_engine.projection.model import MAX_PATHS
from antlab_engine.projection.rank import MIN_PATHS
from antlab_engine.settings import DatabaseSettings, SettingsError, replay_start_from_env
from antlab_engine.sim.feed.rules import FeedGenerationError
from antlab_engine.sim.rules import SCORE_VERSION

from .checks import ROLE_EVAL, ROLE_NAMED, ROLE_REF, BotRow
from .components import ComponentError, Components, components_from_wire, components_of
from .counts_cache import CachedEngineCounts
from .output import STATUS_FINAL, STATUS_PROVISIONAL, build_result, write_overlap, write_run
from .overlap import (
    STATUS_PARTIAL,
    TRAIT_SCHEMA,
    Boots,
    OverlapError,
    assemble_overlap,
)
from .overlap import analyze_cell as analyze_overlap_cell
from .search import analyze_cell

PROFILES = ("ci", "offline", "sample-db", "post-rl")
DEFAULT_MASTER_SEED = 303
DEFAULT_BASE_DATE = date(2026, 1, 1)
MAX_WORKERS = 2
DOSE_FULL = (25, 50, 75)
OFFLINE_REFERENCE = 200
LINE_SCHEMA = "validation-component/1"
DRIVER_SCHEMA = "validation-driver/1"
MANIFEST_FILE = "manifest.json"
CELLS_DIR = "cells"
OVERLAP_DIR = "overlap"
SOURCE_PANEL = "panel"
SOURCE_DB = "db"
_ID = re.compile(r"[a-z][a-z0-9]{0,15}")
_IDENTITY_KEYS = ("schema", "profile", "masterSeed", "baseDate", "replayStart", "cells", "manifest")


class ExperimentError(ValueError):
    """A profile, cell or run directory that cannot be run or analysed."""


class ProfileSkipped(Exception):  # noqa: N818 - a clean skip, not a failure
    """A profile that needs something this machine does not have (a database)."""


@dataclass(frozen=True)
class Cell:
    id: str
    source: str
    worlds: int
    turns: int
    assets: int | None
    generator: str
    paths: int
    reference: int
    newsmix: tuple[int, ...]
    redeclare: int
    dose_required: tuple[int, ...]

    def descriptor(self) -> dict[str, object]:
        return {
            "id": self.id,
            "source": self.source,
            "worlds": self.worlds,
            "turns": self.turns,
            "assets": self.assets,
            "generator": self.generator,
            "paths": self.paths,
            "referenceSize": self.reference,
            "newsmix": list(self.newsmix),
            "redeclare": self.redeclare,
            "doseRequired": list(self.dose_required),
        }

    @classmethod
    def from_descriptor(cls, doc: Mapping[str, object]) -> "Cell":
        try:
            return cls(
                str(doc["id"]),
                str(doc["source"]),
                int(doc["worlds"]),  # type: ignore[call-overload]
                int(doc["turns"]),  # type: ignore[call-overload]
                None if doc["assets"] is None else int(doc["assets"]),  # type: ignore[call-overload]
                str(doc["generator"]),
                int(doc["paths"]),  # type: ignore[call-overload]
                int(doc["referenceSize"]),  # type: ignore[call-overload]
                tuple(int(q) for q in doc["newsmix"]),  # type: ignore[attr-defined]
                int(doc["redeclare"]),  # type: ignore[call-overload]
                tuple(int(q) for q in doc["doseRequired"]),  # type: ignore[attr-defined]
            )
        except (KeyError, TypeError, ValueError) as error:
            raise ExperimentError("a cell descriptor in the run is malformed") from error


def validate_cell(cell: Cell) -> None:
    if not _ID.fullmatch(cell.id):
        raise ExperimentError(f"cell id {cell.id!r} must be lower-case letters and digits")
    if cell.source not in (SOURCE_PANEL, SOURCE_DB):
        raise ExperimentError(f"cell {cell.id}: unknown source {cell.source!r}")
    if cell.generator not in GENERATORS:
        raise ExperimentError(f"cell {cell.id}: generator must be one of {GENERATORS}")
    if cell.worlds < 2:
        raise ExperimentError(f"cell {cell.id}: needs a training and a test world")
    if cell.source == SOURCE_PANEL and (cell.assets is None or cell.assets < MIN_ASSETS):
        raise ExperimentError(f"cell {cell.id}: a panel world needs at least {MIN_ASSETS} assets")
    if not MIN_PATHS <= cell.paths <= MAX_PATHS:
        raise ExperimentError(f"cell {cell.id}: paths must be {MIN_PATHS}..{MAX_PATHS}")
    if cell.reference < 2:
        raise ExperimentError(f"cell {cell.id}: the reference set needs at least 2 bots")
    if not 0 <= cell.redeclare <= cell.reference:
        raise ExperimentError(f"cell {cell.id}: redeclare bots pair with evaluation bots")
    if len(set(cell.newsmix)) != len(cell.newsmix) or any(not 0 < q < 100 for q in cell.newsmix):
        raise ExperimentError(f"cell {cell.id}: newsmix takes distinct percents inside 1..99")
    missing = sorted(set(cell.dose_required) - set(cell.newsmix))
    if missing:
        raise ExperimentError(
            f"cell {cell.id}: the dose-response check needs newsmix {missing}; "
            "a roster without all its dose points would give a partial C5"
        )


def profile_cells(profile: str) -> tuple[Cell, ...]:
    """The cells of a profile (sizes of plan section 5)."""

    def full(cell_id: str, source: str, worlds: int, turns: int, generator: str) -> Cell:
        return Cell(
            cell_id,
            source,
            worlds,
            turns,
            30 if source == SOURCE_PANEL else None,
            generator,
            2000,
            OFFLINE_REFERENCE,
            DOSE_FULL,
            50,
            DOSE_FULL,
        )

    if profile == "ci":
        return (Cell("ci12", SOURCE_PANEL, 3, 12, 12, "simple", 100, 20, (50,), 2, (50,)),)
    if profile == "offline":
        return (
            full("p60s", SOURCE_PANEL, 100, 60, "simple"),
            full("p60e", SOURCE_PANEL, 60, 60, "stationary"),
            full("p24s", SOURCE_PANEL, 60, 24, "simple"),
            full("p12s", SOURCE_PANEL, 60, 12, "simple"),
        )
    if profile == "sample-db":
        return (full("sdb24", SOURCE_DB, 20, 24, "simple"),)
    if profile == "post-rl":
        return (
            full("rl60", SOURCE_DB, 100, 60, "simple"),
            full("rl24", SOURCE_DB, 60, 24, "simple"),
        )
    raise ExperimentError(f"unknown profile {profile!r}; choose one of {', '.join(PROFILES)}")


# ---------------------------------------------------------------------------- roster


@dataclass(frozen=True)
class Spec:
    kind: str
    index: int
    role: str


def roster(cell: Cell) -> tuple[Spec, ...]:
    """Reference random 0..R-1, evaluation random R..2R-1, static 0..R-1, the named bots, and
    redeclare bots paired with the first evaluation random bots (same index)."""
    r = cell.reference
    specs = [Spec("random", i, ROLE_REF) for i in range(r)]
    specs += [Spec("random", r + i, ROLE_EVAL) for i in range(r)]
    specs += [Spec("static", i, ROLE_EVAL) for i in range(r)]
    specs += [Spec(kind, 0, ROLE_NAMED) for kind in ("disciplined", "disciplined-noguard", "news")]
    specs += [Spec(f"newsmix-{q}", 0, ROLE_NAMED) for q in cell.newsmix]
    specs += [Spec("redeclare", r + i, ROLE_EVAL) for i in range(cell.redeclare)]
    return tuple(specs)


def bot_versions(cell_list: Sequence[Cell]) -> dict[str, int]:
    versions = {
        "random": RANDOM_VERSION,
        "static": STATIC_VERSION,
        "disciplined": DISCIPLINED_VERSION,
        "news": NEWS_VERSION,
    }
    if any(c.newsmix for c in cell_list):
        versions["newsmix"] = NEWSMIX_VERSION
    if any(c.redeclare for c in cell_list):
        versions["redeclare"] = REDECLARE_VERSION
    return versions


# ---------------------------------------------------------------------------- lines


def component_line(
    cell_id: str, world_index: int, role: str, record: Mapping[str, object], comp: Components
) -> str:
    """One component line: identity, the stored s63 score, the rank counts and the components."""
    score = record["score"]
    return io.record_line(
        {
            "schema": LINE_SCHEMA,
            "cell": cell_id,
            "worldIndex": world_index,
            "role": role,
            "worldId": comp.world_id,
            "pathLength": comp.path_length,
            "kind": comp.kind,
            "index": comp.index,
            "score63": score["score"],  # type: ignore[index]
            "counts": record["counts"],
            "components": comp.to_wire(),
        }
    )


def row_of(line: Mapping[str, object]) -> BotRow:
    """The analysis row of a parsed line (12-place values, the same for `run` and `analyze`)."""
    world, role, wire = line["worldIndex"], line["role"], line["components"]
    if type(world) is not int or not isinstance(role, str) or not isinstance(wire, Mapping):
        raise ExperimentError("a component line is malformed")
    return BotRow(world, role, components_from_wire(line, wire))


# ---------------------------------------------------------------------------- one world


@dataclass(frozen=True)
class WorldJob:
    cell: Cell
    index: int
    master_seed: int
    world: BotWorld | None = None
    panel: ReturnPanel | None = None


def run_world(job: WorldJob) -> tuple[int, list[str]]:
    """Every bot of the roster on one world; the world's rank counts are drawn once."""
    cell = job.cell
    if job.world is not None and job.panel is not None:
        world, panel = job.world, job.panel
    else:
        world, panel = panel_world(
            job.master_seed,
            job.index,
            length=cell.turns,
            assets=cell.assets or 0,
            generator=cell.generator,
        )
    counts = CachedEngineCounts(panel)
    lines = []
    for spec in roster(cell):
        bot = make_bot(spec.kind, spec.index, job.master_seed, world.seed)
        run = run_bot(world, bot, counts_source=counts, path_count=cell.paths)
        comp = components_of(run.record)
        if comp.kind != spec.kind:
            raise ExperimentError(f"roster bot {spec.kind} recorded kind {comp.kind}")
        lines.append(component_line(cell.id, job.index, spec.role, run.record, comp))
    return job.index, lines


def _results(jobs: Sequence[WorldJob], workers: int) -> Iterator[tuple[int, list[str]]]:
    if workers <= 1:
        for job in jobs:
            yield run_world(job)
        return
    with ProcessPoolExecutor(max_workers=workers) as pool:
        yield from pool.map(run_world, jobs)


# ---------------------------------------------------------------------------- cell files


def _expected(cell: Cell) -> Counter[tuple[str, int, str]]:
    return Counter((s.kind, s.index, s.role) for s in roster(cell))


@dataclass(frozen=True)
class Scan:
    complete: dict[int, list[str]]
    unfinished: list[int]
    bad_lines: int


def scan_cell_file(path: Path, cell: Cell) -> Scan:
    """Group the lines of a cell file by world; a world is finished when its roster is exact."""
    by_world: dict[int, list[tuple[str, tuple[str, int, str]]]] = {}
    bad = 0
    if path.exists():
        for text in path.read_text(encoding="utf-8").split("\n"):
            if not text:
                continue
            try:
                doc = json.loads(text)
                key = (doc["kind"], doc["index"], doc["role"])
                world = doc["worldIndex"]
                valid = (
                    type(doc["kind"]) is str
                    and type(doc["index"]) is int
                    and doc["role"] in (ROLE_REF, ROLE_EVAL, ROLE_NAMED)
                    and doc["schema"] == LINE_SCHEMA
                    and doc["cell"] == cell.id
                    and type(world) is int
                    and 0 <= world < cell.worlds
                )
            except (ValueError, KeyError, TypeError):
                valid = False
            if not valid:
                bad += 1
                continue
            by_world.setdefault(world, []).append((text, key))
    expected = _expected(cell)
    complete: dict[int, list[str]] = {}
    unfinished = []
    for world, entries in sorted(by_world.items()):
        if Counter(key for _, key in entries) == expected:
            complete[world] = [text for text, _ in entries]
        else:
            unfinished.append(world)
    return Scan(complete, unfinished, bad)


def _replace(path: Path, text: str) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8", newline="\n")
    os.replace(temporary, path)


def _file_text(complete: Mapping[int, Sequence[str]]) -> str:
    return "".join(line + "\n" for world in sorted(complete) for line in complete[world])


# ---------------------------------------------------------------------------- manifest


_HEX40 = re.compile(r"[0-9a-f]{40}")


def _git_dir(start: Path) -> Path | None:
    for folder in (start, *start.parents):
        marker = folder / ".git"
        if marker.is_dir():
            return marker
        if marker.is_file():
            text = marker.read_text(encoding="utf-8").strip()
            if text.startswith("gitdir:"):
                return (folder / text[len("gitdir:") :].strip()).resolve()
    return None


def read_git_commit(start: Path) -> str:
    """The short commit hash of the checkout holding `start`, read from `.git`; else "unknown".

    Reads `HEAD`, the ref file or `packed-refs` (a linked worktree keeps refs in its common dir);
    this is `git rev-parse --short HEAD` without starting a process.
    """
    try:
        git_dir = _git_dir(start.resolve())
        if git_dir is None:
            return "unknown"
        head = (git_dir / "HEAD").read_text(encoding="utf-8").strip()
        if _HEX40.fullmatch(head):
            return head[:7]
        if not head.startswith("ref: "):
            return "unknown"
        ref = head[len("ref: ") :]
        common = git_dir
        if (git_dir / "commondir").is_file():
            common = (
                git_dir / (git_dir / "commondir").read_text(encoding="utf-8").strip()
            ).resolve()
        for base in (git_dir, common):
            candidate = base / ref
            if candidate.is_file():
                text = candidate.read_text(encoding="utf-8").strip()
                return text[:7] if _HEX40.fullmatch(text) else "unknown"
        packed = common / "packed-refs"
        if packed.is_file():
            for line in packed.read_text(encoding="utf-8").splitlines():
                sha, _, name = line.partition(" ")
                if name == ref and _HEX40.fullmatch(sha):
                    return sha[:7]
    except (OSError, ValueError):
        return "unknown"
    return "unknown"


def git_commit() -> str:
    return read_git_commit(Path(__file__).parent)


def result_manifest(cells: Sequence[Cell], master_seed: int, commit: str) -> dict[str, object]:
    seeds: list[str] = []
    for cell in cells:
        for index in range(cell.worlds):
            seed = str(world_seed(master_seed, index))
            if seed not in seeds:
                seeds.append(seed)
    return {
        "masterSeed": str(master_seed),
        "worldSeeds": seeds,
        "botVersions": bot_versions(cells),
        "catalogSha256": default_catalog().sha256,
        "scoreVersion": SCORE_VERSION,
        "engineVersion": engine_version,
        "gitCommit": commit,
    }


def _dump(doc: object) -> str:
    return json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def _peak_rss_mb() -> float:
    own = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    children = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
    divisor = 1024 * 1024 if sys.platform == "darwin" else 1024
    return round(max(own, children) / divisor, 1)


def _cpu_seconds() -> float:
    t = os.times()
    return t.user + t.system + t.children_user + t.children_system


# ---------------------------------------------------------------------------- run


@dataclass(frozen=True)
class RunSummary:
    ran: dict[str, list[int]]
    wall_seconds: float
    cpu_seconds: float
    result_path: Path
    summary_path: Path


def _db_jobs(
    cell: Cell, pending: Sequence[int], master_seed: int, base_date: date, replay_start: date
) -> list[WorldJob]:
    from antlab_engine.bots import world_pg  # noqa: PLC0415 - psycopg only when a DB profile runs

    try:
        settings = DatabaseSettings.from_env()
        panel = world_pg.read_panel(settings, base_date)
    except SettingsError as error:
        raise ExperimentError(f"cell {cell.id}: database settings: {error}") from None
    except EngineError as error:
        raise ExperimentError(f"cell {cell.id} panel: {error.code}") from None
    jobs = []
    for index in pending:
        seed = world_seed(master_seed, index)
        try:
            world = world_pg.read_world(
                settings,
                base_date,
                seed,
                length=cell.turns,
                path_seed=seed,
                replay_start=replay_start,
            )
        except EngineError as error:
            raise ExperimentError(f"cell {cell.id} world {index}: {error.code}") from None
        except FeedGenerationError as error:
            raise ExperimentError(f"cell {cell.id} world {index}: {error.failure.value}") from None
        jobs.append(WorldJob(cell, index, master_seed, world, panel))
    return jobs


def _run_cell(
    cell: Cell,
    out: Path,
    master_seed: int,
    workers: int,
    base_date: date,
    replay_start: date | None,
    say: Callable[[str], None],
) -> list[int]:
    path = out / CELLS_DIR / f"{cell.id}.jsonl"
    scan = scan_cell_file(path, cell)
    pending = [w for w in range(cell.worlds) if w not in scan.complete]
    say(
        f"cell {cell.id}: {len(scan.complete)} of {cell.worlds} worlds already finished, "
        f"{len(pending)} to run"
    )
    _replace(path, _file_text(scan.complete))
    if cell.source == SOURCE_DB and replay_start is not None:
        jobs = _db_jobs(cell, pending, master_seed, base_date, replay_start) if pending else []
    else:
        jobs = [WorldJob(cell, w, master_seed) for w in pending]
    complete = dict(scan.complete)
    started = time.monotonic()
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        for index, lines in _results(jobs, workers):
            handle.write("".join(line + "\n" for line in lines))
            handle.flush()
            complete[index] = lines
            say(f"cell {cell.id}: world {index} done ({time.monotonic() - started:.0f} s)")
    _replace(path, _file_text(complete))
    return pending


def run_profile(
    profile: str,
    out: Path,
    *,
    master_seed: int = DEFAULT_MASTER_SEED,
    workers: int = 1,
    base_date: date = DEFAULT_BASE_DATE,
    commit: str | None = None,
    say: Callable[[str], None] = lambda _message: None,
) -> RunSummary:
    if not 1 <= workers <= MAX_WORKERS:
        raise ExperimentError(f"workers must be 1..{MAX_WORKERS} (light resource mode)")
    cells = profile_cells(profile)
    for cell in cells:
        validate_cell(cell)
    replay_start: date | None = None
    if any(c.source == SOURCE_DB for c in cells):
        try:
            DatabaseSettings.from_env()
        except SettingsError as error:
            raise ProfileSkipped(
                f"profile {profile} reads worlds from the database and no database settings are "
                f"available ({error}); nothing was run"
            ) from None
        try:
            replay_start = replay_start_from_env()
        except (SettingsError, ValueError):
            raise ExperimentError(
                "the replay start variable is not a first-of-month date"
            ) from None
    wall_start, cpu_start = time.monotonic(), _cpu_seconds()
    meta: dict[str, object] = {
        "schema": DRIVER_SCHEMA,
        "profile": profile,
        "masterSeed": str(master_seed),
        "baseDate": base_date.isoformat() if any(c.source == SOURCE_DB for c in cells) else None,
        "replayStart": replay_start.isoformat() if replay_start else None,
        "cells": [c.descriptor() for c in cells],
        "manifest": result_manifest(cells, master_seed, git_commit() if commit is None else commit),
    }
    manifest_path = out / MANIFEST_FILE
    spent = {"wallSeconds": 0.0, "cpuSeconds": 0.0}
    if manifest_path.exists():
        try:
            previous = json.loads(manifest_path.read_text(encoding="utf-8"))
            same = all(previous.get(k) == meta[k] for k in _IDENTITY_KEYS)
            before = {k: float(previous["timing"][k]) for k in spent} if same else spent
        except (ValueError, KeyError, TypeError, AttributeError):
            raise ExperimentError(f"{manifest_path} is unreadable; use a fresh --out") from None
        if not same:
            raise ExperimentError(
                f"{out} holds another run (profile, seed, replay start, cells or commit "
                "differ); use a fresh --out to start over"
            )
        spent = before
    (out / CELLS_DIR).mkdir(parents=True, exist_ok=True)
    _replace(manifest_path, _dump({**meta, "workers": workers, "timing": spent}))
    ran = {
        c.id: _run_cell(c, out, master_seed, workers, base_date, replay_start, say) for c in cells
    }
    wall = time.monotonic() - wall_start
    cpu = _cpu_seconds() - cpu_start
    timing: dict[str, object] = {
        "wallSeconds": round(spent["wallSeconds"] + wall, 2),
        "cpuSeconds": round(spent["cpuSeconds"] + cpu, 2),
        "peakRssMbLargestProcess": _peak_rss_mb(),
    }
    _replace(manifest_path, _dump({**meta, "workers": workers, "timing": timing}))
    analysis_wall, analysis_cpu = time.monotonic(), _cpu_seconds()
    result_path, summary_path = analyze_run(out)
    timing["analysisWallSeconds"] = round(time.monotonic() - analysis_wall, 2)
    timing["analysisCpuSeconds"] = round(_cpu_seconds() - analysis_cpu, 2)
    timing["peakRssMbLargestProcess"] = _peak_rss_mb()
    _replace(manifest_path, _dump({**meta, "workers": workers, "timing": timing}))
    return RunSummary(ran, wall, cpu, result_path, summary_path)


# ---------------------------------------------------------------------------- analyze


def analyze_run(out: Path) -> tuple[Path, Path]:
    """Read the written files of a finished run and write `result.json` and `summary.md`."""
    manifest_path = out / MANIFEST_FILE
    if not manifest_path.exists():
        raise ExperimentError(f"{out} has no {MANIFEST_FILE}: not a validation run directory")
    meta = json.loads(manifest_path.read_text(encoding="utf-8"))
    if meta.get("schema") != DRIVER_SCHEMA:
        raise ExperimentError(f"{MANIFEST_FILE} is not {DRIVER_SCHEMA}")
    master_seed = int(meta["masterSeed"])
    pairs = []
    for descriptor in meta["cells"]:
        cell = Cell.from_descriptor(descriptor)
        validate_cell(cell)
        scan = scan_cell_file(out / CELLS_DIR / f"{cell.id}.jsonl", cell)
        missing = [w for w in range(cell.worlds) if w not in scan.complete]
        if missing or scan.bad_lines:
            raise ExperimentError(
                f"cell {cell.id} is unfinished: {len(missing)} of {cell.worlds} worlds lack a "
                f"full roster, {scan.bad_lines} unreadable lines; run it again to resume"
            )
        rows = []
        for world in sorted(scan.complete):
            for text in scan.complete[world]:
                try:
                    rows.append(row_of(json.loads(text)))
                except (ComponentError, ExperimentError, KeyError) as error:
                    raise ExperimentError(f"cell {cell.id} world {world}: {error}") from None
        pairs.append(
            (cell.descriptor(), analyze_cell(rows, master_seed, reference_size=cell.reference))
        )
    status = STATUS_FINAL if meta["profile"] == "post-rl" else STATUS_PROVISIONAL
    result = build_result(meta["manifest"], pairs, status=status, profile=meta["profile"])
    return write_run(out, result)


# ---------------------------------------------------------------------------- overlap


def _trait_lines(path: Path, cell: Cell) -> Iterator[dict[str, object]]:
    if not path.exists():
        return
    for text in path.read_text(encoding="utf-8").split("\n"):
        if not text:
            continue
        try:
            doc = json.loads(text)
            valid = doc["schema"] == TRAIT_SCHEMA and doc["cell"] == cell.id
        except (ValueError, KeyError, TypeError):
            valid = False
        if not valid:
            raise ExperimentError(f"{path} holds a line that is not a {TRAIT_SCHEMA} line")
        yield doc


def analyze_overlap_run(out: Path, *, partial: bool = False) -> tuple[Path, Path]:
    """Read the cell files of a run and write `overlap/overlap.json` and `overlap_summary.md`.

    An unfinished cell is refused unless `partial`; then only its finished worlds are analysed and
    the status is `partial`.
    """
    manifest_path = out / MANIFEST_FILE
    if not manifest_path.exists():
        raise ExperimentError(f"{out} has no {MANIFEST_FILE}: not a validation run directory")
    meta = json.loads(manifest_path.read_text(encoding="utf-8"))
    if meta.get("schema") != DRIVER_SCHEMA:
        raise ExperimentError(f"{MANIFEST_FILE} is not {DRIVER_SCHEMA}")
    boots = Boots(int(meta["masterSeed"]))
    analysed = []
    incomplete = False
    for descriptor in meta["cells"]:
        cell = Cell.from_descriptor(descriptor)
        validate_cell(cell)
        scan = scan_cell_file(out / CELLS_DIR / f"{cell.id}.jsonl", cell)
        missing = [w for w in range(cell.worlds) if w not in scan.complete]
        if missing or scan.bad_lines:
            if not partial:
                raise ExperimentError(
                    f"cell {cell.id} is unfinished: {len(missing)} of {cell.worlds} worlds lack a "
                    f"full roster, {scan.bad_lines} unreadable lines; run it again to resume, "
                    "or pass --partial"
                )
            incomplete = True
        lines = itertools.chain(
            (json.loads(text) for world in sorted(scan.complete) for text in scan.complete[world]),
            _trait_lines(out / OVERLAP_DIR / CELLS_DIR / f"{cell.id}.jsonl", cell),
        )
        try:
            analysed.append(
                analyze_overlap_cell(
                    cell.descriptor(), lines, boots, partial=bool(missing or scan.bad_lines)
                )
            )
        except OverlapError as error:
            raise ExperimentError(f"overlap analysis failed: {error}") from None
        del scan, lines
    if incomplete:
        status = STATUS_PARTIAL
    else:
        status = STATUS_FINAL if meta["profile"] == "post-rl" else STATUS_PROVISIONAL
    try:
        doc = assemble_overlap(meta["manifest"], meta["profile"], analysed, status=status)
    except OverlapError as error:
        raise ExperimentError(f"overlap analysis failed: {error}") from None
    return write_overlap(out / OVERLAP_DIR, doc)
