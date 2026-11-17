"""Timing of POST /v1/projections with a fake reader (no database); run with -m slow -s."""

import platform
import resource
import sys
import time
from decimal import Decimal

import numpy as np
import pytest
from fastapi.testclient import TestClient
from synthetic_panel import asset_names
from synthetic_snapshot import BASE_DATE, snapshot

from antlab_engine.api.projection_models import ProjectionRequest
from antlab_engine.api.projection_panel import panel_from_snapshot
from antlab_engine.api.projections import _portfolios, history_digest
from antlab_engine.app import create_app
from antlab_engine.projection import project
from antlab_engine.settings import Settings

pytestmark = pytest.mark.slow

ASSETS = 180
N = 2000
HEADERS = {"X-Antlab-Engine-Token": "p" * 40}


class Reader:
    def __init__(self, shot):
        self.shot = shot

    def read(self, base_date):
        return self.shot


def payload(assets: list[str]) -> dict:
    share = Decimal("0.005")
    team = {
        "key": "TEAM",
        "cash": f"{Decimal(1) - share * len(assets):f}",
        "weights": [{"asset": a, "weight": f"{share:f}"} for a in assets],
    }
    market = {"key": "MARKET", "cash": "0.1", "weights": [{"asset": assets[0], "weight": "0.9"}]}
    mixed = {
        "key": "MIXED",
        "cash": "0.2",
        "weights": [
            {"asset": assets[1], "weight": "0.5"},
            {"asset": assets[2], "weight": "0.3"},
        ],
    }
    return {
        "seasonSeed": "1",
        "baseDate": BASE_DATE.isoformat(),
        "horizonTurns": 120,
        "n": N,
        "riskLimit": "-0.20",
        "portfolios": [team, market, mixed],
    }


def test_the_route_for_2000_paths_120_turns_180_assets_and_three_portfolios():
    assets = asset_names(ASSETS)
    shot = snapshot(assets)
    body = payload(assets)
    client = TestClient(
        create_app(Settings("p" * 40), history_reader=Reader(shot)), raise_server_exceptions=False
    )

    request = ProjectionRequest.model_validate(body)
    started = time.perf_counter()
    panel = panel_from_snapshot(shot)
    adapted = time.perf_counter()
    books = _portfolios(request)
    result = project(
        panel,
        BASE_DATE,
        season_seed=1,
        horizon=120,
        portfolios=books,
        risk_limit=Decimal("-0.20"),
        n=N,
    )
    projected = time.perf_counter()
    used = sorted({a for p in books for a, _ in p.active})
    history_digest(panel, used, BASE_DATE)
    digested = time.perf_counter()
    print(
        f"\nroute stages: adapter {adapted - started:.3f} s, project {projected - adapted:.3f} s, "
        f"digest {digested - projected:.3f} s"
    )

    begin = time.perf_counter()
    response = client.post("/v1/projections", json=body, headers=HEADERS)
    total = time.perf_counter() - begin
    assert response.status_code == 200, response.text
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak_mb = peak / 1e6 if sys.platform == "darwin" else peak / 1e3
    print(
        f"M7 POST /v1/projections: {total:.3f} s ({len(response.content) / 1e3:.0f} kB); "
        f"process peak RSS {peak_mb:.0f} MB"
    )
    print(
        f"environment: python {platform.python_version()}, numpy {np.__version__}, "
        f"{platform.machine()}"
    )
    assert len(result.portfolios) == 3
    assert total < 4
