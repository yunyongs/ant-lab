"""The batch ledger and lock, plus the batch-start checks that need the CLI (still no model call)."""

import json
import os
import stat
import sys
from decimal import Decimal

import pytest
from gate_helpers import NOW, snapshot
from sub_fakes import request

from antlab_ai.modelport import SubscriptionRefusal
from antlab_ai.modelport.subscription import BatchLedger, BatchLock
from antlab_ai.modelport.subscription.cli_check import check_login, read_version, resolve_binary
from antlab_ai.modelport.subscription.common import AttemptMeta
from antlab_ai.modelport.subscription.ledger import LedgerError, estimate_text

# ---- ledger ------------------------------------------------------------------------------


def spawn(ledger, batch="b1", status="OK", **over):
    """One reservation and its completion, as a spawn writes them."""
    rid = ledger.reserve(
        batch_id=batch,
        route="CLAUDE_CLI",
        model_id="claude-x",
        usage=snapshot("41", "12.5"),
        now=NOW,
    )
    kwargs = {
        "rid": rid,
        "batch_id": batch,
        "status": status,
        "meta": AttemptMeta("OK", None, Decimal("0.5"), 0),
        "tokens_in": 10,
        "tokens_out": 4,
        "latency_ms": 321,
        "now": NOW,
    }
    kwargs.update(over)
    ledger.complete(**kwargs)
    return rid


def lines(path):
    return [json.loads(x) for x in path.read_text().splitlines()]


def test_the_two_rows_of_a_spawn_hold_metadata_only_and_the_file_is_private(tmp_path):
    path = tmp_path / "s" / "calls.jsonl"
    ledger = BatchLedger(path)
    rid = spawn(ledger)
    reserve, done = lines(path)
    assert reserve == {
        "ev": "reserve",
        "rid": rid,
        "at": "2026-11-19T12:00:00+00:00",
        "batch": "b1",
        "route": "CLAUDE_CLI",
        "model": "claude-x",
        "weekly_pct": "41",
        "session_pct": "12.5",
        "usage_as_of": "2026-11-19T12:00:00+00:00",
    }
    assert done == {
        "ev": "done",
        "rid": rid,
        "at": "2026-11-19T12:00:00+00:00",
        "batch": "b1",
        "status": "OK",
        "pattern": None,
        "tokens_in": 10,
        "tokens_out": 4,
        "latency_ms": 321,
        "estimate_usd": "0.500000",
        "error_events": 0,
    }
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700


def test_a_reservation_is_on_disk_before_the_completion_exists(tmp_path):
    ledger = BatchLedger(tmp_path / "calls.jsonl")
    ledger.reserve(batch_id="b1", route="CLAUDE_CLI", model_id="m", usage=None)
    assert len(lines(tmp_path / "calls.jsonl")) == 1
    assert ledger.count("b1") == 1 and len(ledger.incomplete()) == 1


def test_the_ledger_is_fsynced_on_every_append(tmp_path, monkeypatch):
    synced = []
    real = os.fsync
    monkeypatch.setattr(os, "fsync", lambda fd: (synced.append(fd), real(fd))[1])
    BatchLedger(tmp_path / "calls.jsonl").reserve(
        batch_id="b1", route="CLAUDE_CLI", model_id="m", usage=None
    )
    assert len(synced) >= 2  # the file, and the directory entry of a new file


def test_a_short_write_is_completed_and_a_zero_byte_write_fails_closed(tmp_path, monkeypatch):
    path = tmp_path / "calls.jsonl"
    real = os.write
    monkeypatch.setattr(os, "write", lambda fd, data: real(fd, bytes(data[:7])))
    ledger = BatchLedger(path)
    ledger.reserve(batch_id="b1", route="CLAUDE_CLI", model_id="m", usage=None)
    assert lines(path)[0]["ev"] == "reserve"
    zero_writes = []

    def zero(fd, data):
        zero_writes.append(1)
        assert len(zero_writes) < 2, "a second zero-byte write: the writer is looping"
        return 0

    monkeypatch.setattr(os, "write", zero)
    with pytest.raises(LedgerError, match="LEDGER_WRITE"):
        ledger.reserve(batch_id="b1", route="CLAUDE_CLI", model_id="m", usage=None)


def test_rows_are_appended_never_rewritten_and_counted_per_batch(tmp_path):
    ledger = BatchLedger(tmp_path / "calls.jsonl")
    for batch in ("a", "a", "b"):
        spawn(ledger, batch)
    assert (ledger.count("a"), ledger.count("b"), ledger.count("c")) == (2, 1, 0)
    assert len(lines(tmp_path / "calls.jsonl")) == 6
    assert ledger.incomplete() == []


def test_reconcile_closes_every_unfinished_reservation_and_keeps_it_counted(tmp_path):
    ledger = BatchLedger(tmp_path / "calls.jsonl")
    ledger.reserve(batch_id="a", route="CLAUDE_CLI", model_id="m", usage=None)
    ledger.reserve(batch_id="b", route="CLAUDE_CLI", model_id="m", usage=None)
    spawn(ledger, "a")
    assert len(ledger.incomplete()) == 2
    assert ledger.reconcile() == 2
    assert ledger.incomplete() == [] and ledger.count("a") == 2 and ledger.count("b") == 1
    closed = [r for r in lines(tmp_path / "calls.jsonl") if r.get("status") == "RECONCILED"]
    # each completion keeps the batch id of its own reservation, not the batch that reconciled
    assert sorted(r["batch"] for r in closed) == ["a", "b"]


@pytest.mark.parametrize("bad", ["", "-x", "a b", "a/b", "../x", "x" * 65, "ü"])
def test_a_batch_id_is_a_short_safe_token(tmp_path, bad):
    ledger = BatchLedger(tmp_path / "calls.jsonl")
    with pytest.raises(ValueError):
        ledger.count(bad)
    with pytest.raises(ValueError):
        ledger.reserve(batch_id=bad, route="CLAUDE_CLI", model_id="m", usage=None)


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        (None, None),
        (Decimal("0"), "0.000000"),
        (Decimal("0.0000001"), "0.000001"),
        (Decimal("1000"), "1000.000000"),
        (Decimal("1000.000001"), None),
        (Decimal("1E+30"), None),
        (Decimal("-1"), None),
        (Decimal("NaN"), None),
        (Decimal("Infinity"), None),
        (0.5, None),
    ],
)
def test_the_dollar_estimate_is_bounded_or_dropped(value, expected):
    assert estimate_text(value) == expected


def test_pattern_ids_come_from_a_fixed_set_and_sensitive_text_never_gets_in(tmp_path):
    path = tmp_path / "calls.jsonl"
    ledger = BatchLedger(path)
    spawn(ledger, meta=AttemptMeta("ERROR", "unknown_output", None, 2))
    spawn(ledger, meta=AttemptMeta("ERROR", "Please log in as me@example.com", None, 0))
    done = [r for r in lines(path) if r["ev"] == "done"]
    assert done[0]["pattern"] == "unknown_output" and done[0]["error_events"] == 2
    assert done[1]["pattern"] == "unlisted"
    assert "example.com" not in path.read_text()


@pytest.mark.parametrize("status", ["me@example.com", "ok", "", None, 3, "RECONCILED2"])
def test_a_status_outside_the_fixed_set_is_not_written(tmp_path, status):
    ledger = BatchLedger(tmp_path / "calls.jsonl")
    rid = ledger.reserve(batch_id="b1", route="CLAUDE_CLI", model_id="m", usage=None)
    with pytest.raises(LedgerError, match="LEDGER_ROW"):
        ledger.complete(rid=rid, batch_id="b1", status=status)
    assert "example.com" not in (tmp_path / "calls.jsonl").read_text()


@pytest.mark.parametrize("events", ["secret text", -1, 10**6, 1.5, True])
def test_non_numeric_or_absurd_event_counts_are_not_written(tmp_path, events):
    ledger = BatchLedger(tmp_path / "calls.jsonl")
    rid = ledger.reserve(batch_id="b1", route="CLAUDE_CLI", model_id="m", usage=None)
    meta = AttemptMeta("ERROR", None, None, events)
    with pytest.raises(LedgerError):
        ledger.complete(rid=rid, batch_id="b1", status="ERROR", meta=meta)


@pytest.mark.parametrize("value", ["12", -1, 10**12, 1.5, True, None])
def test_token_and_latency_numbers_are_ints_in_range_or_null(tmp_path, value):
    path = tmp_path / "calls.jsonl"
    ledger = BatchLedger(path)
    spawn(ledger, tokens_in=value, tokens_out=value, latency_ms=value)
    done = lines(path)[-1]
    assert done["tokens_in"] is None and done["latency_ms"] is None


def test_a_route_or_model_outside_the_contract_is_not_reserved(tmp_path):
    ledger = BatchLedger(tmp_path / "calls.jsonl")
    with pytest.raises(LedgerError):
        ledger.reserve(batch_id="b1", route="OPENROUTER", model_id="m", usage=None)
    with pytest.raises(LedgerError):
        ledger.reserve(batch_id="b1", route="CLAUDE_CLI", model_id="--bare x", usage=None)


def test_a_completion_needs_a_real_reservation_id(tmp_path):
    with pytest.raises(LedgerError):
        BatchLedger(tmp_path / "calls.jsonl").complete(rid="x", batch_id="b1", status="OK")


def test_an_unreadable_row_makes_the_count_refuse_instead_of_guessing(tmp_path):
    path = tmp_path / "calls.jsonl"
    ledger = BatchLedger(path)
    spawn(ledger)
    with path.open("a") as handle:
        handle.write("not json\n")
    with pytest.raises(LedgerError, match="LEDGER_UNREADABLE"):
        ledger.count("b1")


def test_a_symlinked_loose_or_hardlinked_ledger_is_refused(tmp_path):
    real = tmp_path / "real.jsonl"
    real.write_text("")
    real.chmod(0o600)
    link = tmp_path / "link.jsonl"
    link.symlink_to(real)
    with pytest.raises(LedgerError, match="LEDGER_UNSAFE"):
        BatchLedger(link).count("b1")
    hard = tmp_path / "hard.jsonl"
    os.link(real, hard)
    with pytest.raises(LedgerError, match="LEDGER_UNSAFE"):
        BatchLedger(hard).count("b1")
    loose = tmp_path / "loose.jsonl"
    loose.write_text("")
    loose.chmod(0o644)
    with pytest.raises(LedgerError, match="LEDGER_UNSAFE"):
        BatchLedger(loose).count("b1")
    shared = tmp_path / "shared"
    shared.mkdir()
    shared.chmod(0o777)
    with pytest.raises(LedgerError, match="LEDGER_UNSAFE"):
        BatchLedger(shared / "calls.jsonl").count("b1")


def test_a_symlinked_ancestor_of_the_state_path_is_refused(tmp_path):
    real = tmp_path / "real"
    real.mkdir(mode=0o700)
    (tmp_path / "alias").symlink_to(real)
    with pytest.raises(LedgerError, match="LEDGER_UNSAFE"):
        BatchLedger(tmp_path / "alias" / "sub" / "calls.jsonl").count("b1")
    assert not (real / "sub").exists()


def test_a_group_or_world_writable_ancestor_is_refused_unless_sticky(tmp_path):
    loose = tmp_path / "loose"
    loose.mkdir()
    loose.chmod(0o777)
    with pytest.raises(LedgerError, match="LEDGER_UNSAFE"):
        BatchLedger(loose / "state" / "calls.jsonl").count("b1")
    loose.chmod(0o1777)
    BatchLedger(loose / "state" / "calls.jsonl").count("b1")


def test_the_lock_swapped_for_a_symlink_or_hardlink_is_refused(tmp_path):
    state = tmp_path / "s"
    state.mkdir(mode=0o700)
    real = tmp_path / "real.lock"
    real.write_text("")
    real.chmod(0o600)
    os.link(real, state / "batch.lock")
    with pytest.raises(SubscriptionRefusal, match="LEDGER_UNSAFE"):
        BatchLock(state / "batch.lock").acquire()


def test_the_lock_is_exclusive_private_and_released_on_close(tmp_path):
    path = tmp_path / "s" / "batch.lock"
    first, second = BatchLock(path), BatchLock(path)
    first.acquire()
    first.acquire()  # idempotent
    with pytest.raises(SubscriptionRefusal) as caught:
        second.acquire()
    assert caught.value.code == "BATCH_RUNNING"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    first.release()
    second.acquire()
    second.release()
    second.release()


def test_a_symlinked_lock_is_refused(tmp_path):
    real = tmp_path / "real.lock"
    real.write_text("")
    real.chmod(0o600)
    link = tmp_path / "batch.lock"
    link.symlink_to(real)
    with pytest.raises(SubscriptionRefusal) as caught:
        BatchLock(link).acquire()
    assert caught.value.code == "LEDGER_UNSAFE"


# ---- CLI discovery, version and login ----------------------------------------------------


def env_for(fakes):
    return {"PATH": str(fakes.dir), "HOME": "/home/x"}


def test_the_binary_is_resolved_once_to_an_absolute_path(fakes):
    assert resolve_binary("CLAUDE_CLI", env_for(fakes)) == fakes.path("claude")
    assert resolve_binary("CODEX_CLI", env_for(fakes)) == fakes.path("codex")


def test_a_binary_swapped_on_path_after_resolution_is_not_used(tmp_path, fakes):
    from gate_helpers import make_gate
    from sub_fakes import FakeClis

    from antlab_ai.modelport.subscription import ClaudeCliAdapter, CliConfig

    binary = resolve_binary("CLAUDE_CLI", env_for(fakes))
    decoy_dir = tmp_path / "decoy"
    decoy_dir.mkdir()
    decoy = FakeClis(decoy_dir)
    fakes.scenario("claude", stdout="{}")
    config = CliConfig(binary, "9.9.9", fakes.work_root, source_env={"PATH": str(decoy.dir)})
    ClaudeCliAdapter(config, make_gate(tmp_path)).attempt(request())
    assert len(fakes.calls()) == 1 and decoy.calls() == []


@pytest.mark.parametrize("route", ["CLAUDE_CLI", "CODEX_CLI"])
def test_a_missing_cli_refuses(tmp_path, route):
    with pytest.raises(SubscriptionRefusal) as caught:
        resolve_binary(route, {"PATH": str(tmp_path)})
    assert caught.value.code == "CLI_MISSING"


def test_a_non_executable_or_unknown_route_refuses(tmp_path):
    (tmp_path / "claude").write_text("x")
    for route, path in (("CLAUDE_CLI", str(tmp_path)), ("OPENROUTER", str(tmp_path))):
        with pytest.raises(SubscriptionRefusal):
            resolve_binary(route, {"PATH": path})
    assert not os.access(tmp_path / "claude", os.X_OK)


def test_the_version_is_the_first_version_looking_token(fakes):
    assert read_version(fakes.path("claude"), fakes.work_root, env_for(fakes), {}) == "9.9.9"


def stub(tmp_path, name, body):
    path = tmp_path / "stubs" / name
    path.parent.mkdir(exist_ok=True)
    path.write_text(f"#!{sys.executable}\n{body}\n")
    path.chmod(0o700)
    return str(path)


@pytest.mark.parametrize(
    "body",
    [
        "import sys; sys.exit(1)",
        "print('no version here')",
        "print('9.9.9'); import sys; sys.exit(3)",
    ],
)
def test_a_failing_or_unreadable_version_refuses(tmp_path, body):
    with pytest.raises(SubscriptionRefusal) as caught:
        read_version(stub(tmp_path, "claude", body), tmp_path / "w", {"PATH": "/usr/bin"}, {})
    assert caught.value.code == "CLI_MISSING"


def test_the_version_check_passes_only_allowlisted_names(tmp_path, fakes):
    fakes.scenario("claude", stdout=json.dumps({"loggedIn": True}))
    env = {**env_for(fakes), "ANTHROPIC_API_KEY": "x", "OPENROUTER_API_KEY": "y"}
    read_version(fakes.path("claude"), fakes.work_root, env, {})
    check_login("CLAUDE_CLI", fakes.path("claude"), fakes.work_root, env, {})
    assert all("API_KEY" not in " ".join(c["env"]) for c in fakes.calls())


def login(tmp_path, route, body):
    name = "claude" if route == "CLAUDE_CLI" else "codex"
    binary = stub(tmp_path, name, body)
    check_login(route, binary, tmp_path / "w", {"PATH": "/usr/bin"}, {})


SUBSCRIPTION_JSON = "import json; print(json.dumps({'loggedIn': True, 'authMethod': 'oauth'}))"


def test_a_subscription_login_passes_for_both_clis(tmp_path):
    login(tmp_path, "CLAUDE_CLI", SUBSCRIPTION_JSON)
    login(tmp_path, "CODEX_CLI", "print('Logged in using ChatGPT')")


@pytest.mark.parametrize(
    "body",
    [
        "import sys; sys.exit(1)",
        "print('{}')",
        "print('not json')",
        "import json; print(json.dumps({'loggedIn': False}))",
        "import json; print(json.dumps({'loggedIn': True, 'authMethod': 'api_key'}))",
        "import json; print(json.dumps({'loggedIn': True, 'apiKeySource': 'ANTHROPIC_API_KEY'}))",
        "import json; print(json.dumps({'loggedIn': 'true'}))",
    ],
)
def test_claude_login_that_is_missing_unreadable_or_an_api_key_refuses(tmp_path, body):
    with pytest.raises(SubscriptionRefusal) as caught:
        login(tmp_path, "CLAUDE_CLI", body)
    assert caught.value.code == "NOT_LOGGED_IN"


@pytest.mark.parametrize(
    "body",
    [
        "import sys; sys.exit(1)",
        "print('Not logged in')",
        "print('Logged in using an API key')",
        "print('')",
        "import sys; print('Logged in', file=sys.stderr); sys.exit(2)",
    ],
)
def test_codex_login_that_is_missing_or_an_api_key_refuses(tmp_path, body):
    with pytest.raises(SubscriptionRefusal) as caught:
        login(tmp_path, "CODEX_CLI", body)
    assert caught.value.code == "NOT_LOGGED_IN"


@pytest.mark.parametrize(
    "body",
    [
        "import json; print(json.dumps({'loggedIn': True}))",
        "import json; print(json.dumps({'loggedIn': True, 'authMethod': 5}))",
        "import json; print(json.dumps({'loggedIn': True, 'authMethod': None}))",
        "import json; print(json.dumps({'loggedIn': True, 'authMethod': 'unknown'}))",
        "import json; print(json.dumps({'loggedIn': True, 'authMethod': 'oauth', 'apiKeySource': 'env'}))",
        "import json; print(json.dumps({'loggedIn': True, 'authMethod': 'sso-proxy'}))",
    ],
)
def test_c3_claude_login_with_a_missing_numeric_or_unknown_method_refuses(tmp_path, body):
    with pytest.raises(SubscriptionRefusal) as caught:
        login(tmp_path, "CLAUDE_CLI", body)
    assert caught.value.code == "NOT_LOGGED_IN"


@pytest.mark.parametrize(
    "body",
    [
        "print('Logged in using unknown-provider')",
        "print('Logged in')",
        "print('Logged in using ChatGPT'); print('Logged in using an API key')",
        "print('Not logged in using ChatGPT')",
        "print('Logged in using ChatGPT via proxy')",
    ],
)
def test_c3_codex_login_that_is_not_the_recognised_chatgpt_shape_refuses(tmp_path, body):
    with pytest.raises(SubscriptionRefusal) as caught:
        login(tmp_path, "CODEX_CLI", body)
    assert caught.value.code == "NOT_LOGGED_IN"


@pytest.mark.parametrize("method", ["oauth", "OAuth", "claude.ai", "subscription"])
def test_c3_the_recognised_claude_methods_pass(tmp_path, method):
    login(
        tmp_path,
        "CLAUDE_CLI",
        f"import json; print(json.dumps({{'loggedIn': True, 'authMethod': {method!r}}}))",
    )


def test_n1_the_first_startup_flushes_the_file_and_every_new_directory_before_a_spawn(
    tmp_path, monkeypatch
):
    from gate_helpers import FakeUsage, make_gate

    events = []
    real = os.fsync

    def recorder(fd):
        events.append("dir" if stat.S_ISDIR(os.fstat(fd).st_mode) else "file")
        real(fd)

    monkeypatch.setattr(os, "fsync", recorder)
    gate = make_gate(tmp_path, usage=FakeUsage(), start=False)
    gate.begin(is_terminal=lambda: True)  # count: creates state/, subscription/ and calls.jsonl
    at_begin = list(events)
    # two new directories (their parents are flushed) and the new file with its directory entry
    assert at_begin.count("dir") >= 3 and "file" in at_begin
    gate.finish_start(ask=lambda p: gate.route, say=lambda t: None)
    gate.activate()
    gate.check_attempt(gate.route, gate.model_id)  # reserve
    assert events.count("file") > at_begin.count("file")


# ---- recovery reads only well-formed events (N2) -----------------------------------------


def write_rows(path, rows):
    path.write_text("".join(json.dumps(r) + "\n" for r in rows))
    path.chmod(0o600)


RID = "a" * 32
RESERVE = {"ev": "reserve", "rid": RID, "batch": "b1", "route": "CLAUDE_CLI"}
DONE = {"ev": "done", "rid": RID, "batch": "b1", "status": "OK"}


@pytest.mark.parametrize(
    "rows",
    [
        [{"at": "x", "batch": "b1", "route": "CLAUDE_CLI", "status": "OK"}],  # legacy row
        [{**RESERVE, "rid": None}],
        [{k: v for k, v in RESERVE.items() if k != "rid"}],
        [{**RESERVE, "rid": "short"}],
        [{**RESERVE, "ev": "other"}],
        [{**RESERVE, "route": "OPENROUTER"}],
        [{**RESERVE, "batch": "../x"}],
        [DONE],  # orphan completion
        [RESERVE, DONE, DONE],  # completion repeated
        [RESERVE, RESERVE],  # reservation repeated
        [DONE, RESERVE],  # completion before its reservation
        [RESERVE, {**DONE, "rid": "b" * 32}],
    ],
)
def test_n2_a_malformed_or_unrelated_event_is_refused_never_counted(tmp_path, rows):
    from gate_helpers import make_gate

    path = tmp_path / "state" / "subscription" / "calls.jsonl"
    path.parent.mkdir(parents=True, mode=0o700)
    write_rows(path, rows)
    ledger = BatchLedger(path)
    for call in (lambda: ledger.count("b1"), ledger.incomplete):
        with pytest.raises(LedgerError, match="LEDGER_SCHEMA"):
            call()
    gate = make_gate(tmp_path, start=False)
    with pytest.raises(SubscriptionRefusal) as caught:
        gate.begin(is_terminal=lambda: True)
    assert caught.value.code == "LEDGER_SCHEMA"


def test_n2_a_well_formed_history_is_accepted(tmp_path):
    path = tmp_path / "calls.jsonl"
    write_rows(path, [RESERVE, DONE, {**RESERVE, "rid": "c" * 32}])
    ledger = BatchLedger(path)
    assert ledger.count("b1") == 2 and ledger.incomplete() == ["c" * 32]


def test_r1_a_completion_naming_another_batch_than_its_reservation_is_refused(tmp_path):
    path = tmp_path / "calls.jsonl"
    write_rows(path, [RESERVE, {**DONE, "batch": "other"}])
    with pytest.raises(LedgerError, match="LEDGER_SCHEMA"):
        BatchLedger(path).count("b1")
