# db

Phase 0에서 손으로 쓰는 SQL이다([docs/PLAN.md](../docs/PLAN.md#111-phase-0-체크리스트-sql-postgresql-17)의 체크리스트). 테이블 정의의 근거는 [docs/erd.md](../docs/erd.md)이고, DB는 PostgreSQL 17이다.

## 파일

| 파일 | 만드는 기능 | 내용 |
|------|-------------|------|
| `01_schema_history.sql` | P0-05 | 역사 데이터·종목 풀 테이블 8개 DDL (PK, UNIQUE, FK, CHECK, `idx_` 인덱스, 테이블·컬럼 코멘트). 이 파일의 8개 테이블을 지우고 다시 만든다 |
| `01_schema_history_check.sql` | P0-05 | 위 DDL의 검증. erd 5.3·5.4와 카탈로그를 대조하고, 통과해야 하는 사례와 실패해야 하는 사례를 실행해 스스로 판정한다 |

- 이름 규칙은 `{실행 순서 두 자리}_{종류}_{영역}.sql`이고, 검증은 같은 이름 뒤에 `_check`를 붙인다.
- **새 기능이 파일을 더하는 규칙:** 기능은 자기 파일의 행을 위 표에 더하고, 아래 "전체 순서"와 "DB 다시 만들기"의 명령을 이어 적는다. 다른 기능의 파일은 고치지 않는다.
- 전체 순서: `01_schema_history.sql` → `01_schema_history_check.sql`(선택). 뒤 번호 파일(`02_`, `03_`)은 앞 번호 파일의 테이블을 참조하므로 순서가 곧 의존이다.
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

**(b) 실습 DB 스키마 — `antlab`의 8개 테이블과 데이터를 모두 지운다.** 표본 적재(P0-08) 이후 다시 실행하면 적재한 데이터가 사라진다. 검증만 할 때는 (c)만 실행한다.

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/01_schema_history.sql
```

**(b′) 실습 DB 다시 만들기 — `antlab` 전체를 지우고 처음부터.** 뒤 번호 파일의 테이블이 있어 (b)가 `2BP01`로 멈출 때 쓴다. `antlab`의 모든 데이터가 사라진다.

```bash
dropdb antlab
createdb -E UTF8 -T template0 antlab
psql -X -d postgres -c "ALTER DATABASE antlab SET timezone TO 'Asia/Seoul'"
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/01_schema_history.sql
```

**(c) 제약 검증 — `antlab_test`에서만.**

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history_check.sql
```

**(c′) 검증 DB 다시 만들기 — `antlab_test` 전체를 지우고 처음부터.**

```bash
dropdb antlab_test
createdb -E UTF8 -T template0 antlab_test
psql -X -d postgres -c "ALTER DATABASE antlab_test SET timezone TO 'Asia/Seoul'"
psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history.sql
```

**(d) 임시 인스턴스로 검증 — 상시 DB 서버를 건드리지 않는다.** 저장소 밖에 데이터 디렉터리를 만들어 소켓으로만 접속하는 서버를 잠깐 띄운다(포트를 열지 않는다). 다 쓰면 멈추고 지운다. 실행할 때마다 새 경로를 만든다. 앞 줄에서 오류가 나면 멈추고 마지막 두 줄(정리)만 실행한다.

```bash
D=$(mktemp -d /tmp/antlab-pg.XXXXXX)
initdb -D "${D:?}/data" -U check -A trust -E UTF8
pg_ctl -D "${D:?}/data" -o "-k ${D:?} -c listen_addresses='' -c timezone=Asia/Seoul" -l "${D:?}/data/log" start
createdb -h "${D:?}" -U check -E UTF8 -T template0 antlab_test
psql -X -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/01_schema_history.sql
psql -X -q -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d antlab_test -f db/01_schema_history_check.sql
pg_ctl -D "${D:?}/data" -m fast stop
rm -rf "${D:?}"
```

## 결과 읽는 법

**스키마 파일(`01_schema_history.sql`).**

- 종료 코드가 0이고 `ERROR`·`WARNING`이 없으면 성공이다.
- `P0-05 스키마: 이름이 public이 아닌 곳을 가리킨다`로 멈추면 접속 역할 이름의 스키마 같은 `search_path` 앞쪽 스키마가 있다는 뜻이다. 파일은 아무것도 지우거나 만들지 않고 종료 코드 3으로 끝난다. `search_path`(또는 그 스키마)를 정리한 뒤 다시 실행한다.
- 빈 DB에서 처음 실행하면 `DROP TABLE IF EXISTS`가 `NOTICE: table "…" does not exist, skipping` 8건을 남긴다. 테이블이 없다는 알림일 뿐 오류가 아니다. 두 번째 실행부터는 아무것도 나오지 않는다.

**검증 파일(`01_schema_history_check.sql`).** 마지막 출력이 `p0_05_check` 열의 `PASS`이고 종료 코드가 0이면 통과다. `PASS`는 1–7절이 모두 통과하고 끝의 흔적 확인까지 끝났을 때만 나온다. 먼저 0절(전제) 결과 표가 나오고, 0절에 FAIL이 있으면 거기서 멈춘다(전제를 맞춘 뒤 다시 실행). 이어서 세 가지 표가 나온다.

1. **절별 집계** — `section`(절)마다 `total`(판정 수), `pass`, `fail`. 모든 `fail`이 0이어야 한다. 절은 0 전제, 1 구조, 2 양성, 3 음성, 4 경계, 5 H1, 6 DDL, 7 판정이다.
2. **FAIL 행 목록** — 실패한 판정마다 `item`(무엇을), `expected`(기대), `actual`(실제)을 나란히 보여 준다. **0행이어야 정상**이다.
3. **판정 행** — `verdict`가 `PASS`/`FAIL`이고 전체 `total`, `pass`, `fail`을 보여 준다. 7.4의 `verdict`는 1–7절 판정이고, 최종 판정은 마지막 줄이다.

FAIL이 하나라도 있으면 오류 메시지와 함께 종료 코드 3으로 끝나고, `PASS`는 나오지 않는다(`ON_ERROR_STOP` 없이 돌려도 마지막 줄은 `FAIL`이다. 이때 종료 코드는 0일 수 있다. `-v ON_ERROR_STOP=1` 없이 실행했다면 종료 코드가 아니라 마지막 줄을 본다).

`-q`를 빼면 사례마다 `CALL`이 한 줄씩 출력되어 결과가 묻힌다.

**각 절이 하는 일.**

- **0 전제:** PostgreSQL 17, UTF8, `TimeZone = Asia/Seoul`, `session_replication_role = origin`(FK 검사를 끄지 않음), 8개 테이블이 있고 비어 있음. 8개 이름이 모두 `public`의 테이블로 해석되는지도 본다(`search_path` 앞쪽에 같은 이름의 테이블이 있으면 FAIL). 하나라도 FAIL이면 결과를 보여 주고 바로 멈춘다(픽스처를 넣지 않는다). 현재 DB 이름은 정보 행으로 나온다.
- **1 구조:** erd 5.4와 카탈로그를 대조한다. 테이블별 개수(PK 8·UNIQUE 8·FK 8·CHECK 45·`idx_` 5), 제약·인덱스 이름·종류·테이블 1:1, 정의(CHECK는 erd 식을 PostgreSQL에 다시 파싱시켜 비교), 컬럼 81개의 순서·타입·NULL·기본값·identity·노출 태그, 부동소수 컬럼 없음, 모든 제약 검증됨, FK 트리거 켜져 있음.
- **2 양성:** 통과해야 정상인 사례(과잉 제약이 없다는 증거). 여기서 만든 행이 뒤 절의 픽스처다.
- **3 음성:** 실패해야 정상인 사례. `expected`는 SQLSTATE와 제약 이름이다(`23514` CHECK 위반, `23505` UNIQUE 위반, `23503` FK 위반, `23502` NOT NULL 위반은 컬럼 이름, `428C9` identity 직접 지정). 한 행이 여러 CHECK를 어기면 NOT NULL이 먼저, 그다음 이름의 알파벳 순으로 첫 위반 하나만 보고되므로 그런 사례의 `item`에는 먼저 보고되는 이름을 적어 두었다.
- **4 경계:** 저장 정밀도 경계. CHECK는 반올림된 저장값을 본다(예: `local_return = -1.000000005`는 `-1.00000001`로 저장되어 위반, `-1.000000004`는 `-1.00000000`으로 저장되어 통과). `VARCHAR(5)`에 폭을 넘는 뒤 공백만 있는 `'STOCK '`은 오류 없이 잘려 통과하고, 폭 안의 뒤 공백 `'ETF  '`는 값의 일부라 거부된다.
- **5 H1:** 검증 규칙 H1(같은 기준일의 USD 종목은 환율이 하나, KRW 종목은 1)의 예시 쿼리. 위반 0건 / USD 불일치 1건 / KRW 환율 2 1건을 확인한다.
- **6 DDL:** 음성 DDL(`42830`, `42P01`, `2BP01`, `42710`, `42P07`)과 PostgreSQL이 막지 않는 사례 하나(컬럼 CHECK의 다른 컬럼 참조).
- **7 판정:** 커버리지(`ck_` 45 + `uq_` 8 + `fk_` 8 = 61개 이름이 각각 자기 이름으로 한 번 이상 보고되었는가, `61/61`)와 절별 집계, FAIL 행, 판정.

**알아 둘 점.**

- **흔적:** 검증 파일 전체가 한 트랜잭션이고 7절 뒤에 `ROLLBACK`한다. 끝(8절)에서 8개 테이블의 행 0, `public`의 `zz_` 객체 0, 제약 69개·인덱스 21개 그대로를 다시 확인한다. **identity(번호표) 값 소비만 되돌아오지 않는다.** 검증을 돌리면 다음 INSERT의 `id`가 건너뛴 값부터 시작한다. 그래서 검증은 검증용 DB에서만 돌린다.
- **공백 값:** `asset.ticker`와 `month_event.event_key`의 앞뒤 공백은 DB가 막지 않는다(`'ZZAAPL'`과 `'ZZAAPL '`은 서로 다른 값). 2절에 현재 동작으로 기록해 두었다. 적재(P0-08)가 `trim`하고 공백 포함 값이 없는지 확인한다.
- **스키마 파일을 다시 실행하면** 8개 테이블의 데이터가 모두 사라진다. 표본 적재 이후에는 (b)를 실행하지 않는다.
