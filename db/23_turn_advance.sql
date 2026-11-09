-- =====================================================================
-- my Fund Manager Phase 0 — 턴 진행 (P0-17): 연습 시즌을 한 턴 앞으로 보낸다
-- 근거: docs/erd.md 6.3(turn·turn_price)·7.5(대사 규칙 1–19)·D36·D37·9절 P0-17, docs/PLAN.md §4.1-4·§5.2, docs/SPRING_JPA.md §5.3(턴 전환)·§6(롤백 뒤 기록)
-- 대상: db/01·02·03 스키마, 10 적재, 11 사건 적재(month_event·events_reviewed — 피드 입력), 12 판정, 13 관심 지수(피드 입력), 20 시즌 시작, 21 팀 구성을 실행한 DB (PostgreSQL 17). antlab 에서 실행한다. 14 는 필요 없다.
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/23_turn_advance.sql
--       한 번 실행 = 한 턴이다. 10턴은 셸 반복으로 돌린다 (db/README.md):
--         for i in $(seq 1 10); do psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/23_turn_advance.sql || break; done
--       같은 폴더의 22_reconcile_season_def.sql·24_turn_feed_def.sql·23_turn_advance_def.sql 을 \ir 로 읽는다. 24 정의가 feed/*.csv 를 상대 경로로 읽으므로 반드시 저장소 루트에서 실행한다. -v ON_ERROR_STOP=1 이 없으면 맨 앞 가드가 아무것도 하지 않고 끝낸다
--       (대사 불일치를 종료 코드 3 으로 알리는 방법이 오류 한 번뿐이다).
-- 하는 일: 연습 시즌(20 과 같은 이메일, 정확히 1개)의 현재 턴을 읽어 pg_temp.turn_advance 를 부른다 —
--       다음 턴의 turn_price(풀 전체)와 turn 스냅샷을 만들고 대사 17개를 돌려, 전부 일치면 기록하고 현재 턴을 올려 커밋한다.
--       하나라도 불일치면 실제로 ROLLBACK 한 뒤 새 트랜잭션에서 reconciliation_log 17행만 남기고 오류로 끝난다 (종료 코드 3, 시즌은 그 턴에 그대로).
--       마지막 턴이면 시즌 상태가 FINISHED 가 된다.
-- 다시 실행: 끝난 시즌(FINISHED)이면 "이미 끝났다"고 알리고 아무것도 하지 않은 채 종료 코드 0 으로 끝난다 (20·21 재실행처럼 새 행 없음).
--       그 밖에는 실행할 때마다 한 턴씩 나아간다 (멱등이 아니다 — 의도). 현재 턴은 잠금 없이 읽어 기대 턴으로 넘기므로, 두 실행이 겹치면 둘째는
--       첫째가 커밋한 뒤 잠금을 얻고 TURN_MISMATCH 로 아무것도 쓰지 않는다 (종료 코드 3).
-- 전제가 어긋나 멈추면(연습 시즌이 0개 또는 2개 이상, 이름 가드 등) 행은 만들지 않고 종료 코드 3 이다. 대사 불일치는 로그 17행을 남기고 종료 코드 3 이다.
-- 쓰는 곳: turn_price·turn·season(current_turn_no·status·version·updated_at)·reconciliation_log 의 INSERT/UPDATE 뿐이다. 원장·주문·체결·보유·라인업은 읽기만 한다.
-- 보고: 넘어간 턴 번호·새 turn_price 행 수·turn 행 수·이번 시도 번호·17개 중 MATCH 수·FINISHED 여부만 낸다. 가격·평가액·낙폭·수익률·달은 출력하지 않는다.
--       값을 보는 것은 소유자가 자기 DB 에서 하는 일이다 (db/README.md "턴 진행 결과 읽는 법"). PR·이슈에 붙이지 않는다.
-- 새 문법: psql \gset 으로 쿼리 결과를 변수에 담기, \if 로 흐름 나누기, CALL 로 프로시저 부르기(프로시저 안에서 COMMIT·ROLLBACK).
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-17 턴 진행: ON_ERROR_STOP 이 꺼져 있다. psql -X -v ON_ERROR_STOP=1 -d <DB> -f db/23_turn_advance.sql 로 다시 실행한다'
    \quit
  \endif
\else
  \echo 'P0-17 턴 진행: -v ON_ERROR_STOP=1 없이 실행할 수 없다 (대사 불일치를 종료 코드 3 으로 알리려면 오류에서 멈춰야 한다)'
  \quit
\endif

-- 정의는 트랜잭션 밖에서 읽는다 (턴 전환 프로시저의 COMMIT·ROLLBACK 이 정의를 지우지 않게). 22 정의를 먼저 읽는다.
\ir 22_reconcile_season_def.sql
\ir 24_turn_feed_def.sql
\ir 23_turn_advance_def.sql

-- [전제 단언] 아무것도 쓰지 않는다. 어긋나면 멈춘다 (종료 코드 3). 정리용 DROP 은 이 파일에 없다 (임시 표를 만들지 않는다 — 있다면 pg_temp. 로 한정한다).
DO $$
DECLARE
    v_bad text;
    v_n   bigint;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'formation_change',
                      'turn_price', 'season_path', 'asset', 'asset_position', 'asset_month_return', 'fx_month', 'reconciliation_log', 'turn']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01·02·03 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 격리 수준이 READ COMMITTED 가 아니다. 잠금 뒤 읽기가 이 수준에 기댄다';
    END IF;
    SELECT count(*) INTO v_n FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 연습 시즌이 % 개다 (1 개여야 한다). db/20_season_start.sql 과 db/21_team_buy.sql 을 먼저 실행한다', v_n;
    END IF;
END $$;

-- 연습 시즌과 현재 턴을 변수에 담는다 (잠금 없이 읽는다 — 프로시저가 잠근 뒤 기대 턴과 맞는지 다시 본다)
SELECT se.id AS sid, se.current_turn_no AS cur, (se.status = 'FINISHED') AS fin
FROM season se JOIN member m ON m.id = se.member_id
WHERE lower(m.email) = lower('phase0@example.invalid')
\gset

\if :fin
  \echo 'P0-17 턴 진행: 이미 끝난 시즌이다 (FINISHED) — 아무것도 하지 않았다'
  \quit
\endif

CALL pg_temp.turn_advance(:sid::bigint, :cur::smallint);

-- 보고: 넘어간 턴과 행 수, 이번 시도 번호, 17개 중 MATCH 수, 끝났는지 (값은 내지 않는다)
SELECT (:cur + 1)                                                     AS turn_no,
       (SELECT count(*) FROM turn_price tp WHERE tp.season_id = :sid AND tp.turn_no = :cur + 1) AS new_price_rows,
       (SELECT count(*) FROM turn t WHERE t.season_id = :sid)         AS turn_rows,
       l.attempt_no,
       count(*) FILTER (WHERE l.status = 'MATCH')                     AS match_of_17,
       (SELECT s.status = 'FINISHED' FROM season s WHERE s.id = :sid) AS finished
FROM reconciliation_log l
WHERE l.season_id = :sid AND l.turn_no = :cur + 1
  AND l.attempt_no = (SELECT x.attempt_no FROM reconciliation_log x WHERE x.season_id = :sid AND x.turn_no = :cur + 1 GROUP BY x.attempt_no HAVING count(*) = 17 AND bool_and(x.status = 'MATCH'))
GROUP BY l.attempt_no;
