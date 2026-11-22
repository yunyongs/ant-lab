"""`overlap run`: the probe lines of a finished M3-03 run, then the overlap analysis (M3-06).

For every selected cell the probe roster (trait bots and the evaluation random bots) runs on the
worlds of the main cell without rank counts, so a world costs about half of an M3-03 world.
Each world is one write unit of `overlap/cells/<cell>.jsonl`: a finished world is never run again,
lines are written in world order whatever the worker count, and the run only reads the main cell
files (never rewrites them). Nothing is written to a database.
"""

import os
import resource
import sys
import time
from collections.abc import Callable, Iterator, Mapping, Sequence
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from antlab_engine import __version__ as engine_version
from antlab_engine.bots import io
from antlab_engine.bots.random_bot import VERSION as RANDOM_VERSION
from antlab_engine.bots.runner import BotRun, make_bot, run_bot
from antlab_engine.bots.trait_bot import TRAITS, TraitBot, level_text
from antlab_engine.bots.trait_bot import VERSION as TRAIT_VERSION
from antlab_engine.bots.world import BotWorld
from antlab_engine.bots.world_panel import panel_world
from antlab_engine.settings import DatabaseSettings, SettingsError
from antlab_engine.sim.rules import SCORE_VERSION

from .components import components_of
from .experiment import (
    CELLS_DIR,
    MAX_WORKERS,
    OVERLAP_DIR,
    SOURCE_DB,
    Cell,
    ExperimentError,
    Spec,
    WorldJob,
    _db_jobs,
    _file_text,
    _replace,
    analyze_overlap_run,
    git_commit,
    main_world_ids,
    probe_path,
    read_manifest,
    read_probe,
    scan_cell_file,
    scan_trait_file,
    trait_file,
    trait_roster,
    validate_cell,
)
from .overlap import TRAIT_SCHEMA
from .trade_flags import event_reactions, flags_of

DEFAULT_CELLS = ("p60s", "p24s")
RUN_FILE = "run.json"
RUN_SCHEMA = "process-overlap-run/1"
PROBE_SCHEMA = "process-overlap-probe/1"


def _raw(record: Mapping[str, object]) -> dict[str, object]:
    report, discipline = record["report"], record["discipline"]
    return {
        "turnoverAnnual": report["turnoverAnnual"],
        "panicShare": discipline["panic"],
        "chaseShare": discipline["chase"],
        "noiseShare": record["noiseShare"],
        "nudgeShare": report["nudgeShare"],
    }


def trait_line(
    cell_id: str, world_index: int, spec: Spec, run: BotRun, world: BotWorld, bot: object
) -> str:
    """One probe line: the item components, the traits (trait bots), raw shares and trade flags."""
    comp = components_of(run.record)
    if comp.kind != spec.kind or run.season is None:
        raise ExperimentError(f"probe bot {spec.kind} recorded kind {comp.kind}")
    traits: dict[str, str] = {}
    if isinstance(bot, TraitBot):
        traits = {t: level_text(bot.levels[t]) for t in TRAITS}
    flags = flags_of(run.season, run.signals, event_reactions(world.feed))
    return io.record_line(
        {
            "schema": TRAIT_SCHEMA,
            "cell": cell_id,
            "worldIndex": world_index,
            "role": spec.role,
            "worldId": comp.world_id,
            "pathLength": comp.path_length,
            "kind": comp.kind,
            "index": comp.index,
            "components": comp.to_wire(),
            "traits": traits,
            "raw": _raw(run.record),
            "flags": flags.to_wire(),
        }
    )


def run_probe_world(job: WorldJob) -> tuple[int, str, list[str]]:
    """The probe roster on one world, without rank counts."""
    cell = job.cell
    if job.world is not None:
        world = job.world
    else:
        world, _ = panel_world(
            job.master_seed,
            job.index,
            length=cell.turns,
            assets=cell.assets or 0,
            generator=cell.generator,
        )
    lines = []
    for spec in trait_roster(cell):
        bot = make_bot(spec.kind, spec.index, job.master_seed, world.seed)
        run = run_bot(world, bot, counts_source=None)
        lines.append(trait_line(cell.id, job.index, spec, run, world, bot))
    return job.index, world.world_id, lines


def _results(jobs: Sequence[WorldJob], workers: int) -> Iterator[tuple[int, str, list[str]]]:
    if workers <= 1:
        for job in jobs:
            yield run_probe_world(job)
        return
    with ProcessPoolExecutor(max_workers=workers) as pool:
        yield from pool.map(run_probe_world, jobs)


def probe_provenance() -> dict[str, object]:
    """What the probe lines depend on: bot versions, score and engine version, code commit."""
    return {
        "schema": PROBE_SCHEMA,
        "botVersions": {"random": RANDOM_VERSION, "trait": TRAIT_VERSION},
        "scoreVersion": SCORE_VERSION,
        "engineVersion": engine_version,
        "gitCommit": git_commit(),
    }


def _guard_probe(out: Path, cells: Sequence[Cell]) -> None:
    """Write the provenance on the first run; refuse to resume probe lines of other code."""
    expected = probe_provenance()
    found = read_probe(out)
    if found is None:
        if any(trait_file(out, c).exists() for c in cells):
            raise ExperimentError(
                "the probe folder has lines but no provenance file; move `overlap/` away "
                "or use a new run directory"
            )
        probe_path(out).parent.mkdir(parents=True, exist_ok=True)
        _replace(probe_path(out), io.record_line(expected) + "\n")
    elif found != expected:
        raise ExperimentError(
            "the probe folder was written by other code or bot versions "
            f"(found {found}, now {expected}); move `overlap/` away or use a new run directory"
        )


def _selected(cells: Sequence[Cell], names: Sequence[str] | None) -> list[Cell]:
    by_id = {c.id: c for c in cells}
    if names is None:
        chosen = [by_id[i] for i in DEFAULT_CELLS if i in by_id]
        return chosen or list(cells)
    unknown = [n for n in names if n not in by_id]
    if unknown:
        raise ExperimentError(f"unknown cell {unknown}; the run has {sorted(by_id)}")
    return [by_id[n] for n in names]


@dataclass(frozen=True)
class OverlapRunSummary:
    ran: dict[str, list[int]]
    wall_seconds: float
    cpu_seconds: float
    peak_rss_mb: float
    result_path: Path
    summary_path: Path


def _cpu_seconds() -> float:
    t = os.times()
    return t.user + t.system + t.children_user + t.children_system


def _peak_rss_mb() -> float:
    own = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    children = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
    divisor = 1024 * 1024 if sys.platform == "darwin" else 1024
    return round(max(own, children) / divisor, 1)


def _run_cell(
    cell: Cell,
    out: Path,
    meta: Mapping[str, object],
    workers: int,
    say: Callable[[str], None],
) -> list[int]:
    main = scan_cell_file(out / CELLS_DIR / f"{cell.id}.jsonl", cell)
    missing = [w for w in range(cell.worlds) if w not in main.complete]
    if missing or main.bad_lines:
        raise ExperimentError(
            f"cell {cell.id} of the main run is unfinished ({len(missing)} worlds lack a full "
            "roster); finish it with `run` before `overlap run`"
        )
    ids = main_world_ids(main)
    path = trait_file(out, cell)
    path.parent.mkdir(parents=True, exist_ok=True)
    scan = scan_trait_file(out, cell, main)
    pending = [w for w in range(cell.worlds) if w not in scan.complete]
    say(
        f"cell {cell.id}: {len(scan.complete)} of {cell.worlds} probe worlds already finished, "
        f"{len(pending)} to run"
    )
    _replace(path, _file_text(scan.complete))
    master_seed = int(str(meta["masterSeed"]))
    if cell.source == SOURCE_DB and pending:
        base = date.fromisoformat(str(meta["baseDate"]))
        replay = date.fromisoformat(str(meta["replayStart"]))
        jobs = _db_jobs(cell, pending, master_seed, base, replay)
    else:
        jobs = [WorldJob(cell, w, master_seed) for w in pending]
    complete = dict(scan.complete)
    started = time.monotonic()
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        for index, world_id, lines in _results(jobs, workers):
            if world_id != ids[index]:
                raise ExperimentError(
                    f"cell {cell.id} world {index}: the probe world differs from the world of "
                    "the main cell"
                )
            handle.write("".join(line + "\n" for line in lines))
            handle.flush()
            complete[index] = lines
            say(f"cell {cell.id}: probe world {index} done ({time.monotonic() - started:.0f} s)")
    _replace(path, _file_text(complete))
    return pending


def run_overlap(
    out: Path,
    *,
    workers: int = 1,
    cells: Sequence[str] | None = None,
    say: Callable[[str], None] = lambda _message: None,
) -> OverlapRunSummary:
    """Run the probe cells (resumable), then write `overlap.json` and `overlap_summary.md`."""
    if not 1 <= workers <= MAX_WORKERS:
        raise ExperimentError(f"workers must be 1..{MAX_WORKERS} (light resource mode)")
    meta = read_manifest(out)
    descriptors = [Cell.from_descriptor(d) for d in meta["cells"]]
    for cell in descriptors:
        validate_cell(cell)
    chosen = _selected(descriptors, cells)
    if any(c.source == SOURCE_DB for c in chosen):
        try:
            DatabaseSettings.from_env()
        except SettingsError as error:
            raise ExperimentError(f"a database cell needs database settings ({error})") from None
    _guard_probe(out, descriptors)
    wall_start, cpu_start = time.monotonic(), _cpu_seconds()
    ran = {c.id: _run_cell(c, out, meta, workers, say) for c in chosen}
    result, summary = analyze_overlap_run(out)
    wall, cpu = time.monotonic() - wall_start, _cpu_seconds() - cpu_start
    peak = _peak_rss_mb()
    timing = io.record_line(
        {
            "schema": RUN_SCHEMA,
            "cells": {c: len(w) for c, w in ran.items()},
            "workers": workers,
            "wallSeconds": round(wall, 2),
            "cpuSeconds": round(cpu, 2),
            "peakRssMbLargestProcess": peak,
        }
    )
    _replace(out / OVERLAP_DIR / RUN_FILE, timing + "\n")
    return OverlapRunSummary(
        {c: sorted(w) for c, w in ran.items()}, wall, cpu, peak, result, summary
    )
