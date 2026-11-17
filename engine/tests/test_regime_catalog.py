from datetime import date

import numpy as np
import pytest
from regime_synthetic import FAR, panel

from antlab_engine.regime import TERMS, TERMS_BY_ID, PointInTime, SeriesCode
from antlab_engine.regime.catalog import (
    F_TERMS,
    INDICATOR_INPUTS,
    RATE_GROWTH_INDICATORS,
)
from antlab_engine.regime.model import SERIES_SPECS, Axis, Frequency, Group, Market

S = SeriesCode

# term: (indicator id, sign, group) as written in MACRO_ETF §2.3 / §2.4 step 4 and §2.5
EXPECTED = {
    "1a": (1, 1, Group.R_US), "1b": (1, 1, Group.R_US), "2": (2, 1, Group.R_US),
    "3a": (3, 1, Group.R_KR), "3b": (3, 1, Group.R_KR),
    "4a": (4, 1, Group.R_KR), "4b": (4, 1, Group.R_KR),
    "5": (5, -1, Group.G_US), "6a": (6, -1, Group.G_US), "6b": (6, -1, Group.G_US),
    "7a": (7, 1, Group.G_US), "7b": (7, 1, Group.G_US),
    "8a": (8, 1, Group.G_KR), "8b": (8, 1, Group.G_KR), "9": (9, 1, Group.G_KR),
    "10a": (10, 1, Group.F), "10b": (10, 1, None), "11": (11, 1, Group.F),
    "12a": (12, -1, None), "12b": (12, -1, Group.F),
    "13a": (13, -1, Group.S), "13b": (13, -1, Group.S),
    "14US": (14, -1, Group.G_US), "14KR": (14, -1, Group.G_KR),
}  # fmt: skip


def test_catalog_has_14_indicators_and_24_terms_in_order():
    """TA15."""
    assert [t.term_id for t in TERMS] == list(EXPECTED)
    assert {t.indicator_id for t in TERMS} == set(range(1, 15))
    assert len(TERMS) == len({t.term_id for t in TERMS}) == 24
    assert TERMS_BY_ID["13b"] is TERMS[21]


@pytest.mark.parametrize("term_id", list(EXPECTED))
def test_term_indicator_sign_and_group(term_id):
    indicator, sign, group = EXPECTED[term_id]
    term = TERMS_BY_ID[term_id]
    assert (term.indicator_id, term.sign, term.group) == (indicator, sign, group)


def test_axes_follow_the_indicator_numbers():
    axis = {t.term_id: t.axis for t in TERMS}
    assert {axis[i] for i in ("1a", "2", "3b", "4b")} == {Axis.RATE}
    assert {axis[i] for i in ("5", "6a", "7b", "8a", "9", "14US", "14KR")} == {Axis.GROWTH}
    assert {axis[i] for i in ("10a", "10b", "11", "12a", "12b")} == {Axis.FX}
    assert {axis[i] for i in ("13a", "13b")} == {Axis.SENTIMENT}


def test_f_terms_and_the_rate_growth_set():
    assert F_TERMS == ("10a", "11", "12b")
    assert {t.term_id for t in TERMS if t.group is Group.F} == set(F_TERMS)
    assert [t.term_id for t in TERMS if t.group is None] == ["10b", "12a"]
    assert len(RATE_GROWTH_INDICATORS) == 10
    assert set(RATE_GROWTH_INDICATORS) == {1, 2, 3, 4, 5, 6, 7, 8, 9, 14}
    assert {t.indicator for t in TERMS if t.indicator_id == 14} == {"14US", "14KR"}


def test_indicator_14_has_one_stale_unit_over_both_parts():
    assert INDICATOR_INPUTS[14] == {S.US_BAA10Y, S.KR_CORP_AA, S.KR_KTB3Y}
    assert INDICATOR_INPUTS[3] == {S.KR_KTB3Y, S.KR_BASE_RATE}
    assert INDICATOR_INPUTS[12] == {S.KR_KTB3Y, S.US_DGS2}


def test_series_specs_match_the_publication_rules():
    assert set(SERIES_SPECS) == set(S) and len(S) == 15
    assert {c for c, s in SERIES_SPECS.items() if s.revisable} == {
        S.US_CPILFESL,
        S.US_ICSA,
        S.KR_CPI,
        S.KR_CLI,
        S.KR_EXPORTS,
    }
    assert {c: s.kr_lag_days for c, s in SERIES_SPECS.items() if s.kr_lag_days} == {
        S.KR_CPI: 7,
        S.KR_EXPORTS: 15,
        S.KR_CLI: 35,
    }
    assert {c for c, s in SERIES_SPECS.items() if s.stale_exempt} == {S.KR_BASE_RATE}
    assert {c for c, s in SERIES_SPECS.items() if s.weekly_publication} == {S.US_DTWEXBGS}
    monthly = {c for c, s in SERIES_SPECS.items() if s.frequency is Frequency.MONTHLY}
    assert monthly == {S.US_CPILFESL, S.KR_CPI, S.US_SAHM, S.KR_CLI, S.KR_EXPORTS}
    assert SERIES_SPECS[S.US_ICSA].frequency is Frequency.WEEKLY
    assert all(s.market is Market.KR for c, s in SERIES_SPECS.items() if c.startswith("KR_"))


def test_every_term_input_is_a_known_series_and_every_series_is_used():
    assert {code for t in TERMS for code in t.inputs} == set(S)


@pytest.mark.parametrize("term", TERMS, ids=lambda t: t.term_id)
def test_a_transform_reads_exactly_its_declared_inputs(term):
    """R1: with every other series empty a term still computes; with an input empty it cannot."""
    pit = PointInTime(panel())
    full = {code: pit.snapshot(code, FAR) for code in S}
    only = {code: (v if code in term.inputs else pit.snapshot(code, date(1900, 1, 1)))
            for code, v in full.items()}  # fmt: skip
    out = term.transform(only)
    assert len(out.values) > 0 and bool(np.isfinite(out.values[-1])), term.term_id
    for dropped in term.inputs:
        views = {
            c: (pit.snapshot(c, date(1900, 1, 1)) if c is dropped else v) for c, v in only.items()
        }
        try:
            result = term.transform(views)
        except (IndexError, ValueError):
            continue
        assert len(result.values) == 0 or not bool(np.isfinite(result.values[-1])), (
            term.term_id,
            dropped,
        )


def test_every_transform_returns_aligned_dates_and_values():
    pit = PointInTime(panel())
    views = {code: pit.snapshot(code, FAR) for code in S}
    for term in TERMS:
        out = term.transform(views)
        assert len(out.ref_dates) == len(out.values) > 0, term.term_id
        assert out.ref_dates[-1] <= date(2025, 8, 14), term.term_id
