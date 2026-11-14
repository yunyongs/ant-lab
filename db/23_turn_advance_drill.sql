-- =====================================================================
-- my Fund Manager Phase 0 — 롤백 너머 기록 드릴 (P0-17): 불일치 턴을 실제 ROLLBACK 으로 되돌리고 로그만 남기는 경로를 통째로 지나 본다
-- 근거: docs/erd.md 7.5(대사 규칙 11·17·19)·D28·D36·D37·9절 P0-16·P0-17, docs/PLAN.md §5.2(불일치면 되돌리고 기록), docs/SPRING_JPA.md §6(롤백 뒤 기록)
-- ★ 버리는 DB 전용이다. 이 파일은 커밋된 상태를 일부러 깨뜨리고(라인업 구간 닫기) 로그를 영구히 남긴다 (reconciliation_log 는 추가 전용이라 지울 수 없다).
--   소유자의 상시 DB(antlab·antlab_test)에서는 가드가 멈춘다. 연습 DB 의 사본에서 한다:
--     psql -X -d postgres -c "CREATE DATABASE antlab_drill TEMPLATE antlab"      (antlab 에 다른 접속이 없어야 복사된다 — README)
--     psql -X -v ON_ERROR_STOP=1 -v drill=1 -d antlab_drill -f db/23_turn_advance_drill.sql
-- 전제: 연습 시즌(20 과 같은 이메일)이 진행 중이고 현재 턴 < 경로 길이, 열린 라인업이 있고 보유 중인 종목이 있다 (21 팀 구성 뒤, 23 로 몇 턴 진행한 뒤여도 된다).
-- 하는 일 (한 번의 psql 세션 — 임시 객체와 변수를 이어 쓴다):
--   1 가드(-v drill=1, 값이 정확히 1, DB 이름이 antlab·antlab_test 가 아님, ON_ERROR_STOP 켜짐) → 2 전: 현재 턴·행 수·버전을 임시 표에 찍는다 (트랜잭션 밖이라 롤백에 사라지지 않는다)
--   3 커밋된 상태 깨뜨리기: 보유 종목의 열린 라인업 구간을 닫는다 (end_turn = 현재 턴). 예상 불일치 집합 = {규칙 17 LINEUP_HOLDING, 규칙 19 POSITION_VALUE}.
--     스냅샷의 골키퍼는 잔여 방식이라 ck_turn_position_sum 을 지나고, 규칙 19(직접 합)가 잡는다 — 직접 합이었다면 INSERT 에서 23514 로 터져 기록이 없었을 것이다 (D37).
--   4 ON_ERROR_STOP 을 잠시 끄고 CALL pg_temp.turn_advance → 오류 SQLSTATE 가 P0001(RECONCILIATION_FAILED)인지 psql 변수 LAST_ERROR_SQLSTATE 로 본다 → 다시 켠다
--   5 단언: 현재 턴 그대로·turn_price/turn 새 행 0·season 버전 그대로, reconciliation_log 에 (턴 = 현재 + 1, 시도 1)의 17개 코드 전체 묶음이 있고 MISMATCH 코드 집합이 {LINEUP_HOLDING, POSITION_VALUE},
--     detail 에 금액 키가 없다, 깨진 라인업은 아직 깨진 채다
--   6 고치기(end_turn = NULL) → CALL → 성공: 현재 턴 + 1, 그 턴 시도 2 의 17개가 전부 MATCH, 시도 1 의 행은 그대로 (추가 전용)
--   7 보고: PASS/FAIL 과 건수만. 실행 파일 db/23_turn_advance.sql 의 종료 코드 3 은 셸에서 따로 확인한다 (README 실습):
--        psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill -f db/23_turn_advance.sql ; echo "종료 코드 $?"   — 라인업을 닫은 채 돌리면 3
-- 사례 -v drill_case=flat (#77, 선택): 다음 턴 블록을 보합(수익률·환율 변화 0, 관심 지수 NULL)으로 만들고 CALL 이 성공하는지 본다.
--   Flat-turn case: the next block has no return, no FX change, no attention and no events, so the turn has no feed candidate.
--   Expected: the turn advances with 0 turn_feed rows, 17/17 MATCH, prices equal to the previous turn, stored = regenerated.
--   Throwaway DB only (it edits history rows); verdict column p0_17_drill_flat. Without drill_case this file runs the rollback drill above.
-- 이 드릴이 남기는 것: 한 턴 진행과 불일치 시도 1회분 로그(17행). 시즌 끝까지 가려면 드릴 뒤에 23 을 더 돌린다. 그 DB 의 reconciliation_log 에는 일부러 남긴 불일치가 있다.
-- 보고에 가격·평가액·낙폭·달을 내지 않는다 (건수와 코드뿐).
-- 새 문법: psql \gset·\set·\if 와 LAST_ERROR_SQLSTATE 변수, 프로시저의 실제 ROLLBACK, 임시 표로 롤백 너머 값 나르기.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 1 가드
\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-17 드릴: ON_ERROR_STOP 이 꺼져 있다. psql -X -v ON_ERROR_STOP=1 -v drill=1 -d <버리는 DB> -f db/23_turn_advance_drill.sql 로 다시 실행한다'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'P0-17 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \echo 'P0-17 드릴: -v ON_ERROR_STOP=1 없이 실행할 수 없다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-17 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
\if :{?drill}
\else
  \echo 'P0-17 드릴: -v drill=1 이 없다. 이 파일은 커밋된 상태를 깨뜨리고 로그를 남긴다 — 버리는 DB 에서만 -v drill=1 로 실행한다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-17 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (:'drill' = '1') AS dr_flag_ok \gset
\if :dr_flag_ok
\else
  \echo 'P0-17 드릴: -v drill=1 이어야 한다 (다른 값은 받지 않는다). 버리는 DB 에서만 -v drill=1 로 실행한다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-17 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (current_database() IN ('antlab', 'antlab_test')) AS dr_is_keep \gset
\if :dr_is_keep
  \echo 'P0-17 드릴: 이 DB 는 소유자의 상시 DB 이름(antlab·antlab_test)이다. 사본(CREATE DATABASE antlab_drill TEMPLATE antlab)에서 실행한다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-17 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif

\ir 22_reconcile_season_def.sql
\ir 24_turn_feed_def.sql
\ir 23_turn_advance_def.sql

SET client_min_messages TO warning;
DROP TABLE IF EXISTS pg_temp.dr_before;   -- 정리용 DROP 은 항상 pg_temp. 로 한정한다 (#59)
DROP TABLE IF EXISTS pg_temp.dr_res;
RESET client_min_messages;

-- 전제 단언 (아무것도 쓰지 않는다)
DO $$
DECLARE
    v_n    bigint;
    v_row  record;
    v_bad  text;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'formation_change',
                      'turn_price', 'season_path', 'asset', 'asset_position', 'asset_month_return', 'fx_month', 'reconciliation_log', 'turn']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-17 드릴: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
    SELECT count(*) INTO v_n FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'P0-17 드릴: 연습 시즌이 % 개다 (1 개여야 한다)', v_n;
    END IF;
    SELECT se.status, se.current_turn_no, se.path_length INTO v_row
    FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    IF v_row.status <> 'IN_PROGRESS' OR v_row.current_turn_no >= v_row.path_length THEN
        RAISE EXCEPTION 'P0-17 드릴: 시즌이 진행 중이고 현재 턴이 경로 길이보다 작아야 한다 (이미 끝난 사본이면 20 부터 다시 만든다)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM lineup lu JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id
                   WHERE lu.season_id = (SELECT se.id FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid'))
                     AND lu.end_turn IS NULL AND h.quantity > 0) THEN
        RAISE EXCEPTION 'P0-17 드릴: 보유 중인 종목의 열린 라인업이 없다 (21 팀 구성을 먼저 실행한다)';
    END IF;
END $$;

-- 2 전: 이 값들을 변수와 임시 표에 찍는다 (임시 표는 트랜잭션 밖에서 만들어 이후 ROLLBACK 에 사라지지 않는다)
SELECT se.id AS dr_sid, se.current_turn_no AS dr_cur, se.version AS dr_ver, (se.current_turn_no + 1 = se.path_length) AS dr_last
FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid')
\gset
CREATE TEMP TABLE dr_before AS
SELECT :dr_cur::smallint AS cur, :dr_ver::bigint AS ver,
       (SELECT count(*) FROM turn_price WHERE season_id = :dr_sid) AS price_rows,
       (SELECT count(*) FROM turn WHERE season_id = :dr_sid) AS turn_rows,
       (SELECT count(*) FROM turn_feed WHERE season_id = :dr_sid) AS feed_rows,
       (SELECT count(*) FROM reconciliation_log WHERE season_id = :dr_sid) AS log_rows,
       (SELECT count(*) FROM reconciliation_log WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1) AS log_next_rows;
CREATE TEMP TABLE dr_res (seq integer GENERATED ALWAYS AS IDENTITY, item text NOT NULL, expected text, actual text, ok boolean NOT NULL);

-- 2b flat case (#77): ends with \q, so the rollback drill below never runs for it
\if :{?drill_case}
  SELECT (:'drill_case' = 'flat') AS dr_is_flat \gset
  \if :dr_is_flat
  \else
    \echo 'P0-17 드릴: 알 수 없는 사례다 (flat 만 있다. 사례 없이 실행하면 롤백 드릴이다)'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'P0-17 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \set dr_is_flat false
\endif
\if :dr_is_flat
  SELECT sp.month_block_id AS dr_fb FROM season_path sp WHERE sp.season_id = :dr_sid AND sp.turn_no = :dr_cur + 1
  \gset
  -- the block must have no events (otherwise the feed is the events) and must not be a past block of this season (its history rows are edited)
  SELECT (NOT EXISTS (SELECT 1 FROM month_event WHERE month_block_id = :dr_fb)
          AND NOT EXISTS (SELECT 1 FROM season_path WHERE season_id = :dr_sid AND turn_no <= :dr_cur AND month_block_id = :dr_fb)) AS dr_flat_ok
  \gset
  \if :dr_flat_ok
  \else
    \echo 'P0-17 드릴(flat): 다음 턴 블록에 사건이 있거나 이미 지난 블록이다. 사건이 없는 블록 앞의 턴에서 실행한다'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'P0-17 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
  UPDATE asset_month_return SET local_return = 0
  WHERE month_block_id = :dr_fb AND asset_id IN (SELECT asset_id FROM turn_price WHERE season_id = :dr_sid AND turn_no = :dr_cur);
  UPDATE fx_month SET fx_change = 0 WHERE month_block_id = :dr_fb;
  UPDATE asset_month_attention SET attention_index = NULL WHERE month_block_id = :dr_fb;
  CALL pg_temp.turn_advance(:dr_sid::bigint, :dr_cur::smallint);
  INSERT INTO dr_res (item, expected, actual, ok)
  SELECT x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
  FROM (VALUES
      ('flat: 현재 턴이 하나 올랐다', (SELECT (cur + 1)::text FROM dr_before), (SELECT current_turn_no::text FROM season WHERE id = :dr_sid)),
      ('flat: 다음 턴의 turn 1행·turn_price 가 풀 크기만큼 생겼다', '1/' || (SELECT count(*) FROM asset_position ap JOIN season s ON s.base_date = ap.base_date WHERE s.id = :dr_sid),
          (SELECT (SELECT count(*) FROM turn WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1) || '/' || (SELECT count(*) FROM turn_price WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1))),
      ('flat: 새 턴 가격이 직전 턴과 다른 종목 수가 0 이다', '0',
          (SELECT count(*)::text FROM turn_price a JOIN turn_price b ON b.season_id = a.season_id AND b.asset_id = a.asset_id AND b.turn_no = a.turn_no - 1
           WHERE a.season_id = :dr_sid AND a.turn_no = :dr_cur + 1 AND a.price <> b.price)),
      ('flat: 새 턴의 turn_feed 가 0 행이다', '0', (SELECT count(*)::text FROM turn_feed WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1)),
      ('flat: 그 턴의 대사 17개가 전부 MATCH 다', '17/17',
          (SELECT count(*) || '/' || count(*) FILTER (WHERE l.status = 'MATCH') FROM reconciliation_log l WHERE l.season_id = :dr_sid AND l.turn_no = :dr_cur + 1)),
      ('flat: 저장 = 재생성이다 (둘 다 빈 묶음)', 'true', pg_temp.tf_regen_matches(:dr_sid::bigint, (:dr_cur + 1)::smallint)::text)
  ) AS x(item, expected, actual);
  SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result FROM dr_res ORDER BY seq;
  DO $$
  DECLARE
      v_fail integer;
  BEGIN
      SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM dr_res;
      IF v_fail > 0 OR NOT EXISTS (SELECT 1 FROM dr_res) THEN
          RAISE EXCEPTION 'P0-17 드릴(flat) 실패: FAIL % 건 (위의 결과를 본다)', v_fail;
      END IF;
  END $$;
  DROP TABLE pg_temp.dr_res;
  DROP TABLE pg_temp.dr_before;
  SELECT 'PASS' AS p0_17_drill_flat;
  \q
\endif

-- 3 커밋된 상태 깨뜨리기: 보유 종목의 열린 라인업 구간 하나를 닫는다 (자동 커밋)
SELECT lu.id AS dr_lid
FROM lineup lu JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id
WHERE lu.season_id = :dr_sid AND lu.end_turn IS NULL AND h.quantity > 0
ORDER BY lu.id LIMIT 1
\gset
UPDATE lineup SET end_turn = :dr_cur, updated_at = now() WHERE id = :dr_lid;

-- 4 불일치 턴 전환: 오류가 나야 한다. ON_ERROR_STOP 을 잠시 끄고 SQLSTATE 를 변수에 옮긴 뒤 다시 켠다.
--   아래 ERROR 메시지(P0-17 턴 진행: 대사 불일치 규칙 2 개 …)는 정상이다 — 로그가 커밋된 뒤 나는 RECONCILIATION_FAILED 오류다.
\set ON_ERROR_STOP 0
CALL pg_temp.turn_advance(:dr_sid::bigint, :dr_cur::smallint);
\set dr_state :LAST_ERROR_SQLSTATE
\set dr_msg :LAST_ERROR_MESSAGE
\set ON_ERROR_STOP 1

-- 5 단언 (실패 뒤)
INSERT INTO dr_res (item, expected, actual, ok)
SELECT x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('실패한 CALL 의 SQLSTATE (RECONCILIATION_FAILED)', 'P0001', :'dr_state'),
    ('실패 메시지가 대사 불일치다', 'true', (position('대사 불일치' in :'dr_msg') > 0)::text),
    ('현재 턴이 그대로다 (실제 ROLLBACK 뒤)', (SELECT cur::text FROM dr_before), (SELECT current_turn_no::text FROM season WHERE id = :dr_sid)),
    ('season 버전이 그대로다 (갱신이 롤백됐다)', (SELECT ver::text FROM dr_before), (SELECT version::text FROM season WHERE id = :dr_sid)),
    ('다음 턴의 turn 행이 0 이다', '0', (SELECT count(*)::text FROM turn WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1)),
    ('다음 턴의 turn_price 행이 0 이다', '0', (SELECT count(*)::text FROM turn_price WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1)),
    ('turn_price·turn 행 수가 전과 같다', (SELECT price_rows || '/' || turn_rows FROM dr_before),
        (SELECT (SELECT count(*) FROM turn_price WHERE season_id = :dr_sid) || '/' || (SELECT count(*) FROM turn WHERE season_id = :dr_sid))),
    ('피드: 실패한 턴의 turn_feed 행이 0 이고 전체 행 수가 전과 같다 (피드도 함께 롤백됐다)', '0/' || (SELECT feed_rows::text FROM dr_before),
        (SELECT (SELECT count(*) FROM turn_feed WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1) || '/' || (SELECT count(*) FROM turn_feed WHERE season_id = :dr_sid))),
    ('로그가 17행 늘었다 (reconciliation_log 만 남았다)', (SELECT (log_rows + 17)::text FROM dr_before), (SELECT count(*)::text FROM reconciliation_log WHERE season_id = :dr_sid)),
    ('로그: (다음 턴, 시도 1) 묶음의 코드 집합이 17개 전체다', 'true',
        ((SELECT array_agg(l.check_code::text ORDER BY l.check_code) FROM reconciliation_log l WHERE l.season_id = :dr_sid AND l.turn_no = :dr_cur + 1 AND l.attempt_no = 1)
         = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c))::text),
    ('로그: 이 턴의 시도 1 이 이 드릴로 처음 생긴 시도다 (전에 이 턴 행이 없었다)', '0', (SELECT log_next_rows::text FROM dr_before)),
    ('로그: MISMATCH 코드 집합이 {LINEUP_HOLDING, POSITION_VALUE} 다', 'LINEUP_HOLDING,POSITION_VALUE',
        (SELECT string_agg(l.check_code, ',' ORDER BY l.check_code) FROM reconciliation_log l WHERE l.season_id = :dr_sid AND l.turn_no = :dr_cur + 1 AND l.attempt_no = 1 AND l.status = 'MISMATCH')),
    ('로그: 불일치 행은 INVESTIGATE·상세 있음, 일치 행은 분류·상세 NULL 이다 (어긋난 행 수)', '0',
        (SELECT count(*)::text FROM reconciliation_log l WHERE l.season_id = :dr_sid AND l.turn_no = :dr_cur + 1 AND l.attempt_no = 1
         AND NOT ((l.status = 'MISMATCH' AND l.classification = 'INVESTIGATE' AND l.detail IS NOT NULL) OR (l.status = 'MATCH' AND l.classification IS NULL AND l.detail IS NULL)))),
    ('로그: detail 항목의 키가 계약 안에 있다 (금액 키 없음 — 벗어난 키 수)', '0',
        (SELECT count(*)::text FROM reconciliation_log l, jsonb_array_elements(l.detail -> 'items') i, jsonb_object_keys(i) k
         WHERE l.season_id = :dr_sid AND l.turn_no = :dr_cur + 1 AND l.attempt_no = 1
           AND k NOT IN ('kind', 'kinds', 'turn_no', 'asset_id', 'lineup_id', 'other_lineup_id', 'order_id', 'execution_id', 'ledger_id', 'formation_change_id'))),
    ('깨뜨린 라인업은 아직 닫힌 채다 (롤백은 이미 커밋된 깨짐을 되돌리지 않는다)', 'true',
        (SELECT (end_turn IS NOT NULL)::text FROM lineup WHERE id = :dr_lid))
) AS x(item, expected, actual);

-- 6 고치기 → 다시 CALL (성공해야 한다)
UPDATE lineup SET end_turn = NULL, updated_at = now() WHERE id = :dr_lid;
CALL pg_temp.turn_advance(:dr_sid::bigint, :dr_cur::smallint);

INSERT INTO dr_res (item, expected, actual, ok)
SELECT x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('성공: 현재 턴이 하나 올랐다', (SELECT (cur + 1)::text FROM dr_before), (SELECT current_turn_no::text FROM season WHERE id = :dr_sid)),
    ('성공: 마지막 턴이었다면 FINISHED, 아니면 IN_PROGRESS 다', CASE WHEN :'dr_last' = 't' THEN 'FINISHED' ELSE 'IN_PROGRESS' END, (SELECT status FROM season WHERE id = :dr_sid)),
    ('성공: season 버전이 하나 올랐다', (SELECT (ver + 1)::text FROM dr_before), (SELECT version::text FROM season WHERE id = :dr_sid)),
    ('성공: 다음 턴의 turn 1행·turn_price 가 풀 크기만큼 생겼다', '1/' || (SELECT count(*) FROM asset_position ap JOIN season s ON s.base_date = ap.base_date WHERE s.id = :dr_sid),
        (SELECT (SELECT count(*) FROM turn WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1) || '/' || (SELECT count(*) FROM turn_price WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1))),
    ('성공: 복구 뒤 그 턴 피드가 한 묶음이다 (0–15행·seq 1부터 연속·저장 = 재생성)', 'true',
        (SELECT (count(*) <= 15 AND (count(*) = 0 OR (min(f.seq) = 1 AND max(f.seq) = count(*))) AND pg_temp.tf_regen_matches(:dr_sid::bigint, (:dr_cur + 1)::smallint))::text
         FROM turn_feed f WHERE f.season_id = :dr_sid AND f.turn_no = :dr_cur + 1)),
    ('성공: 그 턴의 시도 번호가 2 이고 17개가 전부 MATCH 다', '2/17',
        (SELECT max(l.attempt_no) || '/' || count(*) FILTER (WHERE l.status = 'MATCH') FROM reconciliation_log l
         WHERE l.season_id = :dr_sid AND l.turn_no = :dr_cur + 1 AND l.attempt_no = (SELECT max(attempt_no) FROM reconciliation_log WHERE season_id = :dr_sid AND turn_no = :dr_cur + 1))),
    ('성공 뒤에도 시도 1 의 17행이 그대로다 (추가 전용 — MISMATCH 2행 포함)', '17/2',
        (SELECT count(*) || '/' || count(*) FILTER (WHERE l.status = 'MISMATCH') FROM reconciliation_log l WHERE l.season_id = :dr_sid AND l.turn_no = :dr_cur + 1 AND l.attempt_no = 1)),
    ('성공 뒤 로그 총 행 수 = 전 + 34 (실패 17 + 성공 17)', (SELECT (log_rows + 34)::text FROM dr_before), (SELECT count(*)::text FROM reconciliation_log WHERE season_id = :dr_sid))
) AS x(item, expected, actual);

-- 7 보고: 건수와 판정만
SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result FROM dr_res ORDER BY seq;
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_17_drill,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM dr_res;
DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM dr_res;
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-17 드릴 실패: FAIL % 건 (위의 결과를 본다)', v_fail;
    END IF;
END $$;
DROP TABLE pg_temp.dr_res;
DROP TABLE pg_temp.dr_before;
