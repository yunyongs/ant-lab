from fractions import Fraction

from conftest import ENGINE_DIR
from gen_stationary_vectors import HEADER, LETTERS, SEEDS, THRESHOLDS, render
from synthetic_months import BASE_DATE, half_recent, history

from antlab_engine.bootstrap.stationary import ENGINE_VERSION, generate

VECTORS = ENGINE_DIR / "tests" / "vectors" / "m2-sb6-v1_vectors.csv"


def read_rows() -> list[dict[str, str]]:
    assert VECTORS.is_file(), f"tracked vector file is missing: {VECTORS}"
    lines = VECTORS.read_text(encoding="utf-8").splitlines()
    assert lines[0] == HEADER
    names = HEADER.split(",")
    return [dict(zip(names, line.split(","), strict=True)) for line in lines[1:]]


def test_vector_file_has_the_expected_shape():
    rows = read_rows()
    assert 40 <= len(rows) <= 50
    assert len({r["case"] for r in rows}) == len(rows)
    assert set(SEEDS) <= {int(r["seed"]) for r in rows}
    assert {int(r["length"]) for r in rows} == {12, 24, 60, 120}
    assert {r["recent"] for r in rows} == {"none", "half", "all"}
    assert {r["history"] for r in rows} == {"sample", "ext348", "gappy", "tiny", "two_januaries"}
    assert {r["history"] for r in rows if r["length"] == "120"} == {"ext348"}
    tiers = {part.split(":")[2] for r in rows for part in r["blocks"].split(" ")}
    assert tiers == set(LETTERS.values())
    assert {int(r["attempt"]) for r in rows} > {0}


def test_every_vector_row_matches_character_for_character():
    for row in read_rows():
        months, replay_start = history(row["history"])
        recent = {"none": [], "half": half_recent(), "all": months}[row["recent"]]
        threshold = Fraction(row["max_overlap"])
        assert threshold == THRESHOLDS[row["recent"]]
        path = generate(
            months,
            BASE_DATE,
            int(row["length"]),
            int(row["seed"]),
            recent_months=recent,
            max_overlap=threshold,
            replay_start=replay_start,
        )
        assert path.engine_version == ENGINE_VERSION
        where = row["case"]
        assert " ".join(f"{m:%Y-%m}" for m in path.months) == row["months"], where
        blocks = " ".join(f"{b.start_turn}:{b.length}:{LETTERS[b.tier]}" for b in path.blocks)
        assert blocks == row["blocks"], where
        assert str(path.overlap_months) == row["overlap_months"], where
        assert str(path.attempt) == row["attempt"], where


def test_the_generator_script_reproduces_the_file_byte_for_byte():
    assert render() == VECTORS.read_text(encoding="utf-8")
