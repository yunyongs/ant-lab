#!/usr/bin/env python3
"""Mocked-HTTP tests of the PostgreSQL data source and database target checks in monitoring_smoke_checks.py (OBS-02, OBS-06). Standard library only, no Docker."""
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
        self.assertIn("PASS S3 antlab-db#11 exists", out.getvalue())
        self.assertIn("PASS S5 antlab-db#11 A", out.getvalue())
        self.assertIn("PASS S3 antlab-engine#13 exists", out.getvalue())
        self.assertIn("PASS S5 antlab-engine#13 A", out.getvalue())
        self.assertNotIn("antlab-engine#11 exists", out.getvalue())


class NumericFrames(unittest.TestCase):
    """OBS-06: a Prometheus panel has data only when a value column holds a finite number."""

    def frames(self, *columns):
        return {"A": {"frames": [{"data": {"values": [list(c) for c in columns]}}]}}

    def test_finite_number_counts(self):
        self.assertTrue(checks.frames_have_value(self.frames([1], [0]), ["A"]))
        self.assertTrue(checks.frames_have_value(self.frames([1], [2.5]), ["A"]))

    def test_null_only_value_column_is_no_data(self):
        self.assertFalse(checks.frames_have_value(self.frames([1], [None]), ["A"]))
        self.assertFalse(checks.frames_have_value(self.frames([1, 2], [None, None]), ["A"]))

    def test_nan_and_infinity_are_no_data(self):
        self.assertFalse(checks.frames_have_value(self.frames([1], [float("nan")]), ["A"]))
        self.assertFalse(checks.frames_have_value(self.frames([1], [float("inf")]), ["A"]))

    def test_label_strings_and_booleans_are_not_numbers(self):
        self.assertFalse(checks.frames_have_value(self.frames([1], ["public"], [None]), ["A"]))
        self.assertFalse(checks.frames_have_value(self.frames([1], [True]), ["A"]))
        self.assertTrue(checks.frames_have_value(self.frames([1], ["public"], [4096]), ["A"]))

    def test_every_target_needs_its_own_number(self):
        ok = {"data": {"values": [[1], [3]]}}
        bad = {"data": {"values": [[1], [None]]}}
        self.assertFalse(checks.frames_have_value({"A": {"frames": [ok]}, "B": {"frames": [bad]}}, ["A", "B"]))

    def test_sql_frame_with_nan_only_is_no_data(self):
        self.assertFalse(checks.frames_have_value(self.frames([float("nan")]), ["A"], {"A"}))


class DatabaseTarget(unittest.TestCase):
    """OBS-06: the Prometheus step waits for the exporter target and for pg_up."""

    def setUp(self):
        checks.failed = False

    def test_prometheus_command_checks_target_up_and_pg_up_of_the_db_job(self):
        calls = []
        with mock.patch.object(checks, "wait_query", lambda prom, expr, op, expected, timeout, check_id: calls.append((expr, op, expected, check_id)) or True):
            checks.cmd_prometheus("http://prom", 5)
        self.assertIn(('up{job="antlab-db"}', "==", 1, "A1b"), calls)
        self.assertIn(('pg_up{job="antlab-db"}', "==", 1, "A1c"), calls)

    def test_prometheus_command_checks_the_engine_target(self):
        calls = []
        with mock.patch.object(checks, "wait_query", lambda prom, expr, op, expected, timeout, check_id: calls.append((expr, op, expected, check_id)) or True):
            checks.cmd_prometheus("http://prom", 5)
        self.assertIn(('up{job="antlab-engine"}', "==", 1, "A1d"), calls)

    def test_exporter_up_but_database_unreachable_fails(self):
        def prom(url, *args, **kwargs):
            expr = urllib_query(url)
            value = {"up{job=\"antlab-db\"}": "1", "pg_up{job=\"antlab-db\"}": "0"}.get(expr)
            return (200, {"status": "success", "data": {"result": [{"value": [0, value]}] if value else []}})
        out = io.StringIO()
        with mock.patch.object(checks, "http", prom), mock.patch.object(checks.time, "sleep"), \
                mock.patch.object(checks.time, "monotonic", side_effect=[0, 0, 0, 100, 100, 100, 100, 100]), contextlib.redirect_stdout(out):
            checks.wait_query("http://prom", checks.Q_DB_UP, "==", 1, 5, "A1b")
            checks.wait_query("http://prom", checks.Q_PG_UP, "==", 1, 5, "A1c")
        self.assertIn("PASS A1b", out.getvalue())
        self.assertIn("FAIL A1c", out.getvalue())
        self.assertTrue(checks.failed)


def urllib_query(url):
    import urllib.parse
    return urllib.parse.parse_qs(urllib.parse.urlparse(url).query)["query"][0]



def rules_body(states):
    return {"data": {"groups": [{"rules": [{"uid": uid, "state": st, "health": h, "lastEvaluation": "2026-11-20T01:00:00Z"} for uid, (st, h) in states.items()]}]}}


class AlertRules(unittest.TestCase):
    """OBS-10: the provisioned rules are evaluated without error, read-only, and nothing can send."""

    def setUp(self):
        checks.failed = False

    def fake(self, states, provenance="file", contacts=()):
        def http(url, method="GET", body=None, auth=False, timeout=15):
            if url.endswith(checks.ALERT_RULES_API):
                return 200, rules_body(states)
            if url.endswith("/alert-rules"):
                return 200, [{"uid": u, "provenance": provenance} for u in checks.ALERT_UIDS]
            if url.endswith("/contact-points"):
                return 200, [{"name": n} for n in contacts]
            return 404, None
        return http

    def run_rules(self, **kwargs):
        states = kwargs.pop("states", {u: ("inactive", "ok") for u in checks.ALERT_UIDS})
        out = io.StringIO()
        with mock.patch.object(checks, "http", self.fake(states, **kwargs)), mock.patch.object(checks.time, "sleep"), \
                mock.patch.object(checks.time, "monotonic", side_effect=[0] + [100] * 40), contextlib.redirect_stdout(out):
            checks.cmd_alert_rules("http://grafana", 5)
        return out.getvalue()

    def test_healthy_provisioned_rules_pass(self):
        self.assertNotIn("FAIL", self.run_rules())
        self.assertFalse(checks.failed)

    def test_a_rule_with_an_evaluation_error_fails(self):
        states = {u: ("inactive", "ok") for u in checks.ALERT_UIDS}
        states["antlab-alert-collector-stale"] = ("inactive", "error")
        self.assertIn("FAIL AL1 antlab-alert-collector-stale", self.run_rules(states=states))

    def test_a_rule_that_never_ran_fails(self):
        def http(url, method="GET", body=None, auth=False, timeout=15):
            if url.endswith(checks.ALERT_RULES_API):
                return 200, {"data": {"groups": [{"rules": [{"uid": u, "state": "inactive", "health": "ok", "lastEvaluation": "0001-01-01T00:00:00Z"} for u in checks.ALERT_UIDS]}]}}
            return 200, []
        out = io.StringIO()
        with mock.patch.object(checks, "http", http), mock.patch.object(checks.time, "sleep"), \
                mock.patch.object(checks.time, "monotonic", side_effect=[0] + [100] * 40), contextlib.redirect_stdout(out):
            checks.cmd_alert_rules("http://grafana", 5)
        self.assertIn("FAIL AL1 antlab-alert-api-down health not evaluated yet", out.getvalue())

    def test_a_missing_rule_fails(self):
        states = {u: ("inactive", "ok") for u in checks.ALERT_UIDS[1:]}
        self.assertIn("FAIL AL1 antlab-alert-api-down health missing", self.run_rules(states=states))

    def test_rules_edited_in_the_ui_fail(self):
        self.assertIn("FAIL AL2", self.run_rules(provenance=""))

    def test_any_contact_point_fails(self):
        self.assertIn("FAIL AL3", self.run_rules(contacts=("ops-webhook",)))

    def test_state_wait_passes_when_the_rule_fires(self):
        out = io.StringIO()
        states = {"antlab-alert-engine-down": ("firing", "ok")}
        with mock.patch.object(checks, "http", self.fake(states)), contextlib.redirect_stdout(out):
            self.assertTrue(checks.cmd_alert_state("http://grafana", "antlab-alert-engine-down", "firing", 5))

    def test_state_wait_fails_when_the_rule_never_fires(self):
        out = io.StringIO()
        states = {"antlab-alert-engine-down": ("pending", "ok")}
        with mock.patch.object(checks, "http", self.fake(states)), mock.patch.object(checks.time, "sleep"), \
                mock.patch.object(checks.time, "monotonic", side_effect=[0, 0, 100, 100]), contextlib.redirect_stdout(out):
            self.assertFalse(checks.cmd_alert_state("http://grafana", "antlab-alert-engine-down", "firing", 5))
        self.assertIn("is pending, wanted firing", out.getvalue())


if __name__ == "__main__":
    unittest.main()
