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


class SqlPanels(unittest.TestCase):
    """OBS-03: SQL targets in the panel runner, the data check and the home dashboard check."""

    def setUp(self):
        checks.failed = False

    def test_sql_target_is_sent_as_raw_sql_with_its_format(self):
        panel = {"datasource": {"type": "grafana-postgresql-datasource", "uid": UID},
                 "targets": [{"refId": "A", "rawSql": "SELECT 1 AS one", "format": "table"}]}
        query = checks.ds_queries(panel)[0]
        self.assertEqual((query["rawSql"], query["format"], query["datasource"]["uid"]), ("SELECT 1 AS one", "table", UID))
        self.assertNotIn("expr", query)

    def test_single_column_sql_frame_counts_as_data(self):
        results = {"A": {"frames": [{"data": {"values": [[1234]]}}]}}
        self.assertTrue(checks.frames_have_value(results, ["A"], {"A"}))
        self.assertFalse(checks.frames_have_value(results, ["A"]))

    def test_all_null_or_empty_sql_frame_is_no_data(self):
        self.assertFalse(checks.frames_have_value({"A": {"frames": [{"data": {"values": [[None]]}}]}}, ["A"], {"A"}))
        self.assertFalse(checks.frames_have_value({"A": {"frames": [{"data": {"values": [[]]}}]}}, ["A"], {"A"}))
        self.assertFalse(checks.frames_have_value({}, ["A"], {"A"}))

    def test_macros_are_not_leftover_variables(self):
        sql = "SELECT $__timeGroupAlias(started_at, '1d'), 1 FROM obs.job_run WHERE $__timeFilter(started_at)"
        self.assertEqual(checks.leftover_variables(sql), [])
        self.assertEqual(checks.leftover_variables("SELECT $unknown"), ["unknown"])

    def home_check(self, status, uid):
        out = io.StringIO()
        with mock.patch.object(checks, "http", lambda *a, **k: (status, {"dashboard": {"uid": uid}})), contextlib.redirect_stdout(out):
            checks.cmd_home_dashboard("http://grafana")
        return out.getvalue()

    def test_home_dashboard_must_be_the_overview(self):
        self.assertIn("PASS A10", self.home_check(200, "antlab-overview"))
        self.assertFalse(checks.failed)
        self.assertIn("FAIL A10", self.home_check(200, "antlab-api"))
        self.assertTrue(checks.failed)
        checks.failed = False
        self.assertIn("FAIL A10", self.home_check(404, None))
        self.assertTrue(checks.failed)

    def test_home_dashboard_redirect_to_the_overview_passes(self):
        out = io.StringIO()
        with mock.patch.object(checks, "http", lambda *a, **k: (200, {"redirectUri": "/d/antlab-overview/ant-lab-overview"})), contextlib.redirect_stdout(out):
            checks.cmd_home_dashboard("http://grafana")
        self.assertIn("PASS A10", out.getvalue())
        out = io.StringIO()
        with mock.patch.object(checks, "http", lambda *a, **k: (200, {"redirectUri": "/d/antlab-api/x"})), contextlib.redirect_stdout(out):
            checks.cmd_home_dashboard("http://grafana")
        self.assertIn("FAIL A10", out.getvalue())

    def test_every_must_have_panel_exists_in_the_repo_dashboards(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            checks.cmd_self_test("monitoring/grafana/provisioning/dashboards/antlab")
        self.assertFalse(checks.failed, out.getvalue())
        self.assertIn("PASS S3 antlab-overview#5 exists", out.getvalue())
        self.assertIn("PASS S5 antlab-collectors#1 A", out.getvalue())


if __name__ == "__main__":
    unittest.main()
