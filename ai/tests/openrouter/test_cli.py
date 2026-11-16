import sys

import pytest

from antlab_ai.openrouter import __main__ as cli
from antlab_ai.openrouter.ledger import Ledger

MODEL = "vendor/model:free"


def test_status_counts_group_by_caller_day_and_status(tmp_path, clock):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    ledger.outcome(ledger.reserve("product:ag01", MODEL), "OK")
    ledger.outcome(ledger.reserve("harness:ask_llm", MODEL), "LIMITED")
    ledger.reserve("harness:ask_llm", MODEL)
    assert cli.status_counts(ledger.rows()) == {
        "attempt harness:ask_llm 2026-01-01": 2,
        "attempt product:ag01 2026-01-01": 1,
        "outcome LIMITED": 1,
        "outcome OK": 1,
    }


def test_probe_without_live_is_refused(tmp_path, monkeypatch):
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", str(tmp_path / "x.jsonl"))
    monkeypatch.setattr(sys, "argv", ["prog", "probe"])
    with pytest.raises(SystemExit):
        cli.main()
    assert not (tmp_path / "x.jsonl").exists()


def test_status_prints_counts_only(tmp_path, monkeypatch, capsys, clock):
    path = tmp_path / "x.jsonl"
    Ledger(path, clock).reserve("harness:ask_llm", MODEL)
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", str(path))
    monkeypatch.setattr(sys, "argv", ["prog", "status"])
    cli.main()
    assert capsys.readouterr().out == "attempt harness:ask_llm 2026-01-01: 1\n"


def test_live_probe_counts_one_key_info_and_two_chats(tmp_path, monkeypatch, capsys, clock):
    path = tmp_path / "x.jsonl"
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", str(path))
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-v1-TESTSECRET")
    monkeypatch.setattr(sys, "argv", ["prog", "probe", "--live"])
    chat_body = {"choices": [{"message": {"content": "OK"}}], "model": MODEL}

    def chat(body, headers, timeout):
        return 200, chat_body, {}

    def key(headers, timeout):
        return 200, {"data": {"usage": 0}}, {}

    real = cli.OpenRouterClient
    monkeypatch.setattr(
        cli,
        "OpenRouterClient",
        lambda api_key, limiter, send_cap=None: real(
            api_key, limiter, chat, lambda s: None, lambda: 0.0, key, send_cap
        ),
    )
    cli.main()
    assert capsys.readouterr().out.split() == ["key_info", "OK", "OK", "OK"]
    rows = Ledger(path).rows()
    attempts = [r for r in rows if r["phase"] == "attempt"]
    assert [r["kind"] for r in attempts] == ["key_info", "chat", "chat"]


def run_probe(tmp_path, monkeypatch, capsys, argv, chat, key):
    path = tmp_path / "x.jsonl"
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", str(path))
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-v1-TESTSECRET")
    monkeypatch.setattr(sys, "argv", ["prog", "probe", "--live", *argv])
    real = cli.OpenRouterClient
    monkeypatch.setattr(
        cli,
        "OpenRouterClient",
        lambda api_key, limiter, send_cap=None: real(
            api_key, limiter, chat, lambda s: None, lambda: 0.0, key, send_cap
        ),
    )
    cli.main()
    return path, capsys.readouterr().out


@pytest.mark.parametrize(
    ("argv", "sends"),
    [([], 3), (["--max-requests", "3"], 3), (["--max-requests", "50"], 3)]
    + [(["--max-requests", "2"], 2), (["--max-requests", "1"], 1)]
    + [(["--max-requests", "0"], 0), (["--max-requests", "-4"], 0)],
)
def test_probe_never_sends_more_than_the_cap_even_when_every_call_fails(
    tmp_path, monkeypatch, capsys, argv, sends
):
    sent = []

    def chat(body, headers, timeout):
        sent.append("chat")
        return 503, {}, {}

    def key(headers, timeout):
        sent.append("key")
        return 503, {}, {}

    path, out = run_probe(tmp_path, monkeypatch, capsys, argv, chat, key)
    assert len(sent) == sends
    if sends:
        attempts = [r for r in Ledger(path).rows() if r["phase"] == "attempt"]
        assert len(attempts) == sends  # every send was reserved; nothing was sent unrecorded
    assert "send cap reached" in out or sends == 3


def test_probe_cap_covers_retries_of_a_single_chat(tmp_path, monkeypatch, capsys):
    sent = []

    def chat(body, headers, timeout):
        sent.append("chat")
        return 503, {}, {}

    def key(headers, timeout):
        sent.append("key")
        return 200, {"data": {"usage": 0}}, {}

    _, out = run_probe(tmp_path, monkeypatch, capsys, [], chat, key)
    assert sent == ["key", "chat", "chat"]  # the second chat's own retries cannot add sends
    assert out.split("\n")[:2] == ["key_info OK", "UPSTREAM"]
