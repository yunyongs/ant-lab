import fcntl
import json
import multiprocessing
import os
import stat
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from antlab_ai.openrouter.ledger import Ledger, LedgerError
from antlab_ai.openrouter.limiter import Limiter, RefusedLocal

MODEL = "vendor/model:free"
ROW_KEYS = {
    "v",
    "phase",
    "call_id",
    "ts",
    "caller",
    "run_id",
    "model",
    "kind",
    "max_tokens",
    "worst_usd",
}


def fixed_clock() -> datetime:
    return datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)


def test_attempt_precedes_outcome(tmp_path):
    ledger = Ledger(tmp_path / "x.jsonl", fixed_clock)
    call_id = ledger.reserve("product:ag01", MODEL)
    ledger.outcome(call_id, "UPSTREAM")
    rows = ledger.rows()
    assert [r["phase"] for r in rows] == ["attempt", "outcome"]
    assert rows[0]["call_id"] == rows[1]["call_id"] == call_id
    assert rows[1]["status"] == "UPSTREAM"
    assert rows[0]["ts"] == "2026-01-01T12:00:00.000Z"


def test_append_only_keeps_earlier_bytes(tmp_path):
    ledger = Ledger(tmp_path / "x.jsonl", fixed_clock)
    ledger.reserve("product:ag01", MODEL)
    before = ledger.path.read_bytes()
    ledger.reserve("harness:ask_llm", MODEL)
    after = ledger.path.read_bytes()
    assert after.startswith(before)
    assert len(after) > len(before)


def test_crashed_reservation_still_counts(tmp_path, clock):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    limiter = Limiter(ledger, 50)
    for _ in range(10):
        limiter.reserve("product:ag01", MODEL)  # no outcome row: simulated crash
    with pytest.raises(RefusedLocal):
        limiter.reserve("product:ag01", MODEL)


def test_utc_day_boundary(tmp_path, clock):
    clock.now = datetime(2026, 1, 1, 23, 59, 59, 999000, tzinfo=UTC)
    limiter = Limiter(Ledger(tmp_path / "x.jsonl", clock), 50)
    for _ in range(10):
        limiter.reserve("product:ag01", MODEL)
    with pytest.raises(RefusedLocal):
        limiter.reserve("product:ag01", MODEL)
    clock.advance(milliseconds=1)
    assert clock().isoformat().startswith("2026-01-02T00:00:00")
    limiter.reserve("product:ag01", MODEL)


def test_minute_window_is_sliding(tmp_path, clock):
    limiter = Limiter(Ledger(tmp_path / "x.jsonl", clock), 50)
    for _ in range(18):
        limiter.reserve("harness:ask_llm", MODEL)
    with pytest.raises(RefusedLocal):
        limiter.reserve("harness:ask_llm", MODEL)
    clock.advance(seconds=59, milliseconds=999)
    with pytest.raises(RefusedLocal):
        limiter.reserve("harness:ask_llm", MODEL)
    clock.advance(milliseconds=2)
    limiter.reserve("harness:ask_llm", MODEL)


def test_product_cap_leaves_harness_bucket(tmp_path, clock):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    limiter = Limiter(ledger, 50)
    for _ in range(10):
        limiter.reserve("product:ag01", MODEL)
    with pytest.raises(RefusedLocal):
        limiter.reserve("product:ag01", MODEL)
    assert len(ledger.rows()) == 10  # a refusal writes no row
    clock.advance(minutes=2)  # leave the minute window; daily buckets are separate
    limiter.reserve("harness:ask_llm", MODEL)
    assert len(ledger.rows()) == 11


def test_harness_cap_is_40_per_day(tmp_path, clock):
    limiter = Limiter(Ledger(tmp_path / "x.jsonl", clock), 50)
    for _ in range(40):
        limiter.reserve("harness:ask_llm", MODEL)
        clock.advance(seconds=10)
    with pytest.raises(RefusedLocal):
        limiter.reserve("harness:ask_llm", MODEL)


def test_account_cap_counts_both_classes(tmp_path, clock):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    limiter = Limiter(ledger, 50)
    for _ in range(40):
        limiter.reserve("harness:ask_llm", MODEL)
        clock.advance(seconds=10)
    for _ in range(10):
        limiter.reserve("product:ag01", MODEL)
        clock.advance(seconds=10)
    assert len(ledger.rows()) == 50
    with pytest.raises(RefusedLocal):
        limiter.reserve("product:ag01", MODEL)


def test_account_cap_refuses_on_its_own(tmp_path, clock):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    for _ in range(50):
        ledger.reserve("other:seed", MODEL)  # counts for the account, not for either class
    clock.advance(minutes=2)  # leave the minute window
    limiter = Limiter(ledger, 50)
    for caller in ("product:ag01", "harness:ask_llm"):
        with pytest.raises(RefusedLocal):
            limiter.reserve(caller, MODEL)
    assert len(ledger.rows()) == 50


def test_tier_1000_product_cap_is_850(tmp_path, clock):
    limiter = Limiter(Ledger(tmp_path / "x.jsonl", clock), 1000)
    for _ in range(850):
        limiter.reserve("product:ag01", MODEL)  # beyond the tier-50 product cap of 10
        clock.advance(seconds=4)  # stays under the 18-per-minute window
    with pytest.raises(RefusedLocal):
        limiter.reserve("product:ag01", MODEL)
    limiter.reserve("harness:ask_llm", MODEL)  # the harness bucket is separate


def test_minute_window_drops_a_row_exactly_sixty_seconds_old(tmp_path, clock):
    limiter = Limiter(Ledger(tmp_path / "x.jsonl", clock), 50)
    for _ in range(18):
        limiter.reserve("harness:ask_llm", MODEL)
    clock.advance(seconds=60)
    limiter.reserve("harness:ask_llm", MODEL)


def test_row_missing_required_keys_fails_closed_with_line_number(tmp_path):
    path = tmp_path / "x"
    ledger = Ledger(path, fixed_clock)
    ledger.reserve("harness:ask_llm", MODEL)
    with path.open("a") as handle:
        handle.write('{"v":1,"phase":"attempt"}\n')
    with pytest.raises(LedgerError, match="line 2"):
        Limiter(ledger).reserve("harness:ask_llm", MODEL)


@pytest.mark.parametrize("tier", [0, 7, 51, 999, 1001])
def test_invalid_tier(tier, tmp_path):
    with pytest.raises(ValueError):
        Limiter(Ledger(tmp_path / "x"), tier)


def test_unknown_caller_class(tmp_path):
    with pytest.raises(ValueError):
        Limiter(Ledger(tmp_path / "x")).reserve("other:thing", MODEL)


@pytest.mark.parametrize(
    "content",
    ['{"v":2,"phase":"attempt"}', '{"v":1}', "not json", "[1]", "null", '{"v":1,"phase":"x"}'],
)
def test_bad_line_fails_closed_with_line_number(tmp_path, content):
    path = tmp_path / "x"
    ledger = Ledger(path, fixed_clock)
    ledger.reserve("harness:ask_llm", MODEL)
    with path.open("a") as handle:
        handle.write(content + "\n")
    with pytest.raises(LedgerError, match="line 2") as info:
        Limiter(ledger).reserve("harness:ask_llm", MODEL)
    assert content not in str(info.value)
    assert len(path.read_text().splitlines()) == 2  # nothing appended


def test_relative_path_rejected():
    with pytest.raises(ValueError):
        Ledger(Path("relative.jsonl"))


def test_rows_are_one_json_object_per_line(tmp_path):
    ledger = Ledger(tmp_path / "x", fixed_clock)
    ledger.outcome(ledger.reserve("harness:ask_llm", MODEL), "OK")
    lines = ledger.path.read_text().splitlines()
    assert len(lines) == 2
    assert all(json.loads(line)["v"] == 1 for line in lines)


def test_attempt_row_key_set_has_no_content_fields(tmp_path):
    ledger = Ledger(tmp_path / "x", fixed_clock)
    ledger.reserve("harness:ask_llm", MODEL)
    assert set(ledger.rows()[0]) == ROW_KEYS


def test_outcome_row_key_set_has_no_content_fields(tmp_path):
    ledger = Ledger(tmp_path / "x", fixed_clock)
    ledger.outcome(ledger.reserve("harness:ask_llm", MODEL), "OK")
    assert set(ledger.rows()[1]) == {
        "v",
        "phase",
        "call_id",
        "ts",
        "status",
        "http",
        "model_actual",
        "provider",
        "generation_id",
        "tokens_in",
        "tokens_out",
        "cost_usd",
        "latency_ms",
        "retry_after_s",
    }


def _refuse_at_twenty(rows, now):
    if len(rows) >= 20:
        raise RefusedLocal("full")


def _wait_for(start_at):
    while time.time() < start_at:
        pass


def _guard_worker(args):
    path, tries, start_at = args
    ledger = Ledger(path, fixed_clock)
    granted = 0
    _wait_for(start_at)
    for _ in range(tries):
        try:
            ledger.reserve("harness:ask_llm", MODEL, guard=_refuse_at_twenty)
            granted += 1
        except RefusedLocal:
            pass
    return granted


def _limiter_worker(args):
    path, tries, start_at = args
    limiter = Limiter(Ledger(path, fixed_clock), 50)
    granted = 0
    _wait_for(start_at)
    for _ in range(tries):
        try:
            limiter.reserve("product:ag01", MODEL)
            granted += 1
        except RefusedLocal:
            pass
    return granted


@pytest.mark.parametrize(("worker", "expected"), [(_guard_worker, 20), (_limiter_worker, 10)])
def test_multiprocess_race_grants_exactly_the_cap(tmp_path, worker, expected):
    path = tmp_path / "x.jsonl"
    with multiprocessing.get_context("spawn").Pool(8) as pool:
        start_at = time.time() + 1.0  # all workers start together, after spawn
        granted = pool.map(worker, [(path, 40, start_at)] * 8)
    assert sum(granted) == expected
    lines = path.read_text().splitlines()
    assert len(lines) == expected
    assert all(json.loads(line)["phase"] == "attempt" for line in lines)


# --- C3: path safety and permissions -----------------------------------------------------------


def lines(path):
    return path.read_text().splitlines()


def test_new_files_are_0600_and_new_directory_0700(tmp_path):
    old = os.umask(0)  # a permissive umask must not widen the modes
    try:
        path = tmp_path / "fresh" / "x.jsonl"
        Ledger(path, fixed_clock).reserve("harness:ask_llm", MODEL)
    finally:
        os.umask(old)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.with_name("x.jsonl.lock").stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700


def test_symlinked_ledger_is_refused_and_target_untouched(tmp_path):
    target = tmp_path / "real.jsonl"
    Ledger(target, fixed_clock).reserve("harness:ask_llm", MODEL)
    before = target.read_bytes()
    link = tmp_path / "link.jsonl"
    link.symlink_to(target)
    with pytest.raises(LedgerError):
        Ledger(link, fixed_clock).reserve("harness:ask_llm", MODEL)
    with pytest.raises(LedgerError):
        Ledger(link, fixed_clock).rows()
    assert target.read_bytes() == before


def test_symlinked_ledger_cannot_get_a_second_lock(tmp_path):
    target = tmp_path / "real.jsonl"
    link = tmp_path / "link.jsonl"
    link.symlink_to(target)
    with pytest.raises(LedgerError):
        Ledger(link, fixed_clock).reserve("harness:ask_llm", MODEL)
    assert not target.exists()


def test_symlinked_lock_file_is_refused(tmp_path):
    other = tmp_path / "other.lock"
    other.write_text("")
    other.chmod(0o600)
    (tmp_path / "x.jsonl.lock").symlink_to(other)
    with pytest.raises(LedgerError):
        Ledger(tmp_path / "x.jsonl", fixed_clock).reserve("harness:ask_llm", MODEL)


def test_directory_as_ledger_is_refused(tmp_path):
    (tmp_path / "x.jsonl").mkdir()
    with pytest.raises(LedgerError):
        Ledger(tmp_path / "x.jsonl", fixed_clock).reserve("harness:ask_llm", MODEL)


def test_fifo_as_ledger_is_refused_without_blocking(tmp_path):
    os.mkfifo(tmp_path / "x.jsonl", 0o600)
    with pytest.raises(LedgerError):
        Ledger(tmp_path / "x.jsonl", fixed_clock).rows()


def test_hard_linked_ledger_is_refused(tmp_path):
    ledger = Ledger(tmp_path / "x.jsonl", fixed_clock)
    ledger.reserve("harness:ask_llm", MODEL)
    os.link(ledger.path, tmp_path / "alias.jsonl")
    with pytest.raises(LedgerError):
        ledger.reserve("harness:ask_llm", MODEL)


@pytest.mark.parametrize("mode", [0o640, 0o604, 0o644, 0o666])
def test_ledger_open_to_group_or_others_is_refused(tmp_path, mode):
    ledger = Ledger(tmp_path / "x.jsonl", fixed_clock)
    ledger.reserve("harness:ask_llm", MODEL)
    ledger.path.chmod(mode)
    with pytest.raises(LedgerError):
        ledger.reserve("harness:ask_llm", MODEL)
    assert len(lines(ledger.path)) == 1


@pytest.mark.parametrize("mode", [0o770, 0o707, 0o777])
def test_directory_writable_by_others_is_refused(tmp_path, mode):
    directory = tmp_path / "shared"
    directory.mkdir()
    directory.chmod(mode)
    with pytest.raises(LedgerError):
        Ledger(directory / "x.jsonl", fixed_clock).reserve("harness:ask_llm", MODEL)
    assert list(directory.iterdir()) == []


def test_reading_a_missing_ledger_creates_nothing(tmp_path):
    assert Ledger(tmp_path / "gone" / "x.jsonl", fixed_clock).rows() == []
    assert not (tmp_path / "gone").exists()


# --- C4: row validation ------------------------------------------------------------------------


def good_attempt(**changes):
    row = {
        "v": 1,
        "phase": "attempt",
        "call_id": str(uuid.uuid4()),
        "ts": "2026-01-01T12:00:00.000Z",
        "caller": "harness:ask_llm",
        "run_id": None,
        "model": MODEL,
        "kind": "chat",
        "max_tokens": 8,
        "worst_usd": "0",
    }
    row.update(changes)
    return row


def good_outcome(call_id, **changes):
    row = {
        "v": 1,
        "phase": "outcome",
        "call_id": call_id,
        "ts": "2026-01-01T12:00:01.000Z",
        "status": "OK",
        "http": 200,
        "model_actual": MODEL,
        "provider": None,
        "generation_id": None,
        "tokens_in": 3,
        "tokens_out": 2,
        "cost_usd": "0.001",
        "latency_ms": 12,
        "retry_after_s": None,
    }
    row.update(changes)
    return row


def seeded(tmp_path, *rows):
    path = tmp_path / "x.jsonl"
    path.write_text("".join(json.dumps(r) + "\n" for r in rows))
    path.chmod(0o600)
    return Ledger(path, fixed_clock)


def test_well_formed_rows_pass(tmp_path):
    attempt = good_attempt()
    ledger = seeded(tmp_path, attempt, good_outcome(attempt["call_id"]))
    assert len(ledger.rows()) == 2


def test_attempt_with_phase_flipped_to_outcome_is_rejected(tmp_path):
    flipped = good_attempt(phase="outcome")  # would silently drop out of the attempt count
    with pytest.raises(LedgerError, match="line 1"):
        seeded(tmp_path, flipped).rows()


BAD_ATTEMPTS = [
    {"v": True},
    {"v": 2},
    {"call_id": "not-a-uuid"},
    {"call_id": 5},
    {"ts": "2026-01-01T12:00:00Z"},
    {"ts": "2026-01-01T12:00:00.000+01:00"},
    {"ts": "2026-13-01T12:00:00.000Z"},
    {"ts": 5},
    {"caller": "nocolon"},
    {"caller": "Product:x"},
    {"caller": "product:has space"},
    {"caller": "product:" + "x" * 65},
    {"caller": 5},
    {"run_id": 5},
    {"model": ""},
    {"model": "two words"},
    {"kind": "other"},
    {"max_tokens": -1},
    {"max_tokens": True},
    {"max_tokens": 1.5},
    {"worst_usd": "NaN"},
    {"worst_usd": "-1"},
    {"worst_usd": 0},
    {"extra": 1},
]


@pytest.mark.parametrize("changes", BAD_ATTEMPTS)
def test_bad_attempt_rows_fail_closed_with_line_number_only(tmp_path, changes):
    ledger = seeded(tmp_path, good_attempt(), good_attempt(**changes))
    with pytest.raises(LedgerError, match=r"^invalid ledger line 2$"):
        ledger.rows()
    with pytest.raises(LedgerError, match="line 2"):
        Limiter(ledger).reserve("harness:ask_llm", MODEL)
    assert len(lines(ledger.path)) == 2


def test_attempt_missing_a_field_is_rejected(tmp_path):
    row = good_attempt()
    del row["kind"]
    with pytest.raises(LedgerError, match="line 1"):
        seeded(tmp_path, row).rows()


BAD_OUTCOMES = [
    {"status": "MAYBE"},
    {"status": 5},
    {"http": 99},
    {"http": 600},
    {"http": "200"},
    {"model_actual": 5},
    {"provider": "has space"},
    {"generation_id": ""},
    {"tokens_in": -1},
    {"tokens_out": 1.5},
    {"cost_usd": 0.5},
    {"cost_usd": "NaN"},
    {"cost_usd": "-1"},
    {"latency_ms": None},
    {"latency_ms": -1},
    {"retry_after_s": -1},
    {"ts": "yesterday"},
    {"extra": 1},
]


@pytest.mark.parametrize("changes", BAD_OUTCOMES)
def test_bad_outcome_rows_fail_closed(tmp_path, changes):
    attempt = good_attempt()
    ledger = seeded(tmp_path, attempt, good_outcome(attempt["call_id"], **changes))
    with pytest.raises(LedgerError, match=r"^invalid ledger line 2$"):
        ledger.rows()


def test_outcome_without_an_attempt_is_rejected(tmp_path):
    with pytest.raises(LedgerError, match="line 1"):
        seeded(tmp_path, good_outcome(str(uuid.uuid4()))).rows()


def test_outcome_before_its_attempt_is_rejected(tmp_path):
    attempt = good_attempt()
    with pytest.raises(LedgerError, match="line 1"):
        seeded(tmp_path, good_outcome(attempt["call_id"]), attempt).rows()


def test_second_outcome_and_duplicate_attempt_are_rejected(tmp_path):
    attempt = good_attempt()
    twice = seeded(
        tmp_path, attempt, good_outcome(attempt["call_id"]), good_outcome(attempt["call_id"])
    )
    with pytest.raises(LedgerError, match="line 3"):
        twice.rows()
    other = tmp_path / "y.jsonl"
    other.write_text(json.dumps(attempt) + "\n" + json.dumps(attempt) + "\n")
    other.chmod(0o600)
    with pytest.raises(LedgerError, match="line 2"):
        Ledger(other, fixed_clock).rows()


def test_non_utf8_ledger_fails_closed_without_content(tmp_path):
    ledger = seeded(tmp_path)
    ledger.path.write_bytes(b"\xff\xfe secret\n")
    with pytest.raises(LedgerError) as info:
        ledger.rows()
    assert "secret" not in str(info.value)


def test_outcome_cannot_overwrite_reserved_fields(tmp_path):
    ledger = Ledger(tmp_path / "x.jsonl", fixed_clock)
    call_id = ledger.reserve("harness:ask_llm", MODEL)
    for name in ("phase", "call_id", "ts", "v", "status", "caller"):
        with pytest.raises(TypeError):
            ledger.outcome(call_id, "OK", **{name: "x"})
    assert len(lines(ledger.path)) == 1


def test_outcome_needs_a_known_open_attempt(tmp_path):
    ledger = Ledger(tmp_path / "x.jsonl", fixed_clock)
    with pytest.raises(LedgerError):
        ledger.outcome(str(uuid.uuid4()), "OK")
    call_id = ledger.reserve("harness:ask_llm", MODEL)
    ledger.outcome(call_id, "OK")
    with pytest.raises(LedgerError):
        ledger.outcome(call_id, "OK")
    assert len(lines(ledger.path)) == 2


def test_invalid_values_are_never_appended(tmp_path):
    ledger = Ledger(tmp_path / "x.jsonl", fixed_clock)
    call_id = ledger.reserve("harness:ask_llm", MODEL)
    with pytest.raises(LedgerError):
        ledger.outcome(call_id, "MAYBE")
    with pytest.raises(LedgerError):
        ledger.outcome(call_id, "OK", tokens_out=-1)
    with pytest.raises(LedgerError):
        ledger.reserve("bad caller", MODEL)
    with pytest.raises(LedgerError):
        ledger.reserve("harness:x", MODEL, kind="weird")
    assert len(lines(ledger.path)) == 1


def test_status_output_cannot_carry_forged_text(tmp_path):
    ledger = seeded(tmp_path, good_attempt(caller="harness:x\nattempt evil 1999-01-01"))
    with pytest.raises(LedgerError):
        ledger.rows()


# --- C5: one UTC instant, read after the lock is held -----------------------------------------


def test_clock_is_read_once_and_only_while_the_lock_is_held(tmp_path):
    path = tmp_path / "x.jsonl"
    reads = []

    def clock():
        probe = os.open(path.with_name("x.jsonl.lock"), os.O_RDWR)
        try:
            fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            reads.append("lock free")
        except BlockingIOError:
            reads.append("lock held")
        finally:
            os.close(probe)
        return datetime(2026, 1, 1, 23, 59, 59, 999000, tzinfo=UTC)

    seen = []
    Ledger(path, clock).reserve("harness:ask_llm", MODEL, guard=lambda rows, now: seen.append(now))
    assert reads == ["lock held"]  # exactly one read, after acquiring the lock
    assert seen == [datetime(2026, 1, 1, 23, 59, 59, 999000, tzinfo=UTC)]


def test_checked_instant_is_the_recorded_instant_across_midnight(tmp_path):
    ticks = iter(
        [datetime(2026, 1, 1, 23, 59, 59, 999000, tzinfo=UTC), datetime(2026, 1, 2, tzinfo=UTC)]
    )
    ledger = Ledger(tmp_path / "x.jsonl", lambda: next(ticks))
    seen = []
    ledger.reserve("harness:ask_llm", MODEL, guard=lambda rows, now: seen.append(now))
    assert ledger.rows()[0]["ts"] == "2026-01-01T23:59:59.999Z"
    assert seen[0].date().isoformat() == "2026-01-01"  # the day that was checked is the day stored


def test_limiter_day_and_minute_use_the_stamped_instant(tmp_path):
    class Stepping:
        """Each read is 0.4 s later: a second read would shift the 60 s boundary."""

        def __init__(self):
            self.now = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)

        def __call__(self):
            current = self.now
            self.now += timedelta(milliseconds=400)
            return current

    step = Stepping()
    limiter = Limiter(Ledger(tmp_path / "x.jsonl", step), 1000)
    for _ in range(18):  # 18 attempts, 0.4 s apart, all inside one minute
        limiter.reserve("harness:ask_llm", MODEL)
    with pytest.raises(RefusedLocal):
        limiter.reserve("harness:ask_llm", MODEL)
    stamped = [r["ts"] for r in limiter.ledger.rows()]
    assert stamped[0] == "2026-01-01T12:00:00.000Z" and stamped[1] == "2026-01-01T12:00:00.400Z"
    step.now = datetime(2026, 1, 1, 12, 0, 59, 999000, tzinfo=UTC)  # 59.999 s after the first row
    with pytest.raises(RefusedLocal):
        limiter.reserve("harness:ask_llm", MODEL)
    step.now = datetime(2026, 1, 1, 12, 1, 0, tzinfo=UTC)  # exactly 60.0 s: the first row ages out
    limiter.reserve("harness:ask_llm", MODEL)


def test_midnight_is_decided_on_the_same_instant_as_the_stamp(tmp_path, clock):
    clock.now = datetime(2026, 1, 1, 23, 59, 59, 999000, tzinfo=UTC)
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    limiter = Limiter(ledger, 50)
    for _ in range(10):
        limiter.reserve("product:ag01", MODEL)
    clock.advance(milliseconds=1)
    limiter.reserve("product:ag01", MODEL)  # new UTC day: the daily count starts again
    assert ledger.rows()[-1]["ts"] == "2026-01-02T00:00:00.000Z"
