# db

Phase 0에서 손으로 쓰는 SQL이다([docs/PLAN.md](../docs/PLAN.md#111-phase-0-체크리스트-sql-postgresql-17)의 체크리스트). 테이블 정의의 근거는 [docs/erd.md](../docs/erd.md)이고, DB는 PostgreSQL 17이다.

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
| `13_calc_attention_def.sql` | P0-11 | 관심 지수 계산식의 유일한 정의(세션 임시 뷰 `attn_calc`). 단독 실행 대상이 아니라 13 두 파일이 `\ir`로 읽는다 — 쓰기와 검증이 같은 정의를 읽으므로 사후 정보 배제(H8) 시험이 복사본이 아니라 실제 계산식을 시험한다 |
| `13_calc_attention.sql` | P0-11 | `asset_month_return`에서 `asset_month_attention`을 계산해 채운다(종목 × 달, 수익률 행과 1:1). 없는 행만 INSERT하고 아무것도 지우거나 고치지 않으며, 다시 실행하면 새 행이 없다(멱등). 이미 있는 값이 계산값과 다르거나 DB에만 있는 행이 있으면 오류로 멈추고 전부 되돌린다. 건수만 보고하고 값은 출력하지 않는다. 01 스키마와 10 적재를 먼저 실행한다 |
| `13_calc_attention_check.sql` | P0-11 | 계산 검증. 윈도 함수로 한 독립 계산 대조, 규칙(NULL·범위·지수 식·구간 시작·NaN), 합성 손 계산 12사례, 사후 정보 배제(기준 달 뒤 수익률을 바꾸거나 지워도 그 달까지의 행이 불변), 순위·그룹 정본 쿼리를 단언한다. 규칙 18개마다 음성 사례가 있고 커버리지 `18/18`을 확인한다. 아무것도 쓰지 않고 `ROLLBACK`하므로 `antlab`에서도 돌린다. 약 1분 반 걸린다 |

- 이름 규칙은 `{실행 순서 두 자리}_{종류}_{영역}.sql`이고, 검증은 같은 이름 뒤에 `_check`를 붙인다.
- **새 기능이 파일을 더하는 규칙:** 기능은 자기 파일의 행을 위 표에 더하고, 아래 "전체 순서"와 "DB 다시 만들기"의 명령을 이어 적는다. 다른 기능의 파일은 고치지 않는다.
- 전체 순서: 아래 번호 순서대로 실행한다. 뒤 번호 파일(`02_`, `03_`)은 앞 번호 파일의 테이블을 참조하므로 순서가 곧 의존이다. 검증 파일(`_check`)은 선택이다. 기능은 자기 파일의 줄만 더한다.
  1. `01_schema_history.sql`
  2. `01_schema_history_check.sql`(선택)
  3. `02_schema_season.sql`
  4. `02_schema_season_check.sql`(선택)
  5. `03_schema_account.sql`
  6. `03_schema_account_check.sql`(선택)
  7. `10_load_sample.sql` (표본 적재, `antlab`에만. 저장소 루트에서 실행. 시세·환율 파생 CSV 3개와 `hand_check.csv`는 추적하지 않으므로 `db/sample/README.md`의 "표본 만들기"로 먼저 만든다)
  8. `10_load_sample_check.sql`(선택)
  9. `11_load_events.sql` (사건 태그 적재, `antlab`에만. 저장소 루트에서 실행. 10 적재가 끝나 있어야 한다. 다른 파일은 시세 파생 CSV가 필요하지만 이 파일이 읽는 `events/`는 git이 추적한다)
  10. `11_load_events_check.sql`(선택)
  11. `13_calc_attention.sql` (관심 지수 계산, `antlab`에만. 저장소 루트에서 실행. 10 적재가 끝나 있어야 한다. 같은 폴더의 `13_calc_attention_def.sql`을 읽는다. 11과는 무관하다)
  12. `13_calc_attention_check.sql`(선택)
- 스키마 파일은 `BEGIN` ... `COMMIT`으로 감싸 있어 중간에 실패하면 전부 없던 일이 된다. `DROP TABLE`에 `CASCADE`를 쓰지 않으므로, 뒤 번호 파일의 테이블이 이미 있는 DB에서 앞 번호 파일만 다시 실행하면 `2BP01`(dependent_objects_still_exist) 오류로 멈춘다. 안전한 실패이며, 전체를 다시 만들 때는 아래 "DB 다시 만들기"를 쓴다.

## 실행 환경

- PostgreSQL 17 (17.11에서 확인). DB 인코딩은 `UTF8`.
- DB `timezone`은 `Asia/Seoul`이어야 한다. 시각 컬럼은 `TIMESTAMPTZ`라 순간 자체는 시간대와 무관하지만, 표시와 `::date` 변환이 이 설정을 따른다. 검증 SQL의 0절이 확인하고 다르면 FAIL이다. 아래 (a) 블록이 DB에 설정을 심는다(새로 연 접속부터 적용).
- `psql` 명령은 `-X`(`~/.psqlrc`를 읽지 않음)와 `-v ON_ERROR_STOP=1`(오류에서 멈추고 종료 코드를 0이 아닌 값으로)을 붙인다. `psql -1`(--single-transaction)은 쓰지 않는다. 파일 안에 `BEGIN`이 있어 겹치면 경고가 난다.
- 접속 옵션(`-h`, `-U` 등)은 환경에 맞게 덧붙인다. 아래 명령은 기본 접속을 가정한다.
- 명령줄이 기준이다. VS Code의 SQL 확장(예: PostgreSQL·SQLTools 확장)으로 파일을 실행하면 확장에 따라 오류가 난 문장 뒤를 계속 실행하거나 결과 집합 일부만 보여 줄 수 있어, 검증 파일의 판정은 명령줄 결과를 믿는다. VS Code에서는 통합 터미널(`Ctrl` + `` ` ``)에서 아래 psql 명령을 그대로 실행한다.

확인:

```bash
psql -X -d postgres -c "SELECT version(); SHOW timezone;"
```

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

**(b″) 표본 적재 — `antlab`(작업 DB)에만.** 검증용 `antlab_test`에는 적재하지 않는다(`01_schema_history_check.sql`·`02_schema_season_check.sql`·`03_schema_account_check.sql` 0절이 빈 테이블을 요구해 적재된 DB에서는 셋 다 FAIL이다). 적재는 아무것도 지우지 않으며 두 번째 실행에는 새 행이 없다.

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/10_load_sample.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/10_load_sample_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/11_load_events.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/11_load_events_check.sql
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/13_calc_attention.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/13_calc_attention_check.sql
```

여섯 명령 모두 저장소 루트에서 실행한다(`\copy` 경로가 실행한 디렉터리 기준이다). 시세·환율 파생 CSV가 없으면 적재가 첫 줄에서 멈추고, `hand_check.csv`가 없으면 검증 6절이 FAIL이다. 둘 다 `db/sample/README.md`의 "표본 만들기"를 먼저 하라는 신호다.

11 적재는 10 적재가 끝난 DB에서만 돈다(비어 있으면 "10 적재를 먼저" 메시지로 멈춘다). 11 검증은 `events/` 파일을 읽으므로 이것도 저장소 루트에서 실행한다.

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

**(c′) 검증 DB 다시 만들기 — `antlab_test` 전체를 지우고 처음부터.**

```bash
dropdb antlab_test
createdb -E UTF8 -T template0 antlab_test
psql -X -d postgres -c "ALTER DATABASE antlab_test SET timezone TO 'Asia/Seoul'"
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/02_schema_season.sql
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/03_schema_account.sql
```

**(d) 임시 인스턴스로 검증 — 상시 DB 서버를 건드리지 않는다.** 저장소 밖에 데이터 디렉터리를 만들어 소켓으로만 접속하는 서버를 잠깐 띄운다(포트를 열지 않는다). 다 쓰면 멈추고 지운다. 실행할 때마다 새 경로를 만든다. 앞 줄에서 오류가 나면 멈추고 마지막 두 줄(정리)만 실행한다.

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
- **1 구조:** erd 5.4와 카탈로그를 대조한다. 테이블별 개수(PK 8·UNIQUE 8·FK 8·CHECK 45·`idx_` 5), 제약·인덱스 이름·종류·테이블 1:1, 정의(CHECK는 erd 식을 PostgreSQL에 다시 파싱시켜 비교), 컬럼 81개의 순서·타입·NULL·기본값·identity·노출 태그, 부동소수 컬럼 없음, 모든 제약 검증됨, FK 트리거 켜져 있음.
- **2 양성:** 통과해야 정상인 사례(과잉 제약이 없다는 증거). 여기서 만든 행이 뒤 절의 픽스처다.
- **3 음성:** 실패해야 정상인 사례. `expected`는 SQLSTATE와 제약 이름이다(`23514` CHECK 위반, `23505` UNIQUE 위반, `23503` FK 위반, `23502` NOT NULL 위반은 컬럼 이름, `428C9` identity 직접 지정). 한 행이 여러 CHECK를 어기면 NOT NULL이 먼저, 그다음 이름의 알파벳 순으로 첫 위반 하나만 보고되므로 그런 사례의 `item`에는 먼저 보고되는 이름을 적어 두었다.
- **4 경계:** 저장 정밀도 경계. CHECK는 반올림된 저장값을 본다(예: `local_return = -1.000000005`는 `-1.00000001`로 저장되어 위반, `-1.000000004`는 `-1.00000000`으로 저장되어 통과). `VARCHAR(5)`에 폭을 넘는 뒤 공백만 있는 `'STOCK '`은 오류 없이 잘려 통과하고, 폭 안의 뒤 공백 `'ETF  '`는 값의 일부라 거부된다.
- **5 H1:** 검증 규칙 H1(같은 기준일의 USD 종목은 환율이 하나, KRW 종목은 1)의 예시 쿼리. 위반 0건 / USD 불일치 1건 / KRW 환율 2 1건을 확인한다.
- **6 DDL:** 음성 DDL(`42830`, `42P01`, `2BP01`, `42710`, `42P07`)과 PostgreSQL이 막지 않는 사례 하나(컬럼 CHECK의 다른 컬럼 참조).
- **7 판정:** 커버리지(`ck_` 45 + `uq_` 8 + `fk_` 8 = 61개 이름이 각각 자기 이름으로 한 번 이상 보고되었는가, `61/61`)와 절별 집계, FAIL 행, 판정.

**검증 파일(`02_schema_season_check.sql`).** 읽는 법은 01 검증과 같다. 마지막 출력이 `p0_06_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. 01 검증과 다른 점:

- **0 전제:** 01의 8개와 02의 9개, **17개 테이블이 모두 비어 있어야 한다**(픽스처가 01 테이블 4개에도 행을 넣는다). 표본을 적재한 DB(`antlab`)에서는 이 전제가 FAIL이므로 검증용 DB에서만 돌린다.
- **1 구조:** 제약·인덱스 101개(PK 9·UNIQUE 7·**유일 인덱스 2**·FK 14·CHECK 60·`idx_` 9), 컬럼 105개. 유일 인덱스 `uq_member_email`(`lower(email)`)과 `uq_lineup_open`(`WHERE end_turn IS NULL`)은 제약이 아니라 인덱스라 `pg_constraint`가 아니라 `pg_indexes`에서 대조하고, 정의는 erd 식을 PostgreSQL에 다시 파싱시켜 비교한다. 이름 규칙 밖의 인덱스(예: `foo`)가 하나라도 있으면 FAIL이다. FK 인덱스 짝(14/14)은 부분·표현식 인덱스를 짝으로 치지 않는다.
- **3 음성:** 기준 행에서 한 컬럼만 어기는 사례가 기본이다. `formation_change`·`turn_feed`의 복합 FK 대상 UNIQUE 2개는 PK에 가려 단독으로 걸리지 않으므로, 한 사례 안에서 PK를 지웠다가 되돌려 증명한다.
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

- **흔적:** 검증 파일 전체가 한 트랜잭션이고 7절 뒤에 `ROLLBACK`한다. 끝(8절)에서 그 파일 테이블의 행 0, `public`의 `zz_` 객체 0, 제약·인덱스 개수 그대로를 다시 확인한다(01은 8개 테이블·제약 69개·인덱스 21개, 02는 17개 테이블의 행 0·제약 90개·인덱스 27개, 03은 22개 테이블의 행 0·제약 45개·인덱스 15개). **identity(번호표) 값 소비만 되돌아오지 않는다.** 검증을 돌리면 다음 INSERT의 `id`가 건너뛴 값부터 시작한다. 그래서 검증은 검증용 DB에서만 돌린다.
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
- 파일을 고친 뒤 적재와 검증 전체를 임시 인스턴스에서 확인하려면 (d)의 흐름에 10·11 줄을 이어 쓴다. 10 적재는 시세·환율 파생 CSV가 필요하므로 그 CSV가 있는 저장소 루트에서만 돈다.

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
