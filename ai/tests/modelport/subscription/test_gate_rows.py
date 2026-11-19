"""One test per refusal row of the subscription gate; each also proves that no process was spawned."""

from pathlib import Path

import pytest
from gate_helpers import MODEL, FakeUsage, ledger_of, make_gate, start_gate
from sub_fakes import claude_envelope, request

from antlab_ai.modelport import SubscriptionRefusal
from antlab_ai.modelport.subscription import (
    ClaudeCliAdapter,
    LedgeredPort,
    OwnerProof,
    verify_owner,
)
from antlab_ai.modelport.subscription.gate import CONTAINER_ENV, FORBIDDEN_ENV

TERMINAL = lambda: True  # noqa: E731


def refusal_code(call) -> str:
    with pytest.raises(SubscriptionRefusal) as caught:
        call()
    return caught.value.code


def adapter(fakes, gate):
    return ClaudeCliAdapter(fakes.config("claude"), gate)


# ---- row 2: forbidden environment names --------------------------------------------------


@pytest.mark.parametrize("name", FORBIDDEN_ENV)
def test_row2_a_forbidden_name_refuses_the_start_and_every_attempt(tmp_path, fakes, name):
    gate = make_gate(tmp_path, environ={name: "x"}, start=False)
    assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == "API_KEY_SET"
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    assert fakes.calls() == []


@pytest.mark.parametrize("name", FORBIDDEN_ENV)
def test_row2_the_name_appearing_after_the_start_stops_the_next_attempt(tmp_path, fakes, name):
    env: dict[str, str] = {}
    gate = make_gate(tmp_path, environ=env)
    fakes.scenario("claude", stdout=claude_envelope())
    env[name] = "x"
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "API_KEY_SET"
    assert fakes.calls() == []


def test_row2_an_empty_value_is_not_set(tmp_path):
    make_gate(tmp_path, environ={"ANTHROPIC_API_KEY": ""}, start=False).begin(is_terminal=TERMINAL)


def test_row2_the_name_appearing_mid_batch_stops_the_next_attempt(tmp_path, fakes):
    env: dict[str, str] = {}
    gate = make_gate(tmp_path, environ=env)
    fakes.scenario("claude", stdout=claude_envelope())
    adapter(fakes, gate).attempt(request())
    env["ANTHROPIC_API_KEY"] = "x"
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "API_KEY_SET"
    assert len(fakes.calls()) == 1


# ---- row 3: container or CI --------------------------------------------------------------


@pytest.mark.parametrize("name", CONTAINER_ENV)
def test_row3_ci_and_container_names_refuse(tmp_path, fakes, name):
    gate = make_gate(tmp_path, environ={name: "true"}, start=False)
    assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == "NOT_OWNER_DEVICE"
    assert fakes.calls() == []


def test_row3_a_dockerenv_marker_refuses(tmp_path):
    marker = tmp_path / "dockerenv"
    marker.write_text("")
    gate = make_gate(tmp_path, markers=(marker,), start=False)
    assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == "NOT_OWNER_DEVICE"


# ---- row 4: owner-started ----------------------------------------------------------------


def test_row4_no_terminal_refuses(tmp_path):
    gate = make_gate(tmp_path, start=False)
    assert refusal_code(lambda: gate.begin(is_terminal=lambda: False)) == "NOT_OWNER_STARTED"


@pytest.mark.parametrize("typed", ["", "claude_cli", "CODEX_CLI", "yes", "CLAUDE_CLI x"])
def test_row4_the_route_name_must_be_typed_exactly(tmp_path, typed):
    gate = make_gate(tmp_path, start=False)
    gate.begin(is_terminal=TERMINAL)
    assert refusal_code(lambda: gate.finish_start(ask=lambda p: typed, say=lambda t: None)) == (
        "NOT_OWNER_STARTED"
    )


def test_row4_the_confirmation_shows_route_model_cap_and_usage_only(tmp_path):
    gate = make_gate(tmp_path, usage=FakeUsage("41.5", "12"), max_calls=7, start=False)
    gate.begin(is_terminal=TERMINAL)
    said: list[str] = []
    gate.finish_start(ask=lambda p: "CLAUDE_CLI\n", say=said.append)
    assert said == [
        f"route=CLAUDE_CLI model={MODEL} batch=batch-1 call_cap=7 used_so_far=0 "
        "weekly=41.5% session=12%"
    ]


# ---- row 5: owner of the agent -----------------------------------------------------------


class Api:
    def __init__(self, **over):
        self.agent = {
            "agentId": 11,
            "modelRoute": "CLAUDE_CLI",
            "modelId": MODEL,
            "ownerMemberId": 1,
        }
        self.agent.update(over)
        self.calls = []

    def register_agent(self, *args):
        self.calls.append(args)
        return self.agent, False


def verify(api, **over):
    kwargs = {
        "agent_id": 11,
        "persona_key": "patient-value",
        "display_name": "x",
        "route": "CLAUDE_CLI",
        "model_id": MODEL,
        "device_owner_id": 1,
    }
    kwargs.update(over)
    return verify_owner(api, **kwargs)


def test_row5_the_matching_owner_is_proven_through_the_idempotent_registration():
    api = Api()
    proof = verify(api)
    assert proof == OwnerProof(11, "CLAUDE_CLI", MODEL, 1)
    assert api.calls == [("patient-value", "CLAUDE_CLI", MODEL, "x")]


@pytest.mark.parametrize(
    "over",
    [
        {"ownerMemberId": 2},
        {"ownerMemberId": "1"},
        {"ownerMemberId": True},
        {"ownerMemberId": None},
        {"agentId": 12},
        {"modelRoute": "CODEX_CLI"},
        {"modelId": "other"},
    ],
)
def test_row5_any_disagreement_refuses(over):
    assert refusal_code(lambda: verify(Api(**over))) == "NOT_OWNER_AGENT"


def test_row5_a_device_setting_that_differs_from_the_servers_owner_refuses():
    assert refusal_code(lambda: verify(Api(), device_owner_id=2)) == "NOT_OWNER_AGENT"


def test_row5_a_gate_without_a_proof_refuses_every_attempt(tmp_path, fakes):
    gate = make_gate(tmp_path, owned=False)
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    fresh = make_gate(tmp_path, owned=False, batch_id="b2")
    fresh.begin(is_terminal=TERMINAL)
    assert refusal_code(lambda: fresh.finish_start(ask=lambda p: "", say=print)) == (
        "NOT_OWNER_AGENT"
    )
    assert fakes.calls() == []


def test_row5_a_proof_for_another_route_or_model_is_not_accepted(tmp_path, fakes):
    gate = make_gate(tmp_path, owned=False)
    assert refusal_code(lambda: gate.attach_owner(OwnerProof(11, "CODEX_CLI", MODEL, 1))) == (
        "NOT_OWNER_AGENT"
    )
    assert refusal_code(lambda: gate.attach_owner(OwnerProof(11, "CLAUDE_CLI", "other", 1))) == (
        "NOT_OWNER_AGENT"
    )


def test_row5_a_request_for_another_model_than_the_proof_covers_is_refused(tmp_path, fakes):
    gate = make_gate(tmp_path)
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request("claude-y"))) == (
        "NOT_OWNER_AGENT"
    )
    assert fakes.calls() == []


# ---- rows 8, 9, 10: usage guard ----------------------------------------------------------


@pytest.mark.parametrize(
    ("weekly", "allowed"), [("69.9", True), ("70", False), ("84.9", False), ("85", False)]
)
def test_row9_the_start_is_refused_from_seventy_percent_weekly(tmp_path, weekly, allowed):
    gate = make_gate(tmp_path, usage=FakeUsage(weekly), start=False)
    gate.begin(is_terminal=TERMINAL)

    def start():
        gate.finish_start(ask=lambda p: "CLAUDE_CLI", say=lambda t: None)

    if allowed:
        start()
    else:
        assert refusal_code(start) == "USAGE_START"


@pytest.mark.parametrize(
    ("weekly", "session", "allowed"),
    [
        ("84.9", "10", True),
        ("85", "10", False),
        ("10", "84.9", True),
        ("10", "85", False),
        ("100", "100", False),
        ("0", "0", True),
    ],
)
def test_row10_an_attempt_stops_at_eighty_five_percent_weekly_or_session(
    tmp_path, fakes, weekly, session, allowed
):
    gate = make_gate(tmp_path, usage=FakeUsage(weekly, session))
    fakes.scenario("claude", stdout=claude_envelope())
    if allowed:
        assert adapter(fakes, gate).attempt(request()).status == "OK"
    else:
        assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "USAGE_STOP"
        assert fakes.calls() == []


def test_row10_a_session_at_the_stop_line_also_refuses_the_start(tmp_path):
    gate = make_gate(tmp_path, usage=FakeUsage("10", "85"), start=False)
    gate.begin(is_terminal=TERMINAL)
    assert refusal_code(lambda: gate.finish_start(ask=lambda p: "CLAUDE_CLI", say=print)) == (
        "USAGE_STOP"
    )


def test_row8_a_missing_reading_refuses_the_start_and_every_attempt(tmp_path, fakes):
    usage = FakeUsage()
    usage.current = None
    gate = make_gate(tmp_path, usage=usage, start=False)
    gate.begin(is_terminal=TERMINAL)
    assert refusal_code(lambda: gate.finish_start(ask=lambda p: "CLAUDE_CLI", say=print)) == (
        "USAGE_UNKNOWN"
    )
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    assert fakes.calls() == []


def test_row8_a_meter_that_fails_after_the_start_refuses_the_attempt(tmp_path, fakes):
    usage = FakeUsage()
    gate = make_gate(tmp_path, usage=usage)
    usage.current = None
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "USAGE_UNKNOWN"
    assert fakes.calls() == []


def test_row10_the_meter_is_read_before_every_attempt(tmp_path, fakes):
    usage = FakeUsage()
    gate = make_gate(tmp_path, usage=usage)
    fakes.scenario("claude", stdout=claude_envelope())
    for _ in range(3):
        adapter(fakes, gate).attempt(request())
    assert usage.reads == 3
    usage.set(weekly="90")
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "USAGE_STOP"
    assert len(fakes.calls()) == 3


# ---- row 11: call cap --------------------------------------------------------------------


def test_row11_the_cap_counts_failed_processes_and_stops_the_next_spawn(tmp_path, fakes):
    gate = make_gate(tmp_path, max_calls=2)
    fakes.scenario("claude", stdout="not json", exit=1)
    port = LedgeredPort(adapter(fakes, gate), gate, ledger := ledger_of(tmp_path))
    assert port.attempt(request()).status == "ERROR"
    assert port.attempt(request()).status == "ERROR"
    assert gate.calls_left() == 0
    assert refusal_code(lambda: port.attempt(request())) == "CALL_CAP"
    assert len(fakes.calls()) == 2
    assert ledger.count("batch-1") == 2


def test_row11_a_timed_out_process_counts_too(tmp_path, fakes):
    gate = make_gate(tmp_path, max_calls=1)
    fakes.scenario("claude", sleep=30)
    port = LedgeredPort(adapter(fakes, gate), gate, ledger_of(tmp_path))
    assert port.attempt(request(timeout_ms=1000)).status == "TIMEOUT"
    assert refusal_code(lambda: port.attempt(request())) == "CALL_CAP"


def test_row11_the_cap_survives_a_restart_inside_the_same_batch_id(tmp_path, fakes):
    fakes.scenario("claude", stdout=claude_envelope())
    first = make_gate(tmp_path, max_calls=3)
    port = LedgeredPort(adapter(fakes, first), first, ledger_of(tmp_path))
    port.attempt(request())
    port.attempt(request())
    resumed = make_gate(tmp_path, max_calls=3)
    assert resumed.calls == 2 and resumed.calls_left() == 1
    port = LedgeredPort(adapter(fakes, resumed), resumed, ledger_of(tmp_path))
    port.attempt(request())
    assert refusal_code(lambda: port.attempt(request())) == "CALL_CAP"
    assert len(fakes.calls()) == 3


def test_row11_a_new_batch_id_starts_a_fresh_count(tmp_path, fakes):
    fakes.scenario("claude", stdout=claude_envelope())
    first = make_gate(tmp_path, max_calls=1)
    LedgeredPort(adapter(fakes, first), first, ledger_of(tmp_path)).attempt(request())
    other = make_gate(tmp_path, max_calls=1, batch_id="batch-2")
    assert other.calls == 0


def test_row11_a_failed_ledger_write_ends_the_batch_after_returning_the_reply(tmp_path, fakes):
    from antlab_ai.modelport.subscription import BatchLedger

    fakes.scenario("claude", stdout=claude_envelope("KEPT"))
    gate = make_gate(tmp_path)
    broken = BatchLedger(tmp_path / "blocked" / "calls.jsonl")
    (tmp_path / "blocked").write_text("a file where a directory belongs")
    port = LedgeredPort(adapter(fakes, gate), gate, broken)
    assert port.attempt(request()).text == "KEPT"
    assert refusal_code(lambda: port.attempt(request())) == "LEDGER_UNSAFE"
    assert len(fakes.calls()) == 1


def test_a_popen_failure_becomes_a_fixed_refusal_not_a_crash(tmp_path, fakes):
    gate = make_gate(tmp_path)
    config = fakes.config("claude")
    (fakes.dir / "claude").unlink()
    port = LedgeredPort(ClaudeCliAdapter(config, gate), gate, ledger_of(tmp_path))
    assert refusal_code(lambda: port.attempt(request())) == "CLI_MISSING"


# ---- row 12: batch lock ------------------------------------------------------------------


def test_row12_a_second_batch_is_refused_until_the_first_ends(tmp_path):
    first = make_gate(tmp_path, lock=True)
    first.begin(is_terminal=TERMINAL)
    second = make_gate(tmp_path, lock=True, batch_id="batch-2")
    assert refusal_code(lambda: second.begin(is_terminal=TERMINAL)) == "BATCH_RUNNING"
    first.close()
    third = make_gate(tmp_path, lock=True, batch_id="batch-3")
    third.begin(is_terminal=TERMINAL)
    third.close()


def test_row12_a_refusal_before_the_lock_leaves_it_free(tmp_path):
    first = make_gate(tmp_path, lock=True, environ={"CI": "1"})
    assert refusal_code(lambda: first.begin(is_terminal=TERMINAL)) == "NOT_OWNER_DEVICE"
    make_gate(tmp_path, lock=True, batch_id="batch-2").begin(is_terminal=TERMINAL)


@pytest.mark.parametrize("change", ["env", "stop", "unknown", "model"])
def test_a_refused_attempt_is_not_counted(tmp_path, fakes, change):
    usage = FakeUsage()
    env: dict[str, str] = {}
    gate = make_gate(tmp_path, usage=usage, environ=env, start=True)
    if change == "env":
        env["ANTHROPIC_API_KEY"] = "x"
    elif change == "stop":
        usage.set(weekly="90")
    elif change == "unknown":
        usage.current = None
    req = request("claude-y") if change == "model" else request()
    assert refusal_code(lambda: adapter(fakes, gate).attempt(req)) in {
        "API_KEY_SET", "NOT_OWNER_AGENT", "USAGE_STOP", "USAGE_UNKNOWN",
    }  # fmt: skip
    assert gate.calls == 0 and gate.calls_left() == 15
    assert ledger_of(tmp_path).count("batch-1") == 0


def test_the_call_is_counted_before_the_spawn_and_after_the_usage_read(tmp_path, fakes):
    seen = []
    usage = FakeUsage()
    gate = make_gate(tmp_path, usage=usage)
    original = usage.read

    def read(route):
        seen.append(gate.calls)
        return original(route)

    usage.read = read
    fakes.scenario("claude", stdout=claude_envelope())
    adapter(fakes, gate).attempt(request())
    assert seen == [0] and gate.calls == 1


# ---- session invariant (C2) --------------------------------------------------------------


def test_an_attempt_before_the_start_is_not_admitted_even_with_a_proof(tmp_path, fakes):
    gate = make_gate(tmp_path, start=False)
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    gate.begin(is_terminal=TERMINAL)
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    gate.finish_start(ask=lambda p: "CLAUDE_CLI", say=lambda t: None)
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    assert fakes.calls() == []
    gate.activate()
    fakes.scenario("claude", stdout=claude_envelope())
    assert adapter(fakes, gate).attempt(request()).status == "OK"


@pytest.mark.parametrize(
    ("kwargs", "begin_code"),
    [
        ({"environ": {"CI": "1"}}, "NOT_OWNER_DEVICE"),
        ({"usage": FakeUsage("75")}, None),
    ],
)
def test_a_startup_refusal_ends_the_session_for_good(tmp_path, fakes, kwargs, begin_code):
    gate = make_gate(tmp_path, start=False, **kwargs)
    if begin_code:
        assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == begin_code
    else:
        gate.begin(is_terminal=TERMINAL)
        assert refusal_code(lambda: gate.finish_start(ask=lambda p: "CLAUDE_CLI", say=print)) == (
            "USAGE_START"
        )
    assert refusal_code(lambda: gate.activate()) == "BATCH_NOT_ACTIVE"
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == "BATCH_NOT_ACTIVE"
    assert fakes.calls() == []


def test_a_closed_gate_admits_nothing(tmp_path, fakes):
    gate = make_gate(tmp_path)
    gate.close()
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"
    assert fakes.calls() == []


def test_a_cli_diagnostic_refusal_invalidates_the_session(tmp_path, fakes):
    gate = make_gate(tmp_path)
    gate.invalidate()
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"


def test_a_lock_that_is_no_longer_held_stops_the_attempts(tmp_path, fakes):
    gate = make_gate(tmp_path, lock=True, start=False)
    start_gate(gate)
    gate._lock.release()  # noqa: SLF001 - the lock lost behind the gate's back
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "BATCH_NOT_ACTIVE"


def test_a_gate_cannot_be_built_without_a_lock_except_for_tests(tmp_path):
    from antlab_ai.modelport.subscription import BatchGate

    with pytest.raises(ValueError, match="lock"):
        BatchGate(
            route="CLAUDE_CLI", model_id=MODEL, batch_id="b", max_calls=1,
            usage=FakeUsage(), ledger=ledger_of(tmp_path),
        )  # fmt: skip


# ---- crash recovery (C1) -----------------------------------------------------------------


def test_the_reservation_is_on_disk_before_the_process_is_spawned(tmp_path, fakes):
    seen = {}
    gate = make_gate(tmp_path)
    cli = adapter(fakes, gate)
    original = fakes.config("claude")
    import antlab_ai.modelport.subscription.claude_cli as module

    real_run = module.run_cli

    def spy(*args, **kwargs):
        seen["rows"] = ledger_of(tmp_path)._rows()  # noqa: SLF001
        return real_run(*args, **kwargs)

    module.run_cli = spy
    try:
        fakes.scenario("claude", stdout=claude_envelope())
        cli.attempt(request())
    finally:
        module.run_cli = real_run
    assert [r["ev"] for r in seen["rows"]] == ["reserve"] and original


def test_a_crash_after_the_spawn_still_counts_and_blocks_the_restart(tmp_path, fakes):
    gate = make_gate(tmp_path, max_calls=1)
    fakes.scenario("claude", stdout=claude_envelope())

    class Crash(BaseException):
        pass

    class CrashingAdapter:
        last_meta = None

        def attempt(self, req):
            gate.check_attempt("CLAUDE_CLI", req.model_id)
            raise Crash

    with pytest.raises(Crash):
        LedgeredPort(CrashingAdapter(), gate, ledger_of(tmp_path)).attempt(request())
    assert ledger_of(tmp_path).count("batch-1") == 1
    assert len(ledger_of(tmp_path).incomplete()) == 1
    # a restart does not get the allowance back, and refuses until the owner reconciles
    again = make_gate(tmp_path, max_calls=3, start=False)
    assert refusal_code(lambda: again.begin(is_terminal=TERMINAL)) == "RECONCILE_REQUIRED"
    assert fakes.calls() == []
    reconciled = make_gate(tmp_path, max_calls=3, start=False, batch_id="batch-1")
    reconciled.begin(is_terminal=TERMINAL, reconcile=True)
    assert reconciled.calls == 1 and ledger_of(tmp_path).incomplete() == []


def test_a_pre_spawn_error_closes_its_reservation_as_aborted_and_still_counts(tmp_path, fakes):
    gate = make_gate(tmp_path, max_calls=2)
    bad_root = ClaudeCliAdapter(fakes.config("claude"), gate)
    bad_root._config = type("C", (), {"work_root": Path(__file__).parent})()  # noqa: SLF001
    port = LedgeredPort(bad_root, gate, ledger_of(tmp_path))
    with pytest.raises(Exception):  # noqa: B017,PT011
        port.attempt(request())
    assert ledger_of(tmp_path).incomplete() == []
    assert ledger_of(tmp_path).count("batch-1") == 1


def test_a_reservation_that_cannot_be_written_refuses_before_any_spawn(tmp_path, fakes):
    from antlab_ai.modelport.subscription import BatchLedger

    gate = make_gate(tmp_path)
    gate._ledger = BatchLedger(tmp_path / "blocked" / "calls.jsonl")  # noqa: SLF001
    (tmp_path / "blocked").write_text("a file where a directory belongs")
    assert refusal_code(lambda: adapter(fakes, gate).attempt(request())) == "LEDGER_UNSAFE"
    assert fakes.calls() == [] and gate.calls == 0


# ---- start order (C4) --------------------------------------------------------------------


def test_the_usage_and_environment_are_checked_again_after_the_confirmation(tmp_path):
    usage = FakeUsage()
    env: dict[str, str] = {}
    gate = make_gate(tmp_path, usage=usage, environ=env, start=False)
    gate.begin(is_terminal=TERMINAL)

    def answer(prompt):
        usage.set(weekly="75")
        return "CLAUDE_CLI"

    assert refusal_code(lambda: gate.finish_start(ask=answer, say=lambda t: None)) == "USAGE_START"
    gate = make_gate(tmp_path, usage=FakeUsage(), environ=env, start=False, batch_id="b2")
    gate.begin(is_terminal=TERMINAL)

    def poison(prompt):
        env["OPENAI_API_KEY"] = "x"
        return "CLAUDE_CLI"

    assert refusal_code(lambda: gate.finish_start(ask=poison, say=lambda t: None)) == "API_KEY_SET"


def test_an_exhausted_cap_refuses_the_start_itself(tmp_path):
    first = make_gate(tmp_path, max_calls=1)
    first.check_attempt("CLAUDE_CLI", MODEL)
    ledger_of(tmp_path).complete(rid=first.take_reservation(), batch_id="batch-1", status="OK")
    again = make_gate(tmp_path, max_calls=1, start=False)
    assert refusal_code(lambda: again.begin(is_terminal=TERMINAL)) == "CALL_CAP"


@pytest.mark.parametrize(
    "env",
    [
        {"OPENROUTER_API_KEY": "x"},
        {"https_proxy": "http://proxy.example:3128"},
        {"ALL_PROXY": "socks5://proxy.example"},
        {"GEMINI_API_KEY": "x"},
        {"AWS_BEARER_TOKEN_BEDROCK": "x"},
    ],
)
def test_c7_provider_keys_and_proxies_refuse_the_start(tmp_path, env):
    gate = make_gate(tmp_path, environ=env, start=False)
    assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == "API_KEY_SET"


@pytest.mark.parametrize(
    "env",
    [
        {"TF_BUILD": "True"},
        {"container": "podman"},
        {"GITLAB_CI": "true"},
        {"JENKINS_URL": "http://j"},
    ],
)
def test_c7_other_ci_and_container_indicators_refuse_the_start(tmp_path, env):
    gate = make_gate(tmp_path, environ=env, start=False)
    assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == "NOT_OWNER_DEVICE"


def test_c7_the_second_container_marker_file_refuses_too(tmp_path):
    marker = tmp_path / "run" / ".containerenv"
    marker.parent.mkdir()
    marker.write_text("")
    gate = make_gate(tmp_path, markers=(tmp_path / "none", marker), start=False)
    assert refusal_code(lambda: gate.begin(is_terminal=TERMINAL)) == "NOT_OWNER_DEVICE"
