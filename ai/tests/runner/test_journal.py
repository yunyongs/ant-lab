"""C8: the local journal."""

import json
import os
import stat
from uuid import uuid4

import pytest
from runner_helpers import reply

from antlab_ai.modelport.fake import scripted_attempt
from antlab_ai.runner.decision import Parsed
from antlab_ai.runner.journal import (
    Journal,
    JournalCorrupt,
    JournalError,
    JournalLocked,
)
from antlab_ai.runner.records import RecordBuilder, RunnerDefect

BUILDER = RecordBuilder("OPENROUTER", "m/x:free")


def record(n=1, status="UPSTREAM"):
    attempt = scripted_attempt(
        text=reply() if status == "OK" else None,
        status=status,
        ledger_call_id=uuid4(),
        request_text="{}",
    )
    verdict = Parsed({}) if status == "OK" else None
    return BUILDER.build(attempt, n, verdict)


def result(outcome="DECISION", turn=1):
    return {"seasonId": 7, "turnNo": turn, "outcome": outcome, "replayed": False}


@pytest.fixture
def state_dir(tmp_path):
    return tmp_path / "runner"


def open_journal(state_dir, agent=11, season=7):
    return Journal.open(state_dir, agent, season)


def test_modes_and_location(state_dir):
    with open_journal(state_dir) as journal:
        assert journal.path == state_dir / "agent-11-season-7.jsonl"
        assert stat.S_IMODE(state_dir.stat().st_mode) == 0o700
        assert stat.S_IMODE(journal.path.stat().st_mode) == 0o600


def test_created_directories_are_private_even_with_a_loose_umask(tmp_path):
    old = os.umask(0)
    try:
        with open_journal(tmp_path / "a" / "b" / "c") as journal:
            assert stat.S_IMODE(journal.path.parent.stat().st_mode) == 0o700
            assert stat.S_IMODE(journal.path.stat().st_mode) == 0o600
    finally:
        os.umask(old)


def test_a_symlinked_file_is_refused(state_dir, tmp_path):
    state_dir.mkdir(mode=0o700)
    target = tmp_path / "elsewhere"
    target.write_text("")
    target.chmod(0o600)  # a private target: only the link itself may be the reason for refusal
    (state_dir / "agent-11-season-7.jsonl").symlink_to(target)
    with pytest.raises(JournalError):
        open_journal(state_dir)


def test_a_symlinked_directory_is_refused(tmp_path):
    real = tmp_path / "real"
    real.mkdir(mode=0o700)
    link = tmp_path / "link"
    link.symlink_to(real)
    with pytest.raises(JournalError):
        open_journal(link)


def test_a_loose_directory_or_file_is_refused(state_dir):
    state_dir.mkdir(mode=0o755)
    state_dir.chmod(0o755)
    with pytest.raises(JournalError):
        open_journal(state_dir)
    state_dir.chmod(0o700)
    (state_dir / "agent-11-season-7.jsonl").write_text("")
    (state_dir / "agent-11-season-7.jsonl").chmod(0o644)
    with pytest.raises(JournalError):
        open_journal(state_dir)


def test_a_foreign_owner_is_refused(state_dir, monkeypatch):
    open_journal(state_dir).close()
    monkeypatch.setattr(os, "geteuid", lambda: os.getuid() + 1)
    with pytest.raises(JournalError):
        open_journal(state_dir)


@pytest.mark.parametrize("agent, season", [(0, 1), (1, 0), (True, 1), ("1", 1), (1, -3)])
def test_ids_must_be_positive_integers(state_dir, agent, season):
    with pytest.raises(JournalError):
        Journal.open(state_dir, agent, season)


def test_a_second_runner_on_the_same_season_is_refused_until_the_first_closes(state_dir):
    first = open_journal(state_dir)
    with pytest.raises(JournalLocked):
        open_journal(state_dir)
    other_season = open_journal(state_dir, season=8)
    other_season.close()
    first.close()
    open_journal(state_dir).close()


def test_rows_are_only_appended(state_dir):
    with open_journal(state_dir) as journal:
        journal.append_attempt(1, record(1))
        before = journal.path.read_bytes()
    with open_journal(state_dir) as journal:
        assert journal.path.read_bytes() == before
        journal.append_attempt(1, record(2), retry_after_s=30)
        after = journal.path.read_bytes()
    assert after.startswith(before) and len(after) > len(before)
    assert after.count(b"\n") == 2


def test_reload_rebuilds_the_state(state_dir):
    with open_journal(state_dir) as journal:
        journal.append_attempt(0, record(1))
        journal.append_attempt(0, record(2, "OK"), retry_after_s=None)
        journal.append_flushed(0, 1)
        journal.append_decided(0, result("DECISION", 0))
        journal.append_advanced(0)
        journal.append_attempt(1, record(1), retry_after_s=30)
        journal.append_pause("LIMITED_DAILY", 1)
        journal.append_resume()
    with open_journal(state_dir) as journal:
        state = journal.state
        turn0, turn1 = state.turns[0], state.turns[1]
        assert [a["attemptNo"] for a in turn0.attempts] == [1, 2]
        assert turn0.decided["outcome"] == "DECISION" and turn0.advanced
        assert turn0.flushed == 2  # a decision stores every attempt
        assert turn1.retry_after == [30] and turn1.decided is None
        assert state.unflushed(1) == turn1.attempts
        assert state.unflushed(0) == [] and state.unflushed(5) == []
        assert state.attempt_total == 3
        assert state.pauses == [(1, "LIMITED_DAILY")]
        assert state.resumes == 1


def test_flushed_marks_split_stored_from_unstored(state_dir):
    with open_journal(state_dir) as journal:
        for n in (1, 2, 3):
            journal.append_attempt(0, record(n))
        journal.append_flushed(0, 2)
        assert [a["attemptNo"] for a in journal.state.unflushed(0)] == [3]
        with pytest.raises(JournalCorrupt):
            journal.append_flushed(0, 1)  # cannot go back
        with pytest.raises(JournalCorrupt):
            journal.append_flushed(0, 4)  # cannot pass the attempts


def test_no_response_streak_counts_decided_turns_and_restarts_after_resume(state_dir):
    with open_journal(state_dir) as journal:
        for turn, outcome in enumerate(["NO_RESPONSE", "NO_RESPONSE"]):
            journal.append_attempt(turn, record(1))
            journal.append_decided(turn, result(outcome, turn))
        assert journal.state.no_response_streak == 2
        journal.append_attempt(2, record(1, "OK"))
        journal.append_decided(2, result("DECISION", 2))
        assert journal.state.no_response_streak == 0
        for turn in (3, 4, 5):
            journal.append_attempt(turn, record(1))
            journal.append_decided(turn, result("NO_RESPONSE", turn))
        assert journal.state.no_response_streak == 3
        journal.append_resume()
        assert journal.state.no_response_streak == 0
    with open_journal(state_dir) as journal:
        assert journal.state.no_response_streak == 0


def test_a_torn_last_line_is_refused_and_never_dropped(state_dir):
    with open_journal(state_dir) as journal:
        journal.append_attempt(0, record(1))
        path = journal.path
    data = path.read_bytes()
    path.write_bytes(data + b'{"v":1,"kind":"att')
    with pytest.raises(JournalCorrupt):
        open_journal(state_dir)
    assert path.read_bytes() == data + b'{"v":1,"kind":"att'
    path.write_bytes(data[:-1])  # newline missing
    with pytest.raises(JournalCorrupt):
        open_journal(state_dir)


@pytest.mark.parametrize(
    "line",
    [
        b"not json\n",
        b"[1]\n",
        b'{"v":2,"kind":"resume"}\n',
        b'{"v":1,"kind":"mystery"}\n',
        b'{"v":1,"kind":"pause","turnNo":0,"reason":"FREE TEXT"}\n',
        b'{"v":1,"kind":"flushed","turnNo":0,"upTo":1}\n',
        b'{"v":1,"kind":"advanced","turnNo":0}\n',
        b'{"v":1,"kind":"attempt","turnNo":-1,"record":{},"retryAfter":null}\n',
        b'{"v":1,"kind":"decided","turnNo":0,"result":{"outcome":"MAYBE"}}\n',
    ],
)
def test_foreign_rows_are_refused(state_dir, line):
    state_dir.mkdir(mode=0o700)
    path = state_dir / "agent-11-season-7.jsonl"
    path.write_bytes(line)
    path.chmod(0o600)
    with pytest.raises(JournalCorrupt):
        open_journal(state_dir)


def test_attempt_numbers_must_follow_one_another(state_dir):
    with open_journal(state_dir) as journal:
        with pytest.raises(JournalCorrupt):
            journal.append_attempt(0, record(2))
        journal.append_attempt(0, record(1))
        with pytest.raises(JournalCorrupt):
            journal.append_attempt(0, record(1))
        assert journal.path.read_bytes().count(b"\n") == 1  # a refused row is never written


def test_nothing_may_follow_a_final_decision_and_an_advance_needs_one(state_dir):
    with open_journal(state_dir) as journal:
        with pytest.raises(JournalCorrupt):
            journal.append_advanced(0)
        journal.append_attempt(0, record(1))
        journal.append_decided(0, result("NO_RESPONSE", 0))
        with pytest.raises(JournalCorrupt):
            journal.append_attempt(0, record(2))
        with pytest.raises(JournalCorrupt):
            journal.append_decided(0, result("NO_RESPONSE", 0))
        journal.append_advanced(0)
        with pytest.raises(JournalCorrupt):
            journal.append_advanced(0)


def test_pause_reasons_are_a_fixed_vocabulary(state_dir):
    with open_journal(state_dir) as journal:
        journal.append_pause("BUDGET")
        with pytest.raises(JournalCorrupt):
            journal.append_pause("the model said no", 1)
        assert journal.state.pauses == [(None, "BUDGET")]


def test_an_unstorable_record_never_reaches_the_file(state_dir):
    bad = record(1)
    bad["requestText"] = "has\x00nul"
    with open_journal(state_dir) as journal:
        with pytest.raises(RunnerDefect):
            journal.append_attempt(0, bad)
        assert journal.path.read_bytes() == b""


def test_rows_hold_the_record_as_made(state_dir):
    with open_journal(state_dir) as journal:
        made = record(1, "OK")
        journal.append_attempt(3, made, retry_after_s=7)
        row = json.loads(journal.path.read_text().splitlines()[0])
    assert row == {"v": 1, "kind": "attempt", "turnNo": 3, "record": made, "retryAfter": 7}


def test_a_closed_journal_refuses_appends(state_dir):
    journal = open_journal(state_dir)
    journal.close()
    journal.close()
    with pytest.raises(JournalError):
        journal.append_resume()
