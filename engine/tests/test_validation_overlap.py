"""Process-item overlap: statistics, assessability, verdict rules, populations and the document."""

import ast
import json
import random
import re
from decimal import Decimal
from pathlib import Path

import pytest
from test_sim_source_rules import float_uses

from antlab_engine.validation import overlap
from antlab_engine.validation.checks import Bootstrap, mean_of
from antlab_engine.validation.overlap import (
    CONSISTENCY,
    ITEMS,
    PAIRS,
    TRAITS,
    Boots,
    OverlapError,
    PairStat,
    assessable,
    build_overlap,
    event_verdict,
    item_verdicts,
    kendall_counts,
    kendall_tau_b,
    pair_stats,
    pair_verdict,
    populations,
    render_summary,
    tracking_of,
    unusable_reason,
    validate_overlap,
)

D = Decimal
MANIFEST = {
    "masterSeed": "303",
    "gitCommit": "abc1234",
    "scoreVersion": "s63-v3",
    "engineVersion": "0",
    "botVersions": {"random": 1},
}
DESCRIPTOR = {"id": "t1", "source": "panel", "worlds": 3}


def ds(*values) -> list[Decimal]:
    return [D(v) for v in values]


# ---------------------------------------------------------------------------- statistics


def slow_counts(xs, ys):
    concordant = discordant = tied_x = tied_y = 0
    n = len(xs)
    for i in range(n):
        for j in range(i + 1, n):
            dx, dy = xs[i] - xs[j], ys[i] - ys[j]
            tied_x += dx == 0
            tied_y += dy == 0
            if dx != 0 and dy != 0:
                if (dx > 0) == (dy > 0):
                    concordant += 1
                else:
                    discordant += 1
    pairs = n * (n - 1) // 2
    return concordant - discordant, pairs - tied_x, pairs - tied_y


def test_kendall_tau_b_on_a_hand_example_with_ties():
    xs, ys = ds(1, 2, 2, 3), ds(1, 3, 2, 3)
    assert kendall_counts(xs, ys) == (4, 5, 5)
    assert kendall_tau_b(xs, ys) == D("0.8")


def test_spearman_with_ties_uses_average_ranks():
    from antlab_engine.validation.checks import spearman

    assert spearman(ds(1, 2, 2, 3), ds(1, 3, 2, 3)) == D("0.833333333333")
    assert spearman(ds(1, 2, 3), ds(1, 2, 3)) == D(1)
    assert spearman(ds(1, 2, 3), ds(3, 2, 1)) == D(-1)


def test_constant_vectors_have_no_correlation_never_zero():
    from antlab_engine.validation.checks import spearman

    flat, ramp = ds(5, 5, 5, 5), ds(1, 2, 3, 4)
    assert kendall_tau_b(flat, ramp) is None and kendall_tau_b(ramp, flat) is None
    assert spearman(flat, ramp) is None and spearman(ramp, flat) is None


def test_exact_kendall_counts_match_a_slow_reference_on_random_vectors():
    rng = random.Random(7)  # noqa: S311
    for _ in range(50):
        n = rng.randint(5, 40)
        xs = [D(rng.randint(0, 6)) for _ in range(n)]
        ys = [D(rng.randint(0, 9)) for _ in range(n)]
        cd, ux, uy = slow_counts(xs, ys)
        assert kendall_counts(xs, ys) == (cd, ux, uy)
        if ux and uy:
            expected = cd / (ux * uy) ** 0.5
            assert abs(float(kendall_tau_b(xs, ys)) - expected) < 1e-9
        else:
            assert kendall_tau_b(xs, ys) is None


# ---------------------------------------------------------------------------- assessability


def vector(n: int, top_tie: int) -> list[Decimal]:
    """n values whose largest tie group has `top_tie` members, the rest distinct."""
    return [D(0)] * top_tie + [D(i + 1) for i in range(n - top_tie)]


def test_a_world_needs_thirty_bots():
    ok = vector(30, 1)
    assert unusable_reason(ok, ok) is None
    short = vector(29, 1)
    assert unusable_reason(short, short) == "few bots"
    assert unusable_reason([], []) == "no input"


def test_tie_share_of_half_is_usable_and_above_half_is_not():
    spread = vector(30, 1)
    assert unusable_reason(vector(30, 15), spread) is None
    assert unusable_reason(vector(30, 16), spread) == "ties"
    assert unusable_reason(spread, vector(30, 16)) == "ties"
    assert unusable_reason(vector(31, 15), spread[:1] + vector(30, 1)) is None
    assert unusable_reason(vector(31, 16), vector(31, 1)) == "ties"
    assert unusable_reason([D(1)] * 40, vector(40, 1)) == "constant"


def test_eighty_percent_of_the_worlds_is_assessable():
    assert assessable(80, 100) and not assessable(79, 100)
    assert assessable(4, 5) and not assessable(3, 5)
    assert not assessable(0, 0)


# ---------------------------------------------------------------------------- pair verdicts


def test_overlap_needs_mean_and_interval_lower_bound():
    assert pair_verdict(D("0.70"), D("0.60"), D(1), is_assessable=True) == "OVERLAP"
    assert pair_verdict(D("0.6999"), D("0.60"), D(1), is_assessable=True) == "PARTIAL"
    assert pair_verdict(D("0.70"), D("0.5999"), D(1), is_assessable=True) == "PARTIAL"
    assert pair_verdict(D("0.70"), D("0.55"), D(1), is_assessable=True) == "PARTIAL"


def test_distinct_needs_the_upper_bound_strictly_below_half():
    assert pair_verdict(D("0.1"), D(0), D("0.4999"), is_assessable=True) == "DISTINCT"
    assert pair_verdict(D("0.1"), D(0), D("0.50"), is_assessable=True) == "PARTIAL"
    assert pair_verdict(D("-0.69"), D("-0.8"), D("0.2"), is_assessable=True) == "DISTINCT"


def test_conflict_and_not_assessable_branches():
    assert pair_verdict(D("-0.70"), D("-0.9"), D("-0.60"), is_assessable=True) == "CONFLICT"
    assert pair_verdict(D("-0.70"), D("-0.9"), D("-0.59"), is_assessable=True) == "PARTIAL"
    assert pair_verdict(D("-0.7001"), D("-0.9"), D("-0.8"), is_assessable=True) == "CONFLICT"
    assert pair_verdict(D("0.9"), D("0.9"), D(1), is_assessable=False) == "NOT_ASSESSABLE"
    assert pair_verdict(None, None, None, is_assessable=True) == "NOT_ASSESSABLE"


def test_kendall_thresholds_are_the_mapped_ones():
    assert (
        pair_verdict(D("0.49"), D("0.41"), D(1), is_assessable=True, thresholds=overlap.KENDALL)
        == "OVERLAP"
    )
    assert (
        pair_verdict(D("0.48"), D("0.41"), D(1), is_assessable=True, thresholds=overlap.KENDALL)
        == "PARTIAL"
    )
    assert (
        pair_verdict(D(0), D(-1), D("0.33"), is_assessable=True, thresholds=overlap.KENDALL)
        == "PARTIAL"
    )


# ---------------------------------------------------------------------------- pair statistics


def ramp_world(offset_x: int, offset_y: int, n: int = 40):
    xs = [D(offset_x + i) for i in range(n)]
    ys = [D(offset_y + i) for i in range(n)]
    return xs, ys


def test_the_mean_of_per_world_correlations_decides_not_the_pooled_rows():
    worlds = [ramp_world(0, 100), ramp_world(100, 0)]
    stat = pair_stats(worlds, 2, Boots(303))
    assert stat.rho == D(1) and stat.tau == D(1)
    assert stat.pooled is not None and stat.pooled < 0
    assert stat.sign_share == D(1)


def test_the_interval_is_a_bootstrap_over_worlds():
    rng = random.Random(3)  # noqa: S311
    worlds = []
    for _ in range(6):
        xs = [D(rng.randint(0, 50)) for _ in range(40)]
        ys = [x + D(rng.randint(-20, 20)) for x in xs]
        worlds.append((xs, ys))
    first = pair_stats(worlds, 6, Boots(303))
    again = pair_stats(worlds, 6, Boots(303))
    assert first == again
    from antlab_engine.validation.checks import spearman

    rhos = [spearman(x, y) for x, y in worlds]
    assert first.rho == mean_of(rhos)
    assert (first.rho_lower, first.rho_upper) == Bootstrap(303, 6).interval(rhos)
    duplicated = pair_stats([*worlds, worlds[0]], 7, Boots(303))
    assert (duplicated.rho_lower, duplicated.rho_upper) != (first.rho_lower, first.rho_upper)
    assert pair_stats(worlds, 6, Boots(304)).rho_lower != first.rho_lower


def test_a_pair_needs_enough_usable_worlds_and_names_the_reason():
    good = ramp_world(0, 0)
    flat = ([D(1)] * 40, [D(i) for i in range(40)])
    four_of_five = pair_stats([good] * 4 + [flat], 5, Boots(1))
    assert four_of_five.is_assessable and four_of_five.usable == 4
    three_of_five = pair_stats([good] * 3 + [flat] * 2, 5, Boots(1))
    assert not three_of_five.is_assessable and three_of_five.verdict == "NOT_ASSESSABLE"
    assert three_of_five.reason == "constant"
    missing = pair_stats([good] * 3, 5, Boots(1))
    assert not missing.is_assessable and missing.reason == "worlds"
    nothing = pair_stats([], 5, Boots(1))
    assert nothing.verdict == "NOT_ASSESSABLE" and nothing.rho is None


def test_kendall_and_spearman_verdicts_that_differ_flag_the_pair_sensitive():
    stat = PairStat(
        "a",
        "b",
        5,
        5,
        True,
        None,
        D("0.7"),
        D("0.6"),
        D(1),
        D(1),
        D("0.4"),
        D("0.3"),
        D("0.5"),
        None,
        "OVERLAP",
        "PARTIAL",
    )
    assert stat.sensitive
    assert not PairStat("a", "b", 5, 5, True, None, *([None] * 8), "PARTIAL", "PARTIAL").sensitive


# ---------------------------------------------------------------------------- tracking


def corr(**own: str):
    """Correlation table for one item: unspecified traits are -0.10."""
    row = {t: (D(own.get(t, "-0.10")), 5, True) for t in TRAITS}
    return {item: row for item in ITEMS}


def test_an_item_tracks_its_trait_by_size_and_margin():
    assert tracking_of("noise", corr(follow="-0.60"))["tracks"] is True
    assert tracking_of("noise", corr(follow="-0.50", route="-0.30"))["tracks"] is True
    assert tracking_of("noise", corr(follow="-0.50", route="-0.31"))["tracks"] is False
    assert tracking_of("noise", corr(follow="-0.4999"))["tracks"] is False


def test_tracking_ignores_the_sign_of_a_rising_item():
    assert tracking_of("noise", corr(follow="0.80"))["tracks"] is False
    assert tracking_of("noise", corr(follow="-0.60", panic="0.90"))["tracks"] is True


def test_discipline_tracks_the_stronger_of_panic_and_chase():
    assert tracking_of("discipline", corr(chase="-0.70"))["tracks"] is True
    assert tracking_of("discipline", corr(panic="-0.70", chase="-0.65"))["tracks"] is True
    assert tracking_of("discipline", corr(panic="-0.70", follow="-0.60"))["tracks"] is False


def test_items_without_a_trait_and_unassessable_traits():
    assert tracking_of("risk", corr()) is None
    assert tracking_of("formation", corr()) is None
    table = corr()
    table["noise"] = {**table["noise"], "follow": (None, 0, False)}
    assert tracking_of("noise", table)["tracks"] is None


# ---------------------------------------------------------------------------- item verdicts


def stat(x: str, y: str, verdict: str, rho: str = "0.1", reason: str | None = None) -> PairStat:
    value = None if verdict == "NOT_ASSESSABLE" else D(rho)
    return PairStat(
        x,
        y,
        3,
        3,
        verdict != "NOT_ASSESSABLE",
        reason,
        value,
        value,
        value,
        None,
        value,
        value,
        value,
        None,
        verdict,
        verdict,
    )


def table(default: str = "PARTIAL", **special: str):
    """Pair table over all items; `special` maps "x_y" to a verdict (and rho via tuple)."""
    out = {}
    for x, y in PAIRS:
        verdict = special.get(f"{x}_{y}", default)
        out[(x, y)] = stat(x, y, verdict, "0.80" if verdict == "OVERLAP" else "0.1")
    return out


TRACKS = {
    "noise": {"tracks": True},
    "nudge": {"tracks": True},
    "discipline": {"tracks": True},
    "turnover": {"tracks": True},
}
NOT_TRACKS = {k: {"tracks": False} for k in TRACKS}


def only_not_tracking(item: str):
    return {**TRACKS, item: {"tracks": False}}


def test_item_verdict_truth_table_for_an_item_with_a_trait():
    overlapping = table(discipline_noise="OVERLAP")
    clean = table()
    assert item_verdicts(clean, clean, TRACKS)["noise"]["verdict"] == "KEEP"
    assert (
        item_verdicts(overlapping, clean, only_not_tracking("noise"))["noise"]["verdict"] == "MERGE"
    )
    assert (
        item_verdicts(overlapping, clean, only_not_tracking("noise"))["noise"]["partner"]
        == "discipline"
    )
    assert item_verdicts(overlapping, clean, TRACKS)["noise"]["verdict"] == "UNDECIDED"
    assert item_verdicts(clean, clean, NOT_TRACKS)["noise"]["verdict"] == "UNDECIDED"
    assert (
        item_verdicts(clean, clean, {**TRACKS, "noise": {"tracks": None}})["noise"]["verdict"]
        == "UNDECIDED"
    )


def test_noise_and_nudge_merge_only_into_discipline_turnover_or_each_other():
    far = table(risk_noise="OVERLAP")
    assert item_verdicts(far, far, NOT_TRACKS)["noise"]["verdict"] == "UNDECIDED"
    near = table(noise_nudge="OVERLAP")
    assert item_verdicts(near, near, only_not_tracking("noise"))["noise"]["partner"] == "nudge"
    assert item_verdicts(near, near, only_not_tracking("nudge"))["nudge"]["partner"] == "noise"
    best = {
        **table(discipline_noise="OVERLAP"),
        ("noise", "turnover"): stat("noise", "turnover", "OVERLAP", "0.90"),
    }
    assert item_verdicts(best, best, only_not_tracking("noise"))["noise"]["partner"] == "turnover"


def test_items_without_a_trait_need_no_overlap_in_either_population():
    clean = table()
    assert item_verdicts(clean, clean, TRACKS)["risk"]["verdict"] == "KEEP"
    overlapping = table(risk_noise="OVERLAP")
    assert item_verdicts(overlapping, clean, TRACKS)["risk"]["verdict"] == "MERGE"
    assert item_verdicts(overlapping, clean, TRACKS)["risk"]["partner"] == "noise"
    assert item_verdicts(clean, overlapping, TRACKS)["risk"]["verdict"] == "UNDECIDED"


def test_the_item_verdict_is_decided_on_the_trait_population_not_the_random_one():
    clean, overlapping = table(), table(discipline_noise="OVERLAP", risk_role="OVERLAP")
    result = item_verdicts(clean, overlapping, TRACKS)
    assert result["noise"]["verdict"] == "KEEP" and result["discipline"]["verdict"] == "KEEP"
    flipped = item_verdicts(overlapping, clean, only_not_tracking("noise"))
    assert flipped["noise"]["verdict"] == "MERGE"


def test_without_a_trait_population_an_item_is_undecided_not_decided_on_random_bots():
    result = item_verdicts(None, table(discipline_noise="OVERLAP"), {})
    assert result["noise"] == {
        "verdict": "UNDECIDED",
        "partner": None,
        "reasons": ["no trait population"],
    }


def test_items_whose_every_pair_is_unassessable_are_not_assessable():
    constant = {k: stat(k[0], k[1], "NOT_ASSESSABLE", reason="constant") for k in PAIRS}
    result = item_verdicts(None, constant, {})
    assert result["nudge"] == {
        "verdict": "NOT_ASSESSABLE",
        "partner": None,
        "reasons": ["constant"],
    }
    mixed_t = table()
    assert item_verdicts(mixed_t, constant, TRACKS)["nudge"]["verdict"] == "KEEP"


def test_two_items_never_merge_into_each_other():
    for first, second, pair in (
        ("risk", "diversification", "risk_diversification"),
        ("noise", "nudge", "noise_nudge"),
    ):
        both = table(**{pair: "OVERLAP"})
        result = item_verdicts(both, both, NOT_TRACKS)
        for item, other in ((first, second), (second, first)):
            assert result[item]["verdict"] == "UNDECIDED" and result[item]["partner"] is None
            assert result[item]["reasons"] == ["mutual overlap"]
            assert result[item]["overlapWith"] == other


def test_a_merge_into_an_item_that_merges_away_is_a_chain_and_undecided():
    chain = {
        **table(risk_turnover="OVERLAP"),
        ("turnover", "noise"): stat("turnover", "noise", "OVERLAP", "0.90"),
    }
    tracking = {**TRACKS, "turnover": {"tracks": False}}
    result = item_verdicts(chain, chain, tracking)
    assert result["turnover"]["verdict"] == "MERGE" and result["turnover"]["partner"] == "noise"
    assert result["risk"]["verdict"] == "UNDECIDED" and result["risk"]["reasons"] == ["merge chain"]
    assert result["risk"]["overlapWith"] == "turnover"
    assert result["noise"]["verdict"] == "UNDECIDED"


def test_a_one_way_merge_stays_a_merge():
    one_way = table(risk_noise="OVERLAP")
    result = item_verdicts(one_way, one_way, TRACKS)
    assert result["risk"] == {
        "verdict": "MERGE",
        "partner": "noise",
        "reasons": ["overlap"],
    }
    pair = table(discipline_noise="OVERLAP")
    result = item_verdicts(pair, pair, only_not_tracking("noise"))
    assert result["noise"] == {
        "verdict": "MERGE",
        "partner": "discipline",
        "reasons": ["overlap and no own-trait tracking"],
    }
    assert result["discipline"]["verdict"] == "UNDECIDED"


def test_event_verdict_thresholds():
    assert event_verdict("panel", D(1), D(0)) == "MECHANICS_ONLY"
    assert event_verdict("db", None, None) == "UNDECIDED"
    assert event_verdict("db", D("0.80"), D(1)) == "NOT_NEEDED"
    assert event_verdict("db", D("0.7999"), D(0)) == "UNDECIDED"
    assert event_verdict("db", D("0.49"), D("0.29")) == "NEEDED"
    assert event_verdict("db", D("0.49"), D("0.30")) == "UNDECIDED"
    assert event_verdict("db", D("0.50"), D("0.10")) == "UNDECIDED"
    assert event_verdict("db", D("0.49"), None) == "UNDECIDED"


# ---------------------------------------------------------------------------- populations


def line(
    world,
    kind,
    index,
    role,
    items=None,
    *,
    schema="validation-component/1",
    traits=None,
    flags=None,
):
    values = {name: None for name in (*ITEMS, CONSISTENCY)}
    values.update({k: str(v) for k, v in (items or {}).items()})
    doc = {
        "schema": schema,
        "cell": "t1",
        "worldIndex": world,
        "kind": kind,
        "index": index,
        "role": role,
        "components": {"items": values},
    }
    if traits is not None:
        doc["traits"] = {t: str(v) for t, v in traits.items()}
    if flags is not None:
        doc["flags"] = {k: str(v) for k, v in flags.items()}
    return doc


def test_only_random_ref_and_eval_bots_enter_the_random_population():
    lines = [
        line(0, "random", 0, "ref", {"risk": 1}),
        line(0, "random", 1, "eval", {"risk": 2}),
        line(0, "static", 0, "eval", {"risk": 3}),
        line(0, "redeclare", 1, "eval", {"risk": 4}),
        line(0, "news", 0, "named", {"risk": 5}),
        line(0, "disciplined", 0, "named", {"risk": 6}),
    ]
    pops = populations(lines)
    assert [(r.kind, r.index) for r in pops["random"].worlds[0]] == [("random", 0), ("random", 1)]
    assert sorted(r.kind for r in pops["named"].worlds[0]) == ["disciplined", "news"]
    assert "trait" not in pops
    assert all(
        r.kind != "static" for p in pops.values() for rows in p.worlds.values() for r in rows
    )


def test_trait_lines_form_their_own_population_and_never_join_random():
    traits = {t: "0.5" for t in TRAITS}
    lines = [
        line(0, "random", 0, "ref", {"risk": 1}),
        line(0, "trait", 0, "eval", {"risk": 2}, schema="process-overlap-trait/1", traits=traits),
        line(0, "random", 7, "eval", {"risk": 9}, schema="process-overlap-trait/1", traits=traits),
    ]
    pops = populations(lines)
    assert [r.index for r in pops["random"].worlds[0]] == [0]
    assert [r.kind for r in pops["trait"].worlds[0]] == ["trait"]


def test_lines_are_checked():
    with pytest.raises(OverlapError):
        populations([{**line(0, "random", 0, "ref"), "schema": "other/1"}])
    broken = line(0, "random", 0, "ref")
    del broken["components"]["items"]["role"]
    with pytest.raises(OverlapError):
        populations([broken])
    with pytest.raises(OverlapError):
        populations([line(0, "trait", 0, "eval", schema="process-overlap-trait/1")])


# ---------------------------------------------------------------------------- constructed lines


def item_values(i: int, level: dict[str, int]) -> dict[str, int]:
    """Items driven by distinct traits plus a bot-specific wiggle; risk and role are free."""
    return {
        "risk": (i * 37) % 101,
        "discipline": 100 - 20 * max(level["panic"], level["chase"]) + (i * 7) % 9,
        "diversification": (i * 53) % 97,
        "turnover": 100 - 20 * level["activity"] + (i * 11) % 9,
        "formation": (i * 29) % 89,
        "role": (i * 17) % 83,
        "noise": 100 - 20 * level["follow"] + (i * 5) % 9,
        "nudge": 100 - 20 * level["route"] + (i * 3) % 9,
    }


def trait_lines(worlds: int = 3, bots: int = 50, *, source: str = "trait") -> list[dict]:
    lines = []
    for w in range(worlds):
        for i in range(bots):
            level = {
                "follow": i % 5,
                "route": (i // 5) % 5,
                "panic": (i * 3 + i // 10) % 5,
                "chase": (i * 2 + i // 7 + w) % 5,
                "activity": (i // 2 + i // 5 * 2) % 5,
                "event": (i // 3) % 5,
            }
            lines.append(
                line(
                    w,
                    "trait",
                    i,
                    "eval",
                    item_values(i, level),
                    schema="process-overlap-trait/1",
                    traits={t: D(v) / 4 for t, v in level.items()},
                    flags={"eventDirection": 10, "eventCaught": 9},
                )
            )
    return lines


def random_lines(worlds: int = 3, bots: int = 50, *, twin_noise: bool = False) -> list[dict]:
    lines = []
    for w in range(worlds):
        for i in range(bots):
            items = {k: (i * (j + 3) + w) % 100 for j, k in enumerate(ITEMS)}
            if twin_noise:
                items["noise"] = items["discipline"]
            lines.append(line(w, "random", i, "ref" if i % 2 else "eval", items))
        lines.append(line(w, "news", 0, "named", {"noise": 5}))
    return lines


def built(*, status="provisional", lines=None, descriptor=DESCRIPTOR):
    return build_overlap(
        MANIFEST, "ci", [(descriptor, lines or [*random_lines(), *trait_lines()])], status=status
    )


def test_the_document_has_every_pair_for_both_populations_and_string_numbers():
    doc = built()
    validate_overlap(doc)
    (cell,) = doc["cells"]
    for name in ("random", "trait"):
        pairs = cell["populations"][name]["pairs"]
        assert [(p["x"], p["y"]) for p in pairs] == list(PAIRS) and len(pairs) == 28
        assert all(isinstance(p["rho"], str) for p in pairs if p["assessable"])
    assert doc["schema"] == "process-overlap/1" and doc["status"] == "provisional"
    assert doc["items"]["consistency"] == {
        "verdict": "NOT_ASSESSABLE",
        "partner": None,
        "reasons": ["no input"],
        "cellSensitive": False,
    }
    assert doc["styleAdherence"]["verdict"] == "NOT_ASSESSABLE" and doc["styleAdherence"]["missing"]
    assert [k["kind"] for k in cell["named"]] == ["news"]


def test_designed_traits_are_tracked_and_pass_into_a_keep_verdict():
    doc = built()
    tracking = doc["cells"][0]["populations"]["trait"]["tracking"]
    assert tracking["noise"]["tracks"] is True and tracking["nudge"]["tracks"] is True
    assert tracking["risk"] is None
    assert doc["items"]["noise"]["verdict"] == "KEEP"
    assert doc["items"]["risk"]["verdict"] in ("KEEP", "UNDECIDED")


def test_overlap_among_random_bots_does_not_decide_a_trait_item():
    doc = built(lines=[*random_lines(twin_noise=True), *trait_lines()])
    pairs = doc["cells"][0]["populations"]["random"]["pairs"]
    twin = next(p for p in pairs if (p["x"], p["y"]) == ("discipline", "noise"))
    assert twin["verdict"] == "OVERLAP"
    trait_pairs = doc["cells"][0]["populations"]["trait"]["pairs"]
    assert (
        next(p for p in trait_pairs if (p["x"], p["y"]) == ("discipline", "noise"))["verdict"]
        != "OVERLAP"
    )
    assert doc["items"]["noise"]["verdict"] == "KEEP"


def test_a_run_without_trait_lines_leaves_items_undecided():
    doc = built(lines=random_lines())
    assert "trait" not in doc["cells"][0]["populations"]
    assert all(doc["items"][i]["verdict"] in ("UNDECIDED", "NOT_ASSESSABLE") for i in ITEMS)
    assert (
        doc["eventResponse"]["verdict"] == "MECHANICS_ONLY"
        and doc["eventResponse"]["coverage"] is None
    )


def test_event_coverage_comes_from_the_trade_flags_and_db_worlds_decide():
    doc = built(descriptor={**DESCRIPTOR, "source": "db"})
    event = doc["eventResponse"]
    assert event["coverage"] == "0.900000000000" and event["verdict"] == "NOT_NEEDED"
    assert event["eventCaughtWon"] == "1350" and event["eventDirectionWon"] == "1500"


def test_build_is_deterministic_and_the_summary_is_short_and_uses_only_document_numbers():
    first, second = built(), built()
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)
    text = render_summary(first)
    assert text == render_summary(second) and len(text.splitlines()) <= 40
    assert text.endswith("\n") and not text.endswith("\n\n")
    haystack = json.dumps(first)
    for number in re.findall(r"-?\d+(?:\.\d+)?", text):
        assert number in haystack, number


def test_lines_can_be_a_generator_and_give_the_same_document():
    lines = [*random_lines(), *trait_lines()]
    as_list = build_overlap(MANIFEST, "ci", [(DESCRIPTOR, lines)], status="provisional")
    as_gen = build_overlap(
        MANIFEST, "ci", iter([(DESCRIPTOR, (x for x in lines))]), status="provisional"
    )
    assert json.dumps(as_list) == json.dumps(as_gen)
    with pytest.raises(OverlapError):
        build_overlap(MANIFEST, "ci", [], status="provisional")


def test_partial_judges_a_cell_on_the_worlds_it_has():
    lines = [x for x in random_lines() if x["worldIndex"] != 2]
    full = built(lines=lines)
    part = built(lines=lines, status="partial")
    assert full["cells"][0]["assessableBase"] == "declared"
    assert part["cells"][0]["assessableBase"] == "analysed"
    assert all(not p["assessable"] for p in full["cells"][0]["populations"]["random"]["pairs"])
    assert any(p["assessable"] for p in part["cells"][0]["populations"]["random"]["pairs"])


def test_rules_describe_the_interval_and_ratios_and_required_keys_are_enforced():
    doc = built()
    assert doc["rules"]["intervalRule"]["unit"] == "world"
    assert doc["rules"]["tieShareLimit"] == "1/2" and doc["rules"]["assessableShare"] == "4/5"
    for key in ("rules", "styleAdherence", "profile"):
        with pytest.raises(OverlapError):
            validate_overlap({k: v for k, v in doc.items() if k != key})


def test_flags_must_be_ascii_digits():
    bad = line(
        0,
        "trait",
        0,
        "eval",
        schema="process-overlap-trait/1",
        traits={t: 0 for t in TRAITS},
        flags={"eventCaught": 1},
    )
    bad["flags"]["eventCaught"] = "\u00b2"
    with pytest.raises(OverlapError):
        populations([bad])


def test_the_validator_refuses_floats_and_unknown_status():
    doc = built()
    with pytest.raises(OverlapError):
        validate_overlap({**doc, "status": "done"})
    with pytest.raises(OverlapError):
        validate_overlap({**doc, "extra": 0.5})
    with pytest.raises(OverlapError):
        validate_overlap({k: v for k, v in doc.items() if k != "items"})


def test_cell_sensitivity_flags_verdicts_that_differ_between_cells():
    lines = [*random_lines(), *trait_lines()]
    other = [{**row, "cell": "t2"} for row in lines if row["kind"] != "trait"]
    doc = build_overlap(
        MANIFEST,
        "ci",
        [(DESCRIPTOR, lines), ({**DESCRIPTOR, "id": "t2"}, other)],
        status="provisional",
    )
    assert doc["primaryCell"] == "t1"
    assert doc["items"]["noise"]["verdict"] == "KEEP" and doc["items"]["noise"]["cellSensitive"]


# ---------------------------------------------------------------------------- source rules


def test_the_module_is_float_free():
    source = Path(overlap.__file__).read_text(encoding="utf-8")
    assert float_uses(ast.parse(source)) == []
