# Play one season by hand / 시즌 한 판을 직접 돌려 보기

Run the Phase 0 API on a throwaway database and play a whole season: start, build the team, advance 12 turns (with a sell and a formation change), reconciliation, report, review.
Phase 0 API를 일회용 DB 위에 띄워 시즌 한 판(시작 → 팀 구성 → 12턴(매도·전술 변경 포함) → 대사 → 성적표 → 복기)을 직접 돌려 본다.

The project below (`antlab-play`, port 55433) is separate from the `antlab-db-1` container and from the `antlab` / `antlab_api` databases you already use, so nothing of yours is touched.
아래 프로젝트(`antlab-play`, 포트 55433)는 기존 `antlab-db-1` 컨테이너와 그 안의 `antlab`·`antlab_api` DB와 따로라서 기존 데이터를 건드리지 않는다.

## Prerequisites / 준비

- Docker, JDK 21, `psql`, Python 3 · the four git-ignored sample CSVs in `db/sample/` (`db/sample/README.md`).
- 표본 CSV 네 개가 `db/sample/`에 있어야 한다(git 무시 파일).

## 1. Database / DB 만들기

```bash
ANTLAB_DB_PORT=55433 docker compose -p antlab-play up -d --wait db
export PGHOST=localhost PGPORT=55433
export ANTLAB_DB_URL=jdbc:postgresql://localhost:55433/antlab_api
```

## 2. Create the schema (first start) / 스키마 만들기 (첫 실행)

```bash
./gradlew bootRun --args='--server.port=18080 --management.server.port=18081'
# when curl -fsS http://127.0.0.1:18080/readyz prints {"status":"UP"}, stop with Ctrl+C
```

## 3. Load the sample / 표본 적재 (앱을 끈 상태)

```bash
psql -X -d antlab_api -c "ALTER DATABASE antlab_api SET timezone TO 'Asia/Seoul'"
for f in 10_load_sample 11_load_events 12_calc_position 13_calc_attention; do
  psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/$f.sql && psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/${f}_check.sql
done
```

## 4. Start the API and play / API를 띄우고 한 판 돌리기

The script signs up `play@example.test` (an existing account is fine) and logs in; every season call carries the token. For its reconciliation step, make that member ADMIN once, after the first run created it, then run the script again: `psql -X -h localhost -p 55433 -d antlab_api -c "UPDATE member SET role = 'ADMIN' WHERE lower(email) = 'play@example.test'"` (it should print `UPDATE 1`). Without it that step is skipped (403). Until SEC-05 every logged-in member plays the same fixed member's seasons.
스크립트는 `play@example.test`로 가입(이미 있으면 그대로)하고 로그인하며, 시즌 호출마다 토큰을 보낸다. 대사 단계를 위해 첫 실행으로 회원이 생긴 뒤 그 회원을 한 번 ADMIN으로 바꾸고(위 psql 명령, `UPDATE 1`이 나와야 한다) 스크립트를 다시 돌린다. 바꾸지 않으면 그 단계는 403으로 건너뛴다. SEC-05 전까지는 로그인한 회원 누구나 같은 고정 회원의 시즌을 쓴다.

```bash
./gradlew bootRun --args='--server.port=18080 --management.server.port=18081'          # keep this terminal / 이 터미널은 그대로 둔다
python3 tools/play/play_season.py --base http://127.0.0.1:18080     # in a second terminal / 두 번째 터미널
```

Options / 옵션: `--turns 12|24|60`, `--risk -0.10|-0.20|-0.30|-0.40`. Each run creates a new season; at most 3 seasons may be in progress.
실행할 때마다 새 시즌이 만들어지고, 진행 중 시즌은 최대 3개다.
To watch the same play in Grafana (containers, monitoring profile), see the README section «Watch one season in Grafana». / 같은 한 판을 Grafana로 보려면(컨테이너, monitoring 프로필) README의 «모니터링으로 한 판 보기»를 본다.

## 5. Try it by hand / 직접 눌러 보기

- Swagger UI (local profile only): <http://127.0.0.1:18080/swagger-ui.html>
- Token / 토큰 (`TOKEN` = `accessToken` from `POST /api/v1/auth/login`, 15 minutes / 15분): `TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"email":"play@example.test","password":"correct-horse-battery"}' http://127.0.0.1:18080/api/v1/auth/login | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')`
- Pool / 풀 (no token / 토큰 없음): `curl -s http://127.0.0.1:18080/api/v1/pool`
- Season / 시즌 조회: `curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18080/api/v1/seasons/1`
- Manual reconciliation / 수동 대사 (ADMIN): `curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'X-Antlab-Dev: true' http://127.0.0.1:18080/api/v1/dev/seasons/1/reconciliation`
- Review before the season ends is `409 SEASON_NOT_FINISHED` with no dates in the body / 시즌이 끝나기 전 복기는 날짜 없이 409다: `curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18080/api/v1/seasons/1/review`

Error bodies are `application/problem+json` with a top-level `code`. Order bodies need `expectedTurnNo` equal to the season's current turn, and a new buy needs `declaredPosition`.
오류 응답은 최상위 `code`가 있는 `application/problem+json`이다. 주문 본문의 `expectedTurnNo`는 시즌의 현재 턴과 같아야 하고, 처음 사는 종목에는 `declaredPosition`이 필요하다.

## Clean up / 정리

```bash
docker compose -p antlab-play down -v     # removes only this project's container and volume / 이 프로젝트의 컨테이너·볼륨만 지운다
```
