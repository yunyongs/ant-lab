"""The tracked L vectors the Java leaderboard test reads: regenerated, m = 10 against M3-03."""

import json
from decimal import Context, Decimal, localcontext
from pathlib import Path

import pytest
from gen_career_l_vectors import career, document

from antlab_engine.validation.composite import career_l

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "career_l_vectors.json"
VECTORS = json.loads(FIXTURE.read_text(encoding="utf-8"))["vectors"]


def test_the_tracked_file_is_what_the_generator_writes():
    expected = json.dumps(document(), indent=2, ensure_ascii=False) + "\n"
    assert FIXTURE.read_text(encoding="utf-8") == expected


def test_every_length_from_three_to_ten_is_covered():
    assert {v["m"] for v in VECTORS} == set(range(3, 11))


@pytest.mark.parametrize("vector", [v for v in VECTORS if v["m"] == 10], ids=lambda v: v["name"])
def test_ten_seasons_equal_the_m303_career_l(vector):
    assert Decimal(vector["lExact"]) == career_l([Decimal(s) for s in vector["seasons"]])
    assert vector["trimEachSide"] == 1


@pytest.mark.parametrize(
    ("seasons", "mean", "sd", "rounded"),
    [
        (["50", "60", "70"], "60", "10", "55.00"),
        (["61.125"] * 3, "61.125", "0", "61.13"),  # HALF_UP, not HALF_EVEN
        (["0"] * 8 + ["100"], "11.11111111111111111111111111111111", None, "-5.56"),
        (["10", "55", "56", "57", "58", "59", "60", "61", "62", "95"], "58.5", None, "48.35"),
    ],
)
def test_hand_values(seasons, mean, sd, rounded):
    result = career([Decimal(s) for s in seasons])
    assert abs(Decimal(result["trimmedMean"]) - Decimal(mean)) < Decimal("1e-30")
    if sd is not None:
        assert Decimal(result["sampleSd"]) == Decimal(sd)
    assert result["l"] == rounded


def test_nine_zeros_and_a_hundred_by_hand():
    # mean 100/9, sd = sqrt((8 (100/9)^2 + (800/9)^2) / 8) = 100/3, L = 100/9 - 50/3
    result = career([Decimal(0)] * 8 + [Decimal(100)])
    with localcontext(Context(prec=80)):
        assert abs(Decimal(result["sampleSd"]) - Decimal(100) / 3) < Decimal("1e-50")
        expected = Decimal(100) / 9 - Decimal(50) / 3
        assert abs(Decimal(result["lExact"]) - expected) < Decimal("1e-50")


def test_fewer_than_three_or_more_than_ten_seasons_are_refused():
    with pytest.raises(ValueError):
        career([Decimal(1), Decimal(2)])
    with pytest.raises(ValueError):
        career([Decimal(1)] * 11)
