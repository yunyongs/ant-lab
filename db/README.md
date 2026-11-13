# db

Phase 0에서 손으로 쓰는 SQL이다([docs/PLAN.md](../docs/PLAN.md#111-phase-0-체크리스트-sql-postgresql-17)의 체크리스트). 테이블 정의의 근거는 [docs/erd.md](../docs/erd.md)이고, DB는 PostgreSQL 17이다.

저장소 루트의 Spring 프로젝트(`src/`, P0-22~)는 이 폴더를 빌드에 넣지 않는다. API의 스키마 파일은 P0-23부터 `src/main/resources/db/migration`에 있다.

## API DB와 실습 DB

`antlab`은 SQL 실습용이며 Spring API는 별도 `antlab_api`를 사용한다. API DB에서는
`01_schema_history.sql`–`03_schema_account.sql` 전체를 다시 실행하지 않는다. 앱을
처음 시작하면 Flyway가 `V1__schema_history.sql`–`V3__schema_account.sql`을 적용한다.
이후 표본 파일이 준비되면 앱을 종료한 상태에서 저장소 루트의 `10 → 11 → 12 → 13`
적재와 각 `_check`를 실행한 다음 앱을 다시 시작한다. 표본 CSV가 없는 환경에서는 적재를
PASS로 기록하지 않는다.

처음 한 번은 저장소 루트의 VS Code 통합 터미널에서 실행한다(접속 환경 변수는 아래 「실행 환경」).

```bash
createdb -E UTF8 -T template0 antlab_api
psql -X -d postgres -c "ALTER DATABASE antlab_api SET timezone TO 'Asia/Seoul'"
./gradlew bootRun        # Flyway가 V1–V3 적용. /readyz 가 UP이면 Ctrl+C로 종료
psql -X -d antlab_api -c "SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank"
                         # 1·2·3 세 줄, success = t
```

표본 적재(앱을 끈 상태. `db/sample/README.md`의 무시 CSV 네 개가 있어야 한다):

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab_api -f db/10_load_sample.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/10_load_sample_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_api -f db/11_load_events.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/11_load_events_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_api -f db/12_calc_position.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/12_calc_position_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_api -f db/13_calc_attention.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/13_calc_attention_check.sql
./gradlew bootRun        # 다시 시작해도 Flyway는 V1–V3를 다시 적용하지 않는다(이력 3행 그대로)
```

적재한 뒤 앱을 띄워 `GET /api/v1/pool`이 200이고 20종목을 돌려주는지 확인한다(`local` 프로필 기본 포트 8080).

시즌 시작 골든(`SeasonStartGoldenTest`, P0-30)은 하네스 컨테이너의 API DB(Flyway로 V1–V3이 적용된 DB)에 이 순서에서 **`10 → 12`만** 적재한다(`SqlGoldenScenario.apiDbLoad()`; 11·13은 시즌 시작에 사용하지 않는다). 시즌은 `POST /api/v1/seasons`로 만들고, 같은 DB에 `20_season_start_check.sql`을 그대로 실행해 `p0_14_check = PASS`인지 확인한다.
`10`을 적재하기 전이면 409 `POOL_NOT_LOADED`, `12`를 계산하기 전이면 409 `POOL_NOT_JUDGED`다. 기준일은
`antlab.pool.base-date` 설정(환경 변수 `ANTLAB_POOL_BASE_DATE`, 기본 2026-01-02)으로 정한다.

`antlab_api`에서는 (b)·(b′)(01–03 전체 파일. 테이블을 지운다)와 20번대 SQL(시즌·팀·대사·턴 —
API에서는 Java가 쓴다)을 실행하지 않는다. 적재한 뒤에는 01–03 `_check`도 실행하지 않는다(0절이 빈 테이블을
요구하기 때문이다). `createdb`가 "already exists"로 끝나면 그 DB가 비어 있는지 먼저 확인한다. 비어 있지 않으면
Flyway가 자동 baseline 없이 시작을 멈춘다. DB를 지우거나 새 이름을 쓸지는 소유자가 정한다.

**소유자 조치(머지 뒤):** 이 저장소의 작업은 소유자 DB(`antlab-db-1`)에 접속하지 않았다. 로컬 API를 쓰기 전에
소유자가 위 블록을 직접 실행한다. `antlab_api`가 이미 있고 테이블이 들어 있으면(이전 `createdb` 안내 때문에
있을 수 있다) 먼저 내용을 확인한다. 그다음 `dropdb antlab_api`로 지우고 다시 만들거나, 보존해야 하면 새 이름과
`ANTLAB_DB_URL`을 쓴다. 기존 실습 DB `antlab`을 API DB로 바꾸는 일은 범위 밖이다(스키마 대조와 수동 baseline이 필요).

### API 컨테이너 (P0-24)

루트 `compose.yaml`의 `api` 서비스는 같은 `db`의 `antlab_api`에 연결된다(컨테이너 안 주소는 `jdbc:postgresql://db:5432/antlab_api`, 호스트 게시 포트 `ANTLAB_DB_PORT`와 무관). `antlab_api`를 만드는 방법은 두 가지다.

- **새 볼륨(빈 데이터 디렉터리로 처음 시작)**: `db` 서비스의 `POSTGRES_DB=antlab_api`가 빈 DB를 만든다. `docker compose --profile api up -d --build --wait`를 한 번 실행하면 Flyway V1–V3까지 적용된다. 시간대 설정(`ALTER DATABASE … SET timezone`)은 필요 없다 — 앱이 세션마다 `Asia/Seoul`을 지정하고(`connection-init-sql`), 컨테이너 DB의 `TZ`도 `Asia/Seoul`이다. psql로 표본 SQL을 실행할 때만 위의 `ALTER DATABASE` 한 줄을 실행한다.
- **기존 볼륨(이미 `antlab`이 들어 있는 `antlab_db-data` 등)**: `POSTGRES_*` 변수는 처음 시작할 때만 적용되므로 `antlab_api`가 자동으로 생기지 않는다. 자동 생성 서비스나 `CREATE DATABASE` 권한은 부여하지 않았다. 먼저 `db`만 올려 확인한다.

```bash
docker compose up -d --wait db
docker compose exec -T db psql -U "$USER" -d postgres -Atc "SELECT datname FROM pg_database WHERE datname = 'antlab_api'"
# 아무것도 안 나오면(없으면) 한 번만 만든다. 이미 있으면 실행하지 않는다(지우거나 다시 만들지 않는다)
docker compose exec -T db createdb -U "$USER" -E UTF8 -T template0 antlab_api
docker compose exec -T db psql -U "$USER" -d postgres -c "ALTER DATABASE antlab_api SET timezone TO 'Asia/Seoul'"
docker compose --profile api up -d --build --wait --wait-timeout 240
```

`$USER`가 기존 DB 역할 이름과 다르면 그 역할 이름을 쓰고 `ANTLAB_DB_USER`에도 같은 값을 준다(OS 사용자 이름을 바꿔도 DB 역할은 바뀌지 않는다). 이 변수는 기존 볼륨의 역할 이름이 다를 때만 지정한다 — 새 볼륨의 역할은 항상 `${USER:-antlab}`로 만들어지므로 다른 이름을 주면 역할이 없어 api가 접속에 실패한다. api 컨테이너가 `Restarting`이면 먼저 `antlab_api` DB가 있는지(위 확인 명령) 확인한다. 소유자 프로젝트의 `db` 컨테이너는 `compose.yaml`이 바뀌면 다음 `up`에서 같은 볼륨으로 다시 만들어진다(데이터는 그대로).

The container `api` runs the `prod` profile: Swagger is off, and the Actuator opens only `health` and `prometheus` on the management port 8081 (not published). To load the sample: `docker compose stop api` → load → `docker compose start api`. / 컨테이너 `api`는 `prod` 프로필이라 Swagger가 꺼져 있고 Actuator는 관리 포트 8081에서 `health`와 `prometheus`만 연다(게시하지 않음). 표본을 적재할 때는 `docker compose stop api` → 적재 → `docker compose start api` 순서로 실행한다.

시험은 소유자의 `antlab`·`antlab_api`에 접속하지 않는다. 시험 실행마다 새 PostgreSQL 17
Testcontainers DB를 시작하고 Flyway를 적용한다. 시험 DB에서 `clean`으로 공유 데이터를 지우지
않는다. API DB의 기존 스키마를 자동 `baseline`하지 않으며, 문제가 있으면 새 DB와
접속 변수를 확인한다.

## 파일

| 파일 | 만드는 기능 | 내용 |
|------|-------------|------|
| `01_schema_history.sql` | P0-05 | 역사 데이터·종목 풀 테이블 8개 DDL (PK, UNIQUE, FK, CHECK, `idx_` 인덱스, 테이블·컬럼 코멘트). 이 파일의 8개 테이블을 지우고 다시 만든다 |
| `01_schema_history_check.sql` | P0-05 | 위 DDL의 검증. erd 5.3·5.4와 카탈로그를 대조하고, 통과해야 하는 사례와 실패해야 하는 사례를 실행해 스스로 판정한다 |
| `02_schema_season.sql` | P0-06 | 회원·시즌·피드·대사 테이블 9개 DDL (PK, UNIQUE, 표현식·부분 유일 인덱스 2개, FK, CHECK, `idx_` 인덱스, 테이블·컬럼 코멘트). 01의 테이블 4개를 FK로 참조하므로 01을 먼저 실행한다. 이 파일의 9개 테이블을 지우고 다시 만든다 |
| `02_schema_season_check.sql` | P0-06 | 위 DDL의 검증. erd 6.3·6.4와 카탈로그를 대조하고(유일 인덱스는 `pg_indexes`), 통과해야 하는 사례와 실패해야 하는 사례를 실행해 스스로 판정한다 |
| `03_schema_account.sql` | P0-07 | 시즌 계좌 테이블 5개 DDL (`cash_account`·`orders`·`execution`·`holding`·`cash_ledger` — PK, UNIQUE, 복합 FK 3개 포함 FK 10개, CHECK 28개, `idx_` 인덱스, 테이블·컬럼 코멘트). 01의 `asset`과 02의 `season`·`formation_change`·`turn_feed`를 FK로 참조하므로 01 → 02를 먼저 실행한다. 이 파일의 5개 테이블을 지우고 다시 만든다 |
| `03_schema_account_check.sql` | P0-07 | 위 DDL의 검증. erd 7.3·7.4와 카탈로그를 대조하고(컬럼 코멘트는 논리명까지), 통과해야 하는 사례와 실패해야 하는 사례를 실행해 스스로 판정한다. 다른 CHECK·PK에 가려진 제약(`ck_orders_type`, `ck_cash_ledger_type`, `uq_orders_id_season`)은 가리는 제약을 사례 안에서 지워 증명하고, 복합 FK의 시즌·턴 일치와 "DDL이 통과시키는 것 — 대사의 몫"을 따로 확인한다 |
| `10_load_sample.sql` | P0-08 | 표본 CSV 6개(`db/sample/`)를 역사 테이블 6개에 적재한다. 아무것도 지우지 않고, 같은 CSV로 다시 실행하면 새 행이 없다(멱등). 값이 CSV와 다르면 오류로 멈춘다. 01 스키마를 먼저 실행한다. 시세·환율에서 만든 CSV 3개(`asset_month_return.csv`, `asset_position.csv`, `fx_month.csv`)는 git이 무시하므로 없을 수 있다 — 하나라도 없으면 DB에 닿기 전에 첫 줄에서 "무시 CSV가 없다. 표본 만들기를 먼저" 메시지로 멈춘다(종료 코드 3) |
| `10_load_sample_check.sql` | P0-08 | 적재 검증. 행 수·형식·H1·H3·H4·H7을 대조하고 손 대조(6절)를 확인한다. 6절의 기대값은 git이 무시하는 `db/sample/hand_check.csv`에서 읽으며, 파일이 없으면 6절이 FAIL이다(검증이 비어 있는데 통과하지 않게 한다). 아무것도 쓰지 않고 `ROLLBACK`하므로 `antlab`에서도 돌린다 |
| `11_load_events.sql` | P0-09 | 사건 태그 파일 3개(`events/`)를 `month_event`에 적재하고 `month_block.events_reviewed`를 FALSE에서 TRUE로만 켠다. 반응 변화율·강도는 파일이 아니라 10 적재가 넣은 DB 값에서 계산한다. 아무것도 지우지 않고, 같은 파일로 다시 실행하면 새 행도 갱신도 없다(멱등). 파일과 DB의 값이 다르면(DB에만 있는 태그, 파일이 뒤로 간 편집 완료 표시 포함) 오류로 멈추고 전부 되돌린다. 01 스키마와 10 적재를 먼저 실행한다 |
| `11_load_events_check.sql` | P0-09 | 사건 적재 검증. 행 수·DB↔파일 대조·형식·에피소드 규칙(H6)·노출 코드의 숫자·두 글자 토큰·금지 토큰(H5)·반응값과 강도·편집 완료 3값을 단언하고, 유형별 표본 수 n과 n < 3 목록을 보고한다. 규칙 21개마다 음성 사례가 있고 커버리지 `21/21`을 확인한다. 아무것도 쓰지 않고 `ROLLBACK`하므로 `antlab`에서도 돌린다. `events/` 파일을 읽으므로 저장소 루트에서 실행한다 |
| `12_calc_position_def.sql` | P0-10 | 시스템 판정 포지션의 유일한 정의다(세션 임시 뷰 9개 `pos_k`·`pos_ret`·`pos_window`·`pos_mkt`·`pos_series`·`pos_dd`·`pos_metric`·`pos_rank`·`pos_judge`와 세션 함수 `pos_classify`·`pos_fail`). 이 파일은 단독으로 실행하지 않고 12 두 파일이 `\ir`로 읽는다. 따라서 계산과 검증이 같은 정의(판정 함수 하나)를 읽으며, 시즌 실측 포지션(P0-20)도 이 파일을 재사용한다. 배열 입력 함수 3개(`pos_block_mkt`·`pos_max_drawdown`·`pos_metrics`, P0-20)는 파일 끝에 더했다. 시가총액 순위는 읽지 않는다 |
| `12_calc_position.sql` | P0-10 | `asset_position`의 판정 8컬럼(창·사용한 달 수·상승/하락 참여율·변동성·최대 낙폭·시스템 판정 포지션)을 **UPDATE 한 문장**으로 채운다. 판정이 비어 있는 행만 채우므로 다시 실행하면 바뀌는 행이 0이고, 이미 판정된 행이 계산값과 다르면(수익률·공식이 바뀜) 오류로 멈추고 전부 되돌린다(정정 없음). 최소 달 수(24, 오른 달 5, 빠진 달 5)에 못 미치면 8컬럼을 NULL로 두고 사유별 건수만 보고한다. 건수만 내고 값은 내지 않는다. 01 스키마와 10 적재를 먼저 실행한다 |
| `12_calc_position_check.sql` | P0-10 | 계산 검증. 다른 방법의 독립 계산 대조(사용자 정의 곱 집계·자기 조인 최고점·합·제곱합 분산식·평균의 비), 합성 손 계산 13종목(밴드 경계·환율 결합·W0·표본 표준편차·최소 달 수 경계), 판정 함수 경계 바로 위·아래 표 25사례, 풀 순위(CEIL·경계 동률·N=1), 사후 정보 배제(H9: 기준일 달 이후·창 앞을 바꿔도 불변, 창 안을 바꾸면 변하고, 창 끝을 밀면 잡힌다), 타입, 규칙 30개마다 변이(음성) 사례와 커버리지 `30/30`. 합성 행은 번호를 직접 지정해 넣고 되돌리므로 번호표를 소비하지 않아 `antlab`에서도 돌린다 |
| `13_calc_attention_def.sql` | P0-11 | 관심 지수 계산식의 유일한 정의(세션 임시 뷰 `attn_calc`). 단독 실행 대상이 아니라 13 두 파일이 `\ir`로 읽는다 — 쓰기와 검증이 같은 정의를 읽으므로 사후 정보 배제(H8) 시험이 복사본이 아니라 실제 계산식을 시험한다 |
| `13_calc_attention.sql` | P0-11 | `asset_month_return`에서 `asset_month_attention`을 계산해 채운다(종목 × 달, 수익률 행과 1:1). 없는 행만 INSERT하고 아무것도 지우거나 고치지 않으며, 다시 실행하면 새 행이 없다(멱등). 이미 있는 값이 계산값과 다르거나 DB에만 있는 행이 있으면 오류로 멈추고 전부 되돌린다. 건수만 보고하고 값은 출력하지 않는다. 01 스키마와 10 적재를 먼저 실행한다 |
| `13_calc_attention_check.sql` | P0-11 | 계산 검증. 윈도 함수로 한 독립 계산 대조, 규칙(NULL·범위·지수 식·구간 시작·NaN), 합성 손 계산 12사례, 사후 정보 배제(기준 달 뒤 수익률을 바꾸거나 지워도 그 달까지의 행이 불변), 순위·그룹 정본 쿼리를 단언한다. 규칙 18개마다 음성 사례가 있고 커버리지 `18/18`을 확인한다. 아무것도 쓰지 않고 `ROLLBACK`하므로 `antlab`에서도 돌린다. 약 1분 반 걸린다 |
| `14_stats_calendar_def.sql` | P0-12 | 통계 보드 정의(세션 임시 뷰 7개: `stat_k`·`stat_series`·`stat_month`·`stat_peak`·`stat_fwd`·`stat_obs`·`stat_board`). 단독 실행 대상이 아니라 14 두 파일이 `\ir`로 읽는다 — 출력과 검증이 같은 정의를 읽는다. 역사 7개 표만 읽고 시즌·계좌 표는 읽지 않으며 아무것도 쓰지 않는다 |
| `14_stats_calendar.sql` | P0-12 | 달력 월(1–12월)·사건 유형의 `START` 뒤 1·3개월·관심 정점 뒤 1·3개월의 평균 수익률·상승 확률·표본 수를 낸다(저장하지 않고 읽을 때 계산). 종류별 건수 표와 통계 보드(n ≥ 5인 조건만, 5 ≤ n < 10은 `일화`)를 출력하고 `ROLLBACK`하므로 `antlab`에서 몇 번이든 돌린다. 10 → 11 → 13을 먼저 실행한다 |
| `14_stats_calendar_check.sql` | P0-12 | 통계 검증. 다른 방법의 독립 계산 대조(윈도 함수 `LEAD`·정수 산술), 합성 손 계산(달력 평균·0 수익률·누적 곱·반올림 한 번), 표본 수 경계(n = 4·5·9·10), 누출(기준일 해 제외·정점 판정에 미래 값 없음·시즌 표를 읽지 않음·관측값 열 없음), 타입(부동소수·NaN 없음)을 단언한다. 규칙 20개마다 정의를 일부러 바꾼 변이 사례가 있고 커버리지 `20/20`을 확인한다. 아무것도 쓰지 않고 `ROLLBACK`한다 |
| `20_season_start.sql` | P0-14 | 연습 시즌을 한 트랜잭션으로 연다 — 연습 회원 → 시즌(D30 8컬럼·요율 3·위험 선언·시작 = 현재 포메이션을 모두 명시) → 손 경로 10턴 → 현금 계좌 + 개설 입금 `DEPOSIT` → 0턴 가격(기준일 풀 전체, 원 단위, 미국 종목은 원화 환산). 제약을 하나라도 어기면 앞에서 성공한 INSERT까지 전부 되돌아간다. 연습 회원이 이미 있으면 새 행 없이 끝나고(멱등), 이미 있는 시즌이 파일과 다르면 오류로 멈춘다. 기준일 풀에 판정이 비어 있으면 "12 계산을 먼저" 메시지로 멈춘다(종료 코드 3). 01·02·03 스키마, 10 적재, 12 판정을 먼저 실행한다. **이 파일은 손 경로의 실제 달을 담고 있다** — MVP 시즌의 경로는 저장소에 들어가지 않는다 |
| `20_season_start_check.sql` | P0-14 | 시즌 시작 검증. 설정(문서에서 다시 옮겨 적은 기대값)·D30 기본값 없음·포메이션/위험 CHECK·원자성(앞 문장까지 되돌아감)·경로(S1·S4·블록 수)·계좌(대사 규칙 1·2·3·9·15)·기준일 풀 전제(H1·H2)·0턴 가격(정수 산술 독립 계산, 풀 완전성, 미래 가격 없음)·합성 손 계산을 단언한다. 시즌을 연 뒤 언제나 참인 규칙 20개마다 음성 사례가 있고 커버리지 `20/20`을 확인한다. INSERT가 없고 `ROLLBACK`하므로 `antlab`에서도 돌린다. 출력은 건수와 판정만이다 |
| `21_team_buy_def.sql` | P0-15 | 매수 정의(세션 임시 함수 6개): 순수 계산 5개(`buy_rate`·`buy_fee`·`buy_affordable`·`buy_avg_price`·`team_qty`)와 매수 한 건의 함수 `pg_temp.order_buy`. 호출자가 트랜잭션을 열고(함수는 `BEGIN`·`COMMIT`을 하지 않는다) 안에서 `season` → `cash_account` 순으로 잠근 뒤 `orders`·`execution`·`holding`·`cash_ledger`(`BUY` → `FEE`)·`cash_account`·`lineup`을 함께 만든다. 오류는 `P0001` + HINT 종류 코드. 단독 실행 대상이 아니라 21 두 파일과 두 세션 실습이 `\ir`로 읽고, 매매(P0-19)도 재사용한다 |
| `21_team_buy.sql` | P0-15 | 연습 시즌의 0턴에 감독의 팀 8종목을 산다. 명단(종목 코드·비중 %·선언 포지션)은 파일 맨 앞 한 곳에 있고 수량은 실행할 때 0턴 가격에서 규칙으로 계산한다. 줄마다 한 트랜잭션(임시 프로시저 안의 `COMMIT`)이라 줄 k에서 실패하면 앞 줄은 남고 다시 실행하면 남은 줄만 산다(이어서 사기). 이미 같은 팀이면 새 행 없이 끝나고, 0턴 주문이 명단과 다르면 아무것도 사지 않고 멈춘다(종료 코드 3). 01·02·03 스키마, 10 적재, 12 판정, 20 시즌 시작을 먼저 실행한다. 출력은 건수와 상태만이다 |
| `21_team_buy_check.sql` | P0-15 | 팀 구성 검증. 팀 크기·명단(문서에서 다시 옮겨 적음)·수량(독립 계산)·체결가·수수료(정수 산술)·원장(유형별 진단 포함)·보유 이동평균(체결 이력 재생)·라인업·포메이션 실제 비중·함수 오류 종류·순수 계산 합성 사례를 단언한다. "팀을 구성한 뒤 언제나 참"인 규칙 22개마다 음성 사례가 있고 커버리지 `22/22`를 확인한다. INSERT가 없고 `ROLLBACK`하므로 `antlab`에서도 돌린다. 출력은 건수와 판정만이다 |
| `22_reconcile_season_def.sql` | P0-16 | 대사 정의(세션 임시 객체): 코드 표 `recon_code`(17행), 규칙 함수 15개 `recon_r01_…`–`recon_r18_…`(erd 7.5의 규칙 번호 그대로, 규칙 5·10·11·19는 머리 주석만), 판정·차이·분류를 한 곳에서 붙이는 `recon_rules(시즌, 검사 턴)`, 기대 가격 `recon_expected_price`·`recon_price`, 결과를 새 시도 번호로 남기는 `recon_log`. 단독 실행 대상이 아니라 22 두 파일과 실습 (h), 다음 기능(P0-17 턴 진행)이 `\ir`로 읽는다. 규칙 함수는 모두 `STABLE`이라 15개가 한 스냅샷을 본다 |
| `22_reconcile_season.sql` | P0-16 | 연습 시즌의 수동 검사 15개를 돌려 `reconciliation_log`에 **일치해도** 15행을 남긴다(새 시도 번호 하나). 한 트랜잭션(`season FOR UPDATE` 아래)이고 커밋한 뒤 보고한다. **다시 실행하면 시도가 늘어 행이 쌓이는 것이 정상이다**(멱등이 아님 — 로그는 "그 시점에 검사했다"는 증거). 불일치가 있으면 행을 커밋한 채 종료 코드 3으로 끝난다. 쓰는 곳은 `reconciliation_log`뿐이다 |
| `22_reconcile_season_check.sql` | P0-16 | 수동 대사 검증. 정상 데이터에서 15개 일치, 코드 목록 = 카탈로그, 로그 제약 사본(CHECK 9개), 음성 사례 20개(바꿔 보고 **기대한 규칙 집합만** 불일치로 걸리는지 — 규칙 2만 {2, 3}), 합성 단위 사례(`recon_price` 경계·반올림 한 번, `detail` 100개 제한), 커버리지 `15/15`를 단언한다. `INSERT`를 하지 않고 `ROLLBACK`해서 `antlab`에서 실행할 수 있다 |
| `23_turn_advance_def.sql` | P0-17 | 턴 진행 정의(세션 임시 객체): 가격 생성기 `ta_prices`(재귀 CTE — 저장된 0턴 가격에서 턴마다 `(1 + 현지 수익률)(1 + 환율 변화)`를 곱하고 **마지막에 한 번** 반올림), 낙폭 `ta_drawdown`(정수 산술로 한 번 반올림), 턴 스냅샷 `ta_snapshot`(골키퍼는 총액에서 다른 셋을 뺀 잔여), 규칙 11·19 `recon_r11_valuation`·`recon_r19_position_value`, 17행 묶음 `recon_rules_turn`, 쓰기 없는 전제 확인 `ta_precheck`, 트랜잭션 문 없는 `ta_apply`, 커밋·롤백을 하는 프로시저 `turn_advance`를 정의한다. 단독 실행 대상은 아니다. 23 세 파일이 22 정의 뒤에 `\ir`로 읽는다(트랜잭션 밖에서) |
| `23_turn_advance.sql` | P0-17 | 연습 시즌을 **한 턴** 앞으로 보낸다. 다음 턴 `turn_price`(풀 전체)와 `turn` 스냅샷을 만든 뒤 대사 17개를 돌린다. 모두 일치하면 `reconciliation_log` 17행을 남기고 현재 턴을 올려 커밋한다(마지막 턴이면 `FINISHED`). 하나라도 불일치하면 **실제로 `ROLLBACK`** 한 뒤 새 트랜잭션에서 로그 17행만 남기고 종료 코드 3으로 끝난다. 10턴은 셸 반복으로 돌린다. 끝난 시즌에서는 "이미 끝났다"고 알리고 종료 코드 0으로 끝낸다. `-v ON_ERROR_STOP=1`이 필요하다 |
| `23_turn_advance_check.sql` | P0-17 | 턴 진행을 검증한다. 진행 상태 계약(`turn`·`turn_price`·로그 17개 묶음), 가격 네 구현 대조(재귀 CTE · 창 함수 배열 · 절차형 반복 곱 · 저장값, "매달 반올림"과 다른 칸 수), 전 턴 스냅샷 재구성(체결 이력에서), 사후 정보 배제, 규칙 11·12·19 음성 사례(규칙 12는 `turn_price` 모든 행), 거부 사례(쓰기 전), 낙폭 합성 단위 사례를 단언한다. 0턴부터 `FINISHED`까지 어느 시점에서도 `PASS`한다. `INSERT`를 하지 않고 `ROLLBACK`하므로 `antlab`에서 돌릴 수 있다 |
| `23_turn_advance_drill.sql` | P0-17 | 롤백 드릴이다. 커밋된 상태를 일부러 깨뜨려(보유 종목의 라인업 구간 닫기) 턴 전환이 실제 `ROLLBACK`으로 되돌려지고 `reconciliation_log`에만 17행(불일치 규칙 17·19)이 남는지 확인한다. 고치고 다시 실행하면 성공하고 시도 번호가 2인지도 확인한다. **버리는 DB 전용** — `-v drill=1`이 필요하고(다른 값은 멈춘다) DB 이름이 `antlab`·`antlab_test`면 멈춘다 |
| `24_turn_feed_def.sql` | P0-18 | 턴 피드 생성의 유일한 정의(세션 임시 객체, 규칙 버전 `p0-18:v2`). 제한 입력(관심 지수·사건 태그·직전 턴의 이야기)만 받아 결정적으로 5–15행을 만든다. 정책값(관심 등급, 가중 비복원 추출, 재선택, ECHO·SPREAD·FADE)과 오류 힌트 코드는 파일 머리에 있다. `feed/*.csv`를 상대 경로로 읽으므로 저장소 루트에서 실행한다. 23 정의가 이 파일의 함수를 부르므로 23 실행 파일이 22 → 24 → 23 순서로 읽는다 |
| `24_turn_feed.sql` | P0-18 | 연습 시즌의 현재 턴 피드가 몇 행이고 빈칸이 없는지, 저장 = 재생성인지를 건수로 낸다. 아무것도 쓰지 않는다. 피드는 이 파일이 아니라 23이 턴마다 만든다 |
| `24_turn_feed_check.sql` | P0-18 | 피드 검증. 저장 행을 독립 계산과 대조하고, 생성기를 다시 돌려 저장 = 재생성과 사후 정보 차단을 확인한다. 변조 시험은 하위 블록에서 하고 되돌린다(양성 대조군이 달라지지 않으면 음성 시험도 무효로 FAIL). 쓰기 없이 `ROLLBACK`하므로 `antlab`에서 실행할 수 있다. 23을 한 번 이상 실행한 뒤 턴 중간에도 `PASS`다 |
| `25_trade_formation_def.sql` | P0-19 | 턴 사이 매매·전술 변경을 세션 임시 함수로 정의한다. 공통 입구 `tr_open`은 격리 수준 확인 → `season` 잠금 → 상태·턴 확인 → `cash_account` 잠금 → 최신 원장 확인 순서로 처리한다. 이 파일은 출발점 검사 `tr_source`, 시각 확정 `tr_event_time`, 마지막 변경 확인 `tr_last_change`, 쓰기 함수 `trade_buy`·`trade_sell`·`formation_change`·`lineup_change`, 조회 함수 `formation_costs`를 제공한다. 21의 순수 계산(`buy_rate`·`buy_fee`·`buy_avg_price`·`buy_affordable`)을 재사용하므로 21 정의를 먼저 `\ir`한다. 호출자가 트랜잭션을 연다. 오류는 `P0001` + HINT(`INSUFFICIENT_HOLDING`·`INVALID_FEED_REFERENCE`·`INVALID_FORMATION`·`LINEUP_MISMATCH`·`EVENT_TIME_CONFLICT` 추가)로 알린다. 21·22·23 정의는 고치지 않는다 |
| `25_trade_formation.sql` | P0-19 | 5턴 뒤 시나리오를 한 트랜잭션으로 실행한다. 먼저 변경 전 DIRECT 부분 매도를 하고, 전술을 1회 변경한다(수비 −10 · 공격 +10). 이어서 변경 뒤 FEED 매수(현재 턴 피드의 미보유 종목), 전량 매도, RANKING 추가 매수를 실행하고, 부분 매도했던 수비 종목의 선언을 미드필더로 변경한다. 종목·수량은 저장 상태에서 규칙으로 고른다(선택 기준은 파일 안 주석). 찾지 못하면 쓰기 전에 멈춘다. **멱등이 아니다** — 1턴 이후 주문이나 전술 변경이 이미 있으면 "이미 적용된 상태"로 멈춘다(종료 코드 3). 현재 턴이 5가 아니어도 멈춘다. 끝에서 22 수동 검사 15개가 모두 일치하는지 단언한다. 출력은 건수만 |
| `25_trade_formation_check.sql` | P0-19 | 매매·전술 변경을 검증한다. 체결 이력을 순서대로 재생해 수량·평균단가·매도 실현 손익을 독립적으로 계산하고 저장 상태와 대조한다. 주문·체결 모양, 수수료 독립 식, 원장 유형·부호·순서·잔액 연쇄, 라인업 스냅샷(`start_turn < k ≤ end_turn`)·구간 겹침, 전술 변경 사슬·주문 연결(규칙 16), 출발점·피드(S6·S7), 변경 비용 조회, 22 수동 검사 15개, 거부 사례(쓰기 전, 행 수·identity 불변)도 확인한다. 규칙마다 일부러 어긴 음성 사례가 있으며, 대상이 없는 포트폴리오는 "적용 불가" 정보 행으로 남긴다(통과 사례 수·커버리지 요구에서 뺀다). 1턴 이후 주문이 하나도 없으면 0절 FAIL이다. `INSERT`가 없고 `ROLLBACK`하므로 `antlab`에서도 실행할 수 있다. 마지막 절(10)이 서브트랜잭션 밖에서 `lineup_change`를 부르므로 `season` 행 잠금은 파일 끝 `ROLLBACK`까지 유지된다 — 실행 중에는 다른 세션을 쓰지 않는다 |
| `25_trade_formation_drill.sql` | P0-19 | 매매 드릴이다(**버리는 DB 전용**). 다음 조건을 모두 만족해야 한다: `-v drill=1`을 지정하고, DB 이름이 `antlab`·`antlab_test`가 아니며, `ON_ERROR_STOP`을 켠다. 사례 `-v drill_case=`는 allcash(전액 현금)·replace(초기 팀 전부 교체)·extremes(미드필더 없음·현금 여유 없음·부분 매도 뒤 포지션 변경)·rebuy(같은 턴 매수→전량 매도·재매수·전술 변경 둘과 주문 연결·변경 비용)·changeonly(주문 없는 변경과 포지션 변경)·atomic(중간 실패 복원)·alert(합성 NUDGE)·pastfeed(지난 턴 피드에서 시작한 주문)·race(두 세션 경합의 한 세션)로 나뉜다. 아래 실습 (l)·(m)을 참고한다 |
| `26_season_report_def.sql` | P0-20 | 시즌 성적표의 유일한 정의다(세션 임시 함수): `sr_report`(1행 37열)·`sr_turns`·`sr_season_positions`·`sr_players`·`sr_change_costs`와 내부 보조. 맨 앞에서 12·21·25 정의를 `\ir`로 직접 읽는다(골든 하네스가 정의 파일 하나만 받기 때문이다). 머리에는 Phase 0에서 뺀 항목 표(X1–X12)가 있다 |
| `26_season_report.sql` | P0-20 | 연습 시즌의 성적표를 출력한다(읽기 전용). 진행 중이면 "잠정" 한 줄을 붙인다. 값은 로컬에서만 확인하고 PR·이슈에는 붙이지 않는다 |
| `26_season_report_check.sql` | P0-20 | 성적표를 검증한다. 원장·유리수·두 번 도는 공식·소수 40자리·재귀 CTE 등 독립 경로로 대조한다. 또한 12 배열 함수가 12 뷰와 같은 값을 내는지 모든 기준일 행에서 확인한다. 변이 사례·사후 정보 배제·거부 사례·합성 손 계산도 확인한다 |
| `90_full_run_check.sql` | P0-21 | 전체 순서의 **최종 판정**이다(짝이 되는 실행 파일이 없는 `_check`). 10턴을 끝내고 마지막 수동 대사(22)까지 돌린 연습 시즌에서 읽기만 한다: 마지막 수동 대사 15행이 모두 `MATCH`, 로그 전체에 `MISMATCH` 0행, 시즌 `FINISHED`, 턴마다 17행 묶음이 있고 범위 밖 로그 0행, 남은 주문·전술 변경 0, 1턴 이후 주문·전술 변경이 있음(25가 실행됨), 지금 데이터를 22의 규칙 함수로 다시 대사해 15규칙 모두 `MATCH`임을 모두 만족할 때만 `p0_21_check`가 `PASS`다. 판정마다 일부러 바꾼 상태에서 실패하는지(음성 사례 7/7)도 확인한다. `INSERT` 없이 `ROLLBACK`하므로 `antlab`에서도 돌린다. 새 계산은 하지 않는다 |

Java `MoneyMathSqlParityTest`(P0-26)는 `21_team_buy_def.sql`의 순수 함수 5개를 `MoneyMath`와 같은 합성 입력으로 대조한다. 함수 원문은 같은 PostgreSQL 연결의 `pg_temp`에 설치하고, `order_buy`나 소유자 DB는 사용하지 않는다. 시험 컨테이너는 `postgres:17` 이미지를 사용한다.

- 이름 규칙은 `{실행 순서 두 자리}_{종류}_{영역}.sql`이고, 검증은 같은 이름 뒤에 `_check`를 붙인다.
- **새 기능이 파일을 더하는 규칙:** 기능별 파일을 위 표에 추가하고, 아래 「전체 순서」 표 한 곳에 줄을 더한다((b′) 등은 표를 가리키므로 따로 더하지 않는다). 다른 기능의 파일은 고치지 않는다. **예외(P0-19):** 선행 기능의 하류 약속(P0-15·P0-17 리뷰)에 따라 `21_team_buy_check.sql`과 `23_turn_advance_check.sql`의 음성 사례만 "포트폴리오에 대상이 있을 때만 적용"하도록 일반화했다. 20·22 파일과 모든 정의·스키마는 고치지 않았다. **예외(P0-20):** erd 9절 P0-10 ③이 맡긴 어댑터로 `12_calc_position_def.sql`에 함수 3개만 덧붙였다(뷰·12 계산·12 검증은 그대로). **예외(P0-21):** 이슈 #63·#71의 요구로 실행 파일 5개(21·22·23·25·26)와 드릴 2개의 `ON_ERROR_STOP` 가드만 `\quit`(종료 코드 0) → 종료 코드 3으로 바꾸고(23의 "이미 끝났다" 종료 코드 0은 그대로), 하류 약속에 따라 `24_turn_feed_check.sql`의 맨 끝을 판정 열 한 칸(`p0_18_check`)으로 맞췄다. 계산·정의는 고치지 않았다.
- 전체 순서: 아래 「전체 순서」 표가 한 곳의 정본이다. 뒤 번호 파일은 앞 번호 파일의 테이블을 참조하므로 순서가 곧 의존이다. 검증 파일(`_check`)은 선택이지만 표 순서에서는 필수 단계다. 기능은 자기 파일의 줄만 더한다.
- 끝난 시즌 뒤 회귀 순서: 23을 `path_length`번 돌려 `FINISHED`가 된 뒤 20 · 21을 다시 실행해도 새 행이 없고(종료 코드 0), 20 · 21 · 22 · 23 검증이 모두 `PASS`한다. 22 수동 검사는 끝난 시즌에서도 돌며 15개가 `turn_no` = `path_length`로 일치한다. 끝난 시즌에서 `order_buy`는 `SEASON_NOT_ACTIVE`로 쓰기 전에 거부된다(23 검증 07절).
- 스키마 파일은 `BEGIN` ... `COMMIT`으로 감싸 있어 중간에 실패하면 전부 없던 일이 된다. `DROP TABLE`에 `CASCADE`를 쓰지 않으므로, 뒤 번호 파일의 테이블이 이미 있는 DB에서 앞 번호 파일만 다시 실행하면 `2BP01`(dependent_objects_still_exist) 오류로 멈춘다. 안전한 실패이며, 전체를 다시 만들 때는 아래 "DB 다시 만들기"를 쓴다.

### 전체 순서

**표를 위에서 아래로 한 번 따라 하면** 빈 DB에서 스키마 → 적재 → 파생 → 시즌 → 10턴(5턴 매매 포함) → 성적표가 오류 없이 끝나고, 마지막 단계(S37)의 한 줄 `p0_21_check = PASS`가 "대사 불일치 0건"을 증명한다. 같은 순서를 SQL 골든 하네스 `README_REGRESSION`이 자동으로 돈다(아래 「SQL 골든 하네스」). 표가 정본이고 그 아래 한 번에 따라 하기 블록은 같은 순서를 옮긴 것이다.

- **DB 칸:** 모든 단계는 **새 DB 하나**에서 이 순서대로 한다(아래 블록의 `antlab_full`). 이미 연습 시즌이 있는 `antlab`에서는 S01이 `2BP01`로 멈추고, (b′)로 지우면 연습 시즌이 사라지므로 처음 따라 할 때는 새 이름을 쓴다. `createdb`가 이미 있다는 오류로 멈추면 빈 DB가 아니라는 뜻이다.
- **다시 실행하면:** 몇 번이든 = 읽기뿐이거나 새 행이 없다. 22는 실행마다 로그 15행이 쌓이고(정상) 23은 실행마다 한 턴 진행한다. 25는 다시 돌리면 종료 코드 3으로 멈춘다(이미 적용).
- **성공 판정:** `p0_xx_check`는 출력 끝 두 줄이 그 열 이름과 `PASS`라는 뜻이다(`| tail -2`). 종료 0은 종료 코드다.
- **골든 칸:** 이 파일의 행을 Java 쪽이 같은 값으로 만드는지 대조할 기능(자세한 표는 「SQL 골든 하네스」의 「골든 파일」).

| 단계 | 파일 | 선행(필수) | 다시 실행하면 | 성공 판정 | 골든 |
|---|---|---|---|---|---|
| S01 | `01_schema_history.sql` | 빈 DB, `timezone` | 02가 있으면 `2BP01`로 멈춘다(지우려면 (b′)). 01만 있으면 8개를 지우고 다시 | 종료 0 | 스키마 일치(V1) |
| S02 | `01_schema_history_check.sql` | S01, **표본 적재 전**(빈 테이블 전제) | 몇 번이든 | `p0_05_check` | — |
| S03 | `02_schema_season.sql` | S01 | 9개를 지우고 다시, 03이 있으면 `2BP01` | 종료 0 | V2 |
| S04 | `02_schema_season_check.sql` | S03, 적재 전 | 몇 번이든 | `p0_06_check` | — |
| S05 | `03_schema_account.sql` | S03 | 5개를 지우고 다시 | 종료 0 | V3 |
| S06 | `03_schema_account_check.sql` | S05, 적재 전 | 몇 번이든 | `p0_07_check` | — |
| S07 | `10_load_sample.sql` | S01, 무시 CSV 3개, 저장소 루트 | 새 행 0(멱등) | 종료 0 | 하네스 입력 |
| S08 | `10_load_sample_check.sql` | S07, `hand_check.csv` | 몇 번이든 | `p0_08_check` | — |
| S09 | `11_load_events.sql` | S07, 루트 | 새 행 0 | 종료 0 | 하네스 입력 |
| S10 | `11_load_events_check.sql` | S09, 루트 | 몇 번이든 | `p0_09_check` | — |
| S11 | `12_calc_position.sql` | S07 | 바뀌는 행 0 | 종료 0 | 12 정의(26이 읽음) |
| S12 | `12_calc_position_check.sql` | S11 | 몇 번이든 | `p0_10_check` | — |
| S13 | `13_calc_attention.sql` | S07, 루트 | 새 행 0 | 종료 0 | 23 입력 |
| S14 | `13_calc_attention_check.sql` | S13 | 몇 번이든 (이 단계가 가장 오래 걸린다) | `p0_11_check` | — |
| S15 | `14_stats_calendar.sql` | S07·S09·S13 | 쓰기 없음 | 종료 0 | — |
| S16 | `14_stats_calendar_check.sql` | S15, 루트 | 몇 번이든 | `p0_12_check` | — |
| S17 | `20_season_start.sql` | S05·S07·S11 | 새 행 0 | 종료 0 | P0-30 |
| S18 | `20_season_start_check.sql` | S17 | 몇 번이든 | `p0_14_check` | — |
| S19 | `21_team_buy.sql` | S17 | 새 행 0(`이미 같은 팀`) | 종료 0 | P0-31 완료(21 정의는 P0-26에서 대조) |
| S20 | `21_team_buy_check.sql` | S19 | 몇 번이든 | `p0_15_check` | — |
| S21 | `22_reconcile_season.sql` | S19 | **15행이 쌓인다(정상)** | 종료 0, `match_count` 15 · `mismatch_rules` 0 | P0-32(22 정의) |
| S22 | `22_reconcile_season_check.sql` | S19 | 몇 번이든 | `p0_16_check` | — |
| S23 | `23_turn_advance.sql` **× 5** | S19, S09·S13(피드 입력), 루트 | 실행마다 한 턴 | 각 종료 0 | P0-33(23·24 정의) |
| S24 | `23_turn_advance_check.sql` | S23(턴 전환 직후) | 몇 번이든 | `p0_17_check` | — |
| S25 | `24_turn_feed.sql` | S23, 루트 | 쓰기 없음 | 종료 0, 빈칸 0·저장 = 재생성 | — |
| S26 | `24_turn_feed_check.sql` | S23, 루트 | 몇 번이든 | `p0_18_check` | — |
| S27 | `25_trade_formation.sql` | 현재 턴 = 5 | **종료 코드 3(이미 적용)** — 다시 돌리지 않는다 | 종료 0 | P0-36·37(25 정의) |
| S28 | `25_trade_formation_check.sql` | S27 | 몇 번이든 | `p0_19_check` | — |
| S29 | 턴 중간 점검: `20`·`21`·`22`·`24` 검증, 그리고 `22_reconcile_season.sql` | S27(턴 중간) | 22만 15행이 쌓인다 | `p0_14`·`p0_15`·`p0_16`·`p0_18`, 그리고 22의 `match_count` 15 · `mismatch_rules` 0 | — |
| S30 | `26_season_report_check.sql` | S27(턴 중간 — 잠정) | 몇 번이든 | `p0_20_check` | — |
| S31 | `23_turn_advance.sql` **× 5** | S27 | 마지막에 `FINISHED` | 각 종료 0 | — |
| S32 | `23`·`24`·`25` 검증 | S31 | 몇 번이든 | `p0_17`·`p0_18`·`p0_19` | — |
| S33 | `20`·`21` 재실행, `23` 재실행 | S31 | 새 행 0 / "이미 끝났다" 종료 0 | 종료 0 | 끝난 뒤 멱등 |
| S34 | `20`·`21` 검증, `22_reconcile_season.sql`, `22` 검증 | S33 | 22만 15행이 쌓인다 | `p0_14`·`p0_15`, 22의 `match_count` 15 · `mismatch_rules` 0, `p0_16` | — |
| S35 | `26_season_report.sql` | S31 | 읽기 전용 | 종료 0(확정) | P0-38(26 정의) |
| S36 | `26_season_report_check.sql` | S31 | 몇 번이든 | `p0_20_check` | — |
| S37 | `90_full_run_check.sql` | S34(마지막 22 실행 뒤) | 몇 번이든 | `p0_21_check` | `OneSeasonE2ETest`(E2E SQL criterion / E2E의 SQL 쪽 기준) |

전체 순서에 넣지 않는 것: `23_turn_advance_drill.sql`·`25_trade_formation_drill.sql`(버리는 DB 전용 — 실습 (i)·(l)·(m)), 실습 (e)–(n).

- **턴 중간에 일부러 넣지 않은 검증:** S27 직후에는 `23_turn_advance_check.sql`을 돌리지 않는다. 현재 턴의 주문·라인업 구간이 범위 밖이라 0절에서 멈춘다(정상). `24_turn_feed_check.sql`은 턴 중간에도 `PASS`라 S29에 있다.
- **"마지막 22 실행"의 뜻:** 시도 번호(`attempt_no`)는 (시즌, 턴)마다 새로 매겨진다. S34의 수동 대사는 턴 10에서 턴 전환 묶음(17행) 다음 시도가 된다. 그래서 90 검증은 코드 15개짜리 묶음 중 `id`가 가장 큰 것을 "마지막 22 실행"으로 본다(시도 번호로 고르지 않는다). 수동 15행과 턴 전환 17행은 코드 집합으로 구분한다.
- **90 검증이 FAIL이면:** 출력의 FAIL 행이 J1–J7 중 어느 판정인지 알려 준다(「결과 읽는 법」의 90 소절). 진행 중인 시즌에서는 0절이 FAIL이고 여기서 멈춘다(끝난 시즌 전제).
- **#62(21 검증의 대사 쿼리를 22 호출로 바꾸자는 제안)는 하지 않았다.** 21 검증은 팀 구성 순간의 더 엄격한 사본(매수만·`BUY` 1건·`FEE` 행 수)이고, 21 검증이 22 정의를 읽으면 "앞 번호가 뒤 번호를 읽지 않는다"는 순서 규칙이 깨진다.
- **걸리는 시간:** 소유자 맥 기준 처음부터 끝까지 약 2분이다(컴퓨터마다 다르다). S14가 가장 오래(1–2분) 걸리고 나머지는 대부분 몇 초다.

**한 번에 따라 하기.** 위 표를 그대로 옮긴 것이다. 저장소 루트에서 아래 블록을 그대로 붙여 넣는다(zsh·bash 어디서나). 블록 전체가 `bash` 하나에서 돌고, 어느 단계든 오류가 나면 그 `bash`만 멈춘다 — 지금 셸은 닫히지 않고, 멈춘 뒤 남은 줄이 지금 셸에서 실행되지도 않는다. 검증은 끝 두 줄만 보인다 — 열 이름과 `PASS`를 눈으로 확인한다.

```bash
bash <<'EOF'
set -eo pipefail
DB=antlab_full
createdb -E UTF8 -T template0 "$DB"            # 이미 있으면 여기서 멈춘다 (빈 DB 보장)
psql -X -d postgres -c "ALTER DATABASE $DB SET timezone TO 'Asia/Seoul'"
run() { psql -X -q -v ON_ERROR_STOP=1 -d "${DB:?}" -f "db/$1"; }
chk() { echo "== $1"; psql -X -q -A -P footer=off -v ON_ERROR_STOP=1 -d "${DB:?}" -f "db/$1" | tail -2; }

run 01_schema_history.sql;       chk 01_schema_history_check.sql          # S01–S02
run 02_schema_season.sql;        chk 02_schema_season_check.sql           # S03–S04
run 03_schema_account.sql;       chk 03_schema_account_check.sql          # S05–S06
run 10_load_sample.sql;          chk 10_load_sample_check.sql             # S07–S08
run 11_load_events.sql;          chk 11_load_events_check.sql             # S09–S10
run 12_calc_position.sql;        chk 12_calc_position_check.sql           # S11–S12
run 13_calc_attention.sql;       chk 13_calc_attention_check.sql          # S13–S14 (1–2분)
run 14_stats_calendar.sql;       chk 14_stats_calendar_check.sql          # S15–S16
run 20_season_start.sql;         chk 20_season_start_check.sql            # S17–S18
run 21_team_buy.sql;             chk 21_team_buy_check.sql                # S19–S20
run 22_reconcile_season.sql;     chk 22_reconcile_season_check.sql        # S21–S22
for i in 1 2 3 4 5; do run 23_turn_advance.sql; done                     # S23
chk 23_turn_advance_check.sql                                             # S24
run 24_turn_feed.sql;            chk 24_turn_feed_check.sql               # S25–S26
run 25_trade_formation.sql;      chk 25_trade_formation_check.sql         # S27–S28
for c in 20_season_start_check 21_team_buy_check 22_reconcile_season_check 24_turn_feed_check; do chk $c.sql; done   # S29
run 22_reconcile_season.sql                                               # S29 (수동 대사 15행)
chk 26_season_report_check.sql                                            # S30
for i in 1 2 3 4 5; do run 23_turn_advance.sql; done                     # S31
for c in 23_turn_advance_check 24_turn_feed_check 25_trade_formation_check; do chk $c.sql; done   # S32
run 20_season_start.sql; run 21_team_buy.sql; run 23_turn_advance.sql     # S33 ("이미 끝났다"까지 종료 0)
chk 20_season_start_check.sql; chk 21_team_buy_check.sql                  # S34
run 22_reconcile_season.sql;     chk 22_reconcile_season_check.sql        # S34
run 26_season_report.sql;        chk 26_season_report_check.sql           # S35–S36
chk 90_full_run_check.sql                                                 # S37 — p0_21_check / PASS
EOF
```

다 쓴 뒤 지우려면 `dropdb antlab_full`. 이어서 「실습 문제」와 실습 (e)–(n)은 이 DB(읽기)나 이 DB의 복사본에서 한다.


## 실행 환경

- PostgreSQL 17 (17.11에서 확인). DB 인코딩은 `UTF8`.
- 기본 서버는 저장소 루트의 `compose.yaml`(Docker Compose)이다. Docker Desktop이 켜져 있어야 한다. 호스트에서는 이 컴퓨터(127.0.0.1)의 5432 포트로만 받고(같은 compose 네트워크의 컨테이너는 직접 접속한다) 비밀번호는 쓰지 않는다(로컬 실습 전용). 데이터는 Docker 볼륨 `db-data`(실제 이름 `antlab_db-data`)에 남는다. 서비스 이름은 `db`, 프로젝트 이름은 `antlab`이다.
  - 켜기: `docker compose up -d` (처음엔 이미지를 받는다. 준비되면 `docker compose ps`가 `healthy`로 보인다)
  - 끄기: `docker compose stop` (데이터는 남는다). 다시 켜기는 `docker compose start`.
  - `docker compose down -v`는 볼륨까지 지워 `antlab`·`antlab_test`의 모든 데이터가 사라진다. 다 지우고 새로 시작할 때만 쓴다.
  - 접속 환경 변수(터미널마다 한 번): `export PGHOST=localhost PGPORT=5432 PGUSER=$USER`. 아래 명령의 `-h`·`-U` 없이 이 값으로 접속한다.
  - `psql`·`createdb` 같은 클라이언트는 따로 필요하다(Homebrew `postgresql@17` 또는 `libpq`). 서버는 Homebrew 서비스로 띄우지 않는다. 같은 5432를 쓰므로 Homebrew 서버와 Compose 서버를 동시에 켜지 않는다.
  - 포트를 바꿔야 하면(5432가 이미 쓰이는 경우 등) `ANTLAB_DB_PORT=55432 docker compose up -d` 뒤 `PGPORT`도 같은 값으로 맞춘다. 이 변수는 그 명령에만 적용되므로 계속 다른 포트를 쓰려면 루트 `.env`(git이 무시한다)에 `ANTLAB_DB_PORT=55432`를 둔다. 셸 변수가 `.env`보다 우선한다. 시험 프로젝트를 다시 `up`할 때도 같은 `ANTLAB_DB_PORT`를 붙이고, 잠깐 멈출 때는 `stop`/`start`를 쓴다. 시험은 `ANTLAB_DB_PORT=55432 docker compose -p antlab-qa up -d`로 따로 띄우고 끝나면 `docker compose -p antlab-qa down -v`로 그 프로젝트만 지운다.
  - 예전 초안(컨테이너 `antlab-postgres`, 볼륨 `ant-lab_antlab-pg17`)에서 옮기는 순서는 PR #41 본문을 따른다(한 번만 하는 일).
- DB `timezone`은 `Asia/Seoul`이어야 한다. 시각 컬럼은 `TIMESTAMPTZ`라 순간 자체는 시간대와 무관하지만, 표시와 `::date` 변환이 이 설정을 따른다. 검증 SQL의 0절이 확인하고 다르면 FAIL이다. 아래 (a) 블록이 DB에 설정을 심는다(새로 연 접속부터 적용).
- `psql` 명령은 `-X`(`~/.psqlrc`를 읽지 않음)와 `-v ON_ERROR_STOP=1`(오류에서 멈추고 종료 코드를 0이 아닌 값으로)을 붙인다. `psql -1`(--single-transaction)은 쓰지 않는다. 파일 안에 `BEGIN`이 있어 겹치면 경고가 난다.
- 접속 옵션(`-h`, `-U` 등)은 환경에 맞게 덧붙인다. 아래 명령은 기본 접속을 가정한다.
- 명령줄이 기준이다. VS Code의 SQL 확장(예: PostgreSQL·SQLTools 확장)으로 파일을 실행하면 확장에 따라 오류가 난 문장 뒤를 계속 실행하거나 결과 집합 일부만 보여 줄 수 있어, 검증 파일의 판정은 명령줄 결과를 믿는다. VS Code에서는 통합 터미널(`Ctrl` + `` ` ``)에서 아래 psql 명령을 그대로 실행한다.

확인:

```bash
psql -X -d postgres -c "SELECT version(); SHOW timezone;"
```

확인 목록(전체 순서를 시작하기 전에 한 번):

- PostgreSQL 17.x이고 DB 인코딩이 `UTF8`이다(`SHOW server_version; SHOW server_encoding;`).
- DB `timezone`이 `Asia/Seoul`이다(`SHOW timezone;` — 새 DB는 위 블록의 `ALTER DATABASE`로 심는다).
- 모든 실행은 **저장소 루트**에서 한다. 적재(`\copy`)와 피드 정의가 상대 경로로 파일을 읽는다.

## 실행 방법

DB는 실행할 때 고른다(파일에 DB 이름이 없다). 실습용 `antlab`과 검증용 `antlab_test`를 따로 둔다. 검증 SQL은 identity(번호표) 값을 소비하므로 **검증용 DB에서만** 돌린다. 저장소 루트에서 실행한다.

**(a) 처음 한 번 — DB 만들기.** 네 줄은 따로 실행한다(한 줄이 실패해도 다른 줄은 그대로 돈다. 이미 있는 DB의 `createdb`는 오류만 내고 아무것도 바꾸지 않는다).

```bash
createdb -E UTF8 -T template0 antlab
createdb -E UTF8 -T template0 antlab_test
psql -X -d postgres -c "ALTER DATABASE antlab SET timezone TO 'Asia/Seoul'"
psql -X -d postgres -c "ALTER DATABASE antlab_test SET timezone TO 'Asia/Seoul'"
```

**(b) 실습 DB 스키마 — `antlab`의 해당 파일 테이블(01은 8개, 02는 9개, 03은 5개)과 데이터를 모두 지운다.** 표본 적재(P0-08) 이후 01·02 줄을 다시 실행하면 적재한 데이터가 사라진다. 검증만 할 때는 (c)만 실행한다. 02가 이미 있는 DB에서 01 줄을, 03이 이미 있는 DB에서 02 줄을 다시 실행하면 `2BP01`로 멈춘다(뒤 번호 파일의 FK가 앞 번호 테이블을 가리킨다). 마지막 번호의 줄만 다시 실행하거나 (b′)를 쓴다. 적재 뒤에 03만 더할 때는 03 줄만 실행한다(03은 01·02 테이블의 행을 건드리지 않아 역사 데이터는 그대로다).

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/01_schema_history.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/02_schema_season.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/03_schema_account.sql
```

**(b′) 실습 DB 다시 만들기 — `antlab` 전체를 지우고 처음부터.** 뒤 번호 파일의 테이블이 있어 (b)가 `2BP01`로 멈출 때 쓴다. `antlab`의 모든 데이터가 사라진다.

```bash
dropdb antlab
createdb -E UTF8 -T template0 antlab
psql -X -d postgres -c "ALTER DATABASE antlab SET timezone TO 'Asia/Seoul'"
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/01_schema_history.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/02_schema_season.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/03_schema_account.sql
```

**(b″) 표본은 `antlab`(작업 DB)에 적재한다. API DB `antlab_api`는 위 「API DB와 실습 DB」 절의 순서를 따른다.** 검증용 `antlab_test`에는 적재하지 않는다(`01_schema_history_check.sql`·`02_schema_season_check.sql`·`03_schema_account_check.sql` 0절이 빈 테이블을 요구하므로 적재된 DB에서는 셋 다 FAIL이다). 적재 과정에서 기존 데이터는 지워지지 않으며 두 번째 실행에서는 새 행이 생기지 않는다.

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/10_load_sample.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/10_load_sample_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/11_load_events.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/11_load_events_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/12_calc_position.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/12_calc_position_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/13_calc_attention.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/13_calc_attention_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/14_stats_calendar.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/14_stats_calendar_check.sql
```

이어서 처음부터 끝까지 따라 하려면 위 「전체 순서」 표의 S07부터 실행한다. 표의 블록은 `antlab`을 지우지 않도록 새 DB(`antlab_full`)를 쓴다.

열 명령 모두 저장소 루트에서 실행한다(`\copy` 경로가 실행한 디렉터리 기준이다). 시세·환율 파생 CSV가 없으면 적재가 첫 줄에서 멈추고, `hand_check.csv`가 없으면 검증 6절이 FAIL이다. 둘 다 `db/sample/README.md`의 "표본 만들기"를 먼저 하라는 신호다.

11 적재는 10 적재가 끝난 DB에서만 돈다(비어 있으면 "10 적재를 먼저" 메시지로 멈춘다). 11 검증은 `events/` 파일을 읽으므로 이것도 저장소 루트에서 실행한다.

**(b‴) 시즌 시작 — `antlab`(작업 DB)에만.** 10 적재와 12 판정(P0-10)이 끝난 뒤 실행한다. 한 트랜잭션이라 중간에 실패하면 전부 없던 일이 되고, 두 번째 실행에는 새 행이 없다.

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/20_season_start.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/20_season_start_check.sql
```

연습 시즌은 시즌 번호가 아니라 이메일로 찾는다(번호표는 롤백되지 않아 DB마다 다를 수 있다).

```sql
SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
```

**(b⁗) 팀 구성 — `antlab`(작업 DB)에만.** 20 시즌 시작 뒤에 실행한다. 줄마다 한 트랜잭션이라 중간에 실패하면 앞 줄은 남고, 다시 실행하면 남은 줄만 산다. 두 번째 실행에는 새 행이 없다.

```bash
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/21_team_buy.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/21_team_buy_check.sql
```

- 팀 명단은 `21_team_buy.sql` 맨 앞 `[팀 명단 한 곳]`에만 있다(한 줄에 한 종목: 시장, 종목 코드, 비중 %, 선언 포지션). 수량은 적지 않는다 — 실행할 때 `floor(줄 예산 ÷ (0턴 체결가 × (1 + 요율)))`로 계산한다(줄 예산 = 초기 자금 × 비중 ÷ 100, 내림). 수량이 가격을 역산하게 하므로 저장소 파일에 적지 않는다.
- 선언 포지션은 감독이 정한다. 시스템 판정(`asset_position.system_position`)은 제안이고 복사하지 않는다. 선언이 판정과 다른 줄이 있어야 P0-20의 역할 수행이 되물을 거리가 생긴다.
- **경로의 달을 기억해 고르지 않는다.** 팀은 역할·분산·비용으로 고른다. 손 경로의 실제 달은 `20_season_start.sql`에 있지만 팀을 고를 때 그 달의 시장을 보거나 떠올리지 않는다(PLAN §4.5, 강제할 수 없는 약속).
- 비중은 선언 포지션의 4분류(골키퍼·수비·미드필더·공격)로 센다. 골키퍼 목표는 골키퍼 종목 줄 비중 합 + 현금 목표이고, 현금은 골키퍼다. 분류 합이 시즌의 시작 포메이션과 다르면 매수 전에 멈춘다.
- 두 실행이 겹치면 줄마다 시즌 잠금을 잡은 뒤 다시 읽어, 같은 줄을 두 번 사지 않고 다른 명단과 기록이 섞이지 않는다. 자기 실행 목록의 줄을 상대가 먼저 샀거나 이 시즌의 0턴 주문이 명단과 달라졌으면 그 줄에서 멈춘다(종료 코드 3). 상대가 다 산 뒤에 목록을 만들었으면 멈추지 않는다. 멈춘 쪽은 같은 명령을 다시 실행한다 — 상대가 다 샀으면 `이미 같은 팀`, 일부만 샀으면 `이어서 삼`, 다른 명단이면 비교에서 멈춘다. 보고 1의 `*_inserted`는 이 시즌 전체의 변화라서 겹친 실행이 넣은 행도 센다.
- `-v ON_ERROR_STOP=1` 없이 실행하면 맨 앞 가드가 안내만 출력하고 아무것도 하지 않고 종료 코드 3으로 끝난다(줄마다 `COMMIT`하므로 단언이 실패해도 매수가 이어지면 안 된다).
- 멈추는 경우(모두 종료 코드 3): `연습 시즌이 0 개다`는 20을 먼저 실행하지 않은 것이다. `명단·전제가 맞지 않아 아무것도 사지 않고 멈춘다`는 명단 크기(6–8)·분류 합·풀 밖 종목·수량 0(1주가 줄 예산보다 비쌈 — 비중을 늘리거나 종목을 바꾼다)·이미 있는 0턴 주문과 명단의 차이다. 첫 20건의 줄과 종목 코드가 나온다(값은 나오지 않는다).
- 줄 k에서 `INSUFFICIENT_CASH`처럼 매수 함수가 실패하면 1..k-1은 커밋된 채 남는다. 정상 동작이다(매수마다 한 트랜잭션). 원인을 고치고 같은 명령을 다시 실행하면 남은 줄만 사고 `이어서 삼`이 나온다. 0턴이 지난 뒤(`current_turn_no > 0`)에는 이어 살 수 없다(`TURN_MISMATCH`).
- **실습 DB를 더럽히지 않는다.** 아래 (f)·(g) 실습은 복사 DB에서 한다. 실습이 만든 0턴 매수가 `antlab`에 남으면 21 재실행이 "명단 밖 종목의 0턴 주문"으로 멈춘다.

**실제 비중 보기 — 소유자가 로컬에서만 본다.** 팀 직후 선언 4분류의 실제 비중(%)과 시작 포메이션 목표다. 가격에서 나온 값이므로 PR·문서·이슈에 붙이지 않는다. 투입액 = 대금 + 수수료로 센다(골키퍼 = 현금 + 골키퍼 줄의 투입액). 정수 주라 남는 돈이 현금으로 가서 비골키퍼 분류는 목표 이하, 골키퍼는 목표 이상이다.

```sql
WITH s AS (SELECT se.* FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid')),
t AS (
    SELECT lu.declared_position AS dp, o.quantity * tp.price + e.fee AS invested
    FROM s
    JOIN orders o ON o.season_id = s.id AND o.turn_no = 0 AND o.side = 'BUY'
    JOIN execution e ON e.order_id = o.id
    JOIN turn_price tp ON tp.season_id = s.id AND tp.turn_no = 0 AND tp.asset_id = o.asset_id
    JOIN lineup lu ON lu.season_id = s.id AND lu.asset_id = o.asset_id AND lu.start_turn = 0)
SELECT x.cls, ROUND(100 * x.v / s.initial_cash, 2) AS actual_pct, x.target AS target_pct
FROM s CROSS JOIN LATERAL (VALUES
    ('골키퍼(현금 포함)', s.initial_cash - (SELECT COALESCE(sum(invested) FILTER (WHERE dp <> 'GOALKEEPER'), 0) FROM t), s.start_goalkeeper),
    ('수비',     (SELECT COALESCE(sum(invested), 0) FROM t WHERE dp = 'DEFENDER'),         s.start_defender),
    ('미드필더', (SELECT COALESCE(sum(invested), 0) FROM t WHERE dp = 'MIDFIELDER'),       s.start_midfielder),
    ('공격',     (SELECT COALESCE(sum(invested), 0) FROM t WHERE dp LIKE 'FORWARD\_%'),    s.start_forward)
) AS x(cls, v, target);
```

**설정을 바꾸고 싶을 때.** 시즌은 한 번 만들면 바뀌지 않는 기록이다(erd D30). 이미 있는 DB에서 설정·경로를 고치고 20을 다시 실행하면 "이미 있는 시즌이 이 파일과 어긋난다"로 멈춘다(아무것도 바뀌지 않음). 바꾸려면 (b′)로 `antlab`을 다시 만들고 10 → 11 → 12 → 13 → 20 순서로 다시 돌린다. 팀을 바꾸려면(이미 산 뒤) 마찬가지로 (b′)부터 10 → 12 → 20 → 21 순서로 다시 돌린다(매수 기록은 지우거나 고치지 않는 기록이다). 팀 구성 이후에 20을 다시 실행해도 통과한다 — 바뀌는 컬럼(`status`·`current_turn_no`·`current_*`·잔액·뒤 원장 행·1턴 이후 가격)은 비교하지 않는다. 경로 리터럴을 고쳤다면 `engine_version`도 올린다(같은 시드·같은 엔진 버전 = 같은 경로).

**(b⁵) 수동 대사 — `antlab`(작업 DB)에.** 20 시즌 시작 뒤에 실행한다(팀 구성 21 뒤에 돌리는 것이 의미 있다). 연습 시즌을 이메일로 찾아 `season`을 잠그고 수동 검사 15개를 한 문장의 스냅샷으로 계산해 새 시도 번호 하나로 `reconciliation_log`에 15행을 남긴다. 일치해도 남긴다(PLAN §5.2).

```bash
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/22_reconcile_season.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/22_reconcile_season_check.sql
```

- **다시 실행하면 행이 늘어난다 — 정상이다.** 20·21과 달리 "이미 같으면 새 행 없음"이 아니다. 실행마다 새 시도 번호와 15행이 쌓이고 앞 시도의 행은 그대로다(로그는 추가 전용). 같은 결과여도 시각이 다른 두 검사이기 때문이다.
- 불일치가 있으면 행을 먼저 커밋한 뒤 종료 코드 3으로 끝난다(메시지에는 코드 이름과 건수뿐). 시즌 상태는 바꾸지 않는다 — 수동 검사에는 되돌릴 턴 전환이 없고, 다음 턴 전환(P0-17)의 대사가 같은 규칙으로 잡아 롤백한다.
- 수동 검사는 규칙 11(평가액)·19(포지션별 평가액)를 돌리지 않는다(턴 전환 전용). 그래서 15개다. 수동 검사 행과 턴 전환 행은 스키마에 구분 컬럼이 없고 코드 집합(15개 대 17개)으로 구분한다.
- `-v ON_ERROR_STOP=1` 없이 실행하면 맨 앞 가드가 안내만 출력하고 아무것도 하지 않고 종료 코드 3으로 끝낸다(불일치를 종료 코드로 알리려면 오류에서 멈춰야 한다). 이때 `reconciliation_log`는 늘지 않는다.
- 22 검증 중 음성 사례 하나(N16)가 서브트랜잭션 안에서 `orders`의 외래 키를 잠깐 지웠다 되돌린다. 그동안 `orders`를 쓰는 다른 세션이 기다리므로 **검증 중에는 다른 세션을 쓰지 않는다**(끝나면 제약과 잠금이 함께 되돌아간다).

**(b⁶) 턴 진행 — `antlab`(작업 DB)에.** 21 팀 구성 뒤에 실행한다. 한 번 실행할 때마다 연습 시즌이 한 턴 진행된다. 다음 턴 `turn_price`(풀 전체)를 만든 뒤 `turn` 스냅샷을 만들고 대사 17개를 돌린다. 모두 일치하면 `reconciliation_log` 17행을 남기고 `current_turn_no`를 올려 커밋한다. 마지막 턴이면 시즌 상태가 `FINISHED`가 된다.

```bash
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/23_turn_advance.sql                       # 한 턴
for i in $(seq 1 10); do psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/23_turn_advance.sql || break; done   # 10턴 (끝나면 다음 실행은 "이미 끝났다"고 알리고 종료 코드 0)
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/23_turn_advance_check.sql
```

- **한 번 실행할 때마다 한 턴 진행한다.** 멱등이 아니므로 실행할 때마다 한 턴씩 나아간다. 아무것도 하지 않는 경우는 끝난 시즌뿐이다.
- **값을 보는 방법.** 보고 표에는 턴 번호·새 `turn_price` 행 수·`turn` 행 수·시도 번호·17개 중 일치 수·끝났는지만 표시한다. 가격·평가액·낙폭은 출력하지 않는다. 자기 DB에서 로컬로 볼 때만 아래처럼 조회한다(PR·이슈에 붙이지 않는다).

```sql
-- 턴별 총액·낙폭·포지션별 평가액 (소유자의 로컬 조회 — 시세에서 나온 금액이다)
SELECT t.turn_no, t.cash_balance, t.holdings_value, t.total_value, t.goalkeeper_value, t.defender_value, t.midfielder_value, t.forward_value, t.drawdown
FROM turn t JOIN season s ON s.id = t.season_id JOIN member m ON m.id = s.member_id
WHERE lower(m.email) = lower('phase0@example.invalid') ORDER BY t.turn_no;
-- 턴 전환 로그: 턴마다 시도 번호와 일치 수 (불일치 시도가 있었다면 번호가 2 이상인 턴이 보인다)
SELECT l.turn_no, l.attempt_no, count(*) AS rules, count(*) FILTER (WHERE l.status = 'MATCH') AS matched
FROM reconciliation_log l JOIN season s ON s.id = l.season_id JOIN member m ON m.id = s.member_id
WHERE lower(m.email) = lower('phase0@example.invalid') GROUP BY l.turn_no, l.attempt_no HAVING count(*) = 17 ORDER BY l.turn_no, l.attempt_no;
```

- **종료 코드 3인 경우.** ① 대사 불일치: `P0-17 턴 진행: 대사 불일치 규칙 n 개 (…)`와 `HINT: RECONCILIATION_FAILED` — 턴은 실제로 되돌려졌고(`current_turn_no`·`turn`·`turn_price`가 그대로) `reconciliation_log`에 그 턴(`turn_no` = 현재 턴 + 1)의 17행이 `INVESTIGATE`로 남았다. 어느 규칙인지는 `check_code`로 본다(아래 "결과 읽는 법"). 원인을 고친 뒤 다시 실행하면 성공하고 그 턴의 시도 번호는 2다. ② 전제 오류(쓰기 전, 로그 없음): 힌트 `SEASON_NOT_FOUND`·`SEASON_NOT_ACTIVE`·`TURN_MISMATCH`(두 실행이 겹쳐 둘째가 졌다)·`NO_PATH`·`NO_RETURN`·`ISOLATION_LEVEL`·`INVALID_ARGUMENT`.
- `-v ON_ERROR_STOP=1` 없이 실행하면 맨 앞 가드가 안내만 출력하고 아무것도 하지 않고 종료 코드 3으로 끝낸다(행 없음).
- 정의 파일은 트랜잭션 밖에서 읽어야 한다. `pg_temp.turn_advance`는 안에서 `COMMIT`·`ROLLBACK`을 하는 프로시저라 **최상위 `CALL`로만** 부른다. 바깥 `BEGIN` 안에서 부르면 `2D000 invalid transaction termination`이다(`EXCEPTION` 블록·`DO` 안도 같다).
- 이 파일은 소음 피드(P0-18)·기준 포트폴리오(P0-20)·적립금(EXT)·턴 사이 매매(P0-19)를 하지 않는다. 23 검증은 "마지막 스냅샷 뒤 매매 없음"을 전제하므로 P0-19 이후에는 턴 전환 직후나 끝난 뒤에 돌린다.

**(c) 제약 검증 — `antlab_test`에서만.** 스키마(01 → 02 → 03)가 만들어져 있는 `antlab_test`에서 검증 세 줄을 실행한다. 검증은 마지막에 `ROLLBACK`하므로 스키마를 다시 만들지 않고 몇 번이든 돌릴 수 있다. 처음 한 번은 아래 스키마 세 줄을 먼저 실행한다.

```bash
psql -X -q -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history_check.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_test -f db/02_schema_season_check.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_test -f db/03_schema_account_check.sql
```

`antlab_test`에 스키마가 아직 없으면(처음 한 번) 검증 앞에 스키마 세 줄을 순서대로 실행한다. 03 파일만 고쳤다면 03 스키마 한 줄과 검증 세 줄이면 된다. 02 스키마 줄은 03이 있는 DB에서, 01 스키마 줄은 02가 있는 DB에서 `2BP01`로 멈추므로 01·02를 고쳤을 때는 (c′)를 쓴다.

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/02_schema_season.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/03_schema_account.sql
```

**(c′) 검증 DB 다시 만들기 — `antlab_test` 전체를 지우고 처음부터.** (c)·(c′)는 스키마(01–03)와 그 검증 세 줄만 한다. 20 이후 검증은 연습 시즌이 있어야 하므로 빈 `antlab_test`에서는 0절이 FAIL이다 — 그 뒤는 위 「전체 순서」 표(새 DB)에서 한다.

```bash
dropdb antlab_test
createdb -E UTF8 -T template0 antlab_test
psql -X -d postgres -c "ALTER DATABASE antlab_test SET timezone TO 'Asia/Seoul'"
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/02_schema_season.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/03_schema_account.sql
```

**(d) 임시 인스턴스로 검증 — 상시 DB 서버를 건드리지 않는다.** 저장소 밖에 데이터 디렉터리를 만들어 소켓으로만 접속하는 서버를 잠깐 띄운다(포트를 열지 않는다). 다 쓰면 멈추고 지운다. `initdb`·`pg_ctl`은 Homebrew `postgresql@17`의 서버 도구를 쓴다(Compose 서버와 무관하며 상시 서버는 켜지 않는다). 실행할 때마다 새 경로를 만든다. 앞 줄에서 오류가 나면 멈추고 마지막 두 줄(정리)만 실행한다.

```bash
D=$(mktemp -d /tmp/antlab-pg.XXXXXX)
initdb -D "${D:?}/data" -U check -A trust -E UTF8
pg_ctl -D "${D:?}/data" -o "-k ${D:?} -c listen_addresses='' -c timezone=Asia/Seoul" -l "${D:?}/data/log" start
createdb -h "${D:?}" -U check -E UTF8 -T template0 antlab_test
psql -X -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/01_schema_history.sql
psql -X -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/02_schema_season.sql
psql -X -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/03_schema_account.sql
psql -X -q -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/01_schema_history_check.sql
psql -X -q -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/02_schema_season_check.sql
psql -X -q -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/03_schema_account_check.sql
pg_ctl -D "${D:?}/data" -m fast stop
rm -rf "${D:?}"
```

**(e) 롤백 실습 — 포메이션 합 99로 시즌 시작이 통째로 되돌아가는 것 보기.** 시즌이 아직 없는 DB(격리 프로젝트 또는 20을 실행하기 전의 `antlab`)에서 한다. 설정의 포메이션 줄(`10, 30, 30, 30` 한 줄)만 바꾼 사본을 만들어 돌리면, 앞에서 이미 성공한 `member` INSERT까지 사라진다. 사본은 저장소 밖에 둔다.

```bash
sed 's/    10, 30, 30, 30   --/    10, 30, 30, 29   --/' db/20_season_start.sql > "${TMPDIR:-/tmp}/20_formation99.sql"
diff db/20_season_start.sql "${TMPDIR:-/tmp}/20_formation99.sql"        # 바뀐 줄이 하나인지 확인한다 (> 줄 하나)
psql -X -v ON_ERROR_STOP=1 -d antlab -f "${TMPDIR:-/tmp}/20_formation99.sql"; echo "종료 코드 $?"   # 3, ck_season_current_formation_sum
psql -X -d antlab -c "SELECT (SELECT count(*) FROM member) AS member, (SELECT count(*) FROM season) AS season, (SELECT count(*) FROM turn_price) AS turn_price"   # 모두 0
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/20_season_start.sql         # 원본은 성공한다
psql -X -d antlab -c "SELECT id FROM member"                            # 1이 아닐 수 있다 — 실패한 INSERT도 번호표(identity)를 썼다
```

포메이션 네 값을 한 줄에서 함께 쓰므로 시작·현재 합 CHECK 둘을 함께 어기고, PostgreSQL은 CHECK를 이름 순으로 평가해 `ck_season_current_formation_sum`을 먼저 보고한다. `-v ON_ERROR_STOP=1`을 빼고 돌리면 첫 오류 뒤 남은 문장이 `current transaction is aborted`(25P02)로 줄줄이 실패하고 마지막 `COMMIT`은 `ROLLBACK`이 되어 행은 남지 않지만 종료 코드가 0일 수 있다.

**(f) 두 세션 동시 매수 실습 — 잠금이 없을 때와 있을 때.** PostgreSQL 기본 격리 수준은 READ COMMITTED다. 문장마다 새 스냅샷을 쓰고 일반 `SELECT`는 잠그지 않는다. 같은 잔액을 두 세션이 동시에 쓰려 하면 어떻게 되는지 터미널 두 개로 본다. **실습은 반드시 복사 DB에서 한다.**

준비 (한 번, 터미널 하나에서):

```bash
psql -X -d postgres -Atc "SELECT count(*) FROM pg_stat_activity WHERE datname = 'antlab' AND pid <> pg_backend_pid()"   # 0이어야 복사된다 (VS Code SQL 확장 접속도 센다)
createdb -T antlab antlab_lab
# ... 실습 ...
dropdb antlab_lab        # 끝나면 지운다 (실습 A′는 일부러 원장 합 ≠ 잔액을 만든다)
```

VS Code 통합 터미널을 둘로 나눈다(`Cmd` + `\`). 두 터미널 모두 저장소 루트에서 `psql -X -d antlab_lab`로 접속하고 각 세션에서 아래 준비를 한다(임시 함수는 세션마다 읽어야 한다). 금액은 화면에 내지 않는다 — 비용은 `\gset`으로 변수에 담고, 판정은 `t`/`f`와 SQLSTATE로만 본다. 비용은 "현재 주문 가능 금액의 60%"라 두 주문의 합이 잔액을 넘는다.

```sql
\set VERBOSITY terse
\ir db/21_team_buy_def.sql
SELECT s.id AS sid FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid') \gset
SELECT floor((balance - reserved) * 0.6) AS cost FROM cash_account WHERE season_id = :sid \gset
SELECT a.id AS aid FROM asset a WHERE a.market = 'KR' AND a.ticker = '411060' \gset
SELECT tp.price AS price, pg_temp.buy_rate('KR', s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate) AS rate
FROM turn_price tp JOIN season s ON s.id = tp.season_id WHERE tp.season_id = :sid AND tp.turn_no = 0 AND tp.asset_id = :aid \gset
SELECT pg_temp.team_qty(:cost, :price, :rate) AS q \gset
```

**시나리오 A — 잠금 없음 (손 SQL, 함수를 쓰지 않는다).** 터미널 1(T1)을 먼저, 1–2초 뒤 터미널 2(T2)를 실행한다.

```sql
-- T1
BEGIN;
SELECT (balance - reserved >= :cost) AS enough FROM cash_account WHERE season_id = :sid;      -- t
SELECT balance AS bal FROM cash_account WHERE season_id = :sid \gset
UPDATE cash_account SET balance = balance - :cost WHERE season_id = :sid;
INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (:sid, 'ADJUSTMENT', -(:cost), (:bal) - (:cost), NULL);
-- (잠시 멈춘다: 10초쯤 기다린 뒤 T2를 실행하고 돌아와서)
COMMIT;

-- T2
BEGIN;
SELECT (balance - reserved >= :cost) AS enough FROM cash_account WHERE season_id = :sid;      -- t (T1이 아직 커밋 전이라 같은 잔액을 본다)
UPDATE cash_account SET balance = balance - :cost WHERE season_id = :sid;                     -- 여기서 기다린다. T1이 커밋하면 최신 행으로 다시 계산해 ERROR 23514
ROLLBACK;
```

**A′ (선택) — 절대값을 쓰면.** T2의 `UPDATE`를 `UPDATE cash_account SET balance = (:bal) - (:cost) ...`로 바꾸고 `COMMIT` 앞에 T1처럼 원장 행을 넣는다. T2는 다른 세션이라 `:bal`이 없으므로, T2의 첫 `SELECT` 뒤(T1이 커밋하기 전)에 `SELECT balance AS bal FROM cash_account WHERE season_id = :sid \gset`를 먼저 실행한다(`INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (:sid, 'ADJUSTMENT', -(:cost), (:bal) - (:cost), NULL);`). 앞에서 읽은 값을 그대로 쓰는 모양은 JPA 변경 감지가 쓰는 모양이다. 오류 없이 커밋되고, 끝난 뒤 `Σ amount = balance`가 `f`다(잃어버린 갱신).

**시나리오 B — 잠금 있음 (함수).** T1은 `BEGIN; SELECT pg_temp.order_buy(:sid, 0::smallint, :aid, :q, 'DEFENDER');` 뒤 멈추고 T2가 같은 문장을 부른다. T2는 첫 문장(`season ... FOR UPDATE`)에서 기다린다(프롬프트가 돌아오지 않는다). T1이 `COMMIT;`하면 T2가 커밋된 최신 잔액을 읽고 `INSUFFICIENT_CASH`(P0001)로 `INSERT` 전에 실패한다. T2는 `ROLLBACK;`한다.

**시나리오 C (선택) — 교착.** T1: `BEGIN; SELECT 1 FROM cash_account WHERE season_id = :sid FOR UPDATE;` 잠시 뒤 `SELECT 1 FROM season WHERE id = :sid FOR UPDATE;`. T2: `BEGIN; SELECT 1 FROM season WHERE id = :sid FOR UPDATE;` 잠시 뒤 `SELECT 1 FROM cash_account WHERE season_id = :sid FOR UPDATE;`. 서로 상대가 가진 잠금을 기다리다 `deadlock_timeout`(기본 1초) 뒤 한쪽이 `40P01`로 끊긴다.

끝난 뒤 보는 쿼리(각 시나리오가 끝나면 이 쿼리로 확인한다. 다음 시나리오 전에 두 세션을 끝내고(`\q`) `dropdb antlab_lab` → `createdb -T antlab antlab_lab` → 다시 접속해 준비 블록을 다시 실행한다):

```sql
SELECT (ca.balance >= 0) AS balance_nonneg,
       (ca.balance = (SELECT sum(amount) FROM cash_ledger l WHERE l.season_id = ca.season_id)) AS sum_ledger_eq_balance
FROM cash_account ca WHERE ca.season_id = :sid;
```

**실습 기록** (2026-11-08, PostgreSQL 17.11, 격리 프로젝트의 복사 DB. 값 없이 `t`/`f`·SQLSTATE·제약 이름만 적는다):

| 시나리오 | T1 | T2 | 끝난 뒤 `balance ≥ 0` · `Σ 원장 = 잔액` | 교훈 |
|---|---|---|---|---|
| A 잠금 없음 | 충분(`t`) → 갱신 → 커밋 | 충분(`t`, 같은 잔액을 봄) → `UPDATE`가 T1 커밋까지 기다림 → 최신 행으로 다시 계산해 `23514` `ck_cash_account_balance_nonneg`, 전체 `ROLLBACK` | `t` · `t` | 일반 `SELECT`는 잠그지 않는다. CHECK가 돈을 지켰지만 마지막 문장에서야 막았다. 앱은 이미 "충분하다"고 판단했다 |
| A′ 절대값 | 같음 | 기다린 뒤 오류 없이 커밋(`00000`, 원장 행도 넣음) | `t` · `f` | 잃어버린 갱신은 CHECK도 못 잡는다. 비관적 락 또는 `@Version`이 필요하다(SPRING_JPA §5.3) |
| B 잠금 있음 | `order_buy` 성공 → 커밋 | `season ... FOR UPDATE`에서 기다림(`t`) → 커밋된 최신 잔액을 읽고 `P0001` `INSUFFICIENT_CASH`(`INSERT` 전, 새 행 0) | `t` · `t` (새 매수 주문 1건) | 잠금은 줄 세우기다. 오류가 검증 단계에서 깔끔하게 난다 |
| C 교착 | 한쪽(이번에는 T1)이 `40P01` deadlock detected | 다른 쪽은 진행·커밋 | `t` · `t` | 잠금 순서를 바꾸면 교착이 생긴다. 고정 순서 `season` → `cash_account`가 규칙인 이유 |

소유자가 직접 해 본 결과는 학습 노트의 연습 답에 적는다(저장소 밖).

**시즌 계좌 쓰기의 잠금 순서 규칙.** 시즌 계좌 5개 테이블(`cash_account`·`orders`·`execution`·`holding`·`cash_ledger`)과 `lineup`에 쓰는 SQL은 모두 먼저 `season` 행을 `FOR UPDATE`로 잠그고, 그다음 `cash_account`를 잠근다. 턴 전환(P0-17)·전술 변경과 매매(P0-19)도 같은 순서다. 검증 파일의 음성 사례(끝에 `ROLLBACK`하는 한 트랜잭션 안의 `UPDATE`)와 (f)의 실습 A·A′·C(일부러 어기는 시연)는 동시에 매수를 돌리지 않는 실습·복사 DB에서만 이 규칙의 예외다. 격리 수준이 REPEATABLE READ 이상이면 같은 순서에서도 둘째 트랜잭션이 `40001`로 실패하고 재시도가 필요하다(`order_buy`는 READ COMMITTED가 아니면 `ISOLATION_LEVEL`로 멈춘다).

**(g) 잔액 부족 실습 — 한 세션.** 매수 한 건은 함수 하나라 원자적이다. 위 준비(`\gset`까지)에 이어 복사 DB에서 한다.

```sql
-- 주문 가능 금액 ÷ 체결가 + 1주 (\gset 줄 끝에는 주석을 달지 않는다 — 인자로 읽힌다)
SELECT floor((balance - reserved) / :price) + 1 AS q_big FROM cash_account WHERE season_id = :sid \gset
SELECT (SELECT count(*) FROM orders) AS orders, (SELECT count(*) FROM execution) AS execution, (SELECT count(*) FROM holding) AS holding,
       (SELECT count(*) FROM cash_ledger) AS ledger, (SELECT count(*) FROM lineup) AS lineup, (SELECT last_value FROM orders_id_seq) AS orders_identity;
BEGIN;
SELECT pg_temp.order_buy(:sid, 0::smallint, :aid, :q_big, 'DEFENDER');       -- ERROR: INSUFFICIENT_CASH (P0001)
ROLLBACK;
-- 위 행 수 쿼리를 다시 실행한다: 모두 그대로이고 identity 마지막 값도 그대로다 (잔액 검사가 INSERT 앞이라 번호표를 쓰지 않는다)

-- SAVEPOINT 변형: 앞의 정상 매수는 남기고 실패한 매수만 되돌린다
BEGIN;
SELECT pg_temp.order_buy(:sid, 0::smallint, :aid, 1, 'DEFENDER');            -- 성공
SAVEPOINT s1;
SELECT pg_temp.order_buy(:sid, 0::smallint, :aid, :q_big, 'DEFENDER');       -- ERROR: INSUFFICIENT_CASH
ROLLBACK TO SAVEPOINT s1;
COMMIT;                                                                      -- 1주 매수만 남는다 (orders +1)
```

대금 + 수수료가 주문 가능 금액과 **정확히 같으면** 통과하고 잔액이 0이 된다. 1원 모자라면 `INSUFFICIENT_CASH`다. 이 경계는 실제 `INSERT`가 필요해 검증 파일에 없고 복사 DB에서 합성 상태로 확인한다(`ADJUSTMENT` 1행으로 잔액을 그 금액에 맞춘 뒤 `order_buy` 한 번). 순수 경계(`buy_affordable`)는 검증 10절이 본다.

**(h) 대사 깨뜨려 보기 — 복사 DB, 한 세션.** 수동 검사가 일부러 깨뜨린 데이터에서 어느 규칙이 걸리는지 본다. 먼저 **로그를 쓰지 않는** 방법(`BEGIN` → `UPDATE` → 조회 → `ROLLBACK`)으로 하고, 그다음 로그를 남기는 방법으로 한다. **실습은 반드시 복사 DB에서 한다.**

```bash
psql -X -d postgres -Atc "SELECT count(*) FROM pg_stat_activity WHERE datname = 'antlab' AND pid <> pg_backend_pid()"   # 0이어야 복사된다
createdb -T antlab antlab_lab
psql -X -d antlab_lab
# ... 실습 ...
dropdb antlab_lab        # 끝나면 지운다
```

```sql
\ir db/22_reconcile_season_def.sql
SELECT se.id AS sid, se.current_turn_no AS cur FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid') \gset
-- 정상: 15행이 모두 MATCH, 불일치 수 0
SELECT rule_no, check_code, status, mismatch_count FROM pg_temp.recon_rules(:sid, :cur::smallint);

BEGIN;
UPDATE cash_account SET balance = balance + 1 WHERE season_id = :sid;
SELECT rule_no, check_code, status, mismatch_count FROM pg_temp.recon_rules(:sid, :cur::smallint) WHERE status = 'MISMATCH';   -- 규칙 2·3
ROLLBACK;
```

`recon_rules`는 조회만 하므로 로그가 쌓이지 않는다. 값이 아니라 `status`·`mismatch_count`만 고르는 것이 습관이다(스칼라 규칙의 기대·실제·차이 컬럼은 시세에서 나온 금액이라 PR·이슈에 붙이지 않는다). `\gset` 줄 끝에는 주석을 달지 않는다.

| 바꾸기 (`BEGIN` … `ROLLBACK` 안에서) | 걸리는 규칙 | 배울 점 |
|---|---|---|
| `UPDATE cash_account SET balance = balance + 1 WHERE season_id = :sid` | **2와 3** | 원장이 1행 이상이고 계좌가 있으면 규칙 3(사슬이 이어지고 최신 = 잔액)이 참일 때 규칙 2가 저절로 참이다(3 ⇒ 2). 그래서 그런 시즌에서는 2만 깨뜨릴 수 없다(원장이 비면 3은 대상이 없어 일치하고 2는 잔액이 0이 아닐 때만 걸린다 — 원장 부재는 규칙 1 `NO_LEDGER`가 잡는다) |
| `UPDATE cash_account SET reserved = 1 WHERE season_id = :sid` | 9 | 지정가가 없어 예약금은 늘 0 |
| `UPDATE holding SET quantity = quantity + 1 WHERE id = (SELECT min(id) FROM holding WHERE season_id = :sid AND quantity > 0)` | 6 | 보유 = 체결의 합 |
| `UPDATE execution SET realized_pnl = 0 WHERE order_id = (SELECT min(id) FROM orders WHERE season_id = :sid)` | 8 | 매수 체결의 실현 손익은 NULL |
| `UPDATE season SET current_defender = current_defender + 1, current_midfielder = current_midfielder - 1 WHERE id = :sid` | 15 | 현재 포메이션 중복 컬럼과 변경 이력의 일치 |
| `UPDATE lineup SET end_turn = start_turn WHERE id = (SELECT min(id) FROM lineup WHERE season_id = :sid AND end_turn IS NULL)` | 17 | 열린 구간 ⇔ 보유 수량 > 0 |

로그를 남기며 깨뜨려 보려면(복사 DB에서만) 위 `UPDATE` 하나를 `COMMIT`한 뒤 `psql`을 나와서 22를 돌린다.

```bash
psql -X -q -v ON_ERROR_STOP=1 -d antlab_lab -f db/22_reconcile_season.sql ; echo "종료 코드 $?"   # 불일치 규칙과 건수가 나오고 종료 코드 3
psql -X -d antlab_lab -c "SELECT attempt_no, check_code, status, mismatch_count, classification FROM reconciliation_log WHERE status = 'MISMATCH' ORDER BY id"
```

불일치 행은 커밋되어 `INVESTIGATE`로 남는다. `detail` 컬럼에는 어느 행인지(주문·체결·원장·종목 번호)와 종류 코드만 들어 있다(금액 없음). 22를 다시 돌리면 시도 번호가 늘어난 새 행이 쌓인다.

**(i) 롤백 드릴 — 버리는 DB, 한 세션.** 커밋된 상태를 일부러 깨뜨린 뒤 턴을 넘긴다. 그러면 실제 `ROLLBACK`이 일어나고 `reconciliation_log`에만 17행이 남는다. `antlab`을 복사한 DB에서 한다(복사 중에는 `antlab`에 다른 접속이 없어야 한다 — VS Code SQL 확장 접속도 센다). 가드가 있어 `antlab`·`antlab_test`에서는 멈춘다.

```bash
psql -X -d postgres -Atc "SELECT count(*) FROM pg_stat_activity WHERE datname = 'antlab' AND pid <> pg_backend_pid()"   # 0이어야 복사된다
createdb -T antlab antlab_drill
psql -X -q -v ON_ERROR_STOP=1 -v drill=1 -d antlab_drill -f db/23_turn_advance_drill.sql     # 중간의 ERROR(RECONCILIATION_FAILED)는 정상이다. 마지막 줄 p0_17_drill = PASS
# 라인업을 닫은 채 실행 파일을 돌려 종료 코드를 보려면 (복사 DB에서만):
psql -X -d antlab_drill -c "UPDATE lineup SET end_turn = start_turn WHERE id = (SELECT min(id) FROM lineup WHERE end_turn IS NULL)"
psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill -f db/23_turn_advance.sql ; echo "종료 코드 $?"   # 3
psql -X -d antlab_drill -c "SELECT turn_no, attempt_no, check_code, status FROM reconciliation_log WHERE status = 'MISMATCH' ORDER BY id"
dropdb antlab_drill        # 끝나면 지운다 (로그는 추가 전용이라 DB째 버리는 것이 정리다)
```

드릴은 같은 세션에서 `CALL pg_temp.turn_advance(...)`를 직접 부르고 `:LAST_ERROR_SQLSTATE`로 오류 종류를 확인한다. 여기서 롤백은 이미 커밋된 깨짐을 되돌리지 않는다는 점을 확인할 수 있다(드릴의 깨진 라인업은 롤백 뒤에도 깨진 채다). 또한 불일치 결과를 담은 **지역 변수**만 롤백을 건너 로그가 된다(임시 표는 롤백과 함께 사라진다).

**(j) "매달 반올림"과 마지막에 한 번 반올림 비교 — `antlab`, 읽기만.** 같은 입력에서 두 계산 결과를 비교한다. 하나는 정확한 곱을 마지막에 한 번 반올림하고, 다른 하나는 매달 반올림한다. 두 값이 다른 (턴, 종목) 칸 수만 센다.

```sql
WITH RECURSIVE s AS (
    SELECT se.id FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid')
), a(k, asset_id, cur, exact, monthly) AS (
    SELECT 0, tp.asset_id, x.currency, tp.price::numeric, tp.price::numeric
    FROM s JOIN turn_price tp ON tp.season_id = s.id AND tp.turn_no = 0 JOIN asset x ON x.id = tp.asset_id
  UNION ALL
    SELECT a.k + 1, a.asset_id, a.cur, a.exact * q.f, ROUND(a.monthly * q.f, 0)
    FROM a
    JOIN s ON true
    JOIN season_path sp ON sp.season_id = s.id AND sp.turn_no = a.k + 1
    JOIN asset_month_return r ON r.asset_id = a.asset_id AND r.month_block_id = sp.month_block_id
    LEFT JOIN fx_month f ON f.month_block_id = sp.month_block_id AND f.currency = a.cur
    CROSS JOIN LATERAL (SELECT (1 + r.local_return) * CASE WHEN a.cur = 'KRW' THEN 1 ELSE 1 + f.fx_change END AS f) q
    WHERE a.k < (SELECT current_turn_no FROM season WHERE id = s.id)
)
SELECT count(*) FILTER (WHERE ROUND(a.exact, 0) <> a.monthly) AS different_cells, count(*) AS cells
FROM a JOIN s ON true JOIN turn_price tp ON tp.season_id = s.id AND tp.turn_no = a.k AND tp.asset_id = a.asset_id
WHERE a.k >= 1 AND tp.price = ROUND(a.exact, 0);
```

`cells`는 1턴 이후 (턴, 종목) 칸 수다. `different_cells`가 0보다 크면 "매달 반올림"이 정답이 아니라는 뜻이다(1턴은 곱이 한 번이라 같다). 저장된 `turn_price`는 마지막에 한 번 반올림한 값이다(`WHERE`의 `tp.price = ROUND(a.exact, 0)`가 그 확인이다). 재귀 CTE(`WITH RECURSIVE`)가 턴을 하나씩 쌓는 구조도 확인한다.

**(k) 두 세션 동시 턴 — 복사 DB.** 같은 턴을 두 실행이 동시에 넘기려 하면 하나만 성공하고 다른 하나는 아무것도 쓰지 않고 `TURN_MISMATCH`로 끝나는 것을 본다. 한 터미널에서 시즌 행을 잠가 둔 채 다른 터미널에서 두 실행을 겹친다.

```bash
# 터미널 1: 시즌을 잠가 두 실행이 모두 "현재 턴"을 읽고 줄을 서게 한다 (5초 뒤 풀린다)
psql -X -d antlab_lab -c "BEGIN" -c "SELECT 1 FROM season FOR UPDATE" -c "SELECT pg_sleep(5)" -c "COMMIT"
# 터미널 2 (터미널 1을 시작한 직후): 같은 파일을 겹쳐 실행
( psql -X -q -v ON_ERROR_STOP=1 -d antlab_lab -f db/23_turn_advance.sql ; echo "A 종료 코드 $?" ) &
( psql -X -q -v ON_ERROR_STOP=1 -d antlab_lab -f db/23_turn_advance.sql ; echo "B 종료 코드 $?" ) & wait
psql -X -d antlab_lab -c "SELECT (SELECT current_turn_no FROM season) AS cur, (SELECT count(*) FROM turn) AS turn_rows"   # 한 턴만 올랐다
```

하나는 보고 표와 종료 코드 0으로 끝나고, 다른 하나는 `HINT: TURN_MISMATCH`와 종료 코드 3으로 끝난다. 둘째는 첫째가 커밋한 뒤에야 잠금을 얻는다. 그때 기대한 턴이 달라진 것을 확인하고 쓰기 전에 멈춘다. 중복 행이 없고 로그도 늘지 않는다(대사를 하지 않았다).

**(l) 매매·전술 변경 드릴 — 버리는 DB, 한 세션.** 정상 재현 DB와 섞지 않는다. 드릴은 일부러 극단 상태를 남긴다. `antlab`을 복사한 DB(또는 23을 5번 돌린 사본)에서 사례마다 새 사본을 만들어 실행한다. 가드가 있어 `antlab`·`antlab_test`에서는 멈춘다.

```bash
createdb -T antlab antlab_drill       # antlab 에 다른 접속이 없어야 복사된다
psql -X -q -v ON_ERROR_STOP=1 -v drill=1 -v drill_case=allcash -d antlab_drill -f db/25_trade_formation_drill.sql    # 마지막 줄 p0_19_drill_allcash = PASS
# 이어서 극단 상태에서 회귀: 25·21·20 검증, 23 으로 한 턴, 23 검증
for f in 25_trade_formation_check 21_team_buy_check 20_season_start_check; do psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill -f db/$f.sql | tail -2; done
psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill -f db/23_turn_advance.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill -f db/23_turn_advance_check.sql | tail -2
dropdb antlab_drill
```

사례 `allcash` · `replace` · `extremes` · `rebuy` · `atomic` · `alert`는 한 번에 하나씩 실행한다. `changeonly`는 새 턴(현재 턴에 주문이 없는 상태)에서 시작한다. 주문 없이 전술 변경 1회와 포지션 변경 1회만 한다. 그러면 현재 턴에 시작·끝난 라인업 구간이 있어 23 검증이 0절 범위 가드에서 멈춘다. 23으로 한 턴 넘긴 뒤에는 `PASS`다. 전액 현금(`allcash`)에서는 보유 수량 > 0·열린 라인업이 있어야 하므로 22 검증이 0절에서 FAIL이다 — 22 검증의 범위 밖이며 결함이 아니다. 21·23·25 검증은 `PASS`이고 대상이 없는 음성 사례는 "적용 불가" 정보 행으로 남는다. `atomic`은 트랜잭션 안에서 임시 트리거를 만들고 쓰기 한가운데(원장 FEE 행·시즌 갱신·새 구간)에서 실패시킨다. 그런 다음 업무 행이 처음과 같은지 확인하고 `ROLLBACK`으로 트리거까지 지운다.

**(m) 두 세션 경합 — 버리는 DB.** 잠금 보유 세션(`-v mode=hold`)이 트랜잭션 안에서 `op`를 실행한 뒤 `-v hold_s`초 동안 잠금을 유지한다. 그동안 다른 세션(`-v mode=run`)이 같은 시즌에 쓰기를 시도한다. 결과는 `RESULT <op> OK` 또는 `RESULT <op> <HINT>` 한 줄이다. `op`는 `sell_all` · `buy1` · `formation` · `lineup` · `turn`이다. 대본의 기대:

| 대본 | 세션 A (먼저) | 세션 B | 기대 |
|---|---|---|---|
| 동시 매도 | `hold sell_all` | `run sell_all` | A `OK`, B `INSUFFICIENT_HOLDING` (합계가 보유를 넘으면 뒤 요청 거절) |
| 주문이 먼저 | `hold buy1`·`sell_all`·`formation`·`lineup` | `run turn` | 둘 다 `OK`, 다음 턴 스냅샷에 그 주문·변경이 들어간다(23 검증 `PASS`) |
| 턴 전환이 먼저 | C `mode=cashlock`(계좌 행 잠금) + A `run turn`(시즌 잠금을 쥔 채 기다림) | B `run buy1`·`sell_all`·`formation`·`lineup` | A `OK`, B `TURN_MISMATCH` (옛 턴 주문·변경이 남지 않는다) |

```bash
D="-X -q -v ON_ERROR_STOP=1 -v drill=1 -v drill_case=race -d antlab_race -f db/25_trade_formation_drill.sql"
psql $D -v mode=hold -v op=sell_all -v hold_s=10 &      # 세션 A
sleep 5; psql $D -v mode=run -v op=sell_all; wait       # 세션 B (정의를 읽는 데 몇 초 걸리므로 A 가 잠금을 쥔 뒤에 시작한다)
```

각 세션이 정의 파일을 읽는 데 1–2초 걸리므로 B는 A가 잠금을 잡은 뒤 시작하도록 간격을 둔다. 교착이 없다는 근거는 잠금 순서(`season` → `cash_account`)가 같고 모든 경로가 끝까지 기다렸다가 종료한다는 사실이다. (zsh에서는 `$D`가 한 단어로 남으므로 bash로 실행한다.)

**(n) 성적표를 읽고 흔들어 보기 — `antlab`, 읽기만 + 복사 DB.** 값은 터미널에서만 확인한다.

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/26_season_report.sql        # 37열 한 행, 턴별 표, 선수별 역할
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/26_season_report_check.sql | tail -2   # p0_20_check = PASS
```

1. 5턴 매매(25) 직후와 23을 한 번 더 실행한 뒤의 `pending_orders`·`pending_changes`를 비교한다. 왜 직후에는 0이 아니고, 한 턴 뒤에는 0이 될까?
2. 복사 DB에서 `UPDATE asset_month_return`으로 대용 종목(`069500`·`SPY`) 경로의 앞 3개월을 −0.05로 바꾸고 `sr_season_positions`의 `reason` 건수를 다시 확인한다. 판정 불능이 줄어드는 이유는 무엇일까?

<details><summary>답</summary>

1. 스냅샷 T에는 턴 T − 1까지의 매매가 담긴다(D23). 턴 T의 주문·변경은 다음 스냅샷에 들어가므로 계산에서 빠지고 `pending_*`로 센다. 턴을 넘기면 스냅샷이 늘어나 그 매매가 보고 범위에 들어온다.
2. 시즌용 최소 달 수는 6·3·3이다. 빠진 달(시장 수익률 ≤ −1%)이 3개 미만이면 `N_DOWN`으로 판정할 수 없다. 빠진 달을 만들면 기준을 충족해 판정되는 종목이 생긴다.

</details>

## 실습 문제 (P0-21)

실행형 실습 (e)–(n) 뒤에 두는 **생각형·쿼리형** 문제다. 모두 위 「전체 순서」를 S37까지 끝낸 DB(블록의 `antlab_full` 또는 같은 상태의 `antlab`)에서 **읽기만** 하거나, 복사 DB(`CREATE DATABASE antlab_copy TEMPLATE antlab_full` — 복사 중에는 원본에 다른 접속이 없어야 한다)에서 한다. 답은 방향과 쿼리, 예상 오류 코드이고 **값은 적지 않는다** — 결과의 가격·금액은 터미널에서만 본다. 과목 연결은 `02_Database`(DQL·DML·Transaction·DDL)와 JPA 단원이다. 먼저 스스로 해 보고 답을 펼친다. 아래 쿼리의 시즌은 이메일로 찾는다.

```sql
-- 문제 공통: 연습 시즌 번호를 변수로 (psql)
SELECT s.id AS sid FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid') \gset
```

**Q1. 순서 = 의존 (복사 DB).** ① S01–S06만 끝난 복사 DB에서 `21_team_buy.sql`을, 이어서 `23_turn_advance.sql`을 실행하고 ② 전체 순서를 S23의 4번째 실행까지만 한 다른 복사 DB(시즌 중간 4턴)에서 `25_trade_formation.sql`을 실행하면 각각 어떤 메시지·종료 코드로 멈추는가? 왜 아무 행도 남지 않는가?

<details><summary>답</summary>

모두 첫 전제 단언(`DO` 블록)이 `RAISE EXCEPTION`으로 멈추고 `psql`은 종료 코드 3으로 끝난다. 메시지 접두어는 `P0-15 팀 구성:`·`P0-17 턴 진행:`·`P0-19 매매:`이고 본문이 "먼저 실행할 것"을 알려 준다(시즌이 없는 DB에서는 "연습 시즌이 0 개다 … 20 시즌 시작을 먼저", 4턴에서의 25는 현재 턴이 5가 아니라는 안내). 전제 단언은 아무것도 쓰기 전에 도는 읽기 전용이고, 쓰기는 그 뒤의 한 트랜잭션 안에서 하므로 오류가 나면 통째로 없던 일이 된다. 21은 줄마다 커밋하지만 전제 단언이 첫 줄 전에 멈춘다. 확인: `psql -X -v ON_ERROR_STOP=1 -d <복사 DB> -f db/21_team_buy.sql; echo $?`.
</details>

**Q2. 멱등 분류 (생각).** 「전체 순서」 표의 실행 파일을 "다시 돌려도 새 행 없음 / 실행마다 행이 늘어남 / 다시 돌리면 멈춤" 셋으로 나눠라. 22가 멱등이 아닌 것이 왜 맞는가?

<details><summary>답</summary>

새 행 없음: 10·11·13·20·21(21은 `이미 같은 팀`)과 쓰기 없는 12 재계산·14·24·26. 실행마다 늘어남: 22(대사 15행), 23(한 턴과 17행). 다시 돌리면 멈춤: 25(종료 코드 3, 이미 적용), 01을 02가 있는 DB에서(`2BP01`), 02를 03이 있는 DB에서(`2BP01`). 22의 로그는 "그 시점에 규칙을 돌려 본 증거"이다. 같은 결과여도 시각이 다른 두 검사라 지워 합치면 증거가 줄어든다. 그래서 로그는 추가 전용이고 시도 번호가 (시즌, 턴)마다 늘어난다.
</details>

**Q3. 대사 묶음 세기 (읽기).** `reconciliation_log`를 (턴, 시도)로 묶어 행 수와 `MATCH` 수를 내고, 15행 묶음과 17행 묶음이 각각 몇 개여야 하는지 맞혀라.

<details><summary>답</summary>

```sql
SELECT turn_no, attempt_no, count(*) AS rows, count(*) FILTER (WHERE status = 'MATCH') AS matched
FROM reconciliation_log WHERE season_id = :sid GROUP BY turn_no, attempt_no ORDER BY turn_no, attempt_no;
SELECT count(*) FILTER (WHERE n = 17) AS turn_bundles, count(*) FILTER (WHERE n = 15) AS manual_bundles
FROM (SELECT count(*) AS n FROM reconciliation_log WHERE season_id = :sid GROUP BY turn_no, attempt_no) q;
```

17행 묶음은 턴 1..10마다 하나씩 10개(턴 전환마다 한 번), 15행 묶음은 수동 22를 실행한 횟수(전체 순서에서는 S21·S29·S34의 3개)다. 모든 묶음에서 `rows = matched`이어야 한다. 묶음 크기가 15와 17로 갈리는 이유는 규칙 11·19가 턴 전환 전용이기 때문이다(코드 집합으로 구분한다).
</details>

**Q4. 원장 = 잔액 (읽기).** 현금 원장의 합과 계좌 잔액이 같은지를 `boolean` 한 칸으로 내라(값은 내지 않는다).

<details><summary>답</summary>

```sql
SELECT (SELECT COALESCE(sum(amount), 0) FROM cash_ledger WHERE season_id = :sid) = (SELECT balance FROM cash_account WHERE season_id = :sid) AS ledger_eq_balance;
```

`true`여야 한다. 대사 규칙 2(`CASH_SUM`)와 같은 질문이다. 원장이 정본이고 잔액은 그 합의 캐시라는 설계(erd)에서 나온다.
</details>

**Q5. 보유 ↔ 라인업 (읽기).** 보유 수량이 있는데 열린 라인업(`end_turn IS NULL`)이 없는 종목, 또는 그 반대를 찾아라. 0행이어야 정상이다. 어느 대사 규칙과 같은 질문인가?

<details><summary>답</summary>

```sql
SELECT COALESCE(h.asset_id, l.asset_id) AS asset_id, h.quantity > 0 AS held, l.asset_id IS NOT NULL AS in_lineup
FROM (SELECT asset_id, quantity FROM holding WHERE season_id = :sid AND quantity > 0) h
FULL JOIN (SELECT DISTINCT asset_id FROM lineup WHERE season_id = :sid AND end_turn IS NULL) l ON l.asset_id = h.asset_id
WHERE h.asset_id IS NULL OR l.asset_id IS NULL;
```

규칙 17 `LINEUP_HOLDING`(보유와 열린 구간이 서로 있어야 한다)이다. 현금은 골키퍼라 종목 라인업이 없다.
</details>

**Q6. 턴별 변화 (읽기).** `turn`의 총액에서 직전 턴 대비 변화율을 `LAG`로 구하고, 저장된 `drawdown`과 무엇이 다른지 설명하라(값은 터미널에서만).

<details><summary>답</summary>

```sql
SELECT turn_no, total_value / NULLIF(lag(total_value) OVER (ORDER BY turn_no), 0) - 1 AS change_from_prev, drawdown
FROM turn WHERE season_id = :sid ORDER BY turn_no;
```

첫 턴은 직전이 없어 변화율이 `NULL`이다. 변화율은 직전 턴 대비이고, `drawdown`은 지금까지의 최고점 대비다. 최고점을 갱신하는 턴은 변화율이 양수여도 `drawdown`이 0이고, 하락이 이어지면 변화율은 작아도 `drawdown`은 계속 깊어진다.
</details>

**Q7. DDL이 못 막는 것 (복사 DB).** `BEGIN` → 수수료를 공식과 1 다르게 바꾸는 `UPDATE` → 22 정의로 `recon_rules` 조회 → `ROLLBACK`. 어느 제약이 막고, 어느 규칙이 잡는가?

<details><summary>답</summary>

```sql
\i db/22_reconcile_season_def.sql   -- 저장소 루트에서 연 대화형 psql
BEGIN;
UPDATE execution SET fee = fee + 1 WHERE id = (SELECT min(id) FROM execution);   -- CHECK 는 통과한다
SELECT check_code, status, mismatch_count FROM pg_temp.recon_rules(:sid, (SELECT current_turn_no FROM season WHERE id = :sid)) WHERE status = 'MISMATCH';
ROLLBACK;
```

`CHECK`·`FK`는 행 하나의 모양만 본다. 수수료가 공식(요율 × 대금 올림)과 맞는지는 다른 표(대금)를 읽어야 해서 DDL이 못 막는다. 규칙 14 `FEE_RULE`이 잡고, 원장의 수수료 행과 체결이 어긋나므로 규칙 4 `ORDER_LEDGER`도 함께 걸린다(03 검증 5.2의 "DDL이 통과시키는 것 — 대사의 몫"과 같은 이야기). 그래서 대사가 있다.
</details>

**Q8. 롤백의 범위 (생각 + 복사 DB).** 23 실행 중 대사가 하나라도 어긋나면 `turn`·`turn_price`·피드는 사라지는데 `reconciliation_log`는 남는다. 같은 트랜잭션이 아닌 이유와, 남겨야 하는 이유는?

<details><summary>답</summary>

턴 전환(스냅샷·가격·피드)은 대사가 모두 `MATCH`일 때만 커밋해야 하므로 하나의 트랜잭션이고, 불일치면 `ROLLBACK`으로 전부 없던 일이 된다. 그 뒤 새 트랜잭션에서 불일치 기록(17행)만 남긴다. 같은 트랜잭션에 두면 롤백이 증거까지 지운다. 로그는 "무엇이 어긋났는지"의 감사 증거라서 되돌린 시도도 남아야 한다. 확인은 드릴 (i)로 한다.
</details>

**Q9. 사후 정보 (생각).** `26_season_report_def.sql` 머리의 제외 항목(X1–X12) 중 하나를 골라, 그 항목을 성적표에 넣으면 어떤 "미래 정보"가 감독에게 새는지 설명하라.

<details><summary>답</summary>

예 X3(경로 백분위): 백분위는 같은 시드에서 갈라진 미래 경로 수천 개의 분포가 있어야 계산된다. 그 분포를 시즌 중에 보여 주면 분포의 모양(오르는 쪽이 많은가, 내리는 쪽이 많은가)이 앞날의 힌트가 되어 감독이 아직 모르는 달을 알게 된다(PLAN §4.5, 사후 정보 금지). 그래서 X3은 예상 분포를 만드는 M2에서 시즌 확정 뒤에만 연다. 같은 방식으로 X7(소음 반응 방향)은 피드의 방향을 실제 다음 달과 맞춰 보는 항목이라 피드가 미래를 알려 주는지 시험하는 셈이 된다. 성적표는 진행 중에도 열 수 있으므로 정의는 스냅샷이 있는 턴까지만 읽고, 마지막 턴의 주문·전술 변경은 `pending_*`로 센다.
</details>

**Q10. SQL → Java (생각).** 「골든 파일」 표에서 `23`을 골라, P0-33이 Java로 같은 턴 전환을 만들 때 어떤 테이블의 어떤 행을 비교해야 "같은 시즌"이라 말할 수 있는가? 비교에서 뺄 열은?

<details><summary>답</summary>

`turn`·`turn_price`·`turn_feed`·`reconciliation_log`(턴마다 17행, 시도 번호)·`season`(현재 턴·상태)과, 전환이 건드리는 `cash_account`·`holding`·`lineup`이다. 대리 키(`id`), 시각(`created_at`), 낙관적 락 `version`은 구현마다 달라 뺀다. 시즌·회원·종목은 자연 키(턴 번호, 시장·종목 코드)로 바꿔 비교한다(「정규화」). 가격·금액은 `NUMERIC`이라 `BigDecimal.compareTo`로 비교한다.
</details>


## SQL 골든 하네스 (P0-25)

Java 시험은 이 폴더의 SQL을 **그대로** 실행해 기준 행을 만든다. 이후 Java 구현이 만든 행과 정확히 비교하는 시험 전용 기반이다(`src/test/java/antlab/support`, 운영 코드에는 없다). 소유자의 `antlab`·`antlab_api`·상시 컨테이너에는 접속하지 않는다. 시험이 일회용 PostgreSQL 17 컨테이너를 따로 띄운다.

**실행 방법.** 저장소 루트의 VS Code 통합 터미널에서 실행한다.

```bash
# 표본 CSV 없이 도는 시험(판정 파서·행 비교기·입력 공급·메타 명령·격리·Flyway DB 검증)
./gradlew test -PexcludeSqlGolden
# 실제 표본 골든 포함: 추적하지 않는 CSV 네 개가 있는 디렉터리를 지정한다
ANTLAB_GOLDEN_SAMPLE_DIR=<db/sample 경로> ./gradlew test
# 실제 표본 골든만
ANTLAB_GOLDEN_SAMPLE_DIR=<db/sample 경로> ./gradlew test -PonlySqlGolden
```

`db/`·`events/`·`feed/`의 파일, 표본 경로 환경 변수, 지정한 경로의 CSV가 바뀌면 Gradle이 시험을 다시 돌린다. 입력이 같으면 `:test UP-TO-DATE`로 건너뛴다. 이 경우 골든이 실행되지 않은 것이 아니라 직전 실행 결과를 그대로 사용한 것이다.

- 추적하지 않는 파일은 `asset_month_return.csv`·`asset_position.csv`·`fx_month.csv`·`hand_check.csv` 네 개뿐이다. 환경 변수가 없으면 작업본의 `db/sample`을 본다. 환경 변수를 지정하지 않았는데 파일이 하나라도 없으면 실제 표본 골든만 "실제 표본 골든을 건너뜁니다. 누락 파일: …"을 밝히고 건너뛴다(성공으로 세지 않는다). **환경 변수를 지정했는데 그 경로에 파일이 없으면 건너뛰지 않고 실패한다**(경로 오타로 골든이 돌지 않은 채 성공하는 것을 막는다). `db/sample/README.md`의 「표본 만들기」로 준비한다. 파일이 있어도 내용이 잘못되면 SQL 검증이 실패한다.
- SQL·`events/`·추적 CSV는 현재 작업본에서 읽는다. 네 CSV만 지정한 곳에서 읽는다. 입력은 경로와 SHA-256으로 고정한다. 호스트 파일은 수정하지 않고, `.env`·`data/raw/`는 복사하지 않는다.
- 컨테이너 작업 루트는 `/work`이고, 그 아래에 `db/`·`events/`·`feed/` 구조를 그대로 둔다(`feed/`는 24 피드 정의가 읽는 `templates.csv`·`variables.csv`뿐이다). `\copy`는 작업 디렉터리를 기준으로, `\ir`은 포함하는 파일의 위치를 기준으로 경로를 해석한다.

**시나리오(순서는 코드에 명시하고, 파일명 정렬로 자동 실행하지 않는다).**

| 시나리오 | 순서 | 목적 |
|---|---|---|
| `README_REGRESSION` | 위 「전체 순서」 표 S01–S37과 같은 순서: 01–03·검증 → 10–14·검증 → 20·21·22·검증 → 23 × 5 → 23·24 검증 → 25·검증 → 턴 중간 점검(20·21·22·24 검증, 22 수동 대사, 26 검증) → 23 × 5 → 23·24·25 검증 → 20·21·23 재실행 → 20·21 검증, 22, 22 검증 → 26 → 26 검증 → 90 | 위 「전체 순서」를 자동 검증한다. 선택 사항인 `_check`도 필수다 |
| `TURN_GOLDEN` | 01–03 → 10–13 → 20 → 21 → 23 × 10 | P0-33이 쓴다. Java와 비교할 기준 시즌. **22를 실행하지 않는다**(시도 번호 보존, P0-17 리뷰 13절) |
| 재생 | 별도 빈 DB 두 개에 같은 `TURN_GOLDEN` | SQL 재현성. 모든 테이블을 행 단위로 비교 |
| `POSITION_BASE_DATE` | 01–03 → 10 → 12 | P0-38 `PositionMeter`를 기준일 풀 전 종목에서 `asset_position` 저장값과 대조 |
| `FORMATION_DRILL` | `TURN_GOLDEN` 5턴 지점 → 25 드릴 `rebuy`(`-v drill=1`, 하네스 `golden_*` DB 전용) → 23 × 5 | P0-38 같은 턴 여러 변경 재생 골든(`FormationChangeDrillGoldenTest`) |
| 끝난 뒤 | 20 → 21 → 23 다시 실행 | 새 행이 없음(멱등) |

`_def`는 다른 파일이 읽는 포함 파일이므로 단계가 아니다. `23_turn_advance_drill.sql`은 기본 회귀에 넣지 않는다. 24–26과 90은 P0-21에서 `README_REGRESSION`에 등록했다. `TURN_GOLDEN`과 끝난 뒤 멱등 시나리오는 그대로 둔다(25 재생은 P0-36 매도·P0-37 전술·라인업 변경까지 구현됐고, 성적표 골든은 P0-38). 22와 23은 실행할 때마다 상태가 변하므로(로그 추가, 턴 진행) 일반 멱등 스크립트로 취급하지 않는다.

**마지막 판정 계약.** 검증 단계는 `psql -X -q -A -P footer=off -v ON_ERROR_STOP=1`로 실행한다. 종료 코드가 0이고 출력 끝 두 줄이 등록한 판정 열 이름(`p0_05_check` … `p0_21_check`)과 정확히 `PASS`일 때만 통과다. 앞쪽의 PASS나 종료 코드만으로는 성공으로 처리하지 않는다. 다른 검사 이름이 나오거나 판정 뒤에 결과가 추가되어도 통과시키지 않는다. 제한 시간(단계당 5분)을 넘기면 실패이며 다시 실행하지 않는다.

**DB 분리.** 하네스가 띄우는 일회용 컨테이너(또는 같은 컨테이너의 별도 DB)에서 역할마다 다른 DB를 쓴다. Flyway를 적용한 API 시험 DB, `01–03`부터 실행하는 SQL 기준 DB, 같은 입력을 적용하는 재생 DB(각각 새로 만든 빈 DB), 드릴 DB(기본 회귀 밖)를 따로 둔다. SQL 기준 DB에는 Flyway를 실행하지 않고, API DB에는 01–03을 실행하지 않는다. 각 SQL DB에는 연습 시즌이 하나뿐이다.

**골든 파일.** 어느 SQL 파일을 어느 Java 시험이 지금 읽고, 앞으로 어느 기능이 읽을지의 표다. SQL 파일을 바꾸면 같은 PR에서 이 표의 Java 쪽도 확인한다(행이 바뀌면 골든이 바뀐다). 새 기능이 SQL 파일을 더하면 이 표에도 줄을 더한다.

| 파일 | 지금 읽는 Java 시험 | 앞으로 읽을 기능 |
|---|---|---|
| `01`–`03` 스키마 | `SqlGoldenHarnessTest`(`README_REGRESSION`·`TURN_GOLDEN`), `SqlGoldenIsolationTest`, `SchemaParityTest`(Flyway DDL과 대조) | P0-23 이후 스키마 일치 |
| `10`–`13` 적재·계산 | `SqlGoldenHarnessTest`(입력 CSV와 함께), `PositionMeterBaseDateGoldenTest`(12 판정을 Java 측정기와 대조), `OneSeasonE2ETest`(API DB sample load 10 → 11 → 12 → 13 / API DB 표본 적재 10 → 11 → 12 → 13) | P0-27–29(적재·판정) |
| `20_season_start.sql` | `TURN_GOLDEN`·끝난 뒤 멱등, `SeasonStartGoldenTest`(`seasonStartGolden` — 같은 설정·경로·시드로 API가 만든 시즌 5개 표와 행 단위 비교) | P0-30 시즌 시작(완료) |
| `21_team_buy_def.sql`·`21_team_buy.sql`·`21_team_buy_check.sql` | `MoneyMathSqlParityTest`(순수 함수 5개를 `MoneyMath`와 같은 합성 입력으로 대조), `BuyOrderGoldenTest`(`teamBuyGolden` — SQL 0턴 주문을 같은 순서·수량·선언 포지션으로 API에 보내고 6개 표를 행 단위로 비교하며, API DB에서 `p0_15_check`를 통과) | P0-26(이미 읽음), P0-31 팀 구성(완료) |
| `22_reconcile_season_def.sql` | `README_REGRESSION`(수동 대사 15행 `MATCH`), `BuyOrderGoldenTest`(API가 만든 0턴 팀에 대해 `recon_rules(시즌, 0)`의 15행이 `MATCH`인지 확인), `ReconciliationSqlCopyTest`(규칙 쿼리 15개가 이 파일 본문의 기계적 치환 사본과 글자 단위로 같은지 확인), `ReconciliationServiceParityTest`·`ReconciliationMutationTest`·`ReconciliationPriceParityTest`(같은 연결에 이 정의를 싣고 Java 결과와 `recon_rules`의 9컬럼을 비교), `ReconciliationGoldenTest`(실제 표본 API 시즌에서 규칙별 결과와 기록 두 시도를 비교) | P0-31·P0-32(읽음), P0-33 턴 전환(11·19를 같은 방식으로 더하고 시험을 23까지 넓힌다) |
| `23_turn_advance*.sql`·`24_turn_feed_def.sql` | `TurnAdvanceGoldenTest`·`ReconciliationSqlCopyTest`·`ReconciliationTurnParityTest`(P0-21 하류)·`TURN_GOLDEN`(10턴 기준 시즌)·재생 | P0-33 턴 전환·피드 |
| `25_trade_formation_def.sql` | `README_REGRESSION`(5턴 매매 포함 전체 순서), `SellOrderGoldenTest`·`FormationChangeGoldenTest`(25 재생) | P0-36·37 매도·전술 변경 재생 — P0-37 골든은 SQL 25의 전술·라인업 변경과 주문 연결을 포함한다. 표본 경로를 지정하고 필수 골든을 건너뛰지 않았는지 확인한다 |
| `26_season_report_def.sql` | `README_REGRESSION`(성적표 검증 PASS), `SeasonReportTradeGoldenTest`·`SeasonReportDrillGoldenTest`·`SeasonReportJavaOnlyGoldenTest` | P0-38 성적표 |
| `90_full_run_check.sql` | `README_REGRESSION`(마지막 단계), `OneSeasonE2ETest`(run unchanged on a 12-turn season played through the API / API로 둔 12턴 시즌에 그대로 실행) | — |


**하류가 쓰는 법.**

| 기능 | 하네스 사용 |
|---|---|
| P0-32 대사(완료) | `queryWithDefinitions`로 `22_reconcile_season_def.sql`을 읽은 같은 세션에서 `recon_rules`를 질의하고, 합성 상태·깨뜨린 사례는 `ReconDefinitions.load`로 같은 JDBC 연결(시험 트랜잭션)에 정의를 싣는다. API 시즌 준비는 `TeamBuyGoldenSeason`이 맡는다 |
| P0-33 턴 전환 | `TURN_GOLDEN` 시즌에서 `captureReplayInput`으로 경로·0턴 팀을 꺼내 Java로 재생한다. `GoldenRowQueries`의 테이블별 질의·명세로 비교한다. 진행 턴 `turn_price`는 풀 전체(현재 표본 200행)를 비교하고, 0턴 가격은 따로 비교한다. 대사는 턴마다 17행·시도 번호를 보존한다 |
| P0-36 매도 | 25 머지 뒤 매도 행동을 턴·순서대로 재생한다(질의·명세 확장) |
| P0-37 전술 변경(완료) | 재생 입력의 행동을 주문·전술 변경·라인업 변경·턴 전환의 자료형으로 나누고, 같은 턴 안의 순서는 기록 시각으로 병합한다(라인업 변경은 그 턴 맨 끝). 비교 대상에는 전술 변경 이력(턴·같은 턴 안 순번), 주문의 변경 연결(논리 키), 라인업 구간(구간 순번 포함)을 더한다. 변경 비용은 `queryWithDefinitions`로 `25_trade_formation_def.sql`의 `formation_costs`를 읽어 내부 조회와 대조한다 |
| P0-38 성적표(완료) | 26 정의를 연습 시즌과 Java 시즌에 돌리고 API 응답과 셋을 대조한다(독려 3열은 반대쪽 단언). 롤백 드릴 시즌·Java 단독 긴 시즌(역할 수행 양성)·같은 턴 여러 변경 드릴 시즌(`FORMATION_DRILL`)을 함께 둔다. 12 정의는 기준일 판정 대조(`POSITION_BASE_DATE`)에도 쓰인다 |
| P0-40 one-season E2E (done) / P0-40 한 판 E2E(완료) | Loads 10–13 into the harness API DB, plays one season over a real port, then runs the dev manual reconciliation (at turn 0 and at the end) and `90` unchanged. / 하네스 컨테이너의 API DB에 10–13을 적재하고 실제 포트로 한 판을 둔 뒤 개발용 수동 대사(0턴과 끝)와 `90`을 그대로 실행한다. |

**정규화.** 대리 키·시즌·회원 식별자는 자연 키(시장·종목 코드, 턴 번호, 재생 행동 키)로 바꿔 비교한다. 제외 열은 이름과 이유를 명세에 적는다(전역으로 넓히지 않는다). NUMERIC은 `BigDecimal.compareTo`로 정확히 비교하며 자릿수 차이만 허용한다. NULL은 빈 값·0과 구분한다. 중복 키는 합치지 않고 보고한다. 같은 키의 한 행이 여러 열에서 달라도 `CHANGED` 한 건으로 처리한다. 보고에는 테이블·행 키·다른 열 이름·건수만 나온다. 가격·금액·원문 출력은 실패 메시지와 시험 보고서에 싣지 않는다. 로컬에서 원문을 봐야 하면 `ANTLAB_GOLDEN_RAW_DIR`에 저장소 밖 경로를 지정한다(저장소 아래 경로는 거부하고, 그 디렉터리에만 쓰며 커밋하지 않는다).

**API로 수동 대사 돌리기(P0-32).** 로컬 `antlab_api`에서는 `22_reconcile_season.sql` 대신 `local` 프로필의 개발 엔드포인트 `POST /api/v1/dev/seasons/{seasonId}/reconciliation`을 쓸 수 있다(본문은 없고 고정 헤더 `X-Antlab-Dev: true`가 있어야 하며(없으면 403 `FORBIDDEN`) SEC-04부터는 `ADMIN` 역할의 토큰도 필요하다(없으면 401, `USER`면 403, ADMIN은 erd §9의 SQL로 만든다). / From SEC-04 it also needs a token with role `ADMIN` (none → 401, `USER` → 403; make ADMIN with the SQL in erd §9)., 호출할 때마다 새 시도 번호로 15행을 기록하며 일치해도 남긴다). 응답에는 규칙 번호·코드·상태·건수·시도 번호에 더해 기대·실제·차이·`detail`이 `reconciliation_log` 행 그대로 담긴다(소유자 결정 2026-11-11 — 22는 값을 출력하지 않지만 이 개발 엔드포인트는 값까지 보여 준다). `local` 프로필은 `127.0.0.1`로만 열린다. 호출 예: `curl -fsS -X POST -H 'X-Antlab-Dev: true' http://127.0.0.1:8080/api/v1/dev/seasons/1/reconciliation`. SQL 수동 대사와 같은 번호 공간을 사용하므로 둘을 섞어 실행해도 시도 번호가 이어진다. `./gradlew bootRun`은 `SPRING_PROFILES_ACTIVE`가 없으면 `local`로 실행되므로 이 경로가 열린다(컨테이너는 compose가 `prod`를 준다).

**이 하네스가 보장하는 것과 아닌 것.** 같은 입력으로 SQL을 두 번 실행하면 같은 행이 나오는지(재현성)를 확인한다. 정규화·비교가 오류를 지우지 않는지도 확인한다. 업무 공식이 옳은지는 `_check`의 독립 계산이 따로 본다. Java와 SQL의 동등성은 P0-32·33·36·38의 시험이 따로 본다.

## 결과 읽는 법

**스키마 파일(`01_schema_history.sql`).**

- 종료 코드가 0이고 `ERROR`·`WARNING`이 없으면 성공이다.
- `P0-05 스키마: 이름이 public이 아닌 곳을 가리킨다`로 멈추면 접속 역할 이름의 스키마 같은 `search_path` 앞쪽 스키마가 있다는 뜻이다. 파일은 아무것도 지우거나 만들지 않고 종료 코드 3으로 끝난다. `search_path`(또는 그 스키마)를 정리한 뒤 다시 실행한다.
- 빈 DB에서 처음 실행하면 `DROP TABLE IF EXISTS`가 `NOTICE: table "…" does not exist, skipping` 8건을 남긴다. 테이블이 없다는 알림일 뿐 오류가 아니다. 두 번째 실행부터는 아무것도 나오지 않는다.

**스키마 파일(`02_schema_season.sql`).**

- 종료 코드가 0이고 `ERROR`·`WARNING`이 없으면 성공이다. 빈 DB에서 처음 실행하면 `NOTICE: table "…" does not exist, skipping` 9건이 나오고, 두 번째 실행부터는 아무것도 나오지 않는다.
- `P0-06 스키마: 이름이 public이 아닌 곳을 가리킨다`로 멈추면 01 스키마와 같은 뜻이다. 이 파일이 이름으로 가리키는 13개 테이블(02의 9개 + 01의 4개) 중 `public`이 아닌 곳으로 해석되는 것이 있다. 아무것도 지우지 않고 종료 코드 3으로 끝난다.
- `relation "month_block" does not exist`(`42P01`)로 멈추면 01 스키마를 먼저 실행하지 않은 것이다. 파일 전체가 한 트랜잭션이라 아무것도 남지 않는다. 01을 실행한 뒤 다시 한다.
- `cannot drop table turn_feed because other objects depend on it`(`2BP01`)로 멈추면 뒤 번호 파일(03)의 테이블이 02의 테이블을 가리키고 있다는 뜻이다. 안전한 실패이며 아무것도 바뀌지 않는다. 전체를 다시 만들 때는 (b′)를 쓴다.

**스키마 파일(`03_schema_account.sql`).**

- 종료 코드가 0이고 `ERROR`·`WARNING`이 없으면 성공이다. 빈 DB에서 처음 실행하면 `NOTICE: table "…" does not exist, skipping` 5건이 나오고, 두 번째 실행부터는 아무것도 나오지 않는다. 03은 늘 다시 실행할 수 있다(03의 테이블을 가리키는 뒤 번호 파일이 아직 없다). 다시 실행하면 03의 5개 테이블만 지우고 다시 만들며 01·02 테이블의 행·제약·인덱스는 그대로다.
- `P0-07 스키마: 이름이 public이 아닌 곳을 가리킨다`로 멈추면 01·02 스키마와 같은 뜻이다. 이 파일이 이름으로 가리키는 9개 테이블(03의 5개 + `season`·`asset`·`formation_change`·`turn_feed`) 중 `public`이 아닌 곳으로 해석되는 것이 있다. 아무것도 지우지 않고 종료 코드 3으로 끝난다.
- `relation "season" does not exist`(`42P01`)로 멈추면 02 스키마(와 01)를 먼저 실행하지 않은 것이다. 파일 전체가 한 트랜잭션이라 아무것도 남지 않는다. 01 → 02를 실행한 뒤 다시 한다.

**검증 파일(`01_schema_history_check.sql`).** 마지막 출력이 `p0_05_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. `PASS`는 1–7절이 모두 통과하고 끝의 흔적 확인까지 끝났을 때만 나온다. 먼저 0절(전제) 결과 표가 나오고, 0절에 FAIL이 있으면 거기서 멈춘다(전제를 맞춘 뒤 다시 실행). 이어서 세 가지 표가 나온다.

1. **절별 집계** — `section`(절)마다 `total`(판정 수), `pass`, `fail`. 모든 `fail`이 0이어야 한다. 절은 0 전제, 1 구조, 2 양성, 3 음성, 4 경계, 5 H1, 6 DDL, 7 판정이다.
2. **FAIL 행 목록** — 실패한 판정마다 `item`(무엇을), `expected`(기대), `actual`(실제)을 나란히 보여 준다. **0행이어야 정상**이다.
3. **판정 행** — `verdict`가 `PASS`/`FAIL`이고 전체 `total`, `pass`, `fail`을 보여 준다. 7.4의 `verdict`는 1–7절 판정이고, 최종 판정은 마지막 줄이다.

FAIL이 하나라도 있으면 오류 메시지와 함께 종료 코드 3으로 끝나고, `PASS`는 나오지 않는다(`ON_ERROR_STOP` 없이 돌려도 마지막 줄은 `FAIL`이다. 이때 종료 코드는 0일 수 있다. `-v ON_ERROR_STOP=1` 없이 실행했다면 종료 코드가 아니라 마지막 줄을 본다).

`-q`를 빼면 사례마다 `CALL`이 한 줄씩 출력되어 결과가 묻힌다.

**각 절이 하는 일 (01 검증 파일 기준).**

- **0 전제:** PostgreSQL 17, UTF8, `TimeZone = Asia/Seoul`, `session_replication_role = origin`(FK 검사를 끄지 않음), 8개 테이블이 있고 비어 있음. 8개 이름이 모두 `public`의 테이블로 해석되는지도 본다(`search_path` 앞쪽에 같은 이름의 테이블이 있으면 FAIL). 하나라도 FAIL이면 결과를 보여 주고 바로 멈춘다(픽스처를 넣지 않는다). 현재 DB 이름은 정보 행으로 나온다.
- **1 구조:** erd 5.4와 카탈로그를 대조한다. 테이블별 개수(PK 8·UNIQUE 8·FK 8·CHECK 47·`idx_` 5), 제약·인덱스 이름·종류·테이블 1:1, 정의(CHECK는 erd 식을 PostgreSQL에 다시 파싱시켜 비교), 컬럼 81개의 순서·타입·NULL·기본값·identity·노출 태그, 부동소수 컬럼 없음, 모든 제약 검증됨, FK 트리거 켜져 있음.
- **2 양성:** 통과해야 정상인 사례(과잉 제약이 없다는 증거). 여기서 만든 행이 뒤 절의 픽스처다.
- **3 음성:** 실패해야 정상인 사례. `expected`는 SQLSTATE와 제약 이름이다(`23514` CHECK 위반, `23505` UNIQUE 위반, `23503` FK 위반, `23502` NOT NULL 위반은 컬럼 이름, `428C9` identity 직접 지정). 한 행이 여러 CHECK를 어기면 NOT NULL이 먼저, 그다음 이름의 알파벳 순으로 첫 위반 하나만 보고되므로 그런 사례의 `item`에는 먼저 보고되는 이름을 적어 두었다.
- **4 경계:** 저장 정밀도 경계. CHECK는 반올림된 저장값을 본다(예: `local_return = -1.000000005`는 `-1.00000001`로 저장되어 위반, `-1.000000004`는 `-1.00000000`으로 저장되어 통과). `VARCHAR(5)`에 폭을 넘는 뒤 공백만 있는 `'STOCK '`은 오류 없이 잘려 통과하고, 폭 안의 뒤 공백 `'ETF  '`는 값의 일부라 거부된다. NUMERIC 컬럼에 `'NaN'`을 넣으면 23514다(NaN은 자기와 같고 모든 수보다 크게 비교되어 하한·등식 CHECK만으로는 막히지 않는다 — 값 CHECK가 없는 참여율 2개·관심 값 2개는 NaN 전용 CHECK가 막는다). `'Infinity'`는 22003.
- **5 H1:** 검증 규칙 H1(같은 기준일의 USD 종목은 환율이 하나, KRW 종목은 1)의 예시 쿼리. 위반 0건 / USD 불일치 1건 / KRW 환율 2 1건을 확인한다.
- **6 DDL:** 음성 DDL(`42830`, `42P01`, `2BP01`, `42710`, `42P07`)과 PostgreSQL이 막지 않는 사례 하나(컬럼 CHECK의 다른 컬럼 참조).
- **7 판정:** 커버리지(`ck_` 47 + `uq_` 8 + `fk_` 8 = 63개 이름이 각각 자기 이름으로 한 번 이상 보고되었는가, `63/63`)와 절별 집계, FAIL 행, 판정.

**검증 파일(`02_schema_season_check.sql`).** 읽는 법은 01 검증과 같다. 마지막 출력이 `p0_06_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. 01 검증과 다른 점:

- **0 전제:** 01의 8개와 02의 9개, **17개 테이블이 모두 비어 있어야 한다**(픽스처가 01 테이블 4개에도 행을 넣는다). 표본을 적재한 DB(`antlab`)에서는 이 전제가 FAIL이므로 검증용 DB에서만 돌린다.
- **1 구조:** 제약·인덱스 101개(PK 9·UNIQUE 7·**유일 인덱스 2**·FK 14·CHECK 60·`idx_` 9), 컬럼 105개. 유일 인덱스 `uq_member_email`(`lower(email)`)과 `uq_lineup_open`(`WHERE end_turn IS NULL`)은 제약이 아니라 인덱스라 `pg_constraint`가 아니라 `pg_indexes`에서 대조하고, 정의는 erd 식을 PostgreSQL에 다시 파싱시켜 비교한다. 이름 규칙 밖의 인덱스(예: `foo`)가 하나라도 있으면 FAIL이다. FK 인덱스 짝(14/14)은 부분·표현식 인덱스를 짝으로 치지 않는다.
- **3 음성:** 기준 행에서 한 컬럼만 어기는 사례가 기본이다. `formation_change`·`turn_feed`의 복합 FK 대상 UNIQUE 2개는 PK에 가려 단독으로 걸리지 않으므로, 한 사례 안에서 PK를 지웠다가 되돌려 증명한다.
- **4 경계:** 01과 같은 NaN 사례 — 턴 금액 7개는 하나씩과 전부 NaN, 대사 기록은 기대·실제·차이의 부분 NaN까지 23514다.
- **5 인덱스:** 부분·표현식 유일 인덱스의 동작(대소문자만 다른 이메일 거부, 같은 종목의 열린 구간 둘 거부, 열린 구간을 닫고 새로 여는 것은 통과, 겹치는 닫힌 구간은 DDL이 막지 못함).
- **7 판정:** 커버리지는 `ck_` 60 + `uq_` 제약 7 + `uq_` 인덱스 2 + `fk_` 14 = 83개 이름이 각각 자기 이름으로 보고되었는가(`83/83`).
- **흔적(8절):** 17개 테이블의 행 0, `zz_` 객체 0, 이 파일 9개 테이블의 제약 90개·인덱스 27개가 그대로다. 01 테이블의 제약·인덱스는 세지 않는다. 01 검증은 02가 있어도 그대로 `PASS`다.

**검증 파일(`03_schema_account_check.sql`).** 읽는 법은 01·02 검증과 같다. 마지막 출력이 `p0_07_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. 01·02 검증과 다른 점:

- **0 전제:** 01의 8개, 02의 9개, 03의 5개, **22개 테이블이 모두 비어 있어야 한다**. 22개 이름은 파일 맨 앞(`BEGIN` 앞)의 임시 표 `zz_table_list` 한 곳에만 있고, 0절의 자기 점검 행이 owner별 개수(01=8 02=9 03=5)를 확인한다. 표본을 적재한 DB(`antlab`)에서는 FAIL이므로 검증용 DB에서만 돌린다.
- **1 구조:** 제약·인덱스 53개(PK 5·UNIQUE 2·FK 10·CHECK 28·`idx_` 8), 컬럼 41개. 컬럼 코멘트는 태그뿐 아니라 논리명 전문(`[노출] 잔액`)을 대조한다. 복합 FK 3개는 컬럼 목록과 순서를 `(a, b, c) → t (x, y, z)` 꼴로 대조하고 `MATCH SIMPLE`(`match=s`)까지 확인한다. FK 인덱스 짝은 10/10이다.
- **3 음성:** `ck_orders_type`(`ck_orders_limit_price`에 가림)과 `ck_cash_ledger_type`(`ck_cash_ledger_amount_sign`·`ck_cash_ledger_ref_order`에 가림)은 DML로는 자기 이름이 보고되지 않는다. 먼저 가림 자체를 기록하고, 같은 사례 안에서 가리는 제약을 지운 뒤 자기 이름이 보고되는 것을 증명한다. `uq_orders_id_season`은 PK에 가려, 그 PK를 가리키는 `fk_execution_order`와 PK를 사례 안에서 지우고 증명한다(`pg_constraint.conindid`로 어느 FK가 어느 인덱스를 타는지 전제 단언을 남긴다). 사례는 모두 서브트랜잭션이 되돌린다.
- **4 경계:** 금액 컬럼에 `'NaN'`을 넣으면 23514다(NaN은 자기와 같고 모든 수보다 크게 비교되어 `>= 0`·원 단위 CHECK만으로는 막히지 않는다). `'Infinity'`는 22003.
- **5 복합 FK:** 5.1은 같은 시즌·같은 턴이면 통과, 다른 시즌의 변경·같은 시즌 다른 턴의 변경·다른 시즌의 피드·다른 시즌 주문을 가리키는 원장은 `23503`인 사례다. 5.2는 DDL이 통과시키는 것 — 수수료가 공식과 1원 다른 체결, 원장 합이 잔액과 다른 계좌처럼 대사 규칙(erd 7.5)이 잡는 행들을 성공 사례로 기록한다.
- **7 판정:** 커버리지는 `ck_` 28 + `uq_` 제약 2 + `fk_` 10 = 40개 이름이 각각 자기 이름으로 보고되었는가(`40/40`).
- **흔적(8절):** 22개 테이블의 행 0, `zz_` 객체 0, 이 파일 5개 테이블의 제약 45개·인덱스 15개가 그대로다. 01·02 검증은 03이 있어도 그대로 `PASS`다.

**알아 둘 점.**

- **흔적:** 검증 파일 전체가 한 트랜잭션이고 7절 뒤에 `ROLLBACK`한다. 끝(8절)에서 그 파일 테이블의 행 0, `public`의 `zz_` 객체 0, 제약·인덱스 개수 그대로를 다시 확인한다(01은 8개 테이블·제약 71개·인덱스 21개, 02는 17개 테이블의 행 0·제약 90개·인덱스 27개, 03은 22개 테이블의 행 0·제약 45개·인덱스 15개). **identity(번호표) 값 소비만 되돌아오지 않는다.** 검증을 돌리면 다음 INSERT의 `id`가 건너뛴 값부터 시작한다. 그래서 검증은 검증용 DB에서만 돌린다.
- **공백 값:** `asset.ticker`와 `month_event.event_key`의 앞뒤 공백은 DB가 막지 않는다(`'ZZAAPL'`과 `'ZZAAPL '`은 서로 다른 값). 2절에 현재 동작으로 기록해 두었다. 적재(P0-08)가 `trim`하고 공백 포함 값이 없는지 확인한다.
- **스키마 파일을 다시 실행하면** 그 파일 테이블의 데이터가 모두 사라진다(01은 8개, 02는 9개, 03은 5개). 표본 적재 이후에는 (b)의 01·02 줄을 실행하지 않는다(03 줄만 더하는 것은 된다 — 03은 01·02의 행을 건드리지 않는다).

**사건 적재(`11_load_events.sql`)와 검증(`11_load_events_check.sql`).** 형식·규칙의 정본은 [`events/README.md`](../events/README.md)다.

- 적재: 종료 코드 0이고 마지막에 표 둘이 나오면 성공이다. 첫 표는 `month_event`의 파일 행 수와 새로 넣은 수, `events_reviewed`를 FALSE에서 TRUE로 바꾼 달 수이고, **다시 실행하면 변경 수가 0·0**이다. 둘째 표는 유형별 표본 수 n(태그가 있는 에피소드 수)과 `category_hidden`(n < 3, 시즌에서 범주를 말하지 않는 카드 대상)이다. Phase 0 표본은 100개 달뿐이라 대부분의 유형이 n < 3인 것이 정상이다.
- 적재가 멈추는 경우(모두 종료 코드 3, DB는 그대로): `events/ 파일이 없다`는 저장소 루트가 아니라는 뜻이고, `파일이 비어 있다(머리글만)`은 CSV에 데이터 행이 없다는 뜻이다 — 둘 다 DB에 닿기 전이다. `입력 검사 실패`는 파일 형식·정렬·유형 규칙·편집 완료 표와의 어긋남이고 메시지 첫 20건이 어느 줄인지 알려 준다. `파일과 DB 가 어긋난다`는 이미 적재한 행을 파일에서 고쳤거나 지웠다는 뜻이다 — 값 정정은 하지 않는다. 고쳐야 하면 (b′)로 `antlab`을 다시 만들고(01 → 02 → 03) 10 → 11 순서로 적재한다. `10 적재를 먼저`는 `month_block`·`asset_month_return`·`fx_month`가 비어 있다는 뜻이다. `column name mismatch in header line`은 CSV 머리글이 열 계약과 다르다는 뜻이다.
- 검증: 마지막 출력이 `p0_09_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. 읽는 법은 10 검증과 같다(0절 전제 표 → 유형별 표본 수 표 → 절별 집계 → FAIL 행 → 판정 → 마지막 줄). 절은 0 전제, 1 행 수, 2 대조·형식, 3 H5, 4 반응·강도, 5 편집 완료, 6 보고·음성, 7 판정이다.
  - **2절:** DB와 `events/tags.csv`가 열 단위로 같은지, 키 형식, 에피소드 규칙, 달당 태그 3개 이하, H6(유형·결과가 `events/types.csv` 목록 안, 범주·방향 규칙)을 본다.
  - **3절(H5):** 노출 코드(유형·결과)에 숫자 0건, 두 글자 토큰은 `US`/`KR`만, 금지 토큰(나라·기관·회사·인물·사건 이름, 검증 파일 안 목록) 0건이다. `events/types.csv`에도 같은 검사를 한다.
  - **4절:** `reaction_change`가 참조 종목·환율의 그 달 값과 정확히 같고 NULL이 0건이며, `strength`가 경계 표(5%·10%)의 단계와 같다.
  - **5절:** 표본 100개 달이 전부 편집 완료이고, 태그가 있는 달은 편집 완료이며, `NONE`인 달은 태그 0건, `TAGGED`인 달은 1건 이상이고, `reviewed.csv`의 달 집합이 DB와 같으며 파일 안에 중복 달이나 `TAGGED`/`NONE` 밖의 값이 없다.
  - **6절:** 표본 수 n < 3 목록이 `tags.csv`에서 독립으로 센 목록과 같은지 보고, 규칙 21개마다 일부러 위반 1건을 만들어 검사 쿼리가 1건으로 잡는지 센 뒤 되돌린다. 7절의 규칙 커버리지는 `21/21`이어야 한다.
  - **8절 흔적:** `month_event`·`month_block`의 행 수·identity·편집 완료 달 수가 실행 전과 같고 `zz_` 객체가 0이다. `events/` 파일이 없으면(저장소 루트가 아니면) 0절이 FAIL이다.
- 10 검증은 11 적재 뒤에도 그대로 `PASS`다(`events_reviewed`와 `event_summary`는 10 검증이 비교하지 않는다).
- 파일을 고친 뒤 적재와 검증 전체를 임시 인스턴스에서 확인하려면 (d)의 흐름에 10·11·12 줄을 이어 쓴다. 10 적재는 시세·환율 파생 CSV가 필요하므로 그 CSV가 있는 저장소 루트에서만 돈다.

**시스템 판정 포지션 계산(`12_calc_position.sql`)과 검증(`12_calc_position_check.sql`).** 정의의 정본은 [`docs/erd.md`](../docs/erd.md) D35(5.3 `asset_position` 코멘트)이고 정의는 `12_calc_position_def.sql` 한 곳이다. 시스템 판정은 시가총액을 읽지 않고(D15), 모든 문턱 비교를 저장되는 8자리 값으로 한다.

- 계산: 종료 코드 0이고 마지막에 표 둘이 나오면 성공이다. 첫 표는 `target_rows`(`asset_position` 행 수)·`filled_now`(이번에 채운 행)·`already_same`(이미 같은 값으로 판정돼 있던 행)·`still_empty`(아직 판정이 비어 있는 행)이고, **다시 실행하면 `filled_now`가 0**이다. 둘째 표는 판정 불능 사유별 건수만 낸다(값은 내지 않는다): `unjudgeable_months`(사용한 달 24 미만)·`unjudgeable_n_up`(오른 달 5 미만)·`unjudgeable_n_down`(빠진 달 5 미만)·`unjudgeable_zero_den`(분모 0). 넷 중 하나라도 0이 아니면 `still_empty`가 0이 아니고, 그 기준일은 H2가 잡는다(시즌 시작 S2가 막는다 — 0 대입·절단은 하지 않는다).
- 계산이 멈추는 경우(모두 종료 코드 3, 아무것도 바뀌지 않는다): `10 적재를 먼저`는 `asset_position`·`asset_month_return`이 비어 있다는 뜻이다. `저장된 판정과 계산값이 어긋난다`는 이미 판정된 행이 지금 수익률·공식으로 계산한 값과 다르다는 뜻이다 — 값 정정은 하지 않는다. 고쳐야 하면 (b′)로 `antlab`을 다시 만들고(01 → 02 → 03) 10 → 12 순서로 다시 돌린다. `시장 대용 종목의 수익률이 없다`는 시장 대용(`069500`·`SPY`)의 행이 창 안 어느 달에 빠졌다는 뜻이고, `USD 종목에 환율 행이 없다`는 미국 종목이 있는 어느 달의 USD 환율 행이 빠졌다는 뜻이다(창 밖 달이어도 멈출 수 있다 — erd H4가 전 구간에 환율을 요구한다). 둘 다 메시지의 `month_start`가 빠진 달이고(0으로 덮지 않고 멈춘다), `NaN`·`월의 첫날이 아닌 행` 메시지는 입력이 계산 가능한 모양이 아니라는 뜻이다. 판정 값이 NUMERIC(12,8) 범위를 넘으면 `22003`으로 멈춘다(절단하지 않는다).
- 검증: 마지막 출력이 `p0_10_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. 읽는 법은 13 검증과 같다(0절 전제 표 → 절별 집계 → FAIL 행 → 판정 → 마지막 줄). 절은 0 전제, 1 저장값, 2 손 계산, 3 경계 표, 4 순위, 5 H9, 6 타입, 8 판정이고 음성 사례("규칙을 일부러 어기면 잡히는가")는 규칙이 속한 절에 `음성 [규칙]` 행으로 나온다. 약 15초 걸린다(환경에 따라 10–25초). 번호를 직접 지정한 합성 행만 넣고 모두 되돌리므로 identity를 소비하지 않아 `antlab`에서도 돌린다. 출력에는 건수와 PASS/FAIL만 나온다(참여율·변동성·종목별 포지션은 내지 않는다).
  - **1절:** 저장값을 사용자 정의 곱 집계(누적 곱)·자기 조인 최고점·합·제곱합 분산식·평균의 비로 따로 계산한 값과 8자리까지 비교하고(정의의 재귀 CTE·`stddev_samp`·합의 비와 다른 방법이다), 판정 8컬럼이 모두 채워졌는지(H2의 전제), 창이 기준일 달의 바로 전 달에서 끝나는지(H2), 저장된 포지션이 저장된 값과 같은 기준일 풀의 순위로 판정 함수를 다시 불러 낸 값과 같은지(일관성)를 본다.
  - **2절:** 먼 미래(2097–2099)의 합성 월 블록 36개와 합성 종목 13개(시세 아님)에서 손으로 계산한 기대값과 비교한다(밴드 경계 ±1% 포함, 미국 종목 환율 결합, 첫 달에 바로 빠진 종목의 낙폭(W0 = 1), 표본 표준편차(n − 1), 최소 달 수 24/23·오른 달 5/4·빠진 달 5/4, 판정 불능의 8컬럼 NULL, 빠진 대용 종목·환율 행은 오류로 멈춤). 여섯 포지션이 모두 한 번씩 나온다.
  - **3절:** 판정 함수를 직접 불러 문턱 바로 위·아래(0.20000000/0.20000001 같은 8자리 차이)와 여러 규칙에 걸리는 사례의 순서를 본다.
  - **4절:** 합성 풀로 k = CEIL(0.2 × N)(N = 20 → 4, 21 → 5, 1 → 1), 경계 동률 포함(4위 동률 둘 → 5개), 반올림 값이 같고 원값이 다른 쌍이 같은 순위인지 본다.
  - **5절(H9):** 기준일 달과 그 뒤 월 블록을 더하고 값을 바꾸거나 창 앞 달(표본의 2007–2012)을 바꿔도 판정 8컬럼이 변이 전과 같은지, 창 안 한 달을 바꾸면 달라지는지(시험이 헛돌지 않음), 창 끝을 기준일 달로 민 정의를 시험이 잡는지(이빨 확인)를 본다.
  - **변이(음성) 사례:** 정의의 글자를 `pg_get_viewdef`·`pg_get_functiondef`로 읽어 `replace`로 바꾼 같은 이름의 뷰·함수를 서브트랜잭션 안에서 다시 만든 뒤(예: 밴드 `≥` → `>`, `stddev_samp` → `stddev_pop`, 최고점에서 W0 빼기, 순서 바꾸기) 규칙의 검사 쿼리가 위반을 세는지 본다. 바꿀 글자가 정의에 없으면 오류라 시험이 헛돌지 않는다.
- 12 뒤에도 10·11·13·14 검증은 그대로 `PASS`다(12는 `asset_position`의 판정 컬럼만 쓴다). 01–03 검증은 적재가 없는 `antlab_test`에서 돌고 12는 그 DB에서 돌지 않는다.
- `\ir`은 psql 메타 명령이라 명령줄이 기준이다. VS Code에서는 통합 터미널(`Ctrl` + `` ` ``)에서 실행한다.

**관심 지수 계산(`13_calc_attention.sql`)과 검증(`13_calc_attention_check.sql`).** 공식의 정본은 [`docs/erd.md`](../docs/erd.md) 5.3(확정 공식 비고)·D33이고 계산식 정의는 `13_calc_attention_def.sql` 한 곳이다.

- 계산: 종료 코드 0이고 마지막에 표 둘이 나오면 성공이다. 첫 표는 `target_rows`(대상 행, 수익률 행 수와 같다)·`inserted_now`(이번에 넣은 행)·`already_same`(이미 같은 값으로 있던 행)이고, **다시 실행하면 `inserted_now`가 0**이다. 둘째 표는 건수만 낸다(값은 내지 않는다): `null_window_short`(창이 12개월 미만이라 z가 NULL인 행), `null_sd_floor`(표준편차 하한 미만이라 NULL인 행), `clipped_plus3`·`clipped_minus3`(±3으로 자른 행), `segment_starts`(표본이 끊긴 뒤 첫 달 행).
- 계산이 멈추는 경우(모두 종료 코드 3, 테이블 행은 그대로 — 빠진 행과 어긋난 행이 함께 있으면 INSERT가 먼저 돌아 identity 번호는 넘어갈 수 있다): `10 적재를 먼저`는 `asset_month_return`이 비어 있다는 뜻이다. `계산값과 DB 가 어긋난다`는 이미 저장된 행이 지금 수익률로 계산한 값과 다르다는 뜻이고(수익률이나 공식이 바뀜), `DB 에만 있는 행`도 같은 계열이다 — 값 정정은 하지 않는다. 고쳐야 하면 (b′)로 `antlab`을 다시 만들고(01 → 02 → 03) 10 → 11 → 13 순서로 다시 돌린다. `NaN`·`월의 첫날이 아닌 행` 메시지는 입력이 계산 가능한 모양이 아니라는 뜻이다.
- 검증: 마지막 출력이 `p0_11_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. 읽는 법은 11 검증과 같다(0절 전제 표 → 절별 집계 → FAIL 행 → 판정 → 마지막 줄). 절은 0 전제, 1 행 수, 2 독립 계산, 3 규칙, 4 손 계산, 5 H8, 6 순위·그룹, 8 판정이고 음성 사례("규칙을 일부러 어기면 잡히는가")는 규칙이 속한 절에 `음성 [규칙]` 행으로 나온다.
  - **2절:** 윈도 함수(`LAG`, 누적 `SUM`, 프레임 `12 PRECEDING AND 1 PRECEDING`)와 합·제곱합 식으로 따로 계산한 창·z·streak·지수가 저장값과 전 행 같은지 본다. 계산 쓰기 쪽(자기 조인·상관 서브쿼리·`stddev_samp`)과 다른 방법이라 서로를 검증한다.
  - **4절:** 합성 수익률(시세 아님) 12사례를 한 구간에 덮어쓰고 손으로 계산한 기대값과 비교한다(정상 z, +3·−3 절단, 0 수익률, 표준편차 0·하한 바로 아래·정확히 하한·바로 위, 0 다음 달 재시작, 보너스 단계와 상한). 서브트랜잭션에서 되돌린다.
  - **5절(H8):** 기준 달 M 7개 × 변이 3종(M 뒤 수익률을 모두 바꿈 / 바로 다음 달만 바꿈 / M 뒤 행을 지움)에서 같은 계산식을 다시 돌려도 M까지의 행이 변이 전 같은 계산식의 결과(그리고 저장값)와 같아야 한다. 시험이 헛돌지 않게 M 뒤 행이 달라지는지(`H8_MOVED`)와, 일부러 다음 달을 보는 뷰를 시험이 잡는지(`H8_TEETH`)도 단언한다. H8은 그 달 **뒤**를 보는지만 시험한다. 창에 그 달 자신을 넣는 실수는 미래를 본 것이 아니므로 H8이 아니라 2절 독립 계산·4절 손 계산이 잡는다. 변이는 `asset_month_return`만 바꾸므로 계산식이 다른 표(예: `fx_month`, `month_event`)를 읽게 바뀌면 H8 변이도 그 표로 넓힌다.
  - **6절:** 순위·그룹의 정본 쿼리 텍스트가 이 절(`zz_canon`)에 있다. 시즌 기준일 `:base_date`의 풀로 걸러 읽는 모양은 아래와 같고(저장하지 않는다), 독립 정의와 합성 동률으로 검증한다. 목록 순서가 필요한 소비자(P0-18 랭킹·피드)는 `display_order`로 정렬한다 — `ORDER BY` 없는 결과의 행 순서는 보장되지 않는다. `:'base_date'`는 `psql -v base_date=2026-01-02`처럼 넘긴다.

    ```sql
    -- 순위: NULL 제외, 동률은 같은 순위, 표시 순서의 마지막 키는 market, ticker
    SELECT t.*, t.attention_rank <= CEIL(0.10 * t.n_ranked) AS is_top
    FROM (SELECT a.month_block_id, a.asset_id, a.attention_index,
                 RANK() OVER (PARTITION BY a.month_block_id ORDER BY a.attention_index DESC) AS attention_rank,
                 COUNT(*) OVER (PARTITION BY a.month_block_id) AS n_ranked,
                 ROW_NUMBER() OVER (PARTITION BY a.month_block_id
                                    ORDER BY a.attention_index DESC, s.market COLLATE "C", s.ticker COLLATE "C") AS display_order
          FROM asset_month_attention a
          JOIN asset_position p ON p.asset_id = a.asset_id AND p.base_date = :'base_date'
          JOIN asset s ON s.id = a.asset_id
          WHERE a.attention_index IS NOT NULL) t
    ORDER BY t.month_block_id, t.display_order;
    -- 그룹 관심 지수: 지수 있는 소속 종목의 평균, n_members(평균에 들어간 수)·n_pool(풀 종목 수) 함께
    SELECT m.id AS month_block_id, a.asset_group_id, ROUND(AVG(t.attention_index), 8) AS group_attention,
           COUNT(t.attention_index) AS n_members, COUNT(*) AS n_pool
    FROM asset_position p JOIN asset a ON a.id = p.asset_id CROSS JOIN month_block m
    LEFT JOIN asset_month_attention t ON t.asset_id = a.id AND t.month_block_id = m.id
    WHERE p.base_date = :'base_date' GROUP BY m.id, a.asset_group_id;
    ```
  - **9절 흔적:** `asset_month_attention`·`asset_month_return`의 행 수·identity가 실행 전과 같고 `zz_` 객체가 0이다.
- `\ir`은 psql 메타 명령이라 명령줄이 기준이다. VS Code에서는 통합 터미널(`Ctrl` + `` ` ``)에서 실행한다.
- 13 뒤에도 10·11 검증은 그대로 `PASS`다(둘은 `asset_month_attention`을 보지 않는다). 01–03 검증은 적재가 없는 `antlab_test`에서 돌고 13은 그 DB에서 돌지 않는다.

**통계 보드(`14_stats_calendar.sql`)와 검증(`14_stats_calendar_check.sql`).** 정의의 정본은 [`docs/erd.md`](../docs/erd.md) D34이고 정의는 `14_stats_calendar_def.sql` 한 곳이다. 기준일을 고르려면 `-v base_date=2026-01-02`처럼 넘긴다(주지 않으면 가장 늦은 기준일).

- 출력: 종료 코드 0이고 표 둘이 나오면 성공이다. 첫 표는 종류별 건수만 낸다(값 없음): `shown`(보드에 나온 조건 수), `hidden`(n < 5라 숨긴 조건 수), `anecdote`(그중 `일화` 조건 수), `obs`(관측 수). 둘째 표가 통계 보드다. 같은 (유형, 달)에 `START`가 둘 이상이면 멈춘다(n 이중 계산 방지).
  - 열: `base_date`(기준일), `kind`(`CALENDAR` 달력 월 / `EVENT` 사건 유형의 `START` 뒤 / `ATTENTION` 관심 정점 뒤), `cond_key`(달력은 `'01'`–`'12'`, 사건은 `event_type`, 정점은 `PEAK`·`NON_PEAK`(순위 대상 중 정점이 아닌 비교용)), `subject`(달력·사건은 시리즈 `'KR:069500'`·`'FX:USD'` 꼴, 정점은 시장 `'KR'`·`'US'`), `horizon`(달력 0, 그 밖에는 기준 달 뒤 1 또는 3개월), `n`(표본 수), `n_up`(오른 건수, 0 수익률은 상승이 아니다), `mean_return`(산술 평균), `up_prob`(`n_up / n`), `sample_note`(5 ≤ n < 10이면 `일화`, n ≥ 10이면 비어 있음).
  - 값은 모두 소수 8자리 `numeric`이고 반올림은 한 번이다. 수익률은 현지 통화이며(환율은 `FX:USD`가 따로), 쓰는 달은 기준일이 속한 해의 1월 이전뿐이다(올해 제외). n < 5인 조건은 나오지 않는다 — Phase 0 표본에서는 사건 유형별 `START`가 적어 `EVENT` 행이 보드에 하나도 없는 것이 정상이다(`hidden`에 센다). 달력은 월마다 n이 10 미만이라 모두 `일화`다.
  - **출력 값을 PR·문서·이슈에 붙이지 않는다.** 시세에서 나온 값이다(`db/sample/README.md` "추적하지 않는 이유"). 터미널에서만 본다.
  - 앞 창은 기준 달 **뒤** 1·3개월의 누적 수익률이다(기준 달 자신은 넣지 않는다). 중간 달이 하나라도 없으면 관측을 만들지 않으므로 2012-04 ↔ 2023-01 끊김을 넘는 창은 없다. 겹치는 창(같은 종목이 연속 달에 정점이거나 사건이 가까이 시작)은 독립 표본이 아니어서 n이 실제 독립 표본 수보다 크게 보일 수 있다.
- 검증: 마지막 출력이 `p0_12_check` 열의 `PASS`이고 종료 코드가 0이면 통과다(읽는 법은 13 검증과 같다). 정의 파일을 글자 그대로 읽어 변이 시험에 쓰므로 **저장소 루트에서 실행한다**(0절이 확인). 절은 0 전제, 1 관측, 2 집계, 3 표본 수, 4 손 계산, 5 누출, 6 정점, 7 타입, 8 판정이고 음성 사례는 규칙이 속한 절에 `음성 [규칙]` 행으로 나온다. 약 20초 걸린다(환경에 따라 10–25초).
  - **1·2·6절:** 관측·정점·집계를 윈도 함수(`LEAD`)와 "나보다 지수가 큰 행 수 + 1"과 정수 산술(합 × 10^24를 `div`로 나눠 0에서 먼 쪽 반올림)로 따로 계산해 정의와 전 행 같은지 본다. 정의(재귀 CTE·내부 조인·`GROUP BY`)와 다른 방법이라 서로를 검증한다.
  - **3절:** 보드에 n < 5가 없고 `일화` 표시가 5 ≤ n < 10과 일치하며, 합성 사건 유형으로 만든 경계 n = 4·5·9·10이 "없음·일화·일화·표시 없음"인지(달력은 n = 4·5) 본다. 실제 표본의 사건 행이 0개여도 "숨긴 조건 수 > 0"을 단언해 빈 결과가 통과로 끝나지 않는다.
  - **4절:** 합성 수익률(시세 아님)을 한 종목에 덮어쓰고 손 계산과 비교한다(달력 평균, 0 수익률은 상승 아님, 3개월 누적 곱, 관측값을 먼저 반올림하면 8자리째가 달라지는 사례에서 한 번 반올림한 값). 서브트랜잭션에서 되돌린다.
  - **5절:** 기준일을 그 해 안으로 옮겨도 그 해의 달을 쓰지 않는지, 정점 판정이 기준 달 뒤 지수를 바꾸거나 지워도 불변인지(헛돌지 않는지, 다음 달 지수를 섞은 샌 정의를 잡는지), 뷰가 읽는 표가 역사 7개뿐인지(`pg_depend`), 보드에 연도·관측값 열이 없는지 본다.
  - **음성 사례(변이):** 정의 파일의 글자를 `replace`로 바꿔 이름만 `zzm_`으로 다시 만들어 "규칙을 어기면 잡히는가"를 센다(예: 최소 n을 4로, 다음 달 지수를 섞은 순위, `season` 조인, `date_part` 사용). 바꿀 글자가 정의에 없으면 오류라 시험이 헛돌지 않는다.
- 14 뒤에도 10·11·13 검증은 그대로 `PASS`다(14는 아무것도 쓰지 않는다).

**연습 시즌 시작(`20_season_start.sql`)과 검증(`20_season_start_check.sql`).** 종료 코드가 0이고 `ERROR`가 없으면 성공이다. 결과 표 두 개가 나온다 — 첫째는 상태(`새로 만듦` 또는 `이미 같은 시즌 (새 행 없음)`)와 테이블별로 이번에 넣은 행 수(처음 실행: 회원 1·시즌 1·경로 10·계좌 1·원장 1·0턴 가격은 풀 크기만큼, 다시 실행하면 모두 0), 둘째는 풀 크기·경로 턴 수·블록 수·달력 위반 수(0이어야 한다)다. 경로의 달·가격은 출력하지 않는다.

- `기준일 풀의 판정이 비어 있다 … 12 계산을 먼저`로 멈추면 `db/12_calc_position.sql`을 먼저 실행하지 않은 것이다(또는 판정 불능 종목이 남았다). 아무것도 넣지 않고 종료 코드 3으로 끝난다.
- `이미 있는 시즌이 이 파일과 어긋난다`로 멈추면 DB의 시즌이 파일의 설정·경로·개설 입금·0턴 가격과 다르다. 값 정정은 하지 않는다. 설정을 바꾸는 방법은 (b‴)를 본다.
- 검증은 마지막 줄 `p0_14_check`가 `PASS`이면 통과다. 0절 전제가 FAIL이면(12 판정이 비었거나 연습 시즌이 없으면) 결과 표를 보여 주고 바로 멈춘다. 이후 읽는 법은 11·13·14 검증과 같다(절별 집계 → FAIL 행 → 커버리지 `20/20` → 판정).

**팀 구성(`21_team_buy.sql`)과 검증(`21_team_buy_check.sql`).** 종료 코드가 0이고 `ERROR`가 없으면 성공이다. 결과 표 두 개가 나온다 — 첫째는 상태(`새로 삼`·`이어서 삼`·`이미 같은 팀 (새 행 없음)`)와 줄 수·이번에 산 줄 수·테이블별로 늘어난 행 수(처음 실행: 주문·체결·보유·라인업 각 8, 원장 16 — 수수료가 0이면 `FEE` 행이 없어 줄어든다, 계좌 갱신 8. 다시 실행하면 모두 0), 둘째는 팀 주문 수·열린 라인업 수·`FEE` 원장 행 수·명단과의 차이 건수(0이어야 한다)다. 가격·수량·금액·비중은 출력하지 않는다.

- 검증은 마지막 줄 `p0_15_check`가 `PASS`이면 통과다. 0절 전제가 FAIL이면(21을 먼저 실행하지 않았거나 20·12가 없으면) 결과 표를 보여 주고 바로 멈춘다. 이후 읽는 법은 20 검증과 같다(절별 집계 → FAIL 행 → 커버리지 `22/22` → 판정). 절은 00 전제, 01 팀, 02 수량, 03 체결, 04 원장, 05 보유, 06 라인업, 07 포메이션, 08 잔액, 09 함수, 10 단위, 11 정보, 12 판정이다.
- 규칙은 "팀을 구성한 뒤 언제나 참"인 것만 둔다. 그래서 턴 진행(P0-17)·매매(P0-19) 뒤에도, 잔액이 0인 DB에서도 `PASS`다. 팀 직후에만 참인 것(보유 수량 = 팀 수량, 열린 라인업 수)은 11절 정보 행으로만 낸다.
- 함수 오류 종류는 HINT로 가린다: `SEASON_NOT_ACTIVE` · `TURN_MISMATCH` · `ASSET_NOT_IN_POOL` · `INVALID_QUANTITY` · `INSUFFICIENT_CASH` · `POSITION_CONFLICT` · `LEDGER_MISMATCH`(Java 도메인 예외 이름과 짝)와 인자·환경 오류 `INVALID_ARGUMENT` · `ISOLATION_LEVEL`. 메시지에 금액·가격이 없다.
- 팀 구성 뒤에도 01·02·03(적재가 없는 `antlab_test`)·10·11·12·13·14 검증과 20 시즌 시작(재실행 새 행 0)·20 검증은 그대로 `PASS`다. 21은 `holding`·`lineup`·`cash_*`·`orders`·`execution`을 쓰지만 판정(`asset_position`)은 읽기만 한다.
- `\ir`은 psql 메타 명령이라 명령줄이 기준이다. VS Code에서는 통합 터미널(`Ctrl` + `` ` ``)에서 실행한다.

**수동 대사(`22_reconcile_season.sql`)와 검증(`22_reconcile_season_check.sql`).** 실행 파일은 종료 코드가 0이고 `ERROR`가 없으면 15개 규칙이 모두 일치한 것이다. 결과 표 두 개가 나온다 — 첫째는 `attempt_no`(이번 시도 번호)·`rows_inserted`(15)·`match_count`·`mismatch_rules`·`mismatch_items`, 둘째는 불일치 규칙의 `check_code`·`mismatch_count`·`classification`(일치면 0행)이다. 기대·실제·차이 값은 출력하지 않는다. 불일치가 있으면 둘째 표가 채워지고 `P0-16 대사: 불일치 규칙 n 개 (…)`로 끝난다(종료 코드 3, 행은 커밋됨). 분류는 항상 `INVESTIGATE`다(분류 자동화는 MVP 이후). `연습 시즌이 0 개다`는 20을 먼저 실행하지 않은 것이고 이때는 행을 만들지 않는다.

**턴 진행(`23_turn_advance.sql`)·검증·드릴.** 실행 파일이 종료 코드 0으로 끝나고 `ERROR`가 없으면 한 턴이 성공한 것이다. 보고 표는 한 줄이며, `turn_no`(넘어간 턴), `new_price_rows`(새 `turn_price` 행 수, 풀 크기와 같아야 한다), `turn_rows`(지금까지 `turn` 행 수 = 현재 턴), `attempt_no`(그 턴의 이번 시도 번호), `match_of_17`(17이어야 한다), `finished`(마지막 턴이면 `t`)를 보여 준다. 끝난 시즌에서는 `P0-17 턴 진행: 이미 끝난 시즌이다 (FINISHED) — 아무것도 하지 않았다`만 나오고 종료 코드 0이다. `P0-17 턴 진행: 대사 불일치 규칙 n 개 (코드 목록)`은 되돌린 뒤 17행이 남았다는 뜻이다 — `reconciliation_log`에서 `status = 'MISMATCH'`인 행의 `check_code`·`mismatch_count`·`detail`(어느 행인지 번호와 종류만, 금액 없음)을 본다. 규칙 11(`VALUATION`)은 스냅샷의 평가액·현금·낙폭이 다른 식의 재계산과 다를 때, 12(`TURN_PRICE`)는 저장된 가격이 PLAN §5.2 공식과 다를 때, 19(`POSITION_VALUE`)는 포지션별 평가액이 라인업으로 직접 묶은 합과 다를 때(라인업이 빠진 보유가 있으면 17 `LINEUP_HOLDING`과 함께 걸린다)다. 힌트 `NO_RETURN`은 다음 달 수익률·환율·0턴 가격이 빠진 풀 종목이 있다는 뜻이고(쓰기 전에 멈춘다) `NO_PATH`는 다음 턴의 경로 블록이 없다는 뜻이다.

**검증 파일(`23_turn_advance_check.sql`).** 마지막 출력의 `p0_17_check` 열이 `PASS`이고 종료 코드가 0이면 통과다. 0절(전제) 표가 먼저 나오며 FAIL이면 거기서 멈춘다. 이어서 절별 집계(00 전제 · 01 진행 상태 · 02 가격 · 03 스냅샷 · 04 사후 정보 · 05 양성 · 06 음성 · 07 거부 · 08 단위 · 09 정보·판정), FAIL 행 목록(0행이어야 정상), 정보 행("매달 반올림"과 다른 칸 수, 17개 시도 수 등 — 건수만), 판정 행이 나온다. 규칙 커버리지는 `3/3`이다(12, 현재 턴 ≥ 1이면 11·19 포함 — 0턴에서는 `1/1`). 0절이 "범위"에서 FAIL이면 현재 턴에 주문이 있는 상태(턴 중간 매매 뒤)라는 신호다 — 헛 PASS가 아니라 멈춤이며 턴을 넘긴 뒤 다시 돌린다.

**드릴(`23_turn_advance_drill.sql`).** 마지막 출력이 `p0_17_drill` 열의 `PASS`이면 통과다(21개 단언). 중간에 나오는 `ERROR: … RECONCILIATION_FAILED`는 드릴이 일부러 낸 불일치의 오류라 정상이다.

**소음 피드(`24_turn_feed.sql`)·검증·23 연결(P0-18).** `23_turn_advance.sql`은 먼저 `24_turn_feed_def.sql`을 적재한 뒤, 가격·턴 스냅샷을 만들고 대사 전에 `pg_temp.tf_append`를 호출한다. 23은 사건 편집 상태(11)와 관심 지수(13)를 입력으로 읽는다. 따라서 01·02·03·10·11·12·13·20·21 뒤에 실행해야 하며, `feed/*.csv`를 상대 경로로 읽으므로 저장소 루트에서 실행해야 한다(14는 필요 없다). 피드는 같은 턴 트랜잭션에 들어가므로 생성 오류나 대사 불일치가 발생하면 함께 되돌아간다. 입력 조립부(`tf_input`)는 현재까지의 `turn_price`, 현재 경로 블록에서 편집이 끝난 사건, 현재·직전 시즌 턴 블록의 저장 관심 지수, 직전 시즌 턴 피드만 JSON으로 넘긴다. 생성기는 업무 표를 직접 조회하지 않는다. 성공한 턴에는 `seq`가 1부터 연속인 5–15행이 생기며 `NUDGE`는 생기지 않는다. 사건 카드와 같은 문장 틀의 소음 템플릿도 후보에 포함되므로, 문장만 보고는 사건인지 알 수 없다. 선택은 시드·턴·규칙 버전 `p0-18:v2`·자연 키(종목 `market:ticker`, 그룹 `code`, 사건 `event_key`)를 해시해 결정한다. 후보를 한 바퀴 모두 사용한 뒤에만 두 번째 바퀴로 넘어간다. 관심 지수는 등급(상위 10% = 3, 상위 절반 = 2, 나머지 = 1, 지수 없음 = 강도 NULL)에 따라 강도와 선택 가중치를 정한다. `db/24_turn_feed_check.sql`의 마지막 `p0_18_check`가 `PASS`여야 한다. 이 검증은 ① 행 수·`seq`·0턴·미래 피드 ② 저장 피드 = 재생성(완료 턴 전부) ③ 사후 정보 차단(미래 전용 블록의 관심·사건 반응·수익률을 바꿔도 현재 턴 피드가 같고, 현재 블록을 바꾸면 달라지는 양성 대조군 2개) ④ ECHO 자격·반복 횟수와 "안 비었음" ⑤ 희소 숨김·현재 블록 사건 전부 포함 ⑥ 같은 옷(정적·생성기 경로) ⑦ 중복 선택 ⑧ 방향·수익률 변수를 `turn_price`에서 독립 계산해 대조 ⑨ 입력 오류 HINT(`FEED_EVENTS_UNREVIEWED`·`FEED_REACTION_MISSING`) ⑩ 손으로 만든 입력의 생성기 단독 시험을 확인한다. 피드 행은 기존 17개 대사 규칙에 포함하지 않는다. `24_turn_feed.sql`은 현재 턴 피드의 행 수·`seq` 빈틈·저장 = 재생성 여부를 읽기 전용으로 낸다. 23 드릴은 불일치 롤백 직후 실패 턴의 피드가 0행인지 확인하고, 복구 뒤에는 그 턴 피드가 한 묶음(5–15행)인지도 확인한다. 23 검증 01절의 피드 항목은 있는 행만 확인하므로, 피드를 만들지 않는 Java 진행 시즌에서도 사용할 수 있다. 피드 누락은 24 검증이 잡는다.

- **로그 보기(값은 보지 않는다).** `SELECT attempt_no, turn_no, check_code, status, mismatch_count FROM reconciliation_log ORDER BY id DESC LIMIT 15;` 값 컬럼(`expected_value`·`actual_value`·`diff_value`)은 소유자가 자기 DB에서만 보고 PR·이슈에 붙이지 않는다. 스칼라 규칙 5개(`CASH_SUM`·`ORDER_LEDGER`·`HOLDING_QTY`·`RESERVED_ZERO`·`FEE_RULE`)만 세 값이 채워진다.
- **`detail` 읽기.** `{"items": [{"kind": …, "order_id"|"execution_id"|"ledger_id"|"asset_id"|"lineup_id"|"other_lineup_id"|"formation_change_id": …, "turn_no"?: …, "kinds"?: […]}], "total": n, "truncated": bool}`. 항목은 최대 100개이고 `total`이 전체 불일치 수다. 규칙 4·7·8은 한 행에 종류가 여럿일 수 있어 `kinds` 배열이 있다. 종류 코드는 정의 파일의 규칙 절 머리 주석에 있다.
- **"대사 불일치 0건"** = 마지막 22 실행의 `MISMATCH`가 0행이다.
- 검증 파일은 마지막 출력이 `p0_16_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. 절은 00 전제 → 01 양성 → 02 구조 → 03 음성 → 04 단위 → 05 정보 → 06 판정이고 07 흔적 확인이 끝에 있다. 00절이 FAIL이면 거기서 멈춘다(`연습 시즌이 정확히 1개`는 20, `0턴 매수 주문이 있다`는 21을 먼저 실행하라는 신호). 03 음성은 사례마다 `불일치 규칙 {…}`의 기대와 실제를 나란히 보인다 — 사례 하나가 기대한 규칙 집합과 **정확히 같을 때만** PASS다(다른 규칙이 함께 걸리면 단독성이 깨진 것). 06절이 커버리지 `15/15`를 보인다.
- 연습 DB에는 매도·전술 변경 데이터가 없어 규칙 8(매도)·15(사슬)·16(묶기)의 양성은 "빈 집합이 일치"다. INSERT가 필요한 사례(원장 행 추가·매도·묶기·겹친 구간·1턴 이후 가격)는 이 파일이 아니라 격리 DB의 합성 시즌과 매매 기능(P0-19)의 검증이 맡는다.

**턴 사이 매매·전술 변경(`25_trade_formation.sql`)·검증·드릴(P0-19).** 실행 파일의 종료 코드가 0이고 `ERROR`가 없으면 시나리오가 성공한 것이다. 보고 표는 한 줄로 출력되며 `orders_now`(현재 턴 주문 4) · `bound_to_change`(변경 뒤 주문 3) · `from_feed`(FEED 주문 1) · `formation_changes`(1) · `executions_now` · `sell_or_fee_rows_now` · `open_lineup` · `turn_no`를 담는다. 가격·수량·금액은 출력하지 않는다. 다시 실행하면 "이미 적용된 상태"로 종료 코드 3이 된다(멱등이 아니다).

- **전체 재현:** 23을 5번 → 25 → (20·21·22 검증과 22 수동 검사) → 23을 5번 → 20·21·23·24·25 검증 순서로 실행한다. `reconciliation_log`의 `MISMATCH`가 0행이면 대사 불일치가 없는 것이다(수동 15개 묶음과 턴 전환 17개 묶음은 구분해서 센다).
- **검증:** 마지막 줄 `p0_19_check`가 `PASS`이고 종료 코드가 0이면 통과다. 절은 00 전제 · 01 모양 · 02 원장 · 03 재생 · 04 라인업 · 05 전술 · 06 출발점 · 07 비용 · 08 대사 · 09 거부 · 10 양성 · 11 정보 · 13 판정 · 14 흔적이다. 절별 집계의 `na` 열에는 "적용 불가" 정보 행 수가 들어가며, 통과 사례 수에는 포함하지 않는다.
- **전술 변경 비용 조회:** `pg_temp.formation_costs(시즌)`은 변경에 연결된 체결의 매수·매도 대금과 수수료·세금의 합을 낸다(주문 없는 변경은 0건 한 줄). 금액은 소유자가 자기 DB에서만 본다. 21·25 정의를 읽은 psql 세션에서 `SELECT * FROM pg_temp.formation_costs((SELECT id FROM season));`을 실행하며, 결과는 저장하지 않는다.
- **출발점 `FEED`/`ALERT`:** `turn_feed_id`가 필수다. 피드는 같은 시즌에 속하고 이미 노출되어야 하며, FEED는 `NUDGE`가 아닌 종류이고 ALERT는 `NUDGE`여야 한다. P0-18은 `NUDGE`를 만들지 않으므로 ALERT 성공 사례는 드릴 `alert`의 합성 피드로만 확인한다.
- **1턴 이후에는 21의 `order_buy`를 쓰지 않는다.** `order_buy`는 0턴 팀 구성 전용이라 전술 변경 연결(`formation_change_id`)과 시각 순서 검사가 없다. 같은 턴에 전술 변경이 있는 채로 부르면 주문이 변경에 묶이지 않아 22 규칙 16이 어긋나고 턴 전환이 막힌다(주문은 불변이라 고칠 길이 없다). 그래서 1턴 이후에 부르면 쓰기 전에 `USE_TRADE_BUY`로 거부한다. 1턴 이후 매수는 25의 `trade_buy`다.
- **과거 턴 피드 (소유자 결정 2026-11-10, 이슈 #94):** 이미 노출된 지난 턴 피드를 참조해 현재 턴 주문을 시작할 수 있다. 주문 턴은 현재 턴으로 기록하고, 참조 피드는 원래 턴을 유지한다. 미래 피드·다른 시즌·종류가 맞지 않는 피드는 `INVALID_FEED_REFERENCE`로 거절한다(`tr_source` 한 곳). 드릴 `pastfeed`와 25 검증 10절이 확인한다.
- 매도 수수료는 매수와 같은 요율로 계산하며 `buy_fee`를 사용한다(세금 없음). 실현 손익에서는 수수료·세금을 빼지 않는다(D9). 전량 매도하면 보유 행은 남겨 두고 수량·평균단가를 0으로 만든 뒤, 열린 구간을 `end_turn = t`로 닫는다. 단일 종목 비중 한도는 강제하지 않는다(erd 6.5 후속 서비스 규칙).
- 같은 시즌의 모든 쓰기 경로(21·25의 주문·변경, 23의 턴 전환)는 `season` → `cash_account` 순서로 잠금을 획득하므로 교착이 없다. 시각 순서(규칙 16)는 잠금을 얻은 뒤 `clock_timestamp()`로 정하며, 앞선 이벤트보다 늦지 않으면 쓰기 전에 `EVENT_TIME_CONFLICT`로 멈춘다.

**성적표(`26_season_report.sql`)·검증(`26_season_report_check.sql`, P0-20).** 정의의 정본은 [`docs/erd.md`](../docs/erd.md) D38이고 정의는 `26_season_report_def.sql` 한 곳이다. 실행 파일은 읽기 전용이라 몇 번이든 돌릴 수 있고 종료 코드가 0이면 성공이다.

- **보고 범위:** 마지막 스냅샷 턴 T까지다. 끝난 시즌이면 확정, 진행 중이면 잠정(`is_final = f`)이다. 턴 T의 주문·전술 변경은 계산에서 빠지고 `pending_orders`·`pending_changes`로 센다.
- **열:** 금액은 원 단위, 비율은 소수 8자리(−0.2 = −20%), 개수는 정수다. `monthly_volatility`는 월 단위이고 연율이 아니다. `role_fit`이 NULL이면 판정된 노출 종목이 없다는 뜻이다(10턴 손 경로는 빠진 달이 2개라 시즌 실측이 모두 판정 불능이다). `nudge_*`는 1턴 이후 매매가 없으면 NULL이다.
- **값은 로컬에서만:** 성적은 시세에서 나온 값이라 PR·이슈에 붙이지 않는다.
- **검증:** 마지막 출력의 `p0_20_check`가 `PASS`이고 종료 코드가 0이면 통과다. 절은 00 전제 · 01 계약 · 02 결과 · 03 기준선 · 04 변동성 · 05 위험·낙폭 · 06 최대 단일 종목 · 07 회전율·독려 · 08 포메이션 준수 · 09 역할 수행 · 10 비용 · 11 대사 집계 · 12 사후 정보 배제 · 13 거부 사례 · 14 합성 단위 · 15 정보다. 읽는 법은 25 검증과 같다. 변이 사례의 대상이 이 DB에 없으면 "적용 불가" 정보 행이 되고(예: 전술 변경이 없는 시점, T < 6), 통과 사례 수에 세지 않는다. 0턴이면 00절에서 멈춘다(종료 코드 3).
- **12 어댑터:** 09절이 `pos_metrics`·`pos_max_drawdown`·`pos_block_mkt`가 12의 뷰와 모든 기준일 행에서 같은 값을 내는지 확인한다.

**최종 판정(`90_full_run_check.sql`, P0-21).** 통과면 출력 끝 두 줄이 `p0_21_check`와 `PASS`이고 종료 코드가 0이다. 실패하면 `ON_ERROR_STOP=1`에서는 `P0-21 … 실패` 오류와 종료 코드 3으로 끝나 판정 줄이 없다(끝 두 줄이 `PASS`가 아니면 실패다). 전체 순서를 S34까지 끝낸 뒤(10턴이 끝나고 마지막 수동 대사 22를 돌린 뒤)에 실행한다. 읽기만 하며 `INSERT` 없이 `ROLLBACK`하므로 `antlab`에서도 돌린다.

- **0절(전제)이 FAIL이면 거기서 멈춘다:** `연습 시즌 개수`는 20을 먼저, `시즌 상태 FINISHED`는 23을 10번 끝까지, `수동 대사가 1개 이상`은 22를 먼저 실행하라는 뜻이다. 턴 중간에는 이 파일이 아니라 S29의 중간 점검을 쓴다.
- **01 양성 일곱 행(J1–J7)이 모두 충족이어야 한다:** J1 마지막 수동 대사(코드 15개 묶음 중 `id`가 가장 큰 것) 15행이 시즌의 마지막 턴에서 모두 `MATCH`. J2 로그 전체에 `MISMATCH` 행 0(성적표의 집계와도 같아야 한다). J3 시즌이 `FINISHED`이고 마지막 턴이며 성적표가 확정. J4 턴 1..10마다 코드 17개 묶음이 있고 범위 밖 턴의 로그가 0행. J5 마지막 턴에 남은 주문·전술 변경이 0. J6 1턴 이후 주문과 전술 변경이 있음(25가 실행됐다). J7 지금 데이터를 22의 규칙 함수로 다시 대사해 15규칙이 모두 `MATCH`(로그 없이 손으로 바꾼 데이터를 잡는다).
- **02 음성 아홉 사례:** 로그 한 행을 `MISMATCH`로, 묶음을 지우거나 턴을 범위 밖으로, 시즌 상태를 진행 중으로 바꾸거나 마지막 턴 주문을 남기거나 로그 없이 체결 데이터만 바꾸면 해당 판정이 실제로 실패하는지 서브트랜잭션에서 본다(각 사례의 `actual`이 실패한 판정 코드). 04절의 커버리지 `7/7`은 J1–J7 각각에 실패를 확인한 사례가 있다는 뜻이다. 사례는 `UPDATE`·`DELETE`만 쓰고 되돌리며, 05절의 흔적 확인이 행 수·내용 지문·번호표가 실행 전과 같음을 확인한다.
- **FAIL 행을 읽는 법:** `01 양성`의 실패 행은 판정 코드와 `actual`(건수만)을 보여 준다. J1·J2가 실패하면 먼저 `reconciliation_log`에서 `MISMATCH` 행의 `check_code`를 본다(`SELECT turn_no, attempt_no, check_code, status, mismatch_count FROM reconciliation_log WHERE status = 'MISMATCH' ORDER BY id;` — 값 열은 보지 않는다). J4가 실패하면 17행 묶음이 없는 턴이 있다는 뜻이라 23이 그 턴을 거치지 않았다.
- **03 정보:** 묶음 개수(턴 전환 · 수동 · 범위 밖 · 불일치)와 턴·전술 변경·주문 건수뿐이다. 가격·금액은 나오지 않는다.
