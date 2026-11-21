"""The roster file of a league entry: strict keys, no seed, no repeated entry."""

from pathlib import Path

import pytest

from antlab_ai.runner.roster import MAX_ENTRIES, RosterError, load_roster, parse_roster

SAMPLE = Path(__file__).resolve().parents[2] / "rosters" / "sample.toml"
ONE = b'[[entry]]\npersona = "patient-value"\nmodel_id = "vendor/m:free"\n'


def test_the_sample_roster_parses_and_defaults_the_route():
    entries = load_roster(SAMPLE)
    assert [e.persona for e in entries] == ["patient-value", "trend-rider", "steady-allocator"]
    assert {e.route for e in entries} == {"OPENROUTER"}


@pytest.mark.parametrize(
    "text",
    [
        b"",
        b"title = 'x'\n" + ONE,
        ONE + b"seed = 5\n",
        ONE + b'route = "NOPE"\n',
        ONE + b"leagueId = 3\n",
        b'[[entry]]\npersona = "patient-value"\n',
        b'[[entry]]\nmodel_id = "m"\n',
        b'[[entry]]\npersona = "Bad Key"\nmodel_id = "m"\n',
        b'[[entry]]\npersona = "patient-value"\nmodel_id = " m"\n',
        b'[[entry]]\npersona = "patient-value"\nmodel_id = ""\n',
        ONE + ONE,
        b'[[entry]]\npersona = "patient-value"\nmodel_id = "m\\u0007"\n',
        b"not toml [",
        b"\xff\xfe",
        ONE + b"x" * 20000,
    ],
)
def test_a_bad_roster_is_refused(text):
    with pytest.raises(RosterError):
        parse_roster(text)


def test_a_roster_over_the_entry_limit_is_refused():
    rows = b"".join(
        b'[[entry]]\npersona = "patient-value"\nmodel_id = "m%d"\n' % n
        for n in range(MAX_ENTRIES + 1)
    )
    with pytest.raises(RosterError):
        parse_roster(rows)


def test_refusals_never_carry_a_value_from_the_file():
    with pytest.raises(RosterError) as caught:
        parse_roster(ONE + b'route = "SECRET-VALUE-9"\n')
    assert "SECRET" not in str(caught.value)


def test_a_missing_file_is_a_roster_error(tmp_path):
    with pytest.raises(RosterError):
        load_roster(tmp_path / "none.toml")
