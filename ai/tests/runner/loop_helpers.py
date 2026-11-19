"""Builders shared by the loop, restart and CLI tests."""

import json
from pathlib import Path

from api_helpers import settings as runner_settings
from fake_spring import FakeSpring
from runner_helpers import reply

from antlab_ai.modelport.fake import scripted_attempt
from antlab_ai.runner.api_client import ApiClient
from antlab_ai.runner.config import RunnerConfig, ScriptedPort, default_timeout_ms
from antlab_ai.runner.journal import Journal
from antlab_ai.runner.loop import SeasonRunner
from antlab_ai.runner.persona import load_persona

MODEL = "vendor/model:free"


def ok(text=None, **kwargs):
    return scripted_attempt(reply() if text is None else text, **kwargs)


def bad(**kwargs):
    """An OK reply that fails the shape check."""
    return scripted_attempt('{"schema": "turn-decision/1"}', **kwargs)


def fail(status, **kwargs):
    return scripted_attempt(
        None, status=status, http_status=kwargs.pop("http_status", None), **kwargs
    )


class Sleeps:
    def __init__(self):
        self.delays: list[float] = []

    def __call__(self, seconds):
        self.delays.append(seconds)


class Harness:
    """One season against a fake API, a scripted model and a journal in ``tmp_path``."""

    def __init__(self, tmp_path: Path, *attempts, path_length=1, route="OPENROUTER", **config):
        self.spring = FakeSpring(path_length=path_length, model=MODEL, route=route)
        self.route = route
        self.path_length = path_length
        self.sleeps = Sleeps()
        self.state_dir = tmp_path / "state"
        self.port = ScriptedPort(attempts, route)
        config.setdefault("turn_timeout_ms", default_timeout_ms(route))
        config.setdefault("budget_calls", 500)
        self.config = RunnerConfig(
            model_requested=MODEL,
            route=route,
            persona=load_persona("patient-value"),
            path_length=path_length,
            **config,
        )

    def api(self) -> ApiClient:
        return ApiClient(
            runner_settings(self.state_dir), transport=self.spring, sleep=lambda s: None
        )

    def run(self, *, resume=False, port=None, send_cap=None, config=None, gate=None):
        """One runner process: open the journal, run, close. A ``Crash`` still closes the journal."""
        with Journal.open(self.state_dir, 1, 7) as journal:
            runner = SeasonRunner(
                api=self.api(),
                journal=journal,
                port=port or self.port,
                config=config or self.config,
                agent_id=1,
                season_id=7,
                sleep=self.sleeps,
                jitter=lambda: 0.0,
                send_cap=send_cap,
                gate=gate,
            )
            self.last_state = runner.run(resume=resume)
            self.journal_state = journal.state
        return self.last_state

    def journal_rows(self) -> list[dict]:
        path = self.state_dir / "agent-1-season-7.jsonl"
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
