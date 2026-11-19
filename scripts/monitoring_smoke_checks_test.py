#!/usr/bin/env python3
"""Mocked-HTTP tests of the PostgreSQL data source checks in monitoring_smoke_checks.py (OBS-02). Standard library only, no Docker."""
import contextlib
import io
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import monitoring_smoke_checks as checks  # noqa: E402

UID = checks.PG_DATASOURCE_UID
GOOD_QUERY = (200, {"results": {"A": {"frames": [{"data": {"values": [["grafana_reader"], [1]]}}]}}})


STORED = {"user": "grafana_reader", "readOnly": True,
          "jsonData": {"maxOpenConns": 8, "maxIdleConns": 8, "maxIdleConnsAuto": False, "sslmode": "disable"}}


def fake_http(ds=(200, STORED), health=(200, {"status": "OK"}), query=GOOD_QUERY):
    def http(url, method="GET", body=None, auth=False, timeout=15):
        if url.endswith("/health"):
            return health
        if url.endswith(f"/api/datasources/uid/{UID}"):
            return ds
        if url.endswith("/api/ds/query"):
            return query
        return 404, None
    return http


class PostgresDataSource(unittest.TestCase):
    def setUp(self):
        checks.failed = False

    def run_check(self, **kwargs):
        out = io.StringIO()
        with mock.patch.object(checks, "http", fake_http(**kwargs)), contextlib.redirect_stdout(out):
            checks.cmd_postgres_datasource("http://grafana")
        return out.getvalue()

    def test_healthy_source_passes(self):
        out = self.run_check()
        self.assertFalse(checks.failed, out)
        self.assertEqual(out.count("PASS A9"), 3)

    def test_unhealthy_source_fails(self):
        out = self.run_check(health=(200, {"status": "ERROR"}))
        self.assertTrue(checks.failed)
        self.assertIn("FAIL A9b", out)

    def test_wrong_user_fails(self):
        out = self.run_check(ds=(200, {**STORED, "user": "antlab_app"}))
        self.assertTrue(checks.failed)
        self.assertIn("FAIL A9a", out)

    def test_editable_source_fails(self):
        self.run_check(ds=(200, {**STORED, "readOnly": False}))
        self.assertTrue(checks.failed)

    def test_stored_caps_must_match(self):
        for key, value in (("maxOpenConns", 100), ("maxIdleConns", 100), ("maxIdleConnsAuto", True), ("sslmode", "require")):
            checks.failed = False
            out = self.run_check(ds=(200, {**STORED, "jsonData": {**STORED["jsonData"], key: value}}))
            self.assertTrue(checks.failed, key)
            self.assertIn("FAIL A9a", out)

    def test_query_as_another_user_fails(self):
        out = self.run_check(query=(200, {"results": {"A": {"frames": [{"data": {"values": [["antlab_app"], [1]]}}]}}}))
        self.assertTrue(checks.failed)
        self.assertIn("FAIL A9c", out)

    def test_missing_source_fails_without_crashing(self):
        out = self.run_check(ds=(404, None), health=(404, None), query=(404, None))
        self.assertTrue(checks.failed)
        self.assertIn("FAIL A9a", out)

    def test_grafana_command_runs_the_data_source_check(self):
        with mock.patch.object(checks, "http", lambda *a, **k: (404, None)), \
                mock.patch.object(checks, "cmd_postgres_datasource") as datasource, \
                mock.patch.object(checks.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            checks.cmd_grafana("http://grafana", "monitoring/grafana/provisioning/dashboards/antlab")
        datasource.assert_called_once_with("http://grafana")


if __name__ == "__main__":
    unittest.main()
