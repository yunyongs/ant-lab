#!/usr/bin/env bash
# Image feed check (MVP-11 follow-up): the api image must hold exactly the feed/*.csv files of the repository, byte for byte,
# in the application classes (the Dockerfile extracts app.jar, so BOOT-INF/classes/feed/ here is BOOT-INF/classes/feed/ in the jar).
# Without them the application only fails at start-up, so the image build alone would not notice.
# Exit codes: 0 pass, 1 assertion failed, 2 usage or preflight.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF2'
Usage: scripts/image_feed_check.sh IMAGE [-h|--help]
  IMAGE  a local api image, for example antlab-api:ci
EOF2
}

die() { printf 'image feed check: %s\n' "$1" >&2; exit 2; }

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  "") usage >&2; exit 2 ;;
esac
[ "$#" -eq 1 ] || { usage >&2; exit 2; }
image="$1"
dir=/app/BOOT-INF/classes/feed

command -v docker >/dev/null || die "docker is not installed"
expected=$(cd feed && ls -1 -- *.csv)
[ -n "$expected" ] || die "no feed/*.csv in the repository"

actual=$(docker run --rm --entrypoint ls "$image" -1 "$dir") || { echo "image feed check: cannot list $dir in $image" >&2; exit 1; }
if [ "$(printf '%s\n' "$actual" | LC_ALL=C sort)" != "$(printf '%s\n' "$expected" | LC_ALL=C sort)" ]; then
  printf 'image feed check: files differ\n  repository: %s\n  image: %s\n' "$(printf '%s' "$expected" | tr '\n' ' ')" "$(printf '%s' "$actual" | tr '\n' ' ')" >&2
  exit 1
fi
for name in $expected; do
  if ! docker run --rm --entrypoint cat "$image" "$dir/$name" | cmp -s - "feed/$name"; then
    echo "image feed check: $name differs from feed/$name" >&2
    exit 1
  fi
done
printf 'image feed check: %s files identical in %s\n' "$(printf '%s\n' "$expected" | wc -l | tr -d ' ')" "$image"
