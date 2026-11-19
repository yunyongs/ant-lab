#!/usr/bin/env python3
"""HTTP/JSON checks for scripts/monitoring_smoke.sh. Standard library only.

Commands:
  wait-ready URL [TIMEOUT]
  wait-query PROM_URL EXPR OP VALUE TIMEOUT [ID]
  prometheus PROM_URL TIMEOUT
  grafana GRAFANA_URL DASHBOARD_DIR
  self-test DASHBOARD_DIR

Every check prints one line "PASS|FAIL <id> <detail>". Exit 0 = all passed, 1 = an assertion failed, 2 = usage.
The Grafana admin password comes from the environment variable SMOKE_GF_PASSWORD and is never printed.
"""
import base64
import glob
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UIDS = ("antlab-api", "antlab-jvm-db", "antlab-game", "antlab-collectors", "antlab-overview", "antlab-db", "antlab-engine")
FOLDER_UID = "antlab"
PG_DATASOURCE_UID = "antlab-postgres"
APPLICATION = "antlab-api"
GRAFANA_BUILTINS = {"__rate_interval", "__range", "__interval", "__timeFilter", "__timeGroupAlias"}
PG_DATASOURCE_TYPE = "grafana-postgresql-datasource"
HOME_UID = "antlab-overview"
TURNS_PLAYED = 12

# (dashboard uid, panel id): panels whose queries must return a value after one played season
MUST_HAVE_DATA = (
    ("antlab-api", 1),   # Request rate
    ("antlab-api", 6),   # Latency p50 / p95 / p99
    ("antlab-jvm-db", 3),   # Heap used by pool
    ("antlab-jvm-db", 10),  # Hikari connections
    ("antlab-game", 6),  # Turn advances by outcome
    # OBS-03: the smoke season has no collector run, series or daily price row, so only panels that answer anyway are listed.
    ("antlab-collectors", 1),  # Last run per job: "no run" rows
    ("antlab-overview", 1),  # API up
    ("antlab-overview", 4),  # JVM uptime
    ("antlab-overview", 5),  # Database size (pg_database_size as grafana_reader)
    ("antlab-overview", 6),  # Collector last runs: "no run" rows
    # OBS-06: every panel of the database dashboard answers once the exporter has scraped the played season.
    ("antlab-db", 1),   # Database reachable (pg_up)
    ("antlab-db", 2),   # Database size
    ("antlab-db", 3),   # Client connections
    ("antlab-db", 4),   # Deadlocks in range
    ("antlab-db", 5),   # Longest open transaction
    ("antlab-db", 6),   # Connections by role and state
    ("antlab-db", 7),   # Database size over time
    ("antlab-db", 8),   # Transactions per second
    ("antlab-db", 9),   # Deadlocks
    ("antlab-db", 10),  # Longest open transaction over time
    ("antlab-db", 11),  # Largest tables
    # OBS-05: the engine dashboard answers once the smoke traffic has been scraped. Panel 11 (regime evaluation duration) is left out:
    # the container has no RegimeSource yet, so evaluations return 503 and are not timed; the panel stays empty until it is wired.
    ("antlab-engine", 1),   # Engine up
    ("antlab-engine", 2),   # Requests per second
    ("antlab-engine", 3),   # Error ratio
    ("antlab-engine", 4),   # Projection slots in use
    ("antlab-engine", 5),   # Process memory
    ("antlab-engine", 6),   # Requests per second by route
    ("antlab-engine", 7),   # Error ratio over time
    ("antlab-engine", 8),   # p95 latency by route
    ("antlab-engine", 9),   # Projection slots
    ("antlab-engine", 10),  # Regime evaluations per second
    ("antlab-engine", 12),  # Process CPU
    ("antlab-engine", 13),  # Process memory over time
)

# BEGIN SMOKE QUERIES
Q_UP = 'up{job="antlab-api"}'
Q_TURNS = 'sum(antlab_turn_advance_total{outcome="committed"})'
Q_SEASONS = 'sum(antlab_season_start_total{outcome="committed"})'
Q_REJECTED = 'sum(antlab_order_rejected_total{code=~".+"})'
# END SMOKE QUERIES

# OBS-06: names from the exporter, checked against src/test/resources/monitoring/metric-names-db.txt (up is made by Prometheus).
# BEGIN SMOKE DB QUERIES
Q_DB_UP = 'up{job="antlab-db"}'
Q_PG_UP = 'pg_up{job="antlab-db"}'
# END SMOKE DB QUERIES

# OBS-05: names from the engine registry, checked against src/test/resources/monitoring/metric-names-engine.txt (up is made by Prometheus).
# BEGIN SMOKE ENGINE QUERIES
Q_ENGINE_UP = 'up{job="antlab-engine"}'
# END SMOKE ENGINE QUERIES

failed = False


def report(ok, check_id, detail=""):
    global failed
    failed = failed or not ok
    print(f"{'PASS' if ok else 'FAIL'} {check_id} {detail}".rstrip(), flush=True)
    return ok


def http(url, method="GET", body=None, auth=False, timeout=15):
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if auth:
        token = base64.b64encode(f"admin:{os.environ.get('SMOKE_GF_PASSWORD', '')}".encode()).decode()
        headers["Authorization"] = "Basic " + token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw) if raw else None
        except ValueError:
            return err.code, None
    except (urllib.error.URLError, OSError, ValueError):
        return 0, None


def compare(op, actual, expected):
    return {"==": actual == expected, ">=": actual >= expected, ">": actual > expected}[op]


def prom_value(prom_url, expr):
    status, body = http(prom_url.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": expr}))
    if status != 200 or not body or body.get("status") != "success":
        return None
    result = body["data"]["result"]
    return float(result[0]["value"][1]) if result else None


def wait_query(prom_url, expr, op, expected, timeout, check_id):
    deadline = time.monotonic() + timeout
    actual = None
    while True:
        actual = prom_value(prom_url, expr)
        if actual is not None and compare(op, actual, expected):
            return report(True, check_id, f"{expr} = {actual:g} ({op} {expected:g})")
        if time.monotonic() >= deadline:
            shown = "no data" if actual is None else f"{actual:g}"
            return report(False, check_id, f"{expr} = {shown}, wanted {op} {expected:g} within {timeout:g}s")
        time.sleep(3)


def wait_ready(url, timeout=60):
    deadline = time.monotonic() + timeout
    while True:
        status, _ = http(url)
        if status == 200:
            return report(True, "ready", url)
        if time.monotonic() >= deadline:
            return report(False, "ready", f"{url} answered {status} for {timeout:g}s")
        time.sleep(2)


def cmd_prometheus(prom_url, timeout):
    wait_query(prom_url, Q_UP, "==", 1, timeout, "A1")
    # up is 1 as soon as the exporter answers; pg_up is 1 only when it reached the database as antlab_metrics.
    wait_query(prom_url, Q_DB_UP, "==", 1, timeout, "A1b")
    wait_query(prom_url, Q_PG_UP, "==", 1, timeout, "A1c")
    # OBS-05: the engine metrics port (8001) answers to Prometheus on the internal network only.
    wait_query(prom_url, Q_ENGINE_UP, "==", 1, timeout, "A1d")
    wait_query(prom_url, Q_TURNS, "==", TURNS_PLAYED, timeout, "A2")
    ok_season = wait_query(prom_url, Q_SEASONS, "==", 1, timeout, "A3a")
    ok_reject = wait_query(prom_url, Q_REJECTED, ">=", 1, timeout, "A3b")
    return ok_season and ok_reject


def flatten(panels):
    out = []
    for panel in panels:
        out.append(panel)
        out.extend(flatten(panel.get("panels", [])))
    return out


def load_repo_dashboards(directory):
    dashboards = {}
    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        with open(path, encoding="utf-8") as handle:
            dash = json.load(handle)
        dashboards[dash["uid"]] = dash
    return dashboards


def substitute(expr):
    return expr.replace("${application}", APPLICATION).replace("$application", APPLICATION)


def leftover_variables(expr):
    names = re.findall(r"\$\{?(\w+)\}?", substitute(expr))
    return sorted(set(n for n in names if n not in GRAFANA_BUILTINS))


def is_sql(target):
    return "rawSql" in target


def ds_queries(panel):
    queries = []
    for target in panel.get("targets", []):
        if is_sql(target):
            queries.append({"refId": target["refId"], "datasource": target.get("datasource") or panel["datasource"],
                            "rawSql": target["rawSql"], "format": target.get("format", "table"), "rawQuery": True,
                            "editorMode": "code", "intervalMs": 15000, "maxDataPoints": 500})
            continue
        query = {
            "refId": target["refId"],
            "datasource": target.get("datasource") or panel["datasource"],
            "expr": substitute(target["expr"]),
            "instant": bool(target.get("instant", False)),
            "range": bool(target.get("range", not target.get("instant", False))),
            "intervalMs": 15000,
            "maxDataPoints": 500,
        }
        queries.append(query)
    return queries


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def frames_have_value(results, ref_ids, sql_refs=()):
    """Every target (refId) of the panel must come back with at least one frame holding a value.

    Prometheus frames are [time, value...]; some value column must hold a finite number (null, NaN and infinity are no data;
    label columns of table frames are ignored). A SQL frame may have one column of any type, so any non-null, non-NaN cell counts."""
    for ref in ref_ids:
        frames = results.get(ref, {}).get("frames", [])
        columns = [frame.get("data", {}).get("values", []) for frame in frames]
        if ref in sql_refs:
            if not any(cell is not None and not (isinstance(cell, float) and math.isnan(cell)) for values in columns for col in values for cell in col):
                return False
            continue
        if not any(len(values) >= 2 and any(finite_number(cell) for col in values[1:] for cell in col) for values in columns):
            return False
    return True


def run_panel(grafana_url, panel):
    body = {"queries": ds_queries(panel), "from": "now-15m", "to": "now"}
    status, resp = http(grafana_url.rstrip("/") + "/api/ds/query", "POST", body, auth=True, timeout=30)
    results = (resp or {}).get("results", {})
    clean = status == 200 and results and all(r.get("status") == 200 and not r.get("error") for r in results.values())
    return clean, results


def cmd_postgres_datasource(base):
    """OBS-02: the provisioned PostgreSQL data source reaches the database as grafana_reader and answers a query."""
    status, ds = http(f"{base}/api/datasources/uid/{PG_DATASOURCE_UID}", auth=True)
    ds = ds or {}
    json_data = ds.get("jsonData") or {}
    caps = (json_data.get("maxOpenConns"), json_data.get("maxIdleConns"), json_data.get("maxIdleConnsAuto"), json_data.get("sslmode"))
    report(status == 200 and ds.get("user") == "grafana_reader" and ds.get("readOnly") is True and caps == (8, 8, False, "disable"), "A9a",
           f"{PG_DATASOURCE_UID} provisioned read-only as grafana_reader with stored caps {caps} (answered {status})")
    status, health = http(f"{base}/api/datasources/uid/{PG_DATASOURCE_UID}/health", auth=True, timeout=30)
    report(status == 200 and (health or {}).get("status") == "OK", "A9b",
           f"{PG_DATASOURCE_UID} health {status} {(health or {}).get('status')}")
    query = {"refId": "A", "datasource": {"type": "grafana-postgresql-datasource", "uid": PG_DATASOURCE_UID},
             "rawSql": "SELECT current_user::text AS who, 1 AS one", "format": "table"}
    status, resp = http(f"{base}/api/ds/query", "POST", {"queries": [query], "from": "now-5m", "to": "now"}, auth=True, timeout=30)
    result = ((resp or {}).get("results") or {}).get("A") or {}
    frames = result.get("frames") or []
    values = frames[0]["data"]["values"] if frames else []
    report(status == 200 and not result.get("error") and values[:1] == [["grafana_reader"]], "A9c",
           f"SELECT through {PG_DATASOURCE_UID} runs as grafana_reader (answered {status})")


def cmd_home_dashboard(base):
    """OBS-03: the overview is the Grafana home page (default home dashboard path from the environment).

    Grafana answers either with the dashboard itself or with a redirect to it; both are accepted."""
    status, home = http(f"{base}/api/dashboards/home", auth=True)
    home = home if isinstance(home, dict) else {}
    uid = (home.get("dashboard") or {}).get("uid")
    redirect = home.get("redirectUri") or ""
    ok = status == 200 and (uid == HOME_UID or f"/d/{HOME_UID}" in redirect)
    report(ok, "A10", f"home dashboard uid {uid} redirect {redirect or '-'} keys {sorted(home)} (answered {status}), wanted {HOME_UID}")


def cmd_grafana(grafana_url, directory):
    base = grafana_url.rstrip("/")
    repo = load_repo_dashboards(directory)

    status, found = http(base + "/api/search?tag=antlab", auth=True)
    listed = {d["uid"]: d for d in found} if status == 200 and isinstance(found, list) else {}
    in_folder = all(d.get("folderUid") == FOLDER_UID for d in listed.values())
    report(set(listed) == set(UIDS) and len(listed) == len(UIDS) and in_folder, "A4",
           f"dashboards {sorted(listed)} folder {FOLDER_UID}")

    loaded = {}
    for uid in UIDS:
        status, body = http(f"{base}/api/dashboards/uid/{uid}", auth=True)
        if status != 200 or not body:
            report(False, "A5", f"{uid} answered {status}")
            continue
        loaded[uid] = body["dashboard"]
        want = len(flatten(repo[uid]["panels"])) if uid in repo else -1
        got = len(flatten(body["dashboard"].get("panels", [])))
        report(body["meta"].get("provisioned") is True and got == want, "A5", f"{uid} provisioned panels {got}/{want}")

    for uid, dash in loaded.items():
        for panel in flatten(dash.get("panels", [])):
            if not panel.get("targets"):
                continue
            ok, _ = run_panel(base, panel)
            report(ok, "A6", f"{uid}#{panel['id']} {panel.get('title', '')}")

    for uid, pid in MUST_HAVE_DATA:
        panel = next((p for p in flatten(loaded.get(uid, {}).get("panels", [])) if p.get("id") == pid), None)
        if panel is None:
            report(False, "A7", f"{uid}#{pid} missing")
            continue
        deadline = time.monotonic() + 120
        while True:
            ok, results = run_panel(base, panel)
            has_data = ok and frames_have_value(results, [t["refId"] for t in panel["targets"]],
                                                {t["refId"] for t in panel["targets"] if is_sql(t)})
            if has_data:
                break
            if time.monotonic() >= deadline:
                break
            time.sleep(5)
        report(has_data, "A7", f"{uid}#{pid} {panel.get('title', '')} has data")

    cmd_postgres_datasource(base)
    cmd_home_dashboard(base)

    status, health = http(base + "/api/health")
    report(status == 200 and (health or {}).get("database") == "ok", "A8a", "grafana health database ok")
    status, _ = http(base + "/api/search")
    report(status == 401, "A8b", f"anonymous /api/search answered {status}")


def cmd_self_test(directory):
    repo = load_repo_dashboards(directory)
    with open(os.path.join(directory, "..", "..", "datasources", "postgres.yaml"), encoding="utf-8") as handle:
        report(f"uid: {PG_DATASOURCE_UID}\n" in handle.read(), "S4", f"postgres data source uid {PG_DATASOURCE_UID}")
    report(set(repo) == set(UIDS), "S1", f"uids {sorted(repo)}")
    for uid, dash in repo.items():
        for panel in flatten(dash["panels"]):
            for target in panel.get("targets", []):
                left = leftover_variables(target["rawSql"] if is_sql(target) else target["expr"])
                report(not left, "S2", f"{uid}#{panel['id']} {target['refId']} leftover variables {left}")
    for uid, dash in repo.items():
        for panel in flatten(dash["panels"]):
            for target in panel.get("targets", []):
                ds = target.get("datasource") or {}
                ok = (ds.get("uid") == PG_DATASOURCE_UID and ds.get("type") == PG_DATASOURCE_TYPE) if is_sql(target) else ds.get("uid") == "antlab-prometheus"
                report(ok, "S5", f"{uid}#{panel['id']} {target['refId']} uses the data source of its query kind")
    for uid, pid in MUST_HAVE_DATA:
        ids = [p.get("id") for p in flatten(repo.get(uid, {}).get("panels", []))]
        report(pid in ids, "S3", f"{uid}#{pid} exists")


def main(argv):
    cmd, args = (argv[1], argv[2:]) if len(argv) > 1 else ("", [])
    try:
        if cmd == "wait-ready" and len(args) in (1, 2):
            wait_ready(args[0], float(args[1]) if len(args) == 2 else 60)
        elif cmd == "wait-query" and len(args) in (5, 6):
            if args[2] not in ("==", ">=", ">"):
                raise ValueError("operator")
            wait_query(args[0], args[1], args[2], float(args[3]), float(args[4]), args[5] if len(args) == 6 else "wait-query")
        elif cmd == "prometheus" and len(args) == 2:
            cmd_prometheus(args[0], float(args[1]))
        elif cmd == "grafana" and len(args) == 2:
            cmd_grafana(*args)
        elif cmd == "self-test" and len(args) == 1:
            cmd_self_test(args[0])
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except ValueError:
        print(__doc__, file=sys.stderr)
        return 2
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
