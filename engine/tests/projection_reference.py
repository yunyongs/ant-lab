"""Independent pure-Python evaluator for the projection tests: lists of floats, no numpy.

It repeats the documented operation order (sequential products, accumulation in ascending asset
order, running maximum, type-7 quantiles with an exact Fraction index) so results can be compared
bit for bit.
"""

from fractions import Fraction

BAND_POINTS = (5, 25, 50, 75, 95)
MDD_POINTS = (5, 10, 25, 50, 75, 95)


def values(factors, paths, held, cash):
    """Value multiples per path: `[1.0] + one value per turn`."""
    result = []
    for path in paths:
        total = [cash] * len(path)
        for column, weight in held:
            grown = 1.0
            for turn, row in enumerate(path):
                grown = grown * factors[row][column]
                total[turn] = total[turn] + grown * weight
        result.append([1.0, *total])
    return result


def quantile(sample, k):
    ordered = sorted(sample)
    h = Fraction((len(ordered) - 1) * k, 100)
    low = h.numerator // h.denominator
    fraction = h - low
    if fraction == 0:
        return ordered[low]
    return ordered[low] + float(fraction) * (ordered[low + 1] - ordered[low])


def running_mdd(path_values):
    peak = path_values[0]
    worst = 0.0
    result = []
    for v in path_values:
        peak = max(peak, v)
        worst = min(worst, v / peak - 1.0)
        result.append(worst)
    return result


def summarize(all_values, turns, limit):
    horizon = len(all_values[0]) - 1
    bands = [
        [quantile([v[t] for v in all_values], k) for k in BAND_POINTS]
        for t in range(1, horizon + 1)
    ]
    drawdowns = [running_mdd(v) for v in all_values]
    summaries = []
    for turn in turns:
        at_turn = [d[turn] for d in drawdowns]
        summaries.append(
            {
                "turn": turn,
                "end": bands[turn - 1],
                "mdd": [quantile(at_turn, k) for k in MDD_POINTS],
                "breach": sum(1 for d in at_turn if d < limit),
            }
        )
    return bands, summaries
