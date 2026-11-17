import csv
import re

from conftest import ENGINE_DIR

FIXTURE = ENGINE_DIR / "tests" / "fixtures" / "history_fake.sql"
SAMPLE_ASSETS = ENGINE_DIR.parent / "db" / "sample" / "asset.csv"
ASSET_ROW = re.compile(r"\('(KR|US)', '([^']+)', '([^']+)'")


def asset_rows() -> list[tuple[str, str, str]]:
    return ASSET_ROW.findall(FIXTURE.read_text(encoding="utf-8"))


def test_x5_fixture_is_marked_fake():
    assert FIXTURE.read_text(encoding="utf-8").startswith("-- FAKE DATA")


def test_x5_every_asset_is_visibly_fake():
    rows = asset_rows()
    assert len(rows) == 3
    assert all(ticker.startswith("ZZ") and name.startswith("FAKE ") for _, ticker, name in rows)


def test_x5_no_fixture_asset_is_a_sample_asset():
    with SAMPLE_ASSETS.open(encoding="utf-8", newline="") as handle:
        real = {(r["market"], r["ticker"]) for r in csv.DictReader(handle)}
    assert real and {(m, t) for m, t, _ in asset_rows()}.isdisjoint(real)
