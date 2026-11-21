"""Builders of the memory step tests: replies as a model would write them, and one season's harness."""

import json
from pathlib import Path

from api_helpers import settings as runner_settings
from fake_spring import FakeSpring

from antlab_ai.memory.reflect import MemoryStep
from antlab_ai.modelport.fake import scripted_attempt
from antlab_ai.runner.api_client import ApiClient
from antlab_ai.runner.config import RunnerConfig, ScriptedPort, default_timeout_ms
from antlab_ai.runner.journal import Journal
from antlab_ai.runner.persona import load_persona

MODEL = "vendor/model:free"
ROUTE = "OLLAMA"  # no per-call ledger id, so two runs write identical bodies
LEAK = "리먼 사태 같은 위기"
YEAR = "2008년 9월에는 줄였다"


def reflection(**changes) -> dict:
    doc = {
        "schema": "season-reflection/1",
        "good": [
            "Kept cash for the turns that needed it.",
            "Sold the largest position before the limit.",
            "Wrote a reason for every order.",
        ],
        "mistakes": [{"type": "OVERTRADE", "text": "Traded in every turn without need."}],
        "ruleCandidates": ["Trade only when the reason is written first."],
    }
    doc.update(changes)
    return doc


def note(rules=None, **changes) -> dict:
    doc = {
        "schema": "operating-note/1",
        "rules": ["Write the reason before the order.", "Keep cash for the next turn."]
        if rules is None
        else rules,
    }
    doc.update(changes)
    return doc


def say(doc) -> object:
    text = doc if isinstance(doc, str) else json.dumps(doc, ensure_ascii=False)
    return scripted_attempt(text)


def fail(status, **kwargs):
    return scripted_attempt(
        None, status=status, http_status=kwargs.pop("http_status", None), **kwargs
    )


class Sleeps:
    def __init__(self):
        self.delays: list[float] = []

    def __call__(self, seconds):
        self.delays.append(seconds)


class StepHarness:
    """One finished season (number 7, three turns) against a fake API and a scripted model."""

    def __init__(self, tmp_path: Path, *attempts, budget_calls=40, versions=0, **config):
        self.spring = FakeSpring(path_length=3, model=MODEL, route=ROUTE)
        self.spring.memory_seasons[7] = "pending"
        for number in range(1, versions + 1):
            self.spring.memory_versions.append(
                {
                    "rules": [f"Rule {number} stays."],
                    "reflection": reflection(),
                    "carried": False,
                    "source": 90 + number,
                }
            )
        self.state_dir = tmp_path / "state"
        self.sleeps = Sleeps()
        self.port = ScriptedPort(attempts, ROUTE)
        self.config = RunnerConfig(
            model_requested=MODEL,
            route=ROUTE,
            persona=load_persona("patient-value"),
            path_length=3,
            turn_timeout_ms=default_timeout_ms(ROUTE),
            budget_calls=budget_calls,
            **config,
        )

    def api(self) -> ApiClient:
        return ApiClient(
            runner_settings(self.state_dir), transport=self.spring, sleep=lambda s: None
        )

    def run(self, *, port=None, send_cap=None, discard_on_budget=False, season_id=7):
        """One runner process: open the journal, run the step, close. A ``Crash`` still closes it."""
        with Journal.open(self.state_dir, 1, season_id) as journal:
            step = MemoryStep(
                api=self.api(),
                journal=journal,
                port=port or self.port,
                config=self.config,
                agent_id=1,
                season_id=season_id,
                sleep=self.sleeps,
                jitter=lambda: 0.0,
                send_cap=send_cap,
                discard_on_budget=discard_on_budget,
            )
            self.last = step.run()
            self.memory_state = journal.state.memory
        return self.last

    def journal_text(self, season_id=7) -> str:
        return (self.state_dir / f"agent-1-season-{season_id}.jsonl").read_text(encoding="utf-8")

    @property
    def requests(self):
        return self.port.requests

    @property
    def submits(self):
        return self.spring.memory_submits
