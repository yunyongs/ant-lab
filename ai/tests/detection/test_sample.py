from datetime import date
from decimal import Decimal

import pytest
from sample_helpers import FIXTURES, write_sample

from antlab_ai.detection.sample import SampleError, detect_segments, load_sample, parse_ratio


def months(*pairs: tuple[int, int]) -> tuple[date, ...]:
    return tuple(date(y, m, 1) for y, m in pairs)


def test_fixture_loads_with_exact_decimals(sample):
    assert [a.key for a in sample.assets] == [
        ("KR", "069500"),
        ("KR", "T0001"),
        ("US", "SPY"),
        ("US", "T0002"),
    ]
    assert len(sample.months) == 9
    assert sample.returns[("KR", "069500")][0] == Decimal("-0.00765500")
    assert all(isinstance(v, Decimal) for v in sample.fx)


def test_segments_split_at_calendar_gaps(sample):
    assert sample.segments == ((0, 5), (5, 9))


def test_segment_boundaries_across_years_and_single_months():
    run = months((2012, 11), (2012, 12), (2013, 1), (2013, 3), (2013, 4), (2013, 6))
    assert detect_segments(run) == ((0, 3), (3, 5), (5, 6))
    assert detect_segments(()) == ()


@pytest.mark.parametrize(
    "text",
    [
        "0.1",
        "0.1234567",
        "0.123456789",
        "1e-3",
        "NaN",
        "Infinity",
        "+0.12345678",
        ".12345678",
        "0.12345678 ",
        "٠.١٢٣٤٥٦٧٨",
        "",
    ],
)
def test_parse_ratio_refuses_anything_but_eight_plain_decimals(text):
    with pytest.raises(SampleError):
        parse_ratio(text)


def test_parse_ratio_is_exact_not_float():
    parsed = parse_ratio("0.10000001")
    assert parsed == Decimal("0.10000001")
    assert parsed != Decimal(float("0.10000001"))
    assert parse_ratio("-0.00000000") == 0


def edit(name: str, old: str, new: str) -> dict[str, str]:
    return {name: (FIXTURES / name).read_text(encoding="utf-8").replace(old, new, 1)}


@pytest.mark.parametrize(
    ("name", "old", "new"),
    [
        ("asset_month_return.csv", "-0.00765500", "-0.007655"),
        ("asset_month_return.csv", "KR,T0001,2001-01-01", "KR,T0001,2001-01-02"),
        ("asset_month_return.csv", "KR,T0001,2001-01-01", "KR,UNKNOWN,2001-01-01"),
        ("asset_month_return.csv", "KR,T0001,2001-01-01", "KR,069500,2001-01-01"),
        ("fx_month.csv", "2001-01-01,USD", "2001-01-01,EUR"),
        ("fx_month.csv", "-0.00922300", "-0.009223"),
        ("month_block.csv", "2001-02-01", "2001-01-01"),
        ("month_block.csv", "2001-02-01", "2001-02-15"),
        ("asset.csv", "KR,T0001", "JP,T0001"),
    ],
)
def test_malformed_input_is_refused(tmp_path, name, old, new):
    directory = write_sample(tmp_path / "s", **edit(name, old, new))
    with pytest.raises(SampleError) as caught:
        load_sample(directory)
    # Messages name files and lines, never cell values.
    assert old not in str(caught.value) or old == "2001-02-01"


def test_every_asset_needs_every_month(tmp_path):
    lines = (FIXTURES / "asset_month_return.csv").read_text(encoding="utf-8").splitlines(True)
    directory = write_sample(tmp_path / "s", **{"asset_month_return.csv": "".join(lines[:-1])})
    with pytest.raises(SampleError, match="every asset"):
        load_sample(directory)


def test_missing_file_is_refused(tmp_path):
    directory = write_sample(tmp_path / "s")
    (directory / "fx_month.csv").unlink()
    with pytest.raises(SampleError, match="missing sample file"):
        load_sample(directory)
