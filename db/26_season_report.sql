-- =====================================================================
-- my Fund Manager Phase 0 — 연습 시즌 성적표 출력 (P0-20): 결과·기준선·과정·비용·대사 상태와 보조 목록
-- 근거: docs/erd.md D38·9절 P0-20, docs/PLAN.md §6.1·§6.2, 정의는 db/26_season_report_def.sql 한 곳 (이 파일이 \ir 로 읽는다)
-- 대상: db/20_season_start.sql → 21_team_buy.sql → 23_turn_advance.sql 을 1번 이상 실행한 DB (PostgreSQL 17). 25 매매 시나리오 전후, 끝난 시즌 모두 된다.
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/26_season_report.sql
--       -v ON_ERROR_STOP=1 이 없으면 맨 앞 가드가 아무것도 하지 않고 끝낸다. 연습 시즌(이메일 phase0@example.invalid)이 정확히 1개여야 한다.
-- 쓰기: 없다. 읽기 전용 트랜잭션이다. 몇 번이든 돌릴 수 있고 실습 DB(antlab)에서도 된다.
-- 출력: ① 진행 중이면 "잠정" 한 줄 ② 성적표 1행(37열, 세로로) ③ 턴별 표 ④ 시즌 실측 포지션별 판정 불능 사유 건수 ⑤ 선수별 역할 ⑥ 전술 변경별 비용
--       열의 뜻: 금액은 원 단위, 비율은 소수 8자리(−0.2 = −20%), 개수는 정수. 열 이름은 docs/erd.md D38 의 표와 같다.
-- 값은 시세에서 나온 성적이다. 터미널에서만 보고 PR·문서·이슈에 붙이지 않는다 (db/sample/README "추적하지 않는 이유").
-- 새 문법: psql 확장 출력 \x, 읽기 전용 트랜잭션, \gset 으로 쿼리 결과를 변수에 담기.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-20 성적표: ON_ERROR_STOP 이 꺼져 있다. psql -X -v ON_ERROR_STOP=1 -d <DB> -f db/26_season_report.sql 로 다시 실행한다'
    \quit
  \endif
\else
  \echo 'P0-20 성적표: -v ON_ERROR_STOP=1 없이 실행할 수 없다 (단언이 실패하면 멈춰야 한다)'
  \quit
\endif

-- 정의는 트랜잭션 밖에서 읽는다 (12 → 21 → 25 → 26)
\ir 26_season_report_def.sql

BEGIN TRANSACTION READ ONLY;

-- 연습 시즌 하나 찾기
DO $$
DECLARE
    v_n bigint;
BEGIN
    SELECT count(*) INTO v_n FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'P0-20 성적표: 연습 시즌이 % 개다 (1 개여야 한다). 20·21·23 을 먼저 실행한다', v_n;
    END IF;
END $$;
SELECT s.id AS sid, (s.status = 'FINISHED') AS finished, s.current_turn_no AS turns
FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid') \gset

-- ① 진행 중이면 잠정
SELECT CASE WHEN :'finished'::boolean THEN '확정 (끝난 시즌)' ELSE '잠정 (마지막 스냅샷 기준 — 턴 ' || :turns || ' 까지)' END AS report_status;

-- ② 성적표 1행
\x on
SELECT * FROM pg_temp.sr_report(:sid);
\x off

-- ③ 턴별 표
SELECT * FROM pg_temp.sr_turns(:sid);

-- ④ 시즌 실측 포지션: 판정 불능 사유별 건수 (판정되면 reason 이 NULL)
SELECT COALESCE(reason, '(판정됨)') AS reason, count(*) AS assets FROM pg_temp.sr_season_positions(:sid) GROUP BY reason ORDER BY reason NULLS FIRST;

-- ⑤ 선수별 역할 (선언 대 실측)
SELECT * FROM pg_temp.sr_players(:sid);

-- ⑥ 전술 변경별 비용
SELECT * FROM pg_temp.sr_change_costs(:sid);

ROLLBACK;
