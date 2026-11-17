#!/usr/bin/env python3
"""Summarise Gradle JUnit XML and compare the skipped testcases with a reviewed list.

The golden tier needs git-ignored sample CSVs, so on a clean checkout it is skipped by design. The
expected-skips file lists every (class, testcase) that may be skipped. The set of skipped testcases
found in the XML must equal that list, so a new silent skip (a missing Docker socket, a new
assumption) or a lost skip (a tag removed, a class deleted, a test disabled) fails the job even when
the total stays the same. Added and removed entries are printed in the summary and in the error.

Usage: python3 -I scripts/ci_test_summary.py RESULTS_DIR --expected-skips FILE [--summary-file FILE]
       python3 -I scripts/ci_test_summary.py RESULTS_DIR --print-skips

The expected-skips file has one `class<TAB>testcase` per line; blank lines and `#` lines are ignored.
--print-skips prints the current set in that format, to regenerate the file after an intended change.

Exit codes: 0 guard holds, 1 guard violated, 2 input error (usage, unreadable file, no or broken XML).
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path

SLOWEST = 15


class InputError(Exception):
    """The guard cannot judge: bad arguments, missing results, unreadable or truncated XML."""


def read_suites(results_dir: Path) -> list[dict]:
    suites = []
    for xml_file in sorted(results_dir.glob("*.xml")):
        try:
            root = ET.parse(xml_file).getroot()
        except (ET.ParseError, OSError) as error:
            raise InputError(f"cannot parse {xml_file.name}: {error}") from error
        if root.tag != "testsuite":
            continue
        name = root.get("name", xml_file.stem)
        try:
            counts = {key: int(root.get(key, "0")) for key in ("tests", "skipped", "failures", "errors")}
            seconds = float(root.get("time", "0"))
        except ValueError as error:
            raise InputError(f"bad number in {xml_file.name}: {error}") from error
        skipped_cases = {
            (case.get("classname") or name, case.get("name", "?"))
            for case in root.iter("testcase")
            if case.find("skipped") is not None
        }
        suites.append(
            {
                "name": name,
                **counts,
                "time": seconds,
                "timestamp": root.get("timestamp", ""),
                "skipped_cases": skipped_cases,
            }
        )
    return suites


def read_expected(path: Path) -> set[tuple[str, str]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise InputError(f"cannot read expected-skips file {path}: {error}") from error
    expected = set()
    for number, line in enumerate(lines, 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 2 or not parts[0].strip() or not parts[1].strip():
            raise InputError(f"{path}:{number}: expected `class<TAB>testcase`")
        entry = (parts[0].strip(), parts[1].strip())
        if entry in expected:
            raise InputError(f"{path}:{number}: duplicate entry {entry[0]} / {entry[1]}")
        expected.add(entry)
    return expected


def wall_seconds(suites: list[dict]) -> float:
    starts, ends = [], []
    for suite in suites:
        try:
            start = datetime.fromisoformat(suite["timestamp"])
        except ValueError:
            continue
        starts.append(start)
        ends.append(start + timedelta(seconds=suite["time"]))
    if not starts:
        return 0.0
    return (max(ends) - min(starts)).total_seconds()


def fmt(entries) -> list[str]:
    return [f"`{cls}` / `{case}`" for cls, case in sorted(entries)]


def render(suites: list[dict], expected_count: int, added: set, removed: set, problems: list[str]) -> str:
    tests = sum(s["tests"] for s in suites)
    skipped = sum(s["skipped"] for s in suites)
    failed = sum(s["failures"] + s["errors"] for s in suites)
    lines = [
        "### Gradle tests",
        "",
        f"- testcases: {tests}, passed: {tests - skipped - failed}, skipped: {skipped} (expected list: {expected_count}), failed: {failed}",
        f"- classes: {len(suites)}, wall time: {wall_seconds(suites):.0f} s",
    ]
    skipped_classes = sorted((s["name"], s["skipped"]) for s in suites if s["skipped"])
    if skipped_classes:
        lines += ["", "Skipped by class:", ""]
        lines += [f"- `{name}`: {count}" for name, count in skipped_classes]
    if added:
        lines += ["", "Skipped but not expected (added):", ""] + [f"- {item}" for item in fmt(added)]
    if removed:
        lines += ["", "Expected to skip but did not (removed):", ""] + [f"- {item}" for item in fmt(removed)]
    lines += ["", f"Slowest {SLOWEST} classes:", "", "| class | seconds |", "|---|---|"]
    for suite in sorted(suites, key=lambda s: -s["time"])[:SLOWEST]:
        lines.append(f"| `{suite['name']}` | {suite['time']:.1f} |")
    if problems:
        lines += ["", "**Guard violated:**", ""]
        lines += [f"- {problem}" for problem in problems]
    return "\n".join(lines) + "\n"


def emit(report: str, summary_file: Path | None) -> None:
    print(report)
    if summary_file:
        try:
            with summary_file.open("a", encoding="utf-8") as summary:
                summary.write(report)
        except OSError as error:
            print(f"cannot write summary file: {error}", file=sys.stderr)


def run(args: argparse.Namespace) -> int:
    suites = read_suites(args.results_dir) if args.results_dir.is_dir() else []
    if not suites:
        raise InputError(f"no JUnit XML found in {args.results_dir}")
    actual = set().union(*(s["skipped_cases"] for s in suites))

    if args.print_skips:
        for cls, case in sorted(actual):
            print(f"{cls}\t{case}")
        return 0

    expected = read_expected(args.expected_skips)
    added, removed = actual - expected, expected - actual
    skipped = sum(s["skipped"] for s in suites)
    failed = sum(s["failures"] + s["errors"] for s in suites)
    problems = []
    if failed:
        problems.append(f"{failed} failed testcase(s)")
    if skipped != len(actual):
        problems.append(f"skipped counter is {skipped} but {len(actual)} distinct skipped testcases were found")
    if added or removed:
        problems.append(
            f"skipped set differs from {args.expected_skips}: {len(added)} added, {len(removed)} removed; "
            "update the file only when the change is intended (see --print-skips)"
        )
    emit(render(suites, len(expected), added, removed, problems), args.summary_file)
    return 1 if problems else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("--expected-skips", type=Path)
    parser.add_argument("--print-skips", action="store_true")
    parser.add_argument("--summary-file", type=Path)
    args = parser.parse_args()
    try:
        if not args.print_skips and args.expected_skips is None:
            raise InputError("--expected-skips is required unless --print-skips is given")
        return run(args)
    except InputError as error:
        message = str(error)
        print(message, file=sys.stderr)
        emit(f"### Gradle tests\n\n**Input error:** {message}\n", args.summary_file)
        return 2


if __name__ == "__main__":
    sys.exit(main())
