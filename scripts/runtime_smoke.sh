#!/usr/bin/env bash
# Runtime smoke (M1-03): two checks of the api image in a throwaway compose project (db + api from compose.yaml).
#   startup-fail  prod profile without ANTLAB_DB_URL must exit non-zero fast and must not fall back to localhost (#171)
#   graceful      docker stop while a signup waits on a table lock: the request must finish with 201 (#169)
# The prod profile logs one JSON object per line (MVP-20), so log texts are read from the JSON fields, not grepped raw.
# Exit codes: 0 pass, 1 assertion failed, 2 usage or preflight, 130 interrupted.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF'
Usage: scripts/runtime_smoke.sh {startup-fail|graceful} [--image TAG] [--negative] [--dry-run] [-h|--help]
  --image TAG  use this local image (tagged as the project's api image, no build); default: build from the Dockerfile
  --negative   graceful only: hold the lock past the 20 s phase timeout; passes only if the request does NOT finish with 201
  --dry-run    validate and print the resolved configuration; calls neither docker nor psql
Environment: ANTLAB_RUNTIME_PROJECT (default antlab-runtime), ANTLAB_RUNTIME_{DB,API}_PORT (default 55480, 18098)
EOF
}

die() { printf 'runtime smoke: %s\n' "$1" >&2; exit 2; }

mode=""
image=""
negative=0
dry_run=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    startup-fail|graceful) [ -z "$mode" ] || { usage >&2; exit 2; }; mode="$1" ;;
    --image) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; image="$2"; shift ;;
    --negative) negative=1 ;;
    --dry-run) dry_run=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
  shift
done
[ -n "$mode" ] || { usage >&2; exit 2; }
[ "$negative" = 0 ] || [ "$mode" = graceful ] || die "--negative applies to graceful only"

project="${ANTLAB_RUNTIME_PROJECT:-antlab-runtime}"
db_port="${ANTLAB_RUNTIME_DB_PORT:-55480}"
api_port="${ANTLAB_RUNTIME_API_PORT:-18098}"

[[ "$project" =~ ^antlab-runtime(-[a-z0-9]+)?$ ]] || die "ANTLAB_RUNTIME_PROJECT must match ^antlab-runtime(-[a-z0-9]+)?\$ (got '$project')"
check_port() { # name value default-of-the-owner-stack
  # No leading zero: bash reads 05432 as octal in -ge/-le while string comparison and Docker read it as 5432.
  [[ "$2" =~ ^[1-9][0-9]*$ && "$2" -ge 1024 && "$2" -le 65535 ]] || die "$1 must be a port number from 1024 to 65535 (got '$2')"
  [ "$2" != "$3" ] || die "$1 must not be $3 (the owner stack's port)"
}
check_port ANTLAB_RUNTIME_DB_PORT "$db_port" 5432
check_port ANTLAB_RUNTIME_API_PORT "$api_port" 8080
[ "$db_port" != "$api_port" ] || die "the database and api ports must differ"
[[ -z "$image" || "$image" =~ ^[A-Za-z0-9._/:-]+$ ]] || die "--image must be a plain image reference (got '$image')"

if [ "$dry_run" = 1 ]; then
  printf 'mode=%s project=%s db_port=%s api_port=%s image=%s negative=%s files=compose.yaml\n' \
    "$mode" "$project" "$db_port" "$api_port" "${image:-build}" "$negative"
  exit 0
fi

work=""
env_file=""
started=0
cleaned=0
stopper_pid=""
locker_pid=""

cleanup() {
  local rc=$?
  set +e
  if [ "$cleaned" = 0 ]; then
    cleaned=1
    [ -n "$stopper_pid" ] && kill "$stopper_pid" 2>/dev/null
    [ -n "$locker_pid" ] && kill "$locker_pid" 2>/dev/null
    if [ "$started" = 1 ]; then
      dc down -v --remove-orphans --rmi local >/dev/null 2>&1 || true
      docker rm -f "$project-startfail" >/dev/null 2>&1 || true
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
dc() { "${compose_cmd[@]}" --profile api "$@"; }

jwt_key="$(python3 -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
printf 'ANTLAB_DB_PORT=%s\n' "$db_port" > "$env_file"
printf 'ANTLAB_API_PORT=%s\n' "$api_port" >> "$env_file"
printf 'ANTLAB_JWT_SECRET=%s\n' "$jwt_key" >> "$env_file"
# The smoke checks the api image alone; the default path source (engine) would need the engine token and service.
printf 'ANTLAB_ENGINE_PATH_SOURCE=java\n' >> "$env_file"

dc version >/dev/null 2>&1 || die "Docker Compose is not available"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"
command -v curl >/dev/null 2>&1 || die "curl is not installed"
for entry in "ANTLAB_RUNTIME_DB_PORT:$db_port" "ANTLAB_RUNTIME_API_PORT:$api_port"; do
  # SO_REUSEADDR: sockets in TIME_WAIT from a previous run must not count as "in use" (Docker binds the same way)
  python3 -c 'import socket, sys; s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", int(sys.argv[1])))' "${entry#*:}" 2>/dev/null \
    || die "${entry%%:*}=${entry#*:} is already in use"
done
if [ -n "$image" ]; then
  docker image inspect "$image" >/dev/null 2>&1 || die "image '$image' is not available locally"
fi

# Every non-empty line of FILE must be one JSON object; the first offender is reported on stderr.
json_lines_only() {
  python3 -c 'import json, sys
for n, line in enumerate(open(sys.argv[1], encoding="utf-8", errors="replace"), 1):
    if not line.strip():
        continue
    try:
        ok = isinstance(json.loads(line), dict)
    except ValueError:
        ok = False
    if not ok:
        print("line %d is not a JSON object: %s" % (n, line.strip()[:200]), file=sys.stderr)
        sys.exit(1)' "$1"
}

# Prints message, error.message and error.stack_trace of every JSON line of FILE, one value per line, unescaped.
# Nested ("error": {"message": ...}) and flat ("error.message") spellings both work. Non-JSON lines are skipped.
json_log_text() {
  python3 -c 'import json, sys
def field(event, dotted):
    node = event
    for part in dotted.split("."):
        node = node.get(part) if isinstance(node, dict) else None
    if node is None and isinstance(event, dict):
        node = event.get(dotted)
    return node if isinstance(node, str) else None
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    try:
        event = json.loads(line)
    except ValueError:
        continue
    if not isinstance(event, dict):
        continue
    for name in ("message", "error.message", "error.stack_trace"):
        value = field(event, name)
        if value:
            print(value)' "$1"
}

fail() {
  printf '\nruntime smoke (%s): FAIL - %s\n' "$mode" "$1" >&2
  dc ps >&2 || true
  dc logs --tail 60 api >&2 || true
  for log in startfail.log stop.log request.out api.err; do
    if [ -f "$work/$log" ]; then printf -- '--- tail of %s\n' "$log" >&2; tail -n 30 "$work/$log" >&2 || true; fi
  done
  exit 1
}

started=1
dc down -v --remove-orphans >/dev/null 2>&1 || true

if [ -n "$image" ]; then
  # compose names a built image <project>-api; a given image takes that name so the same compose file runs it.
  docker tag "$image" "$project-api" || fail "could not tag $image"
  up_flags=(--no-build)
else
  up_flags=(--build)
fi
api_image="$project-api"

startup_fail() {
  echo "== start the image without ANTLAB_DB_URL"
  [ -n "$image" ] || dc build api || fail "image build failed"
  local started_at rc
  started_at="$(date +%s)"
  # Plain docker run: compose.yaml always sets ANTLAB_DB_URL, and an empty value would test a different failure.
  docker run --name "$project-startfail" -e SPRING_PROFILES_ACTIVE=prod -e ANTLAB_JWT_SECRET="$jwt_key" -e ANTLAB_ENGINE_PATH_SOURCE=java \
    "$api_image" > "$work/startfail.log" 2>&1 &
  local pid=$!
  local waited=0
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 60 ]; do sleep 1; waited=$((waited + 1)); done
  if kill -0 "$pid" 2>/dev/null; then fail "the container was still running after 60 s"; fi
  rc=0
  wait "$pid" || rc=$?
  printf 'exited with code %s after %s s\n' "$rc" "$(( $(date +%s) - started_at ))"
  [ "$rc" != 0 ] || fail "exit code 0; the container must fail without ANTLAB_DB_URL"
  [ "$rc" = 1 ] || fail "exit code $rc, wanted 1 (an application start-up failure)"
  json_lines_only "$work/startfail.log" || fail "the start-up log has a line that is not a JSON object"
  local startfail_text
  startfail_text="$(json_log_text "$work/startfail.log")"
  case "$startfail_text" in *"Application run failed"*) ;; *) fail "log line 'Application run failed' is missing" ;; esac
  # Measured: the unresolved ${ANTLAB_DB_URL} placeholder reaches the pool as the url and Hikari rejects it.
  # The quotes are escaped inside the JSON line, so the text is read from the decoded fields.
  case "$startfail_text" in *"'url' must start with \"jdbc\""*) ;; *) fail "the missing-url message is not in the log" ;; esac
  if grep -Eq 'localhost:5432|Connection to .* refused' "$work/startfail.log"; then fail "the app fell back to a local database"; fi
  case "$(cat "$work/startfail.log")" in *"$jwt_key"*) fail "the log carries the signing key" ;; esac
  printf 'PASS exit code 1, start-up failure logged as JSON, no fallback to localhost, no signing key in the log\n'
}

graceful() {
  echo "== start db and api"
  dc up -d "${up_flags[@]}" --wait --wait-timeout 300 db api || fail "db and api did not become healthy"
  local api_url="http://127.0.0.1:$api_port"
  local grace
  grace="$(dc config --format json | python3 -c 'import json, re, sys; v = json.load(sys.stdin)["services"]["api"]["stop_grace_period"]; print(int(re.sub(r"\D", "", v)))')" || fail "could not read stop_grace_period"
  # The compose healthcheck user is the compose database user (POSTGRES_USER=${USER:-antlab}).
  local dbuser="${USER:-antlab}"
  psql_db() { PGHOST=127.0.0.1 PGPORT="$db_port" PGUSER="$dbuser" PGDATABASE=antlab_api psql -X -q -A -t -v ON_ERROR_STOP=1 "$@"; }
  command -v psql >/dev/null 2>&1 || fail "psql is not installed"
  psql_db -c 'SELECT 1' >/dev/null || fail "cannot reach the smoke database"

  local hold=3
  [ "$negative" = 0 ] || hold=25
  echo "== hold an exclusive lock on member for ${hold} s after the request is waiting"
  # The locker session keeps the lock until a marker file appears (bounded), so the lock outlives docker stop's signal.
  rm -f "$work/release"
  (
    export PGHOST=127.0.0.1 PGPORT="$db_port" PGUSER="$dbuser" PGDATABASE=antlab_api
    {
      printf 'BEGIN;\nLOCK TABLE member IN EXCLUSIVE MODE;\n\\! touch "%s/locked"\n' "$work"
      # shellcheck disable=SC2016  # the loop is meant for the shell psql starts, not for this one
      printf '\\! n=0; while [ ! -f "%s/release" ] && [ "$n" -lt 100 ]; do sleep 0.5; n=$((n + 1)); done\n' "$work"
      printf 'COMMIT;\n'
    } | psql -X -q -v ON_ERROR_STOP=1 > "$work/lock.log" 2>&1
  ) &
  locker_pid=$!
  local n=0
  while [ ! -f "$work/locked" ] && [ "$n" -lt 40 ]; do sleep 0.25; n=$((n + 1)); done
  [ -f "$work/locked" ] || fail "could not take the table lock"

  echo "== send a signup that has to wait for the lock"
  local email
  email="runtime-$(date +%s)@example.invalid"
  local payload
  payload="$(python3 -c 'import json, sys; print(json.dumps({"email": sys.argv[1], "name": "runtime smoke", "password": "runtime-smoke-throwaway"}))' "$email")"
  ( curl -s -o "$work/request.out" -w '%{http_code}' --max-time 90 -X POST -H 'Content-Type: application/json' \
      -d "$payload" "$api_url/api/v1/auth/signup" > "$work/request.status" 2>/dev/null || true ) &
  local request_pid=$!

  # Wait until a backend of the api is blocked on the lock; stopping earlier would test nothing.
  n=0
  local waiting=0
  while [ "$n" -lt 40 ]; do
    waiting="$(psql_db -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'antlab_api' AND wait_event_type = 'Lock' AND state = 'active'")" || waiting=0
    [ "$waiting" -ge 1 ] 2>/dev/null && break
    sleep 0.25; n=$((n + 1))
  done
  [ "$waiting" -ge 1 ] 2>/dev/null || fail "no request reached the lock; the in-flight state was not reached"
  printf 'in flight: %s backend(s) waiting on the lock\n' "$waiting"

  echo "== docker stop while the request is in flight"
  local stop_start stop_end
  stop_start="$(date +%s)"
  ( docker stop --timeout "$grace" "$project-api-1" > "$work/stop.log" 2>&1; date +%s > "$work/stop_end" ) &
  stopper_pid=$!
  # While the signup is still waiting, a new connection must no longer be served: Tomcat stops accepting at the
  # start of the graceful phase. Through the published port that shows as curl exit 52 (the proxy closes) or 7.
  n=0
  local refused=0 probe_rc
  while [ "$n" -lt 40 ] && kill -0 "$request_pid" 2>/dev/null; do
    probe_rc=0
    curl -s -o /dev/null --connect-timeout 1 --max-time 2 "$api_url/readyz" || probe_rc=$?
    if [ "$probe_rc" != 0 ] && kill -0 "$request_pid" 2>/dev/null; then refused=1; break; fi
    sleep 0.25; n=$((n + 1))
  done
  [ "$refused" = 0 ] || printf 'a new connection during shutdown was not served (curl exit %s) while the signup was still in flight\n' "$probe_rc"
  sleep "$hold"
  : > "$work/release"
  wait "$request_pid" || true
  wait "$stopper_pid" || true
  stopper_pid=""
  stop_end="$(cat "$work/stop_end" 2>/dev/null || date +%s)"
  local status
  status="$(cat "$work/request.status" 2>/dev/null || true)"
  local exit_code
  exit_code="$(docker inspect --format '{{.State.ExitCode}}' "$project-api-1" 2>/dev/null || echo unknown)"
  local elapsed=$((stop_end - stop_start))
  # --no-log-prefix: compose would put "api-1  | " in front of every JSON line.
  dc logs --no-log-prefix api > "$work/api.log" 2> "$work/api.err" || fail "could not read the api log"
  json_lines_only "$work/api.log" || fail "the api log has a line that is not a JSON object"
  local api_log
  api_log="$(json_log_text "$work/api.log")"
  printf '%s\n' "$api_log" | grep -i 'graceful shutdown' | cut -c1-200 || true
  printf 'request status=%s, container exit code=%s, stop took %s s (stop_grace_period %s s), new connection not served during shutdown=%s\n' \
    "$status" "$exit_code" "$elapsed" "$grace" "$refused"

  if [ "$negative" = 1 ]; then
    # A lock longer than the 20 s phase timeout must make the request fail: the smoke can fail.
    [ "$status" != 201 ] || fail "negative control: the request finished with 201 although the lock outlived the phase timeout"
    case "$api_log" in *"Graceful shutdown aborted with one or more requests still active"*) ;; *) fail "negative control: the phase timeout is not in the api log" ;; esac
    printf 'PASS negative control: the request did not finish (status %s) once the lock outlived the phase timeout\n' "${status:-none}"
    return 0
  fi

  [ "$status" = 201 ] || fail "the in-flight signup answered '$status', wanted 201"
  [ "$refused" = 1 ] || fail "a new connection during shutdown was still served"
  [ "$elapsed" -lt "$grace" ] || fail "stop took $elapsed s, not less than stop_grace_period $grace s"
  [ "$exit_code" != 137 ] || fail "the container was killed (exit 137)"
  [ "$exit_code" = 143 ] || fail "exit code $exit_code, wanted 143 (SIGTERM handled by the JVM)"
  case "$api_log" in *"Graceful shutdown complete"*) ;; *) fail "log line 'Graceful shutdown complete' is missing" ;; esac
  # Measured: the "complete" line is also logged after a timeout, so the abort line must be absent as well.
  case "$api_log" in *"Graceful shutdown aborted"*) fail "the graceful phase was aborted with requests still active" ;; esac
  printf 'PASS in-flight signup got 201, new connection not served, graceful shutdown complete, exit 143 in %s s (< %s s)\n' "$elapsed" "$grace"
}

case "$mode" in
  startup-fail) startup_fail ;;
  graceful) graceful ;;
esac
printf '\nruntime smoke (%s): PASS\n' "$mode"
