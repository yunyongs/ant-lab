from datetime import date

from conftest import ENGINE_DIR
from synthetic_months import BASE_DATE, sample_shape

from antlab_engine.bootstrap import simple
from antlab_engine.bootstrap.simple import generate_months

# The Java test resource is the single source of truth; it is read in place, never copied.
VECTORS = ENGINE_DIR.parent / "src" / "test" / "resources" / "engine" / "p0-boot6-v1_vectors.csv"


def read_vectors() -> list[tuple[int, int, str]]:
    assert VECTORS.is_file(), f"tracked vector file is missing: {VECTORS}"
    lines = VECTORS.read_text(encoding="utf-8").splitlines()
    assert lines[0] == "seed,length,months"
    rows = []
    for line in lines[1:]:
        seed, length, months = line.split(",", 2)
        rows.append((int(seed), int(length), months))
    return rows


def test_constants_are_pinned():
    assert simple.BLOCK_LENGTH == 6
    assert simple.ALLOWED_LENGTHS == (12, 24, 60)
    assert simple.MAX_LENGTH == 60
    assert date(2007, 1, 1) == simple.REPLAY_START
    assert simple.ENGINE_VERSION == "p0-boot6-v1"


def test_vector_file_has_the_expected_shape():
    rows = read_vectors()
    assert len(rows) == 18
    assert {seed for seed, _, _ in rows} == {14, 0, -1, -(2**63), 2**63 - 1, 1234567}
    assert {length for _, length, _ in rows} == {12, 24, 60}
    for _, length, months in rows:
        assert len(months.split(" ")) == length


def test_every_vector_row_matches_character_for_character():
    shape = sample_shape()
    for seed, length, expected in read_vectors():
        path = generate_months(shape, BASE_DATE, length, seed)
        actual = " ".join(f"{d:%Y-%m}" for d in path)
        assert actual == expected, f"seed={seed} length={length}"
