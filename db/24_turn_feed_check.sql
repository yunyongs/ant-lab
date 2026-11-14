-- =====================================================================
-- my Fund Manager Phase 0 — 턴 피드 검증 SQL (P0-18)
-- 저장 행을 독립 계산(turn_price·month_event·asset_month_attention 직접 조회)과 비교하고, 생성기를 다시 돌려 저장 = 재생성과 사후 정보 차단을 확인한다.
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/24_turn_feed_check.sql   (feed/*.csv 를 상대 경로로 읽는다)
--       23 을 한 번 이상 실행한 연습 시즌이 대상이다. 변조 시험은 하위 블록(EXCEPTION)에서 하고 반드시 되돌린다. 마지막은 ROLLBACK 이다.
-- 판정: 마지막 줄 p0_18_check 가 PASS 이고 종료 코드 0.
-- 변조 시험 규칙: 두 호출이 모두 성공할 때만 비교한다 (오류를 "변화"로 세지 않는다). 미래 불변성(SAME)은 행 수와 무관하게 보되, 양성 대조군(DIFF)은 비어 있지 않은 합성 입력과 비어 있지 않은 실제 턴에서 따로 확인한다.
--   Future invariance holds for any row count; the positive controls run on a non-empty synthetic input and on a non-empty live turn, and a skipped control is a visible row that fails on a played season.
-- =====================================================================
\ir 24_turn_feed_def.sql

BEGIN;
CREATE TEMP TABLE tf_check (item text NOT NULL, ok boolean NOT NULL);

-- 생성 결과와 저장 결과를 같은 모양(jsonb 배열, seq 순서)으로 만든다
CREATE FUNCTION pg_temp.zz_regen(p_sid bigint, p_turn smallint) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY g.seq), '[]'::jsonb) FROM pg_temp.tf_generate(pg_temp.tf_input(p_sid, p_turn)) g
$$;
CREATE FUNCTION pg_temp.zz_stored(p_sid bigint, p_turn smallint) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY s.seq), '[]'::jsonb)
    FROM (SELECT f.seq, f.kind, f.asset_id, f.asset_group_id, f.month_event_id, f.category_hidden, f.direction, f.strength,
                 f.repeat_count, f.template_id, f.template_vars, f.body
          FROM turn_feed f WHERE f.season_id = p_sid AND f.turn_no = p_turn) s
$$;

-- Future-only blocks tampered (attention negated, event reaction shifted, returns halved): true when the regenerated feed is unchanged. NULL on error.
CREATE FUNCTION pg_temp.zz_future_same(p_sid bigint, p_turn smallint) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE
    v_base jsonb := pg_temp.zz_regen(p_sid, p_turn);
    v_same boolean := NULL;
BEGIN
    BEGIN
        UPDATE asset_month_attention SET attention_index = -attention_index
         WHERE month_block_id IN (SELECT month_block_id FROM season_path WHERE season_id = p_sid GROUP BY month_block_id HAVING min(turn_no) > p_turn);
        UPDATE month_event SET reaction_change = reaction_change + 0.25
         WHERE month_block_id IN (SELECT month_block_id FROM season_path WHERE season_id = p_sid GROUP BY month_block_id HAVING min(turn_no) > p_turn);
        UPDATE asset_month_return SET local_return = round(local_return * 0.5, 8)
         WHERE month_block_id IN (SELECT month_block_id FROM season_path WHERE season_id = p_sid GROUP BY month_block_id HAVING min(turn_no) > p_turn);
        v_same := pg_temp.zz_regen(p_sid, p_turn) IS NOT DISTINCT FROM v_base;
        RAISE EXCEPTION 'probe' USING ERRCODE = 'ZZ999';
    EXCEPTION WHEN SQLSTATE 'ZZ999' THEN NULL;
              WHEN OTHERS THEN v_same := NULL;
    END;
    RETURN v_same;
END $$;
-- Current block attention negated: true when the regenerated feed changes. NULL on error.
CREATE FUNCTION pg_temp.zz_attention_flips(p_sid bigint, p_turn smallint) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE
    v_base jsonb := pg_temp.zz_regen(p_sid, p_turn);
    v_diff boolean := NULL;
BEGIN
    BEGIN
        UPDATE asset_month_attention SET attention_index = -attention_index
         WHERE month_block_id = (SELECT month_block_id FROM season_path WHERE season_id = p_sid AND turn_no = p_turn);
        v_diff := pg_temp.zz_regen(p_sid, p_turn) IS DISTINCT FROM v_base;
        RAISE EXCEPTION 'probe' USING ERRCODE = 'ZZ999';
    EXCEPTION WHEN SQLSTATE 'ZZ999' THEN NULL;
              WHEN OTHERS THEN v_diff := NULL;
    END;
    RETURN v_diff;
END $$;
-- Hand input with a real attention signal: group 1 gains (SPREAD), asset 102 falls from the top (FADE). p_sign = -1 negates every attention value.
CREATE FUNCTION pg_temp.zz_syn(p_sign numeric) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT format('{"season_id":1,"turn_no":4,"seed":"7","events":[],
        "assets":[{"asset_id":101,"asset_name":"가나","group_id":1,"group_code":"G1","group_name":"갑","market":"KR","ticker":"A","ret":0.05,"prev_ret":0.03,"streak":1,
                   "attention_index":%1$s,"attention_rank":1,"attention_n":2,"prev_attention":%2$s,"prev_attention_rank":2,
                   "prev_story_direction":null,"prev_echo_repeat":1,"focus_name":"가나"},
                  {"asset_id":102,"asset_name":"다라","group_id":2,"group_code":"G2","group_name":"을","market":"KR","ticker":"B","ret":-0.04,"prev_ret":-0.02,"streak":1,
                   "attention_index":%2$s,"attention_rank":2,"attention_n":2,"prev_attention":%1$s,"prev_attention_rank":1,
                   "prev_story_direction":null,"prev_echo_repeat":1,"focus_name":"가나"}],
        "groups":[{"group_id":1,"group_code":"G1","group_name":"갑","group_ret":0.05,"attention_index":%1$s,"prev_attention":%2$s},
                  {"group_id":2,"group_code":"G2","group_name":"을","group_ret":-0.04,"attention_index":%2$s,"prev_attention":%1$s}]}',
        (2 * p_sign)::text, (1 * p_sign)::text)::jsonb
$$;
CREATE FUNCTION pg_temp.zz_gen(p_input jsonb) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY g.seq), '[]'::jsonb) FROM pg_temp.tf_generate(p_input) g
$$;

DO $$
DECLARE
    v_sid bigint;
    v_cur smallint;
    v_bad bigint;
    v_k smallint;        -- 미래 전용 블록이 남은 가장 큰 완료 턴
    v_ke smallint;       -- 사건이 있는 가장 큰 완료 턴
    v_base jsonb;
    v_kn smallint;       -- largest completed turn with a future-only block and a non-empty feed
    v_ka smallint;       -- largest completed turn with an attention index and a non-empty feed
    v_t smallint;
    v_pos jsonb;
    v_neg jsonb;
    v_after jsonb;
    v_err boolean;
    v_hint text;
    v_kz smallint;       -- 사건이 없는 가장 큰 완료 턴 (the largest completed turn whose block has no events)
    v_nz integer;
    v_diff_event boolean;
    v_eligible bigint;
    v_input jsonb;
    v_out jsonb;
BEGIN
    SELECT s.id, s.current_turn_no INTO v_sid, v_cur
    FROM season s JOIN member m ON m.id = s.member_id
    WHERE lower(m.email) = lower('phase0@example.invalid');
    IF NOT FOUND THEN RAISE EXCEPTION 'P0-18 검증: 연습 시즌이 없다'; END IF;

    -- 1 행 수 ---------------------------------------------------------------
    INSERT INTO tf_check
    SELECT '완료 턴마다 0–15행·seq 연속', count(*) = 0
    FROM (SELECT completed.turn_no
          FROM generate_series(1, v_cur) AS completed(turn_no)
          LEFT JOIN turn_feed f ON f.season_id = v_sid AND f.turn_no = completed.turn_no
          GROUP BY completed.turn_no
          HAVING count(f.id) > 15
              OR (count(f.id) > 0 AND (min(f.seq) IS DISTINCT FROM 1 OR max(f.seq) IS DISTINCT FROM count(f.id)))) x;
    INSERT INTO tf_check VALUES ('0턴 피드 없음', NOT EXISTS (SELECT 1 FROM turn_feed WHERE season_id = v_sid AND turn_no < 1));
    INSERT INTO tf_check VALUES ('미래 피드 없음', NOT EXISTS (SELECT 1 FROM turn_feed WHERE season_id = v_sid AND turn_no > v_cur));
    INSERT INTO tf_check VALUES ('NUDGE 제외', NOT EXISTS (SELECT 1 FROM turn_feed WHERE season_id = v_sid AND kind = 'NUDGE'));

    -- 2 저장 = 재생성 (완료 턴 전부; 입력 조립부와 생성기를 실제로 다시 실행)
    SELECT count(*) INTO v_bad
    FROM generate_series(1, v_cur) t(turn_no)
    WHERE pg_temp.zz_stored(v_sid, t.turn_no::smallint) IS DISTINCT FROM pg_temp.zz_regen(v_sid, t.turn_no::smallint);
    INSERT INTO tf_check VALUES ('저장 피드 = 재생성 (완료 턴 전부)', v_bad = 0);

    -- 3 사건: 현재 블록만, 전부 포함, 희소 숨김
    INSERT INTO tf_check VALUES ('사건은 현재 블록만 참조', NOT EXISTS (
        SELECT 1 FROM turn_feed f JOIN season_path sp ON sp.season_id = f.season_id AND sp.turn_no = f.turn_no
        JOIN month_event e ON e.id = f.month_event_id WHERE f.season_id = v_sid AND e.month_block_id <> sp.month_block_id));
    SELECT count(*) INTO v_bad
    FROM generate_series(1, v_cur) t(turn_no)
    JOIN season_path sp ON sp.season_id = v_sid AND sp.turn_no = t.turn_no
    WHERE (SELECT count(*) FROM month_event e WHERE e.month_block_id = sp.month_block_id)
       <> (SELECT count(*) FROM turn_feed f WHERE f.season_id = v_sid AND f.turn_no = t.turn_no AND f.kind = 'EVENT');
    INSERT INTO tf_check VALUES ('현재 블록 사건이 전부 EVENT 행으로 들어 있다', v_bad = 0);
    -- 희소 판정을 month_event 에서 따로 센다 (유형별 서로 다른 에피소드 수 < 3 이면 비공개)
    SELECT count(*) INTO v_bad
    FROM turn_feed f
    JOIN month_event e ON e.id = f.month_event_id
    JOIN tf_templates t ON t.template_id = f.template_id
    WHERE f.season_id = v_sid AND f.kind = 'EVENT'
      AND (f.category_hidden IS DISTINCT FROM ((SELECT count(DISTINCT substr(y.event_key, 1, 6)) FROM month_event y WHERE y.event_type = e.event_type) < 3)
           OR t.category_hidden IS DISTINCT FROM f.category_hidden
           OR (f.category_hidden AND (f.direction IS NOT NULL
                                     OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(f.template_vars) k) IS DISTINCT FROM ARRAY['reaction']))
           OR (NOT f.category_hidden AND (t.event_category IS DISTINCT FROM e.category OR f.direction IS NOT NULL)));
    INSERT INTO tf_check VALUES ('희소 숨김: 에피소드 3 미만이면 비공개 템플릿·{reaction}만·방향 없음, 아니면 범주 템플릿', v_bad = 0);

    -- 4 ECHO: 직전 시즌 턴의 같은 종목·같은 방향 이야기 + 관심이 꺾이지 않음 + 반복 횟수 누적, 그리고 비어 있지 않음
    INSERT INTO tf_check VALUES ('ECHO 자격·반복 횟수', NOT EXISTS (
        SELECT 1 FROM turn_feed f
        WHERE f.season_id = v_sid AND f.kind = 'ECHO'
          AND (f.turn_no < 2
               OR NOT EXISTS (SELECT 1 FROM turn_feed p WHERE p.season_id = f.season_id AND p.turn_no = f.turn_no - 1 AND p.asset_id = f.asset_id
                                AND p.kind IN ('RISE','FALL','ECHO') AND p.direction = f.direction)
               OR f.repeat_count IS DISTINCT FROM coalesce((SELECT max(p.repeat_count) FROM turn_feed p WHERE p.season_id = f.season_id
                                                            AND p.turn_no = f.turn_no - 1 AND p.asset_id = f.asset_id AND p.kind = 'ECHO'), 0) + 1
               OR EXISTS (SELECT 1 FROM season_path c JOIN season_path pv ON pv.season_id = c.season_id AND pv.turn_no = c.turn_no - 1
                          JOIN asset_month_attention ca ON ca.month_block_id = c.month_block_id AND ca.asset_id = f.asset_id
                          JOIN asset_month_attention pa ON pa.month_block_id = pv.month_block_id AND pa.asset_id = f.asset_id
                          WHERE c.season_id = f.season_id AND c.turn_no = f.turn_no
                            AND ca.attention_index IS NOT NULL AND pa.attention_index IS NOT NULL AND ca.attention_index < pa.attention_index))));
    WITH pr AS (
        SELECT tp.asset_id, tp.turn_no, round(tp.price / NULLIF(lag(tp.price) OVER w, 0) - 1, 8) AS ret
        FROM turn_price tp WHERE tp.season_id = v_sid WINDOW w AS (PARTITION BY tp.asset_id ORDER BY tp.turn_no)
    ), pr2 AS (
        SELECT pr.*, lag(pr.ret) OVER (PARTITION BY pr.asset_id ORDER BY pr.turn_no) AS prev_ret FROM pr
    )
    SELECT count(*) INTO v_eligible
    FROM pr2 x
    WHERE x.turn_no BETWEEN 2 AND v_cur AND round(x.ret * 100, 0) <> 0 AND x.prev_ret IS NOT NULL AND sign(x.prev_ret) = sign(x.ret)
      AND EXISTS (SELECT 1 FROM turn_feed p WHERE p.season_id = v_sid AND p.turn_no = x.turn_no - 1 AND p.asset_id = x.asset_id
                    AND p.kind IN ('RISE','FALL','ECHO') AND p.direction = CASE WHEN x.ret > 0 THEN 'UP' ELSE 'DOWN' END)
      AND NOT EXISTS (SELECT 1 FROM season_path c JOIN season_path pv ON pv.season_id = c.season_id AND pv.turn_no = c.turn_no - 1
                      JOIN asset_month_attention ca ON ca.month_block_id = c.month_block_id AND ca.asset_id = x.asset_id
                      JOIN asset_month_attention pa ON pa.month_block_id = pv.month_block_id AND pa.asset_id = x.asset_id
                      WHERE c.season_id = v_sid AND c.turn_no = x.turn_no
                        AND ca.attention_index IS NOT NULL AND pa.attention_index IS NOT NULL AND ca.attention_index < pa.attention_index);
    INSERT INTO tf_check VALUES ('ECHO 안 비었음: 자격 있는 (턴, 종목)이 있으면 시즌 ECHO 행이 1개 이상',
        v_eligible = 0 OR EXISTS (SELECT 1 FROM turn_feed WHERE season_id = v_sid AND kind = 'ECHO'));

    -- 5 FADE·방향·렌더
    INSERT INTO tf_check VALUES ('FADE 자격: 현재·직전 관심이 있고 내려갔다', NOT EXISTS (
        SELECT 1 FROM turn_feed f JOIN season_path sp ON sp.season_id=f.season_id AND sp.turn_no=f.turn_no
        LEFT JOIN asset_month_attention ca ON ca.asset_id=f.asset_id AND ca.month_block_id=sp.month_block_id
        WHERE f.season_id=v_sid AND f.kind='FADE'
          AND (ca.attention_index IS NULL OR NOT EXISTS (SELECT 1 FROM asset_month_attention pa
              JOIN season_path pp ON pp.season_id=sp.season_id AND pp.turn_no=sp.turn_no-1
              WHERE pa.asset_id=f.asset_id AND pa.month_block_id=pp.month_block_id
                AND pa.attention_index IS NOT NULL AND ca.attention_index < pa.attention_index))));
    -- 저장 방향·수익률 변수를 turn_price 에서 직접 계산한 값과 대조한다 (종목은 종목 수익률, 그룹은 풀 종목 수익률의 동일 가중 평균을 소수 8자리로 한 번 반올림)
    SELECT count(*) INTO v_bad
    FROM turn_feed f
    CROSS JOIN LATERAL (
        SELECT CASE WHEN f.asset_id IS NOT NULL THEN
                    (SELECT round(tp.price / NULLIF(pv.price, 0) - 1, 8) FROM turn_price tp
                     JOIN turn_price pv ON pv.season_id = tp.season_id AND pv.asset_id = tp.asset_id AND pv.turn_no = tp.turn_no - 1
                     WHERE tp.season_id = f.season_id AND tp.turn_no = f.turn_no AND tp.asset_id = f.asset_id)
                    ELSE
                    (SELECT round(avg(round(tp.price / NULLIF(pv.price, 0) - 1, 8)), 8) FROM turn_price tp
                     JOIN turn_price pv ON pv.season_id = tp.season_id AND pv.asset_id = tp.asset_id AND pv.turn_no = tp.turn_no - 1
                     JOIN asset a ON a.id = tp.asset_id
                     WHERE tp.season_id = f.season_id AND tp.turn_no = f.turn_no AND a.asset_group_id = f.asset_group_id) END AS ret) x
    LEFT JOIN tf_templates t ON t.template_id = f.template_id
    WHERE f.season_id = v_sid AND f.kind IN ('RISE','FALL','ECHO','SPREAD') AND f.direction IS NOT NULL
      AND (x.ret IS NULL
           OR f.direction IS DISTINCT FROM CASE WHEN x.ret > 0 THEN 'UP' ELSE 'DOWN' END
           OR (f.template_vars ? 'ret' AND (f.template_vars ->> 'ret')::numeric IS DISTINCT FROM x.ret)
           OR (f.template_vars ? 'group_ret' AND (f.template_vars ->> 'group_ret')::numeric IS DISTINCT FROM x.ret)
           -- 렌더 값이 0% 이면 사건 카드와 틀이 같은 소음만 허용한다
           OR (round(x.ret * 100, 0) = 0
               AND pg_temp.tf_shape(t.text) NOT IN (SELECT pg_temp.tf_shape(e.text) FROM tf_templates e WHERE e.kind = 'EVENT' AND e.status = 'ACTIVE')));
    INSERT INTO tf_check VALUES ('방향·수익률 변수 = turn_price 독립 계산, 0% 렌더는 사건 틀 소음만', v_bad = 0);
    SELECT count(*) INTO v_bad
    FROM turn_feed f LEFT JOIN tf_templates t ON t.template_id=f.template_id
    WHERE f.season_id=v_sid AND (t.template_id IS NULL OR pg_temp.tf_render(t.text, f.template_vars) IS DISTINCT FROM f.body);
    INSERT INTO tf_check VALUES ('템플릿 등록·정확한 변수 재렌더', v_bad = 0);

    -- 6 중복 선택: 같은 (종류, 대상)이 겹치는 수는 후보 수가 모자랄 때만 나온다.
    --   후보 수의 하한 = 사건 수 + 렌더 %가 0이 아닌 종목 수 (종목마다 RISE·FALL 후보가 하나씩은 있다)
    SELECT count(*) INTO v_bad
    FROM (SELECT f.turn_no, count(*) AS n,
                 count(*) - count(DISTINCT (f.kind, coalesce(f.asset_id, 0), coalesce(f.asset_group_id, 0), coalesce(f.month_event_id, 0))) AS dup,
                 (SELECT count(*) FROM month_event e JOIN season_path sp ON sp.month_block_id = e.month_block_id
                  WHERE sp.season_id = f.season_id AND sp.turn_no = f.turn_no)
                 + (SELECT count(*) FROM turn_price tp JOIN turn_price pv ON pv.season_id = tp.season_id AND pv.asset_id = tp.asset_id AND pv.turn_no = tp.turn_no - 1
                    WHERE tp.season_id = f.season_id AND tp.turn_no = f.turn_no AND round(round(tp.price / NULLIF(pv.price, 0) - 1, 8) * 100, 0) <> 0) AS lower_bound
          FROM turn_feed f WHERE f.season_id = v_sid GROUP BY f.season_id, f.turn_no) x
    WHERE x.dup > greatest(0, x.n - x.lower_bound);
    INSERT INTO tf_check VALUES ('중복 선택: 후보가 남는 턴에서는 같은 (종류, 대상)이 겹치지 않는다', v_bad = 0);

    -- 7 관심 지수가 강도에 닿는다 (관심 데이터가 있으면 강도가 하나로만 몰리지 않는다)
    INSERT INTO tf_check VALUES ('관심 등급이 강도에 닿는다 (소음 강도가 1 하나뿐이지 않다)',
        v_cur < 1 OR NOT EXISTS (SELECT 1 FROM asset_month_attention WHERE attention_index IS NOT NULL)
        OR (SELECT count(DISTINCT strength) FROM turn_feed WHERE season_id = v_sid AND kind <> 'EVENT' AND strength IS NOT NULL) >= 2);

    -- 8 같은 옷: 사건 카드마다 같은 틀의 소음 템플릿이 있고, 생성기 경로(tf_template_pool)에서도 후보가 된다
    SELECT count(*) INTO v_bad
    FROM tf_templates e
    WHERE e.kind = 'EVENT' AND e.status = 'ACTIVE'
      AND (NOT EXISTS (SELECT 1 FROM tf_templates n WHERE n.kind <> 'EVENT' AND n.status = 'ACTIVE' AND n.direction = 'UP' AND pg_temp.tf_shape(n.text) = pg_temp.tf_shape(e.text))
        OR NOT EXISTS (SELECT 1 FROM tf_templates n WHERE n.kind <> 'EVENT' AND n.status = 'ACTIVE' AND n.direction = 'DOWN' AND pg_temp.tf_shape(n.text) = pg_temp.tf_shape(e.text)));
    INSERT INTO tf_check VALUES ('같은 옷 ① 정적: 사건 템플릿마다 UP·DOWN 소음 짝이 있다', v_bad = 0);
    SELECT count(*) INTO v_bad
    FROM tf_templates e
    CROSS JOIN (VALUES ('UP', 5), ('UP', 0), ('DOWN', -5), ('DOWN', 0)) AS s(dir, r)
    WHERE e.kind = 'EVENT' AND e.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1
                      FROM (VALUES ('RISE'), ('FALL'), ('SPREAD')) AS k(kind)
                      CROSS JOIN LATERAL pg_temp.tf_template_pool(k.kind, 'GROUP', s.dir, s.r, ARRAY['group_name', 'group_ret']) p
                      JOIN tf_templates n ON n.template_id = p.template_id
                      WHERE pg_temp.tf_shape(n.text) = pg_temp.tf_shape(e.text));
    INSERT INTO tf_check VALUES ('같은 옷 ② 생성기 경로: 사건 템플릿마다 양·0·음 그룹 수익률에서 같은 틀 소음이 후보가 된다', v_bad = 0);

    -- 9 사후 정보 차단 (변조는 하위 블록 안에서 하고 되돌린다)
    SELECT max(k.turn_no) INTO v_k
    FROM season_path k
    WHERE k.season_id = v_sid AND k.turn_no BETWEEN 1 AND v_cur
      AND EXISTS (SELECT 1 FROM season_path f WHERE f.season_id = v_sid
                    AND f.month_block_id NOT IN (SELECT b.month_block_id FROM season_path b WHERE b.season_id = v_sid AND b.turn_no <= k.turn_no));
    SELECT max(sp.turn_no) INTO v_ke
    FROM season_path sp
    WHERE sp.season_id = v_sid AND sp.turn_no BETWEEN 1 AND v_cur AND EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = sp.month_block_id);
    -- ① Future invariance holds for any row count, so a valid 0-row feed (#77) is checked too. It cannot be hollow here: ①b repeats it on a non-empty feed.
    IF v_k IS NULL THEN
        INSERT INTO tf_check VALUES ('사후 정보 차단 ① 건너뜀: 미래 전용 블록이 남은 턴 없음', v_cur < 1);
    ELSE
        INSERT INTO tf_check VALUES ('사후 정보 차단 ① 미래 전용 블록의 관심·사건 반응·수익률을 바꿔도 현재 턴 피드가 같다 (행 수와 무관, 0행 포함)',
            pg_temp.zz_future_same(v_sid, v_k) IS TRUE);
    END IF;
    -- ①b Same tamper, same compare, on the largest completed turn that still has a future-only block and a non-empty feed
    v_kn := NULL;
    FOR v_t IN SELECT k.turn_no FROM season_path k
               WHERE k.season_id = v_sid AND k.turn_no BETWEEN 1 AND v_cur
                 AND EXISTS (SELECT 1 FROM season_path f WHERE f.season_id = v_sid
                               AND f.month_block_id NOT IN (SELECT b.month_block_id FROM season_path b WHERE b.season_id = v_sid AND b.turn_no <= k.turn_no))
               ORDER BY k.turn_no DESC
    LOOP
        IF jsonb_array_length(pg_temp.zz_regen(v_sid, v_t)) > 0 THEN v_kn := v_t; EXIT; END IF;
    END LOOP;
    IF v_kn IS NULL THEN
        INSERT INTO tf_check VALUES ('사후 정보 차단 ①b 건너뜀: 비어 있지 않은 턴 없음', v_cur < 1);
    ELSE
        INSERT INTO tf_check VALUES ('사후 정보 차단 ①b 비어 있지 않은 피드에서 미래 전용 블록을 바꿔도 현재 턴 피드가 같다',
            pg_temp.zz_future_same(v_sid, v_kn) IS TRUE);
    END IF;
    -- ② Positive control on a synthetic non-empty hand input: negating the attention index must change the output
    v_pos := pg_temp.zz_gen(pg_temp.zz_syn(1));
    v_neg := pg_temp.zz_gen(pg_temp.zz_syn(-1));
    INSERT INTO tf_check VALUES ('사후 정보 차단 ② 양성 대조군(합성 입력): 비어 있지 않은 손 입력에서 관심 지수 부호를 뒤집으면 DIFF',
        jsonb_array_length(v_pos) > 0 AND v_pos IS DISTINCT FROM v_neg);
    -- ②b Same control on live data: largest completed turn whose block has an attention index and whose feed is non-empty
    v_ka := NULL;
    FOR v_t IN SELECT k.turn_no FROM season_path k
               WHERE k.season_id = v_sid AND k.turn_no BETWEEN 1 AND v_cur
                 AND EXISTS (SELECT 1 FROM asset_month_attention ama WHERE ama.month_block_id = k.month_block_id AND ama.attention_index IS NOT NULL)
               ORDER BY k.turn_no DESC
    LOOP
        IF jsonb_array_length(pg_temp.zz_regen(v_sid, v_t)) > 0 THEN v_ka := v_t; EXIT; END IF;
    END LOOP;
    IF v_ka IS NULL THEN
        INSERT INTO tf_check VALUES ('사후 정보 차단 ②b 건너뜀: 관심 지수가 있고 피드가 비어 있지 않은 턴 없음', v_cur < 1);
    ELSE
        INSERT INTO tf_check VALUES ('사후 정보 차단 ②b 양성 대조군(실제 데이터): 현재 블록 관심 변조는 DIFF', pg_temp.zz_attention_flips(v_sid, v_ka) IS TRUE);
    END IF;
    -- ③ Positive control: changing the current block's event reaction must change the feed (a turn with events)
    v_diff_event := (v_ke IS NULL);
    IF v_ke IS NOT NULL THEN
        v_base := pg_temp.zz_regen(v_sid, v_ke);
        v_err := false;
        BEGIN
            UPDATE month_event SET reaction_change = reaction_change + 0.25
             WHERE month_block_id = (SELECT month_block_id FROM season_path WHERE season_id = v_sid AND turn_no = v_ke);
            v_after := pg_temp.zz_regen(v_sid, v_ke);
            v_diff_event := v_after IS DISTINCT FROM v_base;
            RAISE EXCEPTION 'probe' USING ERRCODE = 'ZZ999';
        EXCEPTION WHEN SQLSTATE 'ZZ999' THEN NULL;
                  WHEN OTHERS THEN v_err := true;
        END;
        v_diff_event := v_diff_event AND NOT v_err;
    END IF;
    INSERT INTO tf_check VALUES ('사후 정보 차단 ③ 양성 대조군: 현재 블록 사건 반응 변조는 DIFF', v_diff_event);

    -- 10 입력 오류는 명시적 오류다 (미편집 블록, 반응값 NULL). 기대하는 HINT 가 나와야 PASS.
    IF v_cur >= 1 THEN
        v_hint := NULL;
        BEGIN
            UPDATE month_block SET events_reviewed = false WHERE id = (SELECT month_block_id FROM season_path WHERE season_id = v_sid AND turn_no = v_cur);
            PERFORM pg_temp.tf_input(v_sid, v_cur);
            RAISE EXCEPTION 'probe' USING ERRCODE = 'ZZ999';
        EXCEPTION WHEN SQLSTATE 'ZZ999' THEN NULL;
                  WHEN OTHERS THEN GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
        END;
        INSERT INTO tf_check VALUES ('입력 오류: 사건 편집이 끝나지 않은 블록은 FEED_EVENTS_UNREVIEWED', v_hint IS NOT DISTINCT FROM 'FEED_EVENTS_UNREVIEWED');
        IF v_ke IS NOT NULL THEN
            v_hint := NULL;
            BEGIN
                UPDATE month_event SET reaction_change = NULL WHERE id = (SELECT min(e.id) FROM month_event e
                    WHERE e.month_block_id = (SELECT month_block_id FROM season_path WHERE season_id = v_sid AND turn_no = v_ke));
                PERFORM pg_temp.tf_input(v_sid, v_ke);
                RAISE EXCEPTION 'probe' USING ERRCODE = 'ZZ999';
            EXCEPTION WHEN SQLSTATE 'ZZ999' THEN NULL;
                      WHEN OTHERS THEN GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
            END;
            INSERT INTO tf_check VALUES ('입력 오류: 사건 반응값 NULL 은 FEED_REACTION_MISSING', v_hint IS NOT DISTINCT FROM 'FEED_REACTION_MISSING');
        END IF;
    END IF;

    -- 10b Flat turn through the real input assembler (#77): no candidate is 0 rows, not an error. Rolled back below.
    SELECT max(sp.turn_no) INTO v_kz
    FROM season_path sp
    WHERE sp.season_id = v_sid AND sp.turn_no BETWEEN 1 AND v_cur
      AND NOT EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = sp.month_block_id);
    IF v_kz IS NOT NULL THEN
        v_nz := NULL; v_err := false;
        BEGIN
            UPDATE turn_price a SET price = b.price
            FROM turn_price b
            WHERE a.season_id = v_sid AND a.turn_no = v_kz
              AND b.season_id = a.season_id AND b.asset_id = a.asset_id AND b.turn_no = v_kz - 1;
            UPDATE asset_month_attention SET attention_index = NULL
            WHERE month_block_id = (SELECT month_block_id FROM season_path WHERE season_id = v_sid AND turn_no = v_kz);
            v_nz := (SELECT count(*) FROM pg_temp.tf_generate(pg_temp.tf_input(v_sid, v_kz)));
            RAISE EXCEPTION 'probe' USING ERRCODE = 'ZZ999';
        EXCEPTION WHEN SQLSTATE 'ZZ999' THEN NULL;
                  WHEN OTHERS THEN v_err := true;
        END;
        INSERT INTO tf_check VALUES ('생성기 ⑥ 보합 턴(수익률·관심 없음, 사건 없음)을 실제 입력 조립으로 넣으면 오류 없이 0행', NOT v_err AND v_nz = 0);
    ELSE
        INSERT INTO tf_check VALUES ('생성기 ⑥ 건너뜀: 사건이 없는 완료 턴 없음', v_cur < 1);
    END IF;

    -- 11 생성기 단독 (손으로 만든 작은 입력)
    -- 종목 A: 직전 UP 이야기 + 이번 UP, 반복 2번째 → ECHO(repeat_count = 2). 종목 B: 렌더 0% → 방향 문장 없음. 관심 전부 NULL → SPREAD 없음.
    v_input := '{"season_id":1,"turn_no":4,"seed":"7","events":[],
        "assets":[{"asset_id":101,"asset_name":"가나","group_id":1,"group_code":"G1","group_name":"갑","market":"KR","ticker":"A","ret":0.05,"prev_ret":0.03,"streak":3,
                   "attention_index":null,"attention_rank":null,"attention_n":0,"prev_attention":null,"prev_attention_rank":null,
                   "prev_story_direction":"UP","prev_echo_repeat":2,"focus_name":null},
                  {"asset_id":102,"asset_name":"다라","group_id":2,"group_code":"G2","group_name":"을","market":"KR","ticker":"B","ret":0.001,"prev_ret":0.0,"streak":1,
                   "attention_index":null,"attention_rank":null,"attention_n":0,"prev_attention":null,"prev_attention_rank":null,
                   "prev_story_direction":null,"prev_echo_repeat":1,"focus_name":null}],
        "groups":[{"group_id":1,"group_code":"G1","group_name":"갑","group_ret":0.05,"attention_index":null,"prev_attention":null},
                  {"group_id":2,"group_code":"G2","group_name":"을","group_ret":0.001,"attention_index":null,"prev_attention":null}]}';
    v_err := false; v_out := NULL;
    BEGIN v_out := (SELECT jsonb_agg(to_jsonb(g) ORDER BY g.seq) FROM pg_temp.tf_generate(v_input) g);
    EXCEPTION WHEN OTHERS THEN v_err := true; END;
    INSERT INTO tf_check VALUES ('생성기 단독 ① 직전 방향성 이야기가 있는 종목은 ECHO(repeat_count 2)가 나온다',
        NOT v_err AND EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(v_out, '[]')) x WHERE x ->> 'kind' = 'ECHO' AND (x ->> 'asset_id')::bigint = 101 AND (x ->> 'repeat_count')::int = 2));
    INSERT INTO tf_check VALUES ('생성기 단독 ② 렌더 0% 종목은 종목 방향 문장이 없다',
        NOT v_err AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(v_out, '[]')) x WHERE (x ->> 'asset_id')::bigint = 102));
    INSERT INTO tf_check VALUES ('생성기 단독 ③ 관심 지수가 전부 NULL 이면 SPREAD·FADE·강도가 없다',
        NOT v_err AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(v_out, '[]')) x WHERE x ->> 'kind' IN ('SPREAD', 'FADE') OR x ->> 'strength' IS NOT NULL));
    v_err := false; v_nz := NULL;
    BEGIN v_nz := (SELECT count(*) FROM pg_temp.tf_generate('{"season_id":1,"turn_no":4,"seed":"7","events":[],"assets":[],"groups":[]}'::jsonb));
    EXCEPTION WHEN OTHERS THEN v_err := true; END;
    INSERT INTO tf_check VALUES ('생성기 단독 ④ 후보가 하나도 없으면 오류 없이 0행', NOT v_err AND v_nz = 0);
    -- ⑤ one reviewed event and every asset and group flat: the feed is exactly that event
    v_err := false; v_out := NULL;
    BEGIN
        v_out := (SELECT jsonb_agg(to_jsonb(g) ORDER BY g.seq) FROM pg_temp.tf_generate('{"season_id":1,"turn_no":4,"seed":"7",
            "events":[{"event_id":1,"event_key":"EV1","category":"TRADE","reaction":0.02,"strength":2,"hidden":false}],
            "assets":[{"asset_id":101,"asset_name":"가나","group_id":1,"group_code":"G1","group_name":"갑","market":"KR","ticker":"A","ret":0,"prev_ret":0,"streak":1,
                       "attention_index":null,"attention_rank":null,"attention_n":0,"prev_attention":null,"prev_attention_rank":null,
                       "prev_story_direction":null,"prev_echo_repeat":1,"focus_name":null}],
            "groups":[{"group_id":1,"group_code":"G1","group_name":"갑","group_ret":0,"attention_index":null,"prev_attention":null}]}'::jsonb) g);
    EXCEPTION WHEN OTHERS THEN v_err := true; END;
    INSERT INTO tf_check VALUES ('생성기 단독 ⑤ 사건만 있고 소음 후보가 없으면 그 사건 1행(kind EVENT, seq 1)',
        NOT v_err AND jsonb_array_length(coalesce(v_out, '[]')) = 1 AND v_out -> 0 ->> 'kind' = 'EVENT' AND v_out -> 0 ->> 'seq' = '1');
    -- Retirement guard: no loaded function body may still raise the retired no-candidate code (a half retirement would fail here)
    INSERT INTO tf_check VALUES ('은퇴한 후보 없음 오류 코드가 로드된 정의 어디에도 없다', NOT EXISTS (
        SELECT 1 FROM pg_proc WHERE pronamespace = pg_my_temp_schema() AND prosrc LIKE '%FEED\_NO\_CANDIDATE%'));
END $$;
SELECT item, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result FROM tf_check ORDER BY item;
-- 집계 행. 판정 열(p0_18_check)은 다른 검증 파일처럼 맨 끝 두 줄에 한 열로 낸다 (README 「전체 순서」와 골든 하네스의 판정 계약)
SELECT CASE WHEN count(*) > 0 AND bool_and(ok) THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM tf_check;
SELECT (count(*) > 0 AND bool_and(ok)) AS tf_ok FROM tf_check \gset
DO $$ BEGIN IF EXISTS (SELECT 1 FROM tf_check WHERE NOT ok) THEN RAISE EXCEPTION 'P0-18 검증 실패'; END IF; END $$;
ROLLBACK;
SELECT CASE WHEN :'tf_ok'::boolean THEN 'PASS' ELSE 'FAIL' END AS p0_18_check;
