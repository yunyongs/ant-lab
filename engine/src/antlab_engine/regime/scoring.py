"""Indicator signals, group means, axis scores R and G, F and S, quality (§2.4 steps 3–4, §2.5).

A stale indicator is excluded and the weights of the rest are renormalised; nothing decays.
"""

from collections.abc import Iterable, Sequence

from antlab_engine.regime.catalog import F_TERMS, RATE_GROWTH_INDICATORS, TERMS
from antlab_engine.regime.model import Group, TermSignal, TermStatus
from antlab_engine.regime.params import DEFAULT_PARAMS, RegimeParams

_GROUP_INDICATORS: dict[Group, tuple[str, ...]] = {
    group: tuple(
        dict.fromkeys(t.indicator for t in TERMS if t.group is group)
    )  # catalog order, no repeats
    for group in (Group.R_US, Group.R_KR, Group.G_US, Group.G_KR, Group.S)
}


def mean_or_none(values: Iterable[float]) -> float | None:
    items = list(values)
    return sum(items) / len(items) if items else None


def _ok(signals: Sequence[TermSignal]) -> dict[str, float]:
    return {s.term_id: s.z for s in signals if s.status is TermStatus.OK and s.z is not None}


def indicator_values(signals: Sequence[TermSignal]) -> dict[str, float | None]:
    """Mean of the OK terms of each indicator that feeds a group.

    Keys are "1" … "13", "14US", "14KR" and "14" (the mean of the two parts that are available).
    """
    ok = _ok(signals)
    out: dict[str, float | None] = {}
    for key in dict.fromkeys(t.indicator for t in TERMS if t.group in _GROUP_INDICATORS):
        out[key] = mean_or_none(
            ok[t.term_id] for t in TERMS if t.indicator == key and t.term_id in ok
        )
    out["14"] = mean_or_none(v for k in ("14US", "14KR") if (v := out[k]) is not None)
    return out


def group_scores(signals: Sequence[TermSignal]) -> dict[Group, float | None]:
    """R_US, R_KR, G_US, G_KR, S (equal weight over available indicators) and F (mean of its
    three terms)."""
    values = indicator_values(signals)
    scores: dict[Group, float | None] = {
        group: mean_or_none(v for k in keys if (v := values[k]) is not None)
        for group, keys in _GROUP_INDICATORS.items()
    }
    ok = _ok(signals)
    scores[Group.F] = mean_or_none(ok[t] for t in F_TERMS if t in ok)
    return scores


def blend(first: float | None, second: float | None, weights: tuple[float, float]) -> float | None:
    """Weighted mean of the available values; a missing one gives its weight to the other."""
    parts = [(v, w) for v, w in ((first, weights[0]), (second, weights[1])) if v is not None]
    total = sum(w for _, w in parts)
    if not parts or total <= 0:
        return None
    return sum(v * w for v, w in parts) / total


def combine_axes(
    r_us: float | None,
    r_kr: float | None,
    g_us: float | None,
    g_kr: float | None,
    params: RegimeParams = DEFAULT_PARAMS,
) -> tuple[float | None, float | None]:
    """R = 0.5 R_US + 0.5 R_KR and G = 0.4 G_US + 0.6 G_KR, renormalised over what is available."""
    return blend(r_us, r_kr, params.rate_weights), blend(g_us, g_kr, params.growth_weights)


def quality_ok(stale: Iterable[int], params: RegimeParams = DEFAULT_PARAMS) -> bool:
    """False when more than `quality_max_stale` of the ten rate and growth indicators are stale."""
    return sum(1 for i in set(stale) if i in RATE_GROWTH_INDICATORS) <= params.quality_max_stale
