"""AG-01 renderer output must trip FINGERPRINT: a model that copies a rendered line cannot store it."""

import os

import pytest
from memory_helpers import SAMPLE_FIXTURES

from antlab_ai.detection.plan import build_units, render_unit
from antlab_ai.detection.sample import SAMPLE_DIR_ENV, load_sample
from antlab_ai.memory.filter import scan_text


def _lines(sample, units):
    for unit in units:
        _, user = render_unit(sample, unit)
        for line in user.splitlines():
            if line[:3] in ("KR ", "US ") and line.count("%") >= 3:
                yield unit, line


def _check(sample, terms):
    """Per window: lines with at least 3 cells, and whole tables (all asset lines pasted as one string)."""
    lines = {1: 0, 3: 0, 6: 0}
    tables = {1: 0, 3: 0, 6: 0}
    for unit, line in _lines(sample, build_units(sample)):
        cells = line.split(":", 1)[1]
        assert "FINGERPRINT" in {h.code for h in scan_text(cells, "/l", terms)}
        lines[unit.window] += 1
    for unit in build_units(sample):
        _, user = render_unit(sample, unit)
        table = "\n".join(line for line in user.splitlines() if line[:3] in ("KR ", "US "))
        assert "FINGERPRINT" in {h.code for h in scan_text(table, "/t", terms)}
        tables[unit.window] += 1
    return lines, tables


def test_rendered_tables_of_the_synthetic_fixture_trip_the_fingerprint(terms):
    lines, tables = _check(load_sample(SAMPLE_FIXTURES), terms)
    # The fixture segments are too short for 6-month windows; the real sample covers all three.
    assert lines[3] > 0 and tables[1] > 0 and tables[3] > 0


def test_rendered_cross_sections_of_the_p0_08_sample_trip_the_fingerprint(terms):
    directory = os.environ.get(SAMPLE_DIR_ENV)
    if not directory:
        pytest.skip(f"{SAMPLE_DIR_ENV} is not set; the P0-08 sample CSVs are git-ignored")
    lines, tables = _check(load_sample(directory), terms)
    assert lines[3] > 0 and lines[6] > 0
    assert tables[1] > 0 and tables[3] > 0 and tables[6] > 0
