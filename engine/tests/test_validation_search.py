"""Weight grid, screen and selection: nearest to the draft, training worlds only, held out."""

import random

import pytest
from validation_tables import D, prow, selection_world, tables, world_rows

from antlab_engine.validation.checks import (
    FORMULA_63,
    FORMULA_S,
    Bootstrap,
    CheckError,
    evaluate_checks,
)
from antlab_engine.validation.composite import DRAFT_63, DRAFT_S
from antlab_engine.validation.search import (
    OUTCOME_NONE_PASSING,
    OUTCOME_NOT_ROBUST,
    OUTCOME_PROPOSAL,
    SELECTION_CHECKS,
    SearchError,
    distance_squared,
    draft_units,
    grid_units,
    screen,
    search_weights,
    weights_of,
)

SEED = 23
WORLDS = range(6)


def table_63(disciplined, news, *, poison=None):
    """Six identical worlds (even indices train, odd test); `poison` rewrites the odd ones."""
    worlds = []
    for w in WORLDS:
        d, n = poison if poison is not None and w % 2 == 1 else (disciplined, news)
        worlds.append(selection_world(w, disciplined=d, news=n))
    return tables(*worlds)


def selected_weights(doc):
    return None if doc["proposal"] is None else doc["proposal"]["weights"]


# ---------------------------------------------------------------------------- grid


def test_s63_grid_is_the_nine_result_weights_from_030_to_070():
    points = grid_units(FORMULA_63)
    assert points == tuple((a, 20 - a) for a in range(6, 15))
    assert [weights_of(FORMULA_63, p)[0] for p in points][0] == D("0.30")
    assert weights_of(FORMULA_63, points[-1]) == (D("0.70"), D("0.30"))


def test_s_grid_respects_every_constraint_and_contains_the_draft():
    points = grid_units(FORMULA_S)
    assert len(points) == len(set(points)) == 596
    assert list(points) == sorted(points)
    assert all(sum(p) == 20 and min(p) >= 1 and 3 <= p[3] <= 10 for p in points)
    assert min(p[3] for p in points) == 3 and max(p[3] for p in points) == 10
    assert draft_units(FORMULA_S) in points and draft_units(FORMULA_63) in grid_units(FORMULA_63)
    for p in points:
        weights_of(FORMULA_S, p)


def test_draft_units_are_the_draft_weights():
    assert weights_of(FORMULA_S, draft_units(FORMULA_S)) == (
        DRAFT_S.ret,
        DRAFT_S.risk_adjusted,
        DRAFT_S.risk,
        DRAFT_S.process,
    )
    assert weights_of(FORMULA_63, draft_units(FORMULA_63)) == (DRAFT_63.result, DRAFT_63.process)


def test_distance_is_squared_euclidean_in_steps():
    assert distance_squared((6, 6, 3, 5), (6, 6, 3, 5)) == 0
    assert distance_squared((4, 4, 2, 10), (6, 6, 3, 5)) == 4 + 4 + 1 + 25


# ---------------------------------------------------------------------------- selection


def test_exactly_one_passing_point_is_selected_when_the_draft_fails():
    result = search_weights(table_63((50, 69), (27, 50)), FORMULA_63, SEED)
    doc = result.doc
    assert doc["draft"]["passesSelection"] is False
    assert doc["screenPassing"] == {"train": 1, "test": 1}
    assert doc["outcome"] == OUTCOME_PROPOSAL == result.outcome
    assert selected_weights(doc) == {"result": "0.45", "process": "0.55"}
    assert result.proposal_units == (9, 11)
    assert doc["selected"]["draftKept"] is False
    assert doc["selected"]["distanceSquaredSteps"] == 2
    assert doc["heldOut"] == {"robust": True, "failed": []}


def test_two_passing_points_select_the_nearer_one_not_the_best_separated():
    # disciplined edge 19 x (1 - a), news edge -20 x (1 - a): passes for a = 0.30 .. 0.45;
    # the separation is largest at 0.30, the draft 0.50 is nearest to 0.45
    result = search_weights(table_63((50, 69), (50, 30)), FORMULA_63, SEED)
    assert result.doc["screenPassing"]["train"] == 4
    assert selected_weights(result.doc) == {"result": "0.45", "process": "0.55"}


def test_a_passing_draft_is_selected_unchanged():
    result = search_weights(table_63((50, 90), (10, 50)), FORMULA_63, SEED)
    selected = result.doc["selected"]
    assert result.doc["draft"]["passesSelection"] is True
    assert selected["draftKept"] is True and selected["distanceSquaredSteps"] == 0
    assert selected_weights(result.doc) == {"result": "0.50", "process": "0.50"}


def test_no_passing_point_is_reported():
    result = search_weights(table_63((50, 50), (50, 50)), FORMULA_63, SEED)
    assert result.outcome == OUTCOME_NONE_PASSING and result.proposal_units is None
    assert result.doc["screenPassing"] == {"train": 0, "test": 0}
    assert result.doc["selected"] is None and result.doc["proposal"] is None
    assert result.doc["heldOut"] is None


def test_s_selection_is_the_nearest_point_with_lexicographic_ties():
    disciplined, news = (50, 50, 50, 70), (50, 50, 50, 30)
    worlds = tables(
        *(
            world_rows(
                w,
                random=(50, 50, 50, 50),
                disciplined=disciplined,
                news=news,
                noguard=disciplined,
                mixes={50: (50, 50, 50, 50)},
                statics=((50, 50, 50, 50),),
            )
            for w in WORLDS
        )
    )
    result = search_weights(worlds, FORMULA_S, SEED)
    assert result.doc["screenPassing"] == {"train": 36, "test": 36}
    # process must be 0.50; (4, 4, 2), (4, 5, 1) and (5, 4, 1) are equally near, the first wins
    assert result.proposal_units == (4, 4, 2, 10)
    assert selected_weights(result.doc) == {
        "return": "0.20",
        "riskAdjusted": "0.20",
        "risk": "0.10",
        "process": "0.50",
    }


# ---------------------------------------------------------------------------- held out and leakage


def test_selection_never_reads_the_test_worlds():
    clean = search_weights(table_63((50, 69), (27, 50)), FORMULA_63, SEED)
    poisoned = search_weights(
        table_63((50, 69), (27, 50), poison=((50, 50), (50, 50))), FORMULA_63, SEED
    )
    assert poisoned.doc["screenPassing"]["train"] == clean.doc["screenPassing"]["train"] == 1
    assert poisoned.doc["selected"]["weights"] == clean.doc["selected"]["weights"]
    assert poisoned.doc["selected"]["train"] == clean.doc["selected"]["train"]
    assert poisoned.doc["draft"]["train"] == clean.doc["draft"]["train"]
    # the held-out half does see the poison
    assert poisoned.doc["screenPassing"]["test"] == 0
    assert poisoned.outcome == OUTCOME_NOT_ROBUST and poisoned.proposal_units is None
    assert poisoned.doc["proposal"] is None
    assert set(poisoned.doc["heldOut"]["failed"]) >= {"C3", "C4"}
    assert clean.outcome == OUTCOME_PROPOSAL


def test_world_order_does_not_change_any_result():
    forward = jittered(1)
    backward = tuple(reversed(forward))
    boot = Bootstrap(SEED, len(forward))
    weights = weights_of(FORMULA_63, (8, 12))
    assert evaluate_checks(backward, FORMULA_63, weights, boot) == evaluate_checks(
        forward, FORMULA_63, weights, boot
    )
    assert (
        screen(backward, FORMULA_63, grid_units(FORMULA_63), boot).tolist()
        == screen(forward, FORMULA_63, grid_units(FORMULA_63), boot).tolist()
    )
    assert (
        search_weights(backward, FORMULA_63, SEED).doc
        == search_weights(forward, FORMULA_63, SEED).doc
    )


def test_the_same_world_twice_is_refused():
    worlds = jittered(1)
    with pytest.raises(CheckError):
        evaluate_checks(worlds + worlds[:1], FORMULA_63, (D("0.5"), D("0.5")), Bootstrap(SEED, 7))


def test_a_passing_draft_is_kept_even_when_the_float_screen_misses_it():
    # exact C3 mean is 10.000000000000 at the draft, but the 1e-9 rounded sums fall one unit short
    edges = ["10.0000000004", "10.0000000003", "9.9999999993"]
    worlds = []
    for w in WORLDS:
        edge = D(edges[w // 2]) if w % 2 == 0 else D(10)
        worlds.append(selection_world(w, disciplined=(50, 50 + 2 * edge), news=(27, 50)))
    result = search_weights(tables(*worlds), FORMULA_63, SEED)
    assert result.doc["draft"]["passesSelection"] is True
    assert result.doc["selected"]["draftKept"] is True
    assert selected_weights(result.doc) == {"result": "0.50", "process": "0.50"}


def test_the_screen_never_rejects_a_point_the_exact_checks_pass():
    for seed in (1, 2, 3, 4):
        worlds = jittered(seed)
        boot = Bootstrap(SEED, len(worlds))
        points = grid_units(FORMULA_63)
        flags = screen(worlds, FORMULA_63, points, boot)
        for point, row in zip(points, flags.tolist(), strict=True):
            exact = evaluate_checks(worlds, FORMULA_63, weights_of(FORMULA_63, point), boot)
            for flag, check in zip(row, SELECTION_CHECKS, strict=True):
                assert flag or exact[check]["passed"] is not True


def test_held_out_bots_decide_robustness():
    worlds = []
    for w in WORLDS:
        rows = selection_world(w, disciplined=(50, 69), news=(27, 50))
        rows = [r for r in rows if r.kind != "disciplined-noguard"]
        rows.append(prow(w, "disciplined-noguard", 0, *((50, 50) if w % 2 else (50, 69))))
        worlds.append(rows)
    result = search_weights(tables(*worlds), FORMULA_63, SEED)
    assert result.outcome == OUTCOME_NOT_ROBUST
    assert result.doc["heldOut"]["failed"] == ["C6"]
    assert result.doc["selected"] is not None


def test_both_halves_are_required():
    with pytest.raises(SearchError):
        search_weights(
            tables(selection_world(0, disciplined=(50, 69), news=(27, 50))), FORMULA_63, 1
        )


def test_the_search_needs_the_selection_bots():
    rows = world_rows(0, disciplined=None, news=(27, 50)), world_rows(1, disciplined=None)
    with pytest.raises(SearchError):
        search_weights(tables(*rows), FORMULA_63, 1)


# ---------------------------------------------------------------------------- float screen = exact


def jittered(seed, worlds=6, bots=6, parts=2):
    rng = random.Random(seed)  # noqa: S311 - a test fixture, not security

    def vec(low, high):
        return tuple(rng.randint(low, high) for _ in range(parts))

    rows = []
    for w in range(worlds):
        each = tuple(vec(30, 70) for _ in range(bots))
        disciplined, news = vec(45, 95), vec(5, 60)
        rows.append(
            world_rows(
                w,
                random_each=each,
                disciplined=disciplined,
                news=news,
                mixes={25: vec(40, 90), 50: vec(30, 70), 75: vec(10, 60)},
            )
        )
    return tables(*rows)


@pytest.mark.parametrize(("formula", "parts", "step"), [(FORMULA_63, 2, 1), (FORMULA_S, 4, 9)])
@pytest.mark.parametrize("seed", [1, 2, 3])
def test_float_screen_agrees_with_the_exact_checks(formula, parts, step, seed):
    worlds = jittered(seed, parts=parts)
    boot = Bootstrap(SEED, len(worlds))
    points = grid_units(formula)[::step]
    flags = screen(worlds, formula, points, boot)
    for point, row in zip(points, flags.tolist(), strict=True):
        exact = evaluate_checks(worlds, formula, weights_of(formula, point), boot)
        assert row == [exact[c]["passed"] is True for c in SELECTION_CHECKS], point


def test_the_screen_finds_passing_and_failing_points_in_the_jittered_tables():
    seen = set()
    for seed in (1, 2, 3, 4, 5):
        worlds = jittered(seed)
        flags = screen(worlds, FORMULA_63, grid_units(FORMULA_63), Bootstrap(SEED, len(worlds)))
        seen.update(map(tuple, flags.tolist()))
    assert len(seen) > 1


def test_every_search_result_is_json_ready_strings():
    result = search_weights(table_63((50, 69), (27, 50)), FORMULA_63, SEED)

    def walk(value):
        assert not isinstance(value, float)
        if isinstance(value, dict):
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(result.doc)
