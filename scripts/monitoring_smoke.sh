#!/usr/bin/env bash
# Monitoring smoke (MON-05): throwaway compose project with db, api, prometheus and grafana; plays one season,
# checks Prometheus and the three Grafana dashboards, then removes the project. Manual only (needs Docker and the sample CSVs).
# Exit codes: 0 pass, 1 assertion failed, 2 usage or preflight, 130 interrupted.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF'
Usage: scripts/monitoring_smoke.sh [--keep] [--dry-run] [-h|--help]
  --keep     leave the stack up after a pass (needs GF_SECURITY_ADMIN_PASSWORD in the environment)
  --dry-run  validate and print the resolved configuration; calls neither docker nor psql
Environment: ANTLAB_SMOKE_PROJECT (default antlab-smoke), ANTLAB_SMOKE_{DB,API,PROMETHEUS,GRAFANA}_PORT,
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
if [ "$keep" = 1 ] && [ -z "${GF_SECURITY_ADMIN_PASSWORD:-}" ]; then
  die "--keep needs GF_SECURITY_ADMIN_PASSWORD in the environment so you can sign in to Grafana afterwards"
fi

if [ "$dry_run" = 1 ]; then
  printf 'project=%s db_port=%s api_port=%s prometheus_port=%s grafana_port=%s files=compose.yaml,compose.play.yaml profiles=api,monitoring sample_dir=%s\n' \
    "$project" "$db_port" "$api_port" "$prometheus_port" "$grafana_port" "$sample_dir"
  exit 0
fi

work=""
env_file=""
started=0
cleaned=0
checks=0

cleanup() {
  local rc=$?
  set +e
  if [ "$cleaned" = 0 ]; then
    cleaned=1
    if [ "$started" = 1 ]; then
      if [ "$keep" = 1 ] && [ "$rc" = 0 ]; then
        printf 'stack left running: Grafana http://127.0.0.1:%s (user admin), Prometheus http://127.0.0.1:%s\n' "$grafana_port" "$prometheus_port"
        printf 'remove it with: docker %s -p %s -f compose.yaml -f compose.play.yaml --profile api --profile monitoring down -v --rmi local\n' compose "$project"
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
compose_cmd=(docker compose -p "$project" --env-file "$env_file" -f compose.yaml -f compose.play.yaml)
dc() { "${compose_cmd[@]}" "$@"; }

gf_password="${GF_SECURITY_ADMIN_PASSWORD:-}"
[ -n "$gf_password" ] || gf_password="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
jwt_key="$(python3 -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
printf 'ANTLAB_DB_PORT=%s\n' "$db_port" > "$env_file"
printf 'ANTLAB_API_PORT=%s\n' "$api_port" >> "$env_file"
printf 'ANTLAB_PROMETHEUS_PORT=%s\n' "$prometheus_port" >> "$env_file"
printf 'ANTLAB_GRAFANA_PORT=%s\n' "$grafana_port" >> "$env_file"
printf 'GF_SECURITY_ADMIN_PASSWORD=%s\n' "$gf_password" >> "$env_file"
printf 'ANTLAB_JWT_SECRET=%s\n' "$jwt_key" >> "$env_file"
printf 'COMPOSE_PROFILES=api,monitoring\n' >> "$env_file"

dc version >/dev/null 2>&1 || die "Docker Compose is not available"
command -v psql >/dev/null 2>&1 || die "psql is not installed (version 16 or later is needed for the sample load)"
psql_major="$(psql --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
[ "$psql_major" -ge 16 ] 2>/dev/null || die "psql 16 or later is needed (found '$(psql --version)')"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"
for csv in asset_month_return.csv asset_position.csv fx_month.csv; do
  [ -f "$sample_dir/$csv" ] || die "$sample_dir/$csv is missing (set ANTLAB_GOLDEN_SAMPLE_DIR)"
done
for entry in "ANTLAB_SMOKE_DB_PORT:$db_port" "ANTLAB_SMOKE_API_PORT:$api_port" \
             "ANTLAB_SMOKE_PROMETHEUS_PORT:$prometheus_port" "ANTLAB_SMOKE_GRAFANA_PORT:$grafana_port"; do
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
  dc logs --tail 60 api prometheus grafana >&2 || true
  for log in play.log load.log; do
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
# compose.play.yaml goes away with SEC-05.
python3 tools/play/play_season.py --base "$api_url" --turns 12 > "$work/play.log" 2>&1 || fail "play script failed"
# The play script signed the member up; log in again with its constants (the token is never printed).
token="$(PYTHONPATH=tools/play python3 -c 'import sys, play_season as p; a = p.Api(sys.argv[1]); print(p.need(*a.call("POST", "/api/v1/auth/login", {"email": p.PLAY_EMAIL, "password": p.PLAY_PASSWORD}))["accessToken"])' "$api_url")" || fail "login failed"
reject_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
  -d '{"expectedTurnNo":0,"side":"BUY","assetId":1,"quantity":1}' "$api_url/api/v1/seasons/999999999/orders" || true)"
[ "$reject_status" = 404 ] || fail "rejected-order probe answered $reject_status, wanted 404"

echo "== check Prometheus and Grafana"
helper prometheus "$prom_url" 90 || fail "Prometheus assertion failed (A1-A3)"
helper wait-ready "$grafana_url/api/health" 60 || fail "Grafana is not ready"
SMOKE_GF_PASSWORD="$gf_password" helper grafana "$grafana_url" "$dashboard_dir" || fail "Grafana assertion failed (A4-A8)"

checks="$(grep -c '^PASS ' "$work/checks.log" || true)"
printf '\nmonitoring smoke: PASS (%s checks)\n' "$checks"
