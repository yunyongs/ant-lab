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
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UIDS = ("antlab-api", "antlab-jvm-db", "antlab-game")
FOLDER_UID = "antlab"
APPLICATION = "antlab-api"
GRAFANA_BUILTINS = {"__rate_interval", "__range", "__interval"}
TURNS_PLAYED = 12

# (dashboard uid, panel id): panels whose queries must return a value after one played season
MUST_HAVE_DATA = (
    ("antlab-api", 1),   # Request rate
    ("antlab-api", 6),   # Latency p50 / p95 / p99
    ("antlab-jvm-db", 3),   # Heap used by pool
    ("antlab-jvm-db", 10),  # Hikari connections
    ("antlab-game", 6),  # Turn advances by outcome
)

# BEGIN SMOKE QUERIES
Q_UP = 'up{job="antlab-api"}'
Q_TURNS = 'sum(antlab_turn_advance_total{outcome="committed"})'
Q_SEASONS = 'sum(antlab_season_start_total{outcome="committed"})'
Q_REJECTED = 'sum(antlab_order_rejected_total{code=~".+"})'
# END SMOKE QUERIES

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


def ds_queries(panel):
    queries = []
    for target in panel.get("targets", []):
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


def frames_have_value(results, ref_ids):
    """Every target (refId) of the panel must come back with at least one frame holding a value."""
    for ref in ref_ids:
        frames = results.get(ref, {}).get("frames", [])
        if not any(len(frame.get("data", {}).get("values", [])) >= 2
                   and any(len(col) > 0 for col in frame["data"]["values"][1:]) for frame in frames):
            return False
    return True


def run_panel(grafana_url, panel):
    body = {"queries": ds_queries(panel), "from": "now-15m", "to": "now"}
    status, resp = http(grafana_url.rstrip("/") + "/api/ds/query", "POST", body, auth=True, timeout=30)
    results = (resp or {}).get("results", {})
    clean = status == 200 and results and all(r.get("status") == 200 and not r.get("error") for r in results.values())
    return clean, results


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
            has_data = ok and frames_have_value(results, [t["refId"] for t in panel["targets"]])
            if has_data:
                break
            if time.monotonic() >= deadline:
                break
            time.sleep(5)
        report(has_data, "A7", f"{uid}#{pid} {panel.get('title', '')} has data")

    status, health = http(base + "/api/health")
    report(status == 200 and (health or {}).get("database") == "ok", "A8a", "grafana health database ok")
    status, _ = http(base + "/api/search")
    report(status == 401, "A8b", f"anonymous /api/search answered {status}")


def cmd_self_test(directory):
    repo = load_repo_dashboards(directory)
    report(set(repo) == set(UIDS), "S1", f"uids {sorted(repo)}")
    for uid, dash in repo.items():
        for panel in flatten(dash["panels"]):
            for target in panel.get("targets", []):
                left = leftover_variables(target["expr"])
                report(not left, "S2", f"{uid}#{panel['id']} {target['refId']} leftover variables {left}")
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
