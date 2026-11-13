#!/usr/bin/env bash
# Web end-to-end run (WEB-10): throwaway compose project with db and api only, loads the sample, then plays one season
# in a real browser at phone and desktop width (npm run e2e in web/), then removes the project.
# Manual only (needs Docker, psql 16+, Python 3 and the git-ignored sample CSVs).
# Exit codes: 0 pass, 1 assertion failed, 2 usage or preflight, 130 interrupted.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF2'
Usage: scripts/web_e2e.sh [--keep] [--dry-run] [-h|--help]
  --keep     leave the stack up after a pass
  --dry-run  validate and print the resolved configuration; calls neither docker nor psql
Environment: ANTLAB_E2E_PROJECT (default antlab-e2e), ANTLAB_E2E_{DB,API,WEB}_PORT, ANTLAB_E2E_OUT_DIR (default web/test-results),
             ANTLAB_GOLDEN_SAMPLE_DIR (default db/sample of the API checkout; needs asset_month_return.csv, asset_position.csv, fx_month.csv),
             ANTLAB_E2E_API_DIR (checkout whose compose.yaml, Dockerfile and db/ build and load the API; default: this repository)
EOF2
}

die() { printf 'web e2e: %s\n' "$1" >&2; exit 2; }

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

# Values from the caller's shell must not leak into the throwaway stack.
unset ANTLAB_JWT_SECRET COMPOSE_PROFILES ANTLAB_DB_USER

project="${ANTLAB_E2E_PROJECT:-antlab-e2e}"
db_port="${ANTLAB_E2E_DB_PORT:-55476}"
api_port="${ANTLAB_E2E_API_PORT:-18096}"
web_port="${ANTLAB_E2E_WEB_PORT:-5196}"
repo_dir="$PWD"
api_dir="${ANTLAB_E2E_API_DIR:-$repo_dir}"
sample_dir="${ANTLAB_GOLDEN_SAMPLE_DIR:-$api_dir/db/sample}"
out_base="${ANTLAB_E2E_OUT_DIR:-$repo_dir/web/test-results}"

[[ "$project" =~ ^antlab-e2e(-[a-z0-9]+)?$ ]] || die "ANTLAB_E2E_PROJECT must match ^antlab-e2e(-[a-z0-9]+)?\$ (got '$project')"
check_port() { # name value default-of-the-owner-stack
  [[ "$2" =~ ^[0-9]+$ && "$2" -ge 1024 && "$2" -le 65535 ]] || die "$1 must be a port number from 1024 to 65535 (got '$2')"
  [ "$2" != "$3" ] || die "$1 must not be $3 (the owner stack's port)"
}
check_port ANTLAB_E2E_DB_PORT "$db_port" 5432
check_port ANTLAB_E2E_API_PORT "$api_port" 8080
check_port ANTLAB_E2E_WEB_PORT "$web_port" 5173

if [ "$dry_run" = 1 ]; then
  printf 'project=%s db_port=%s api_port=%s web_port=%s api_dir=%s sample_dir=%s out_base=%s profiles=api\n' \
    "$project" "$db_port" "$api_port" "$web_port" "$api_dir" "$sample_dir" "$out_base"
  exit 0
fi

work=""
started=0
cleaned=0

dc() {
  ANTLAB_DB_PORT="$db_port" ANTLAB_API_PORT="$api_port" \
    docker compose -p "$project" --env-file "$work/compose.env" -f "$api_dir/compose.yaml" "$@"
}

cleanup() {
  local rc=$?
  set +e
  if [ "$cleaned" = 0 ]; then
    cleaned=1
    if [ "$started" = 1 ]; then
      if [ "$keep" = 1 ] && [ "$rc" = 0 ]; then
        printf 'stack left running: api http://127.0.0.1:%s, db 127.0.0.1:%s\n' "$api_port" "$db_port"
        printf 'remove it with: ANTLAB_DB_PORT=%s ANTLAB_API_PORT=%s docker compose -p %s -f %s/compose.yaml --profile api down -v --rmi local\n' \
          "$db_port" "$api_port" "$project" "$api_dir"
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
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out_dir="$out_base/$stamp"

command -v docker >/dev/null 2>&1 || die "docker is not installed"
docker compose version >/dev/null 2>&1 || die "Docker Compose is not available"
command -v psql >/dev/null 2>&1 || die "psql is not installed (version 16 or later is needed for the sample load)"
psql_major="$(psql --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
[ "$psql_major" -ge 16 ] 2>/dev/null || die "psql 16 or later is needed (found '$(psql --version)')"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"
command -v npm >/dev/null 2>&1 || die "npm is not installed"
for path in compose.yaml Dockerfile db events; do
  [ -e "$api_dir/$path" ] || die "$api_dir/$path is missing (check ANTLAB_E2E_API_DIR)"
done
for csv in asset_month_return.csv asset_position.csv fx_month.csv; do
  [ -f "$sample_dir/$csv" ] || die "$sample_dir/$csv is missing (set ANTLAB_GOLDEN_SAMPLE_DIR)"
done
for entry in "ANTLAB_E2E_DB_PORT:$db_port" "ANTLAB_E2E_API_PORT:$api_port" "ANTLAB_E2E_WEB_PORT:$web_port"; do
  # SO_REUSEADDR: sockets in TIME_WAIT from a previous run must not count as "in use" (Docker binds the same way)
  python3 -c 'import socket, sys; s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", int(sys.argv[1])))' "${entry#*:}" 2>/dev/null \
    || die "${entry%%:*}=${entry#*:} is already in use"
done

# One-run signing key; it only ever lives in this temp file, which is deleted on exit.
jwt_key="$(python3 -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
printf 'ANTLAB_DB_PORT=%s\n' "$db_port" > "$env_file"
printf 'ANTLAB_API_PORT=%s\n' "$api_port" >> "$env_file"
printf 'ANTLAB_JWT_SECRET=%s\n' "$jwt_key" >> "$env_file"
printf 'COMPOSE_PROFILES=api\n' >> "$env_file"

mkdir "$work/stage"
cp -R "$api_dir/db" "$api_dir/events" "$work/stage/"
for csv in asset_month_return.csv asset_position.csv fx_month.csv; do
  cp "$sample_dir/$csv" "$work/stage/db/sample/$csv"
done

fail() {
  printf '\nweb e2e: FAIL - %s\n' "$1" >&2
  if [ "$started" = 1 ]; then
    dc ps >&2 || true
    dc logs --tail 80 api >&2 || true
  fi
  for log in load.log e2e.log; do
    if [ -f "$work/$log" ]; then printf -- '--- tail of %s\n' "$log" >&2; tail -n 40 "$work/$log" >&2 || true; fi
  done
  printf 'screenshots and report: %s\n' "$out_dir" >&2
  exit 1
}

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

echo "== start api"
dc up -d --wait --wait-timeout 300 api || fail "api did not become healthy after the sample load"

echo "== browser play at 375x812 and 1280x800"
cd "$repo_dir/web"
[ -d node_modules ] || npm ci || fail "npm ci failed"
# Chromium goes to the user cache, never into the repository; installing again is a no-op.
npx playwright install chromium || fail "could not install Chromium for the test runner"
mkdir -p "$out_dir"
ANTLAB_API_PORT="$api_port" ANTLAB_E2E_WEB_PORT="$web_port" ANTLAB_E2E_OUT_DIR="$out_dir" \
  npm run e2e 2>&1 | tee "$work/e2e.log" || fail "browser play failed"

passed="$(sed -n -E 's/^ *([0-9]+) passed.*/\1/p' "$work/e2e.log" | tail -n 1)"
[ "$passed" = 2 ] || fail "expected 2 passing projects, saw '${passed:-none}'"
printf '\nweb e2e: PASS (%s projects) in %ss\nscreenshots and report: %s\n' "$passed" "$SECONDS" "$out_dir"
