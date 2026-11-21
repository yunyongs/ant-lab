"""AG-05 E: the memory rows of the journal."""

import json

import pytest
from step_helpers import MODEL, ROUTE, reflection

from antlab_ai.modelport.fake import scripted_attempt
from antlab_ai.runner.journal import Journal, JournalCorrupt
from antlab_ai.runner.records import RecordBuilder


def record(kind, number, status="OK", text="{}"):
    builder = RecordBuilder(ROUTE, MODEL)
    return builder.build_as(scripted_attempt(text), number, status, kind)


@pytest.fixture
def journal(tmp_path):
    with Journal.open(tmp_path / "state", 1, 7) as opened:
        yield opened


def reopened(journal):
    return Journal.open(journal.path.parent, 1, 7)


def test_rows_survive_a_reopen(journal):
    journal.append_memory_base(2)
    journal.append_memory_attempt(
        "REFLECTION", record("REFLECTION", 1, "INVALID"), [("/good/0#s1", "YEAR")]
    )
    journal.append_memory_attempt(
        "REFLECTION", record("REFLECTION", 2, text=json.dumps(reflection()))
    )
    journal.append_memory_stop("BUDGET")
    memory = journal.state.memory
    assert (memory.base, memory.sent, memory.last_stop) == (2, 2, "BUDGET")
    assert memory.barrier == {"REFLECTION": 2, "PLAYBOOK": 0}
    journal.close()
    with reopened(journal) as again:
        mem = again.state.memory
        assert mem.violations["REFLECTION"] == [[("/good/0#s1", "YEAR")], []]
        assert [r["attemptNo"] for r in mem.attempts["REFLECTION"]] == [1, 2]
        assert mem.barrier == memory.barrier and mem.done is None


def test_a_new_base_drops_the_playbook_attempts_and_keeps_the_reflection(journal):
    journal.append_memory_base(None)
    journal.append_memory_attempt("REFLECTION", record("REFLECTION", 1))
    journal.append_memory_attempt("PLAYBOOK", record("PLAYBOOK", 1))
    journal.append_memory_base(None)
    assert len(journal.state.memory.attempts["PLAYBOOK"]) == 1
    journal.append_memory_base(1)
    memory = journal.state.memory
    assert memory.attempts["PLAYBOOK"] == [] and len(memory.attempts["REFLECTION"]) == 1
    assert memory.sent == 2
    journal.append_memory_attempt("PLAYBOOK", record("PLAYBOOK", 1))  # numbering restarts


def test_a_reject_marks_the_last_reply_and_a_rejected_reflection_clears_the_rewrite(journal):
    journal.append_memory_base(None)
    journal.append_memory_attempt("REFLECTION", record("REFLECTION", 1))
    journal.append_memory_attempt("PLAYBOOK", record("PLAYBOOK", 1))
    journal.append_memory_reject("PLAYBOOK", [("/rules/0#s1", "PRICE")])
    memory = journal.state.memory
    assert memory.attempts["PLAYBOOK"][0]["status"] == "INVALID"
    assert memory.violations["PLAYBOOK"] == [[("/rules/0#s1", "PRICE")]]
    assert memory.attempts["REFLECTION"][0]["status"] == "OK"
    journal.append_memory_reject("REFLECTION", [("/good/0#s1", "YEAR")])
    assert journal.state.memory.attempts["PLAYBOOK"] == []
    assert journal.state.memory.attempts["REFLECTION"][0]["status"] == "INVALID"


def test_a_failed_append_leaves_the_state_and_the_file_alone(journal):
    journal.append_memory_attempt("REFLECTION", record("REFLECTION", 1, "INVALID"))
    size = journal.path.stat().st_size
    with pytest.raises(JournalCorrupt):
        journal.append_memory_reject("REFLECTION", [("/good/0#s1", "YEAR")])  # no accepted reply
    assert journal.path.stat().st_size == size
    assert journal.state.memory.attempts["REFLECTION"][0]["status"] == "INVALID"


@pytest.mark.parametrize(
    "action",
    [
        lambda j: j.append_memory_attempt("REFLECTION", record("REFLECTION", 2)),
        lambda j: j.append_memory_attempt("PLAYBOOK", record("REFLECTION", 1)),
        lambda j: j.append_memory_attempt(
            "PLAYBOOK", record("PLAYBOOK", 1), [("/rules/0", "bad code!")]
        ),
        lambda j: j.append_memory_attempt(
            "PLAYBOOK", record("PLAYBOOK", 1), [("/rules/리먼", "YEAR")]
        ),
        lambda j: j.append_memory_stop("lower case"),
        lambda j: j.append_memory_done("MAYBE", None),
        lambda j: j.append_memory_done("ACCEPTED", 0),
        lambda j: j.append_memory_base(0),
    ],
)
def test_a_malformed_memory_row_is_refused_and_never_written(journal, action):
    with pytest.raises(JournalCorrupt):
        action(journal)
    assert journal.path.stat().st_size == 0


def test_nothing_follows_the_outcome(journal):
    journal.append_memory_done("ACCEPTED", 1)
    with pytest.raises(JournalCorrupt):
        journal.append_memory_base(None)
    assert journal.state.memory.done == {"outcome": "ACCEPTED", "version": 1}


def test_turn_rows_and_memory_rows_share_one_file_without_touching_each_other(journal):
    journal.append_memory_attempt("REFLECTION", record("REFLECTION", 1))
    assert journal.state.attempt_total == 0 and journal.state.memory.sent == 1
    journal.append_pause("BUDGET", 0)
    assert journal.state.pauses == [(0, "BUDGET")]


def test_a_stored_row_with_an_unknown_step_is_corrupt_on_load(journal):
    journal.append_memory_base(None)
    journal.close()
    with journal.path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"v": 1, "kind": "mattempt", "step": "OTHER", "record": {}}) + "\n")
    with pytest.raises(JournalCorrupt):
        reopened(journal)
