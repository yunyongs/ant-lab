-- =====================================================================
-- my Fund Manager Phase 0 — 수동 대사 (P0-16): 연습 시즌의 수동 검사 15개를 돌려 reconciliation_log 에 남긴다
-- 근거: docs/erd.md 7.5(대사 규칙 1–19)·6.3(reconciliation_log)·D28·D36·9절 P0-16·P0-17, docs/PLAN.md §5.2("일치해도 기록한다"·불일치면 되돌림),
--       docs/SPRING_JPA.md §6(대사, 롤백 뒤 기록)
-- 대상: db/01·02·03 스키마, 10 적재, 12 판정, 20 시즌 시작을 실행한 DB (PostgreSQL 17). antlab 에서 실행한다. 21 팀 구성 뒤에 돌리는 것이 의미 있지만
--       20 만 실행한 DB 에서도 돈다 (팀이 없으면 비교할 체결이 없어 15개가 모두 일치한다). 11·13 은 필요 없다.
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/22_reconcile_season.sql
--       같은 폴더의 22_reconcile_season_def.sql 을 \ir 로 읽는다. -v ON_ERROR_STOP=1 이 없으면 맨 앞 가드가 아무것도 하지 않고 종료 코드 3으로 끝낸다
--       (불일치를 종료 코드 3 으로 알리는 방법이 오류 한 번뿐이다).
-- 하는 일: 연습 시즌(20 과 같은 이메일, 정확히 1개)을 찾아 season 을 잠그고(FOR UPDATE) 현재 턴으로 recon_rules 15행을 계산해
--       새 시도 번호 하나로 reconciliation_log 에 15행을 남긴다. 일치해도 남긴다 (PLAN §5.2). 한 트랜잭션이고 커밋한 뒤 보고한다.
-- 다시 실행(멱등이 아니다 — 의도): 실행할 때마다 새 시도 번호와 15행이 쌓인다. 로그는 "그 시점에 검사했다"는 증거이고 같은 결과여도 시각이 다른 두 검사다.
--       앞 시도의 행은 바꾸지 않는다 (추가 전용). 그래서 20·21 과 달리 "이미 같으면 새 행 없음"이 아니다 — 다시 실행하면 행이 늘어나는 것이 정상이다.
-- 불일치일 때: 결과를 먼저 커밋한 뒤(행은 남는다) 트랜잭션 밖에서 오류로 끝난다 (종료 코드 3, 메시지는 코드 이름과 건수뿐). 시즌 상태는 바꾸지 않는다 —
--       수동 검사에는 되돌릴 턴 전환이 없다. 다음 턴 전환(P0-17)의 대사가 같은 규칙으로 잡아 롤백하고 시즌이 그 턴에 멈춘다 (PLAN §5.2).
--       전제가 어긋나 멈추면(연습 시즌이 0개 또는 2개 이상 등) 행은 만들지 않고 종료 코드 3 이다.
-- 쓰는 곳: reconciliation_log 의 INSERT 만. 아무것도 고치거나 지우지 않는다. 규칙 11·19 는 돌리지 않는다 (턴 전환 전용).
-- 수동 검사 행과 턴 전환 행의 구분: 스키마에 종류 컬럼이 없다. 수동 = 코드 15개·turn_no = 현재 턴, 턴 전환 = 코드 17개·turn_no = 넘어가려던 턴.
-- 보고: 시도 번호·넣은 행 수·일치/불일치 건수·불일치 코드와 건수만 낸다. 기대·실제·차이 값(시세에서 나온 금액)은 출력하지 않는다.
--       값을 보는 것은 소유자가 자기 DB 에서 하는 일이다 (db/README.md "대사 결과 읽는 법"). PR·이슈에 붙이지 않는다.
-- 새 문법: SELECT … FOR UPDATE 로 잠근 뒤 같은 트랜잭션에서 계산, 임시 표로 트랜잭션 사이에 값 건네기, 커밋 뒤 오류로 종료 코드 만들기(DO 블록),
--       jsonb_agg 로 결과 행 묶음 만들기.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-16 대사: ON_ERROR_STOP 이 꺼져 있다. psql -X -v ON_ERROR_STOP=1 -d <DB> -f db/22_reconcile_season.sql 로 다시 실행한다'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'P0-16 대사: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \echo 'P0-16 대사: -v ON_ERROR_STOP=1 없이 실행할 수 없다 (불일치를 종료 코드 3 으로 알리려면 오류에서 멈춰야 한다)'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-16 대사: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif

\ir 22_reconcile_season_def.sql

-- 지난 실행이 남긴 임시 표를 지운다 (같은 세션에서 다시 읽어도 되도록. 없을 때의 알림은 숨긴다)
SET client_min_messages TO warning;
DROP TABLE IF EXISTS pg_temp.rc_run;   -- 정리용 DROP 은 항상 pg_temp. 로 한정한다 (임시 표가 없을 때 이름이 public 의 같은 이름 표로 풀려 지우는 사고를 막는다)
RESET client_min_messages;

-- 연습 시즌 찾기: 20·21 과 같은 이메일 조회 (시즌 번호를 적지 않는다)
-- [전제 단언] 아무것도 쓰지 않는다. 어긋나면 멈춘다 (종료 코드 3).
DO $$
DECLARE
    v_bad text;
    v_n   bigint;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'formation_change',
                      'turn_price', 'season_path', 'asset', 'asset_position', 'asset_month_return', 'fx_month', 'reconciliation_log']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-16 대사: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01·02·03 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'P0-16 대사: 격리 수준이 READ COMMITTED 가 아니다. 한 스냅샷 가정이 이 수준에 기댄다';
    END IF;
    SELECT count(*) INTO v_n FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'P0-16 대사: 연습 시즌이 % 개다 (1 개여야 한다). db/20_season_start.sql 을 먼저 실행한다 (20 시즌 시작을 먼저)', v_n;
    END IF;
END $$;

-- 이번 실행의 시즌·검사 턴·시도 번호를 트랜잭션 사이에 건네는 표 (커밋 뒤 보고가 읽는다)
CREATE TEMP TABLE rc_run (season_id bigint NOT NULL, turn_no smallint NOT NULL, attempt_no smallint NOT NULL);

BEGIN;

-- 계산과 기록은 한 트랜잭션. 잠금(season FOR UPDATE)을 먼저 잡은 뒤 현재 턴을 읽는다 — 잠그기 전에 읽은 턴은 그사이 바뀔 수 있다 (READ COMMITTED).
-- recon_rules 는 STABLE 이라 15개 규칙이 이 문장의 한 스냅샷을 본다. recon_log 가 같은 잠금 아래에서 시도 번호(max + 1)를 정한다.
DO $$
DECLARE
    v_season  bigint;
    v_turn    smallint;
    v_rows    jsonb;
    v_attempt smallint;
BEGIN
    SELECT se.id, se.current_turn_no INTO v_season, v_turn
    FROM season se JOIN member m ON m.id = se.member_id
    WHERE lower(m.email) = lower('phase0@example.invalid')
    FOR UPDATE OF se;
    SELECT jsonb_agg(to_jsonb(r) ORDER BY r.rule_no) INTO v_rows FROM pg_temp.recon_rules(v_season, v_turn) r;
    v_attempt := pg_temp.recon_log(v_season, v_turn, v_rows);
    INSERT INTO rc_run VALUES (v_season, v_turn, v_attempt);
END $$;

COMMIT;

-- 보고 1: 시도 번호와 이번에 넣은 행 수 (값은 내지 않는다)
SELECT r.attempt_no,
       count(*)                                         AS rows_inserted,
       count(*) FILTER (WHERE l.status = 'MATCH')       AS match_count,
       count(*) FILTER (WHERE l.status = 'MISMATCH')    AS mismatch_rules,
       COALESCE(sum(l.mismatch_count), 0)               AS mismatch_items
FROM rc_run r
JOIN reconciliation_log l ON l.season_id = r.season_id AND l.turn_no = r.turn_no AND l.attempt_no = r.attempt_no
GROUP BY r.attempt_no;

-- 보고 2: 불일치 규칙의 코드와 건수 (일치면 0행)
SELECT l.check_code, l.mismatch_count, l.classification
FROM rc_run r
JOIN reconciliation_log l ON l.season_id = r.season_id AND l.turn_no = r.turn_no AND l.attempt_no = r.attempt_no
WHERE l.status = 'MISMATCH'
ORDER BY l.id;

-- 불일치가 있으면 커밋된 행을 남긴 채 오류로 끝난다 (종료 코드 3). 메시지에는 코드 이름만 넣는다.
DO $$
DECLARE
    v_n     bigint;
    v_codes text;
BEGIN
    SELECT count(*), string_agg(l.check_code, ', ' ORDER BY l.id) INTO v_n, v_codes
    FROM rc_run r
    JOIN reconciliation_log l ON l.season_id = r.season_id AND l.turn_no = r.turn_no AND l.attempt_no = r.attempt_no
    WHERE l.status = 'MISMATCH';
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-16 대사: 불일치 규칙 % 개 (%). 행은 reconciliation_log 에 INVESTIGATE 로 남았다. db/README.md "대사 결과 읽는 법"을 본다', v_n, v_codes;
    END IF;
END $$;

DROP TABLE pg_temp.rc_run;
