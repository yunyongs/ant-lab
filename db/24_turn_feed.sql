-- P0-18 턴 피드 생성 진입점: 연습 시즌의 현재 턴 피드가 몇 행이고 빈칸이 없는지, 저장 = 재생성인지 낸다. 아무것도 쓰지 않는다.
-- 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/24_turn_feed.sql   (feed/*.csv 를 상대 경로로 읽는다)
-- 피드는 23_turn_advance.sql 이 턴마다 만든다. 23 정의보다 먼저 읽지 않는다.
\ir 24_turn_feed_def.sql
SELECT count(*) AS feed_rows,
       count(*) FILTER (WHERE seq <> row_number) AS seq_gaps,
       (SELECT pg_temp.tf_regen_matches(s.id, s.current_turn_no)
          FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid')) AS stored_equals_regenerated
FROM (SELECT f.*, row_number() OVER (ORDER BY seq)::smallint AS row_number
      FROM turn_feed f JOIN season s ON s.id = f.season_id
      JOIN member m ON m.id = s.member_id
      WHERE lower(m.email) = lower('phase0@example.invalid')
        AND f.turn_no = s.current_turn_no) x;
