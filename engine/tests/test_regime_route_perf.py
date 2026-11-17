"""Timing of POST /v1/regime/evaluate over 29 years with a fake source (TB12); -m slow -s."""

import time
from datetime import date

import pytest
from fastapi.testclient import TestClient
from regime_synthetic import raw_panel

from antlab_engine.app import create_app
from antlab_engine.regime import RegimeInputs
from antlab_engine.settings import Settings

pytestmark = pytest.mark.slow

BUDGET_SECONDS = 8.0
HEADERS = {"X-Antlab-Engine-Token": "p" * 40}


class Source:
    def __init__(self, rows):
        self.rows = tuple(rows)

    def read(self, upper: date) -> RegimeInputs:
        return RegimeInputs(tuple(r for r in self.rows if r.ref_date <= upper), {})


def test_29_years_through_the_route_stay_inside_the_budget():
    rows = raw_panel(29, 2)
    app = create_app(Settings("p" * 40), regime_source=Source(rows))
    client = TestClient(app, raise_server_exceptions=False)
    months = [
        f"{y}-{m:02d}" for y in range(1997, 2026) for m in range(1, 13) if (y, m) <= (2025, 8)
    ]
    payload = {"labelVersion": "m2-rl-v1", "months": months}
    wall, cpu = time.perf_counter(), time.process_time()
    response = client.post("/v1/regime/evaluate", json=payload, headers=HEADERS)
    wall, cpu = time.perf_counter() - wall, time.process_time() - cpu
    print(  # noqa: T201
        f"\nregime route: {len(rows)} rows, {len(months)} months, {wall:.2f} s wall, "
        f"{cpu:.2f} s cpu, {len(response.content)} bytes"
    )
    assert response.status_code == 200
    result = response.json()
    assert len(result["months"]) == len(months)
    assert result["months"][-1]["status"] == "EVALUATED"
    assert cpu < BUDGET_SECONDS  # CPU time: a busy machine must not fail the check
