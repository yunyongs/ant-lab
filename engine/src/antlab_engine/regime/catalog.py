"""The 14 indicators as 24 terms (MACRO_ETF §2.3, §2.4 steps 2 and 4, §2.5). Constants only.

`sign` makes a positive z mean rate up / growth up / USD strong / risk-on. `group` says which
group mean a term feeds; terms without a group are recorded only (10b, 12a). Source ids:
DGS2, CPILFESL, ICSA, SAHMREALTIME, T10Y3M, DTWEXBGS, VIXCLS, BAA10Y (FRED); 817Y002, 722Y001,
901Y009, 901Y067, 901Y118, 731Y001 and the AA- corporate yield (ECOS).
"""

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from types import MappingProxyType

from antlab_engine.regime import transforms as tf
from antlab_engine.regime.model import (
    Axis,
    Group,
    Region,
    SeriesCode,
    SeriesView,
    Transformed,
)

Views = Mapping[SeriesCode, SeriesView]
S = SeriesCode


@dataclass(frozen=True, slots=True)
class Term:
    term_id: str
    indicator_id: int
    indicator: str  # grouping key: "1".."13", and "14US" / "14KR" for indicator 14
    axis: Axis
    region: Region
    group: Group | None
    sign: int
    inputs: tuple[SeriesCode, ...]
    lookback: int  # observations (daily, weekly) or months (monthly)
    transform: Callable[[Views], Transformed]
    percentile: bool = False  # term 13b: window percentile mapped to the z scale, no z-score


def _daily(
    code: SeriesCode, fn: Callable[..., object], *args: int
) -> Callable[[Views], Transformed]:
    def run(views: Views) -> Transformed:
        view = views[code]
        return Transformed(view.ref_dates, fn(view.values, *args))

    return run


def _monthly(
    code: SeriesCode, fn: Callable[..., object], *args: int
) -> Callable[[Views], Transformed]:
    def run(views: Views) -> Transformed:
        view = views[code]
        return Transformed(view.ref_dates, fn(tf.month_index(view.ref_dates), view.values, *args))

    return run


def _level(code: SeriesCode, offset: float = 0.0) -> Callable[[Views], Transformed]:
    def run(views: Views) -> Transformed:
        view = views[code]
        return Transformed(view.ref_dates, view.values - offset)

    return run


def _base_rate_gap(views: Views) -> Transformed:
    left = views[S.KR_KTB3Y]
    return Transformed(left.ref_dates, left.values - tf.as_of_join(left, views[S.KR_BASE_RATE]))


def _kr_us_spread(views: Views) -> Transformed:
    return tf.spread_on_left_dates(views[S.KR_KTB3Y], views[S.US_DGS2])


def _kr_us_spread_delta(views: Views) -> Transformed:
    spread = _kr_us_spread(views)
    return Transformed(spread.ref_dates, tf.delta(spread.values, 126))


def _kr_credit_delta(views: Views) -> Transformed:
    spread = tf.same_date_spread(views[S.KR_CORP_AA], views[S.KR_KTB3Y])
    return Transformed(spread.ref_dates, tf.delta(spread.values, 63))


def _identity(views: Views) -> Transformed:
    view = views[S.US_VIX]
    return Transformed(view.ref_dates, view.values)


R, G, FX, SENT = Axis.RATE, Axis.GROWTH, Axis.FX, Axis.SENTIMENT
US, KR, GL = Region.US, Region.KR, Region.GLOBAL


def _term(
    term_id: str,
    indicator: str,
    axis: Axis,
    region: Region,
    group: Group | None,
    sign: int,
    inputs: tuple[SeriesCode, ...],
    lookback: int,
    transform: Callable[[Views], Transformed],
    percentile: bool = False,
) -> Term:
    indicator_id = int(indicator.removesuffix("US").removesuffix("KR"))
    return Term(
        term_id,
        indicator_id,
        indicator,
        axis,
        region,
        group,
        sign,
        inputs,
        lookback,
        transform,
        percentile,
    )


TERMS: tuple[Term, ...] = (
    _term("1a", "1", R, US, Group.R_US, 1, (S.US_DGS2,), 63, _daily(S.US_DGS2, tf.delta, 63)),
    _term(
        "1b", "1", R, US, Group.R_US, 1, (S.US_DGS2,), 126, _daily(S.US_DGS2, tf.minus_mean, 126)
    ),
    _term(
        "2",
        "2",
        R,
        US,
        Group.R_US,
        1,
        (S.US_CPILFESL,),
        12,
        _monthly(S.US_CPILFESL, tf.core_cpi_acceleration),
    ),
    _term("3a", "3", R, KR, Group.R_KR, 1, (S.KR_KTB3Y,), 63, _daily(S.KR_KTB3Y, tf.delta, 63)),
    _term("3b", "3", R, KR, Group.R_KR, 1, (S.KR_KTB3Y, S.KR_BASE_RATE), 0, _base_rate_gap),
    _term("4a", "4", R, KR, Group.R_KR, 1, (S.KR_CPI,), 12, _monthly(S.KR_CPI, tf.year_over_year)),
    _term(
        "4b",
        "4",
        R,
        KR,
        Group.R_KR,
        1,
        (S.KR_CPI,),
        15,
        _monthly(S.KR_CPI, tf.year_over_year_change, 3),
    ),
    _term(
        "5",
        "5",
        G,
        US,
        Group.G_US,
        -1,
        (S.US_ICSA,),
        26,
        _daily(S.US_ICSA, tf.mean_ratio_minus_one, 4, 26),
    ),
    _term("6a", "6", G, US, Group.G_US, -1, (S.US_SAHM,), 0, _level(S.US_SAHM)),
    _term(
        "6b", "6", G, US, Group.G_US, -1, (S.US_SAHM,), 3, _monthly(S.US_SAHM, tf.month_delta, 3)
    ),
    _term("7a", "7", G, US, Group.G_US, 1, (S.US_T10Y3M,), 0, _level(S.US_T10Y3M)),
    _term("7b", "7", G, US, Group.G_US, 1, (S.US_T10Y3M,), 63, _daily(S.US_T10Y3M, tf.delta, 63)),
    _term("8a", "8", G, KR, Group.G_KR, 1, (S.KR_CLI,), 3, _monthly(S.KR_CLI, tf.month_delta, 3)),
    _term("8b", "8", G, KR, Group.G_KR, 1, (S.KR_CLI,), 0, _level(S.KR_CLI, 100.0)),
    _term(
        "9",
        "9",
        G,
        KR,
        Group.G_KR,
        1,
        (S.KR_EXPORTS,),
        14,
        _monthly(S.KR_EXPORTS, tf.exports_year_over_year),
    ),
    _term(
        "10a",
        "10",
        FX,
        KR,
        Group.F,
        1,
        (S.KR_USDKRW,),
        200,
        _daily(S.KR_USDKRW, tf.over_mean_minus_one, 200),
    ),
    _term("10b", "10", FX, KR, None, 1, (S.KR_USDKRW,), 63, _daily(S.KR_USDKRW, tf.pct_change, 63)),
    _term(
        "11",
        "11",
        FX,
        GL,
        Group.F,
        1,
        (S.US_DTWEXBGS,),
        63,
        _daily(S.US_DTWEXBGS, tf.pct_change, 63),
    ),
    _term("12a", "12", FX, GL, None, -1, (S.KR_KTB3Y, S.US_DGS2), 0, _kr_us_spread),
    _term("12b", "12", FX, GL, Group.F, -1, (S.KR_KTB3Y, S.US_DGS2), 126, _kr_us_spread_delta),
    _term(
        "13a",
        "13",
        SENT,
        US,
        Group.S,
        -1,
        (S.US_VIX,),
        50,
        _daily(S.US_VIX, tf.over_mean_minus_one, 50),
    ),
    _term("13b", "13", SENT, US, Group.S, -1, (S.US_VIX,), 0, _identity, percentile=True),
    _term(
        "14US", "14US", G, US, Group.G_US, -1, (S.US_BAA10Y,), 63, _daily(S.US_BAA10Y, tf.delta, 63)
    ),
    _term("14KR", "14KR", G, KR, Group.G_KR, -1, (S.KR_CORP_AA, S.KR_KTB3Y), 63, _kr_credit_delta),
)

INDICATOR_IDS: tuple[int, ...] = tuple(range(1, 15))
# an indicator is stale when any of its input series is stale (indicator 14: either part)
INDICATOR_INPUTS: Mapping[int, frozenset[SeriesCode]] = MappingProxyType(
    {i: frozenset(s for t in TERMS if t.indicator_id == i for s in t.inputs) for i in INDICATOR_IDS}
)
# MACRO_ETF §2.4 step 3: the ten rate and growth indicators that feed the quality flag
RATE_GROWTH_INDICATORS: tuple[int, ...] = (1, 2, 3, 4, 5, 6, 7, 8, 9, 14)
F_TERMS: tuple[str, ...] = ("10a", "11", "12b")
TERMS_BY_ID: Mapping[str, Term] = MappingProxyType({t.term_id: t for t in TERMS})
