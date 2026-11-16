#!/usr/bin/env python3
"""Summarise Gradle JUnit XML and guard the number of skipped tests.

The golden tier needs git-ignored sample CSVs, so on a clean checkout it is skipped by design. The
pinned skip count keeps that skip from growing silently (a missing Docker socket, a new assumption)
or shrinking unnoticed (a tag removed or a class deleted).

Usage: python3 -I scripts/ci_test_summary.py RESULTS_DIR --expect-skipped N [--summary-file FILE]

Exit codes: 0 guard holds, 1 guard violated, 2 usage error or no results.
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path

SLOWEST = 15


def read_suites(results_dir: Path) -> list[dict]:
    suites = []
    for xml_file in sorted(results_dir.glob("*.xml")):
        root = ET.parse(xml_file).getroot()
        if root.tag != "testsuite":
            continue
        skipped_cases = [
            case.get("name", "?") for case in root.iter("testcase") if case.find("skipped") is not None
        ]
        suites.append(
            {
                "name": root.get("name", xml_file.stem),
                "tests": int(root.get("tests", "0")),
                "skipped": int(root.get("skipped", "0")),
                "failures": int(root.get("failures", "0")),
                "errors": int(root.get("errors", "0")),
                "time": float(root.get("time", "0")),
                "timestamp": root.get("timestamp", ""),
                "skipped_cases": skipped_cases,
            }
        )
    return suites


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


def render(suites: list[dict], expected: int, problems: list[str]) -> str:
    tests = sum(s["tests"] for s in suites)
    skipped = sum(s["skipped"] for s in suites)
    failed = sum(s["failures"] + s["errors"] for s in suites)
    lines = [
        "### Gradle tests",
        "",
        f"- testcases: {tests}, passed: {tests - skipped - failed}, skipped: {skipped} (pinned: {expected}), failed: {failed}",
        f"- classes: {len(suites)}, wall time: {wall_seconds(suites):.0f} s",
    ]
    skipped_classes = sorted((s["name"], s["skipped"]) for s in suites if s["skipped"])
    if skipped_classes:
        lines += ["", "Skipped by class:", ""]
        lines += [f"- `{name}`: {count}" for name, count in skipped_classes]
    lines += ["", f"Slowest {SLOWEST} classes:", "", "| class | seconds |", "|---|---|"]
    for suite in sorted(suites, key=lambda s: -s["time"])[:SLOWEST]:
        lines.append(f"| `{suite['name']}` | {suite['time']:.1f} |")
    if problems:
        lines += ["", "**Guard violated:**", ""]
        lines += [f"- {problem}" for problem in problems]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("--expect-skipped", type=int, required=True)
    parser.add_argument("--summary-file", type=Path)
    args = parser.parse_args()

    if args.expect_skipped < 0:
        print("--expect-skipped must be >= 0", file=sys.stderr)
        return 2
    suites = read_suites(args.results_dir) if args.results_dir.is_dir() else []
    if not suites:
        print(f"no JUnit XML found in {args.results_dir}", file=sys.stderr)
        return 2

    skipped = sum(s["skipped"] for s in suites)
    failed = sum(s["failures"] + s["errors"] for s in suites)
    problems = []
    if failed:
        problems.append(f"{failed} failed testcase(s)")
    if skipped != args.expect_skipped:
        problems.append(
            f"skipped testcases are {skipped}, pinned value is {args.expect_skipped}: "
            "a silent skip appeared or a golden test left the tier; update the pin only when that is intended"
        )

    report = render(suites, args.expect_skipped, problems)
    print(report)
    if args.summary_file:
        with args.summary_file.open("a", encoding="utf-8") as summary:
            summary.write(report)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
