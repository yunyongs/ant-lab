#!/usr/bin/env bash
# Monitoring smoke (MON-05): throwaway compose project with db, api, prometheus, grafana and postgres-exporter; plays one season,
# checks Prometheus (api and database targets) and the Grafana dashboards, then removes the project. Manual only (needs Docker and the sample CSVs).
# Exit codes: 0 pass, 1 assertion failed, 2 usage or preflight, 130 interrupted.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF'
Usage: scripts/monitoring_smoke.sh [--keep] [--dry-run] [-h|--help]
  --keep     leave the stack up after a pass (needs GF_SECURITY_ADMIN_PASSWORD in the environment)
  --dry-run  validate and print the resolved configuration; calls neither docker nor psql
Environment: ANTLAB_SMOKE_PROJECT (default antlab-smoke), ANTLAB_SMOKE_{DB,API,PROMETHEUS,GRAFANA,ENGINE}_PORT,
             ANTLAB_GOLDEN_SAMPLE_DIR (default db/sample; needs asset_month_return.csv, asset_position.csv, fx_month.csv)
EOF
}

die() { printf 'monitoring smoke: %s\n' "$1" >&2; exit 2; }

keep=0
dry_run=0
for arg in "$@"; do
  case "$arg" in
    --keep) keep=1 ;;
    --dry-run) dry_run=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

project="${ANTLAB_SMOKE_PROJECT:-antlab-smoke}"
db_port="${ANTLAB_SMOKE_DB_PORT:-55472}"
api_port="${ANTLAB_SMOKE_API_PORT:-18094}"
prometheus_port="${ANTLAB_SMOKE_PROMETHEUS_PORT:-19104}"
grafana_port="${ANTLAB_SMOKE_GRAFANA_PORT:-13014}"
engine_port="${ANTLAB_SMOKE_ENGINE_PORT:-18004}"
sample_dir="${ANTLAB_GOLDEN_SAMPLE_DIR:-$PWD/db/sample}"
dashboard_dir=monitoring/grafana/provisioning/dashboards/antlab

[[ "$project" =~ ^antlab-smoke(-[a-z0-9]+)?$ ]] || die "ANTLAB_SMOKE_PROJECT must match ^antlab-smoke(-[a-z0-9]+)?\$ (got '$project')"
check_port() { # name value default-of-the-owner-stack
  [[ "$2" =~ ^[0-9]+$ && "$2" -ge 1024 && "$2" -le 65535 ]] || die "$1 must be a port number from 1024 to 65535 (got '$2')"
  [ "$2" != "$3" ] || die "$1 must not be $3 (the owner stack's port)"
}
check_port ANTLAB_SMOKE_DB_PORT "$db_port" 5432
check_port ANTLAB_SMOKE_API_PORT "$api_port" 8080
check_port ANTLAB_SMOKE_PROMETHEUS_PORT "$prometheus_port" 9090
check_port ANTLAB_SMOKE_GRAFANA_PORT "$grafana_port" 3000
check_port ANTLAB_SMOKE_ENGINE_PORT "$engine_port" 8000
if [ "$keep" = 1 ] && [ -z "${GF_SECURITY_ADMIN_PASSWORD:-}" ]; then
  die "--keep needs GF_SECURITY_ADMIN_PASSWORD in the environment so you can sign in to Grafana afterwards"
fi

if [ "$dry_run" = 1 ]; then
  printf 'project=%s db_port=%s api_port=%s prometheus_port=%s grafana_port=%s engine_port=%s files=compose.yaml profiles=api,monitoring sample_dir=%s\n' \
    "$project" "$db_port" "$api_port" "$prometheus_port" "$grafana_port" "$engine_port" "$sample_dir"
  exit 0
fi

work=""
env_file=""
started=0
cleaned=0
checks=0
traffic=""

cleanup() {
  local rc=$?
  set +e
  if [ "$cleaned" = 0 ]; then
    cleaned=1
    [ -n "$traffic" ] && kill "$traffic" 2>/dev/null
    if [ "$started" = 1 ]; then
      if [ "$keep" = 1 ] && [ "$rc" = 0 ]; then
        printf 'stack left running: Grafana http://127.0.0.1:%s (user admin), Prometheus http://127.0.0.1:%s\n' "$grafana_port" "$prometheus_port"
        printf 'remove it with: docker %s -p %s -f compose.yaml --profile api --profile monitoring down -v --rmi local\n' compose "$project"
      else
        dc down -v --remove-orphans --rmi local >/dev/null 2>&1 || true
      fi
    fi
    [ -n "$work" ] && rm -rf "$work" || true
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

umask 077
work="$(mktemp -d)"
env_file="$work/compose.env"
compose_cmd=(docker compose -p "$project" --env-file "$env_file" -f compose.yaml)
dc() { "${compose_cmd[@]}" "$@"; }

gf_password="${GF_SECURITY_ADMIN_PASSWORD:-}"
[ -n "$gf_password" ] || gf_password="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
jwt_key="$(python3 -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
printf 'ANTLAB_DB_PORT=%s\n' "$db_port" > "$env_file"
printf 'ANTLAB_API_PORT=%s\n' "$api_port" >> "$env_file"
printf 'ANTLAB_PROMETHEUS_PORT=%s\n' "$prometheus_port" >> "$env_file"
printf 'ANTLAB_GRAFANA_PORT=%s\n' "$grafana_port" >> "$env_file"
printf 'ANTLAB_ENGINE_PORT=%s\n' "$engine_port" >> "$env_file"
printf 'ANTLAB_ENGINE_TOKEN=%s\n' "$(python3 -c 'import secrets; print(secrets.token_hex(32))')" >> "$env_file"
printf 'GF_SECURITY_ADMIN_PASSWORD=%s\n' "$gf_password" >> "$env_file"
printf 'ANTLAB_JWT_SECRET=%s\n' "$jwt_key" >> "$env_file"
printf 'COMPOSE_PROFILES=api,monitoring\n' >> "$env_file"

# Compose gives the process environment precedence over --env-file. An exported ANTLAB_ENGINE_PORT=8000 or a real token from the
# owner's shell would silently win, so every variable that Compose interpolates is dropped; only the env file above supplies them.
for name in $(compgen -e); do
  case "$name" in
    ANTLAB_SMOKE_*|ANTLAB_GOLDEN_SAMPLE_DIR) ;;
    ANTLAB_*|GF_*|GRAFANA_*|COMPOSE_*) unset "$name" ;;
  esac
done

dc version >/dev/null 2>&1 || die "Docker Compose is not available"
command -v psql >/dev/null 2>&1 || die "psql is not installed (version 16 or later is needed for the sample load)"
psql_major="$(psql --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
[ "$psql_major" -ge 16 ] 2>/dev/null || die "psql 16 or later is needed (found '$(psql --version)')"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"
for csv in asset_month_return.csv asset_position.csv fx_month.csv; do
  [ -f "$sample_dir/$csv" ] || die "$sample_dir/$csv is missing (set ANTLAB_GOLDEN_SAMPLE_DIR)"
done
for entry in "ANTLAB_SMOKE_DB_PORT:$db_port" "ANTLAB_SMOKE_API_PORT:$api_port" \
             "ANTLAB_SMOKE_PROMETHEUS_PORT:$prometheus_port" "ANTLAB_SMOKE_GRAFANA_PORT:$grafana_port" \
             "ANTLAB_SMOKE_ENGINE_PORT:$engine_port"; do
  # SO_REUSEADDR: sockets in TIME_WAIT from a previous run must not count as "in use" (Docker binds the same way)
  python3 -c 'import socket, sys; s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", int(sys.argv[1])))' "${entry#*:}" 2>/dev/null \
    || die "${entry%%:*}=${entry#*:} is already in use"
done

mkdir "$work/stage"
cp -R db events "$work/stage/"
for csv in asset_month_return.csv asset_position.csv fx_month.csv; do
  cp "$sample_dir/$csv" "$work/stage/db/sample/$csv"
done

fail() {
  printf '\nmonitoring smoke: FAIL - %s\n' "$1" >&2
  dc ps >&2 || true
  dc logs --tail 60 api prometheus grafana postgres-exporter >&2 || true
  for log in play.log load.log reader.log metrics.log; do
    if [ -f "$work/$log" ]; then printf -- '--- tail of %s\n' "$log" >&2; tail -n 30 "$work/$log" >&2 || true; fi
  done
  exit 1
}

helper() { python3 scripts/monitoring_smoke_checks.py "$@" | tee -a "$work/checks.log"; }
prom_url="http://127.0.0.1:$prometheus_port"
grafana_url="http://127.0.0.1:$grafana_port"
api_url="http://127.0.0.1:$api_port"

started=1
dc down -v --remove-orphans >/dev/null 2>&1 || true

echo "== start db and api (first build takes minutes)"
dc up -d --build --wait --wait-timeout 300 db api || fail "db and api did not become healthy"

echo "== check the built image"
image_id="$(dc images -q api | head -n 1)"
[ -n "$image_id" ] || fail "could not find the api image"
image_env="$(docker image inspect --format '{{json .Config.Env}}' "$image_id")" || fail "docker image inspect failed"
# The key is a run-time value only; the image must not carry it (the value is never printed).
case "$image_env" in *ANTLAB_JWT_SECRET*) fail "the api image carries the signing key variable in its Env" ;; esac
printf 'PASS image env carries no signing key\n' | tee -a "$work/checks.log"

echo "== create the Grafana reader role (OBS-02)"
# Flyway has run (the api started once); the script refuses to run before the obs views exist. Trust auth: no password is set.
{ echo "SET antlab.expected_database = 'antlab_api';"; cat db/roles/grafana_reader.sql; } \
  | PGHOST=127.0.0.1 PGPORT="$db_port" PGUSER="${USER:-antlab}" PGDATABASE=antlab_api psql -X -q -v ON_ERROR_STOP=1 > "$work/reader.log" 2>&1 \
  || fail "grafana_reader role script failed"
printf 'PASS grafana_reader role script ran\n' | tee -a "$work/checks.log"

echo "== create the statistics role for postgres_exporter (OBS-06)"
# Needs no table, so it could run earlier; it runs here next to the reader script. Trust auth: no password is set.
{ echo "SET antlab.expected_database = 'antlab_api';"; cat db/roles/antlab_metrics.sql; } \
  | PGHOST=127.0.0.1 PGPORT="$db_port" PGUSER="${USER:-antlab}" PGDATABASE=antlab_api psql -X -q -v ON_ERROR_STOP=1 > "$work/metrics.log" 2>&1 \
  || fail "antlab_metrics role script failed"
printf 'PASS antlab_metrics role script ran\n' | tee -a "$work/checks.log"

echo "== load sample"
dc stop api || fail "could not stop api"
(
  cd "$work/stage"
  export PGHOST=127.0.0.1 PGPORT="$db_port" PGUSER="${USER:-antlab}" PGDATABASE=antlab_api
  psql -X -q -v ON_ERROR_STOP=1 -c "ALTER DATABASE antlab_api SET timezone TO 'Asia/Seoul'" || exit 1
  for f in db/10_load_sample.sql db/11_load_events.sql db/12_calc_position.sql db/13_calc_attention.sql; do
    psql -X -q -v ON_ERROR_STOP=1 -f "$f" || exit 1
  done
) > "$work/load.log" 2>&1 || fail "sample load failed"

echo "== start api, prometheus and grafana"
dc up -d --wait --wait-timeout 300 || fail "stack did not become healthy"
helper wait-query "$prom_url" 'up{job="antlab-api"}' == 1 90 up-first || fail "Prometheus never saw the api target up"

echo "== play one season"
# The api container runs the prod profile: every season call needs a token.
python3 tools/play/play_season.py --base "$api_url" --turns 12 > "$work/play.log" 2>&1 || fail "play script failed"
# The play script signed the member up; log in again with its constants (the token is never printed).
token="$(PYTHONPATH=tools/play python3 -c 'import sys, play_season as p; a = p.Api(sys.argv[1]); print(p.need(*a.call("POST", "/api/v1/auth/login", {"email": p.PLAY_EMAIL, "password": p.PLAY_PASSWORD}))["accessToken"])' "$api_url")" || fail "login failed"
# The header goes in through stdin (printf is a shell builtin), so the token is never in a process argument list.
reject_status="$(printf 'Authorization: Bearer %s\n' "$token" | curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H @- \
  -d '{"expectedTurnNo":0,"side":"BUY","assetId":1,"quantity":1}' "$api_url/api/v1/seasons/999999999/orders" || true)"
[ "$reject_status" = 404 ] || fail "rejected-order probe answered $reject_status, wanted 404"

# After signup, login and the rejected order: the api container log must not carry the signing key (nothing is printed).
api_log="$(dc logs api 2>&1)" || fail "could not read the api log"
case "$api_log" in *"$jwt_key"*) fail "api log carries the signing key" ;; esac
printf 'PASS api log carries no signing key\n' | tee -a "$work/checks.log"

echo "== check the exporter exports no session text (OBS-06)"
# A session with a secret-looking application_name runs a statement with a secret-looking comment while the exporter is scraped.
# Neither string may appear in the scrape: the default stat_activity collector (application_name as a label) is switched off and the
# custom queries never select statement text.
sentinel_app="OBS06_APPNAME_SENTINEL"
sentinel_stmt="OBS06_STATEMENT_SENTINEL"
PGAPPNAME="$sentinel_app" PGHOST=127.0.0.1 PGPORT="$db_port" PGUSER="${USER:-antlab}" PGDATABASE=antlab_api \
  psql -X -q -c "SELECT pg_sleep(20) /* $sentinel_stmt */" > /dev/null 2>&1 &
holder=$!
visible=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
  seen="$(PGHOST=127.0.0.1 PGPORT="$db_port" PGUSER="${USER:-antlab}" PGDATABASE=antlab_api psql -X -q -t -A -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = '$sentinel_app' AND query LIKE '%$sentinel_stmt%'" 2>/dev/null || true)"
  if [ "$seen" = 1 ]; then visible=1; break; fi
  sleep 1
done
[ "$visible" = 1 ] || fail "the sentinel session never became visible in pg_stat_activity"
dc exec -T postgres-exporter wget -q -O - http://127.0.0.1:9187/metrics > "$work/exporter.metrics" || fail "could not scrape the exporter"
kill "$holder" 2>/dev/null || true
wait "$holder" 2>/dev/null || true
grep -q '^pg_up 1' "$work/exporter.metrics" || fail "the exporter scrape has no pg_up 1"
if grep -q -e "$sentinel_app" -e "$sentinel_stmt" "$work/exporter.metrics"; then fail "the exporter scrape carries session text"; fi
printf 'PASS exporter scrape carries no application_name or statement text\n' | tee -a "$work/checks.log"

echo "== keep light traffic on the api while Prometheus and Grafana are checked"
# A rate needs counter increases between two scrapes. The played season ended before the series of its URIs were first scraped, so
# without traffic the latency quantiles stay NaN (a panel that only looks alive). Unknown-path requests count as ordinary requests.
traffic_loop() {
  while true; do
    curl -s -o /dev/null --max-time 5 "$api_url/api/v1/seasons/999999999" || true
    sleep 3
  done
}
traffic_loop &
traffic=$!

echo "== check Prometheus and Grafana"
helper prometheus "$prom_url" 90 || fail "Prometheus assertion failed (A1-A3)"
helper wait-ready "$grafana_url/api/health" 60 || fail "Grafana is not ready"
SMOKE_GF_PASSWORD="$gf_password" helper grafana "$grafana_url" "$dashboard_dir" || fail "Grafana assertion failed (A4-A8)"

checks="$(grep -c '^PASS ' "$work/checks.log" || true)"
printf '\nmonitoring smoke: PASS (%s checks)\n' "$checks"
