-- =====================================================================
-- my Fund Manager Phase 0 — 턴 피드 생성 정의 (P0-18)
-- 제한 입력만 받아 결정적으로 생성한다. 영구 업무 표를 읽는 책임은 tf_input 에 둔다.
-- 규칙 버전 p0-18:v2 (tf_rule_version). 정책값은 이 버전의 것이다:
--   관심 등급   관심 지수가 있는 대상 중 순위 상위 10% 이내(동률 포함) = 3, 상위 절반 이내 = 2, 나머지 = 1, 지수 NULL = 강도 NULL (tf_grade)
--   선택 가중치 등급과 같은 정수(NULL = 1). 후보마다 w 장의 표를 만들어 가장 작은 해시가 그 후보의 키가 된다 (가중 비복원 추출)
--   재선택     후보를 한 바퀴 다 쓴 뒤에만 두 번째 바퀴 (round). 행 수는 0–15 (후보가 없으면 0행, 사건만 있으면 그 사건들), 사건은 현재 블록 것을 모두 포함
--   ECHO       직전 시즌 턴에 같은 종목의 같은 방향 이야기(RISE·FALL·ECHO)가 있고 관심이 꺾이지 않았을 때 (반복 횟수 = 직전 ECHO 반복 + 1, 없으면 1)
--   SPREAD     그룹 관심 지수가 직전 시즌 턴보다 올랐을 때 (둘 다 있어야 한다)
--   FADE       직전 시즌 턴 관심 순위 5위 이내였던 종목의 관심이 내려갔을 때, focus_name 은 현재 턴 관심 1위
--   해시       md5 앞 64비트 & Long.MAX. 입력 문자열 = 시드:턴:규칙 버전:용도:자연 키(종목 market:ticker, 그룹 code, 사건 event_key):바퀴
--   오류       모두 P0001 + HINT 코드 (FEED_INPUT·FEED_EVENTS_UNREVIEWED·FEED_REACTION_MISSING·FEED_RENDER·FEED_ALREADY_STORED)
--             FEED_RENDER = render or generation-result contract violated (template missing, more than 15 rows, placeholder errors). The former no-candidate error code is retired (#77): no candidate is a valid 0-row result.
--   규칙 버전 유지   입력 → 출력이 이전에 성공하던 경우는 바이트 단위로 같고, 예전에 오류이던 입력(후보 없음, 사건만 있음)만 0–3행을 돌려준다. 버전 문자열은 해시 입력이라 올리면 저장된 v2 피드의 "저장 = 재생성"이 깨진다.
-- =====================================================================

DO $$
BEGIN
    IF to_regclass('pg_temp.tf_templates') IS NOT NULL THEN DROP TABLE pg_temp.tf_templates; END IF;
    IF to_regclass('pg_temp.tf_variables') IS NOT NULL THEN DROP TABLE pg_temp.tf_variables; END IF;
END $$;

CREATE TEMP TABLE tf_templates (
    template_id varchar(40), kind varchar(6), status varchar(7), target varchar(5),
    direction varchar(4), strength smallint, event_category varchar(12),
    event_type varchar(30), outcome varchar(20), category_hidden boolean, text varchar(300)
) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE tf_variables (
    name varchar(30), type varchar(14), source text, turn_offset smallint, kinds varchar(80), max_len smallint
) ON COMMIT PRESERVE ROWS;

\copy tf_templates (template_id, kind, status, target, direction, strength, event_category, event_type, outcome, category_hidden, text) FROM 'feed/templates.csv' WITH (FORMAT csv, HEADER MATCH)
\copy tf_variables (name, type, source, turn_offset, kinds, max_len) FROM 'feed/variables.csv' WITH (FORMAT csv, HEADER MATCH)

CREATE OR REPLACE FUNCTION pg_temp.tf_hash(p_text text)
RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
    SELECT ('x' || substr(md5(coalesce(p_text, '')), 1, 16))::bit(64)::bigint & 9223372036854775807
$$;

CREATE OR REPLACE FUNCTION pg_temp.tf_render(p_text text, p_vars jsonb)
RETURNS varchar(300) LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_name text;
    v_type text;
    v_value text;
    v_out text := '';
    v_pos integer := 1;
    v_open integer;
    v_close integer;
    v_num numeric;
    v_max smallint;
BEGIN
    IF p_text IS NULL OR p_vars IS NULL OR jsonb_typeof(p_vars) <> 'object' THEN
        RAISE EXCEPTION 'P0-18 피드: 렌더 입력이 올바르지 않다' USING HINT = 'FEED_RENDER';
    END IF;
    WHILE v_pos <= length(p_text) LOOP
        v_open := strpos(substr(p_text, v_pos), '{');
        IF v_open = 0 THEN v_out := v_out || substr(p_text, v_pos); EXIT; END IF;
        v_open := v_open + v_pos - 1;
        v_out := v_out || substr(p_text, v_pos, v_open - v_pos);
        v_close := strpos(substr(p_text, v_open + 1), '}');
        IF v_close = 0 THEN RAISE EXCEPTION 'P0-18 피드: 닫히지 않은 자리' USING HINT = 'FEED_RENDER'; END IF;
        v_close := v_close + v_open;
        v_name := substr(p_text, v_open + 1, v_close - v_open - 1);
        IF v_name !~ '^[a-z][a-z0-9_]*$' THEN RAISE EXCEPTION 'P0-18 피드: 잘못된 자리 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
        IF NOT (p_vars ? v_name) THEN RAISE EXCEPTION 'P0-18 피드: 템플릿 변수 누락 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
        SELECT type INTO v_type FROM pg_temp.tf_variables WHERE name = v_name;
        IF NOT FOUND THEN RAISE EXCEPTION 'P0-18 피드: 허용되지 않은 변수 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
        IF v_type = 'name' THEN
            IF jsonb_typeof(p_vars -> v_name) <> 'string' THEN RAISE EXCEPTION 'P0-18 피드: 이름 변수 타입 오류 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
            v_value := p_vars ->> v_name;
        ELSIF v_type IN ('pct_signed', 'pct_unsigned') THEN
            IF jsonb_typeof(p_vars -> v_name) <> 'number' THEN RAISE EXCEPTION 'P0-18 피드: 비율 변수 타입 오류 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
            v_num := round((p_vars ->> v_name)::numeric * 100, 0);
            IF v_type = 'pct_signed' THEN
                v_value := CASE WHEN v_num > 0 THEN '+' || v_num::text || '%'
                                WHEN v_num < 0 THEN '-' || abs(v_num)::text || '%'
                                ELSE '0%' END;
            ELSE v_value := v_num::text || '%'; END IF;
        ELSIF v_type = 'int' THEN
            IF jsonb_typeof(p_vars -> v_name) <> 'number' THEN RAISE EXCEPTION 'P0-18 피드: 정수 변수 타입 오류 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
            v_num := (p_vars ->> v_name)::numeric;
            IF v_num <> trunc(v_num) THEN RAISE EXCEPTION 'P0-18 피드: 정수가 아닌 값 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
            v_value := v_num::bigint::text;
        ELSE RAISE EXCEPTION 'P0-18 피드: 변수 타입 오류 (%)', v_name USING HINT = 'FEED_RENDER';
        END IF;
        SELECT max_len INTO v_max FROM pg_temp.tf_variables WHERE name = v_name;
        IF v_max IS NOT NULL AND length(v_value) > v_max THEN RAISE EXCEPTION 'P0-18 피드: 변수 길이 초과 (%)', v_name USING HINT = 'FEED_RENDER'; END IF;
        v_out := v_out || v_value;
        v_pos := v_close + 1;
    END LOOP;
    IF EXISTS (SELECT 1 FROM jsonb_object_keys(p_vars) AS k(key)
               WHERE NOT EXISTS (SELECT 1 FROM regexp_matches(p_text, '\{([a-z][a-z0-9_]*)\}', 'g') AS m WHERE m[1] = k.key))
       OR length(v_out) > 300 THEN
        RAISE EXCEPTION 'P0-18 피드: 렌더 결과가 계약을 벗어났다' USING HINT = 'FEED_RENDER';
    END IF;
    RETURN v_out::varchar(300);
END $$;

-- 규칙 버전·정책 상수. 해시 입력, 관심 등급, FADE 상위 기준이 모두 여기서 나온다 (다른 곳에 따로 적지 않는다).
CREATE OR REPLACE FUNCTION pg_temp.tf_rule_version()
RETURNS text LANGUAGE sql IMMUTABLE AS $$ SELECT 'p0-18:v2'::text $$;

-- 선택 해시: 시드·턴·규칙 버전·용도·자연 키. 용도는 count(행 수)·pick(후보 선택)·tpl(템플릿 고르기)·order(최종 순서)로 나눈다.
CREATE OR REPLACE FUNCTION pg_temp.tf_pick_hash(p_input jsonb, p_usage text, p_key text)
RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
    SELECT pg_temp.tf_hash((p_input ->> 'seed') || ':' || (p_input ->> 'turn_no') || ':' || pg_temp.tf_rule_version() || ':' || p_usage || ':' || p_key)
$$;

-- 관심 등급 (규칙 버전 p0-18:v2): 관심 지수가 있는 대상 가운데 순위가 상위 10% 이내(동률 포함) = 3, 상위 절반 이내 = 2, 나머지 = 1, 관심 지수 NULL = NULL.
-- 정수 올림만 쓴다 (Java 에서도 같은 결과). 선택 가중치는 등급과 같은 정수이고 NULL 은 1 이다.
CREATE OR REPLACE FUNCTION pg_temp.tf_grade(p_rank integer, p_n integer)
RETURNS smallint LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN p_rank IS NULL OR p_n IS NULL OR p_n = 0 THEN NULL
                WHEN p_rank <= (p_n + 9) / 10 THEN 3
                WHEN p_rank <= (p_n + 1) / 2 THEN 2
                ELSE 1 END::smallint
$$;

-- 문장 틀: 자리 {이름}을 그 변수의 타입으로 바꾼 문장 (feed/check_templates.sql 의 SHAPE 와 같은 방식)
CREATE OR REPLACE FUNCTION pg_temp.tf_shape(p_text text)
RETURNS text LANGUAGE plpgsql STABLE AS $$
DECLARE v_out text := p_text; v_name text;
BEGIN
    FOR v_name IN SELECT DISTINCT m[1] FROM regexp_matches(p_text, '\{([a-z][a-z0-9_]*)\}', 'g') AS m LOOP
        v_out := replace(v_out, '{' || v_name || '}', '{' || coalesce((SELECT v.type FROM pg_temp.tf_variables v WHERE v.name = v_name), '?') || '}');
    END LOOP;
    RETURN v_out;
END $$;

-- 템플릿 자격 (선택 경로와 같은 함수 — 검증의 같은 옷 시험도 이 함수를 지난다)
--   종류·대상·방향이 맞고, 자리가 모두 값을 가진 변수(p_keys)이며,
--   렌더한 정수 %(p_r)가 0이면 사건 카드와 문장 틀이 같은 소음만 남긴다 (feed/README 약속 10).
--   EVENT 는 범주 비공개 여부·범주가 같은 템플릿. 후보 순서는 template_id 바이트 순서다.
CREATE OR REPLACE FUNCTION pg_temp.tf_template_pool(p_kind text, p_target text, p_dir text, p_r integer, p_keys text[],
                                                    p_hidden boolean DEFAULT false, p_category text DEFAULT NULL)
RETURNS TABLE(template_id varchar(40)) LANGUAGE sql STABLE AS $$
    SELECT t.template_id
    FROM pg_temp.tf_templates t
    WHERE t.status = 'ACTIVE' AND t.kind = p_kind AND t.target = p_target
      AND (CASE WHEN p_kind = 'EVENT'
                THEN t.category_hidden = p_hidden AND (p_hidden OR t.event_category = p_category)
                     AND t.event_type IS NULL AND t.outcome IS NULL
                ELSE t.direction IS NOT DISTINCT FROM p_dir END)
      AND coalesce((SELECT bool_and(m[1] = ANY (p_keys)) FROM regexp_matches(t.text, '\{([a-z][a-z0-9_]*)\}', 'g') AS m), true)
      AND (p_r IS NULL OR p_r <> 0
           OR pg_temp.tf_shape(t.text) IN (SELECT pg_temp.tf_shape(e.text) FROM pg_temp.tf_templates e WHERE e.kind = 'EVENT' AND e.status = 'ACTIVE'))
    ORDER BY t.template_id COLLATE "C"
$$;

CREATE OR REPLACE FUNCTION pg_temp.tf_input(p_season_id bigint, p_turn smallint)
RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_block bigint;
    v_seed text;
    v_events jsonb;
    v_assets jsonb;
    v_groups jsonb;
BEGIN
    IF p_turn IS NULL OR p_turn < 1 THEN RAISE EXCEPTION 'P0-18 피드: 현재 턴이 올바르지 않다' USING HINT = 'FEED_INPUT'; END IF;
    SELECT sp.month_block_id, s.seed::text INTO v_block, v_seed
    FROM season_path sp JOIN season s ON s.id = sp.season_id
    WHERE sp.season_id = p_season_id AND sp.turn_no = p_turn;
    IF NOT FOUND THEN RAISE EXCEPTION 'P0-18 피드: 현재 턴 경로가 없다' USING HINT = 'FEED_INPUT'; END IF;
    IF NOT EXISTS (SELECT 1 FROM month_block WHERE id = v_block AND events_reviewed) THEN
        RAISE EXCEPTION 'P0-18 피드: 사건 편집이 끝나지 않았다' USING HINT = 'FEED_EVENTS_UNREVIEWED';
    END IF;
    IF EXISTS (SELECT 1 FROM month_event WHERE month_block_id = v_block AND reaction_change IS NULL) THEN
        RAISE EXCEPTION 'P0-18 피드: 사건 반응값이 없다' USING HINT = 'FEED_REACTION_MISSING';
    END IF;
    -- 숨김 사건에는 범주를 넣지 않는다 (생성기는 category_hidden 만 안다).
    SELECT jsonb_agg(jsonb_build_object('event_id', e.id, 'event_key', e.event_key,
                                        'category', CASE WHEN e.hidden THEN NULL ELSE e.category END,
                                        'reaction', e.reaction_change, 'strength', e.strength, 'hidden', e.hidden)
                     ORDER BY e.event_key COLLATE "C")
    INTO v_events
    FROM (SELECT x.*, (SELECT count(DISTINCT left(y.event_key, 6)) FROM month_event y WHERE y.event_type = x.event_type) < 3 AS hidden
          FROM month_event x WHERE x.month_block_id = v_block) e;
    WITH asset_rows AS (
        SELECT a.id, a.name, g.id AS group_id, g.code AS group_code, g.name AS group_name, a.market, a.ticker,
               round(tp.price / NULLIF(prev.price, 0) - 1, 8) AS ret,
               CASE WHEN pp.turn_no IS NULL THEN NULL ELSE round(pp.price / NULLIF(pp0.price, 0) - 1, 8) END AS prev_ret,
               (SELECT count(*) FROM turn_price q
                JOIN turn_price qp ON qp.season_id=q.season_id AND qp.asset_id=q.asset_id AND qp.turn_no=q.turn_no-1
                WHERE q.season_id = p_season_id AND q.asset_id = a.id AND q.turn_no BETWEEN 1 AND p_turn
                  AND sign(q.price-qp.price)=sign(tp.price-prev.price)
                  AND q.turn_no > coalesce((SELECT max(b.turn_no) FROM turn_price b
                                           JOIN turn_price bp ON bp.season_id=b.season_id AND bp.asset_id=b.asset_id AND bp.turn_no=b.turn_no-1
                                           WHERE b.season_id=p_season_id AND b.asset_id=a.id AND b.turn_no BETWEEN 1 AND p_turn
                                             AND sign(b.price-bp.price)<>sign(tp.price-prev.price)),0)) AS streak,
               ama.attention_index,
               -- 순위는 관심 지수가 있는 현재 풀 종목끼리만 매긴다 (NULL 은 순위 없음, erd 9절). 동률은 같은 순위.
               CASE WHEN ama.attention_index IS NULL THEN NULL
                    ELSE rank() OVER (PARTITION BY (ama.attention_index IS NULL) ORDER BY ama.attention_index DESC) END AS attention_rank,
               count(ama.attention_index) OVER () AS attention_n,
               (SELECT old.attention_index FROM season_path old_path
                JOIN asset_month_attention old ON old.month_block_id = old_path.month_block_id AND old.asset_id = a.id
                WHERE old_path.season_id = p_season_id AND old_path.turn_no = p_turn - 1) AS prev_attention,
               (SELECT count(*) + 1 FROM season_path old_path
                JOIN asset_month_attention old ON old.month_block_id=old_path.month_block_id
                WHERE old_path.season_id=p_season_id AND old_path.turn_no=p_turn-1
                  AND old.asset_id IN (SELECT asset_id FROM turn_price WHERE season_id=p_season_id AND turn_no=p_turn)
                  AND old.attention_index IS NOT NULL
                  AND old.attention_index > (SELECT old2.attention_index FROM asset_month_attention old2
                                             WHERE old2.month_block_id=old_path.month_block_id AND old2.asset_id=a.id)) AS prev_attention_rank,
               -- 직전 시즌 턴에 같은 종목의 방향성 이야기(RISE·FALL·ECHO)가 있었는가, 있었다면 그 방향과 ECHO 반복 횟수
               (SELECT tf.direction FROM turn_feed tf WHERE tf.season_id=p_season_id AND tf.turn_no=p_turn-1
                  AND tf.asset_id=a.id AND tf.kind IN ('RISE','FALL','ECHO') AND tf.direction IS NOT NULL
                ORDER BY tf.seq DESC LIMIT 1) AS prev_story_direction,
               (SELECT coalesce(max(tf.repeat_count),0)+1 FROM turn_feed tf WHERE tf.season_id=p_season_id AND tf.turn_no=p_turn-1
                  AND tf.asset_id=a.id AND tf.kind='ECHO') AS prev_echo_repeat,
               (SELECT top_asset.name FROM asset top_asset
                JOIN asset_month_attention top_attention ON top_attention.asset_id = top_asset.id AND top_attention.month_block_id = v_block
                WHERE top_attention.attention_index IS NOT NULL
                  AND EXISTS (SELECT 1 FROM turn_price pool WHERE pool.season_id=p_season_id AND pool.turn_no=p_turn AND pool.asset_id=top_asset.id)
                ORDER BY top_attention.attention_index DESC, top_asset.market COLLATE "C", top_asset.ticker COLLATE "C" LIMIT 1) AS focus_name
        FROM turn_price tp
        JOIN turn_price prev ON prev.season_id = tp.season_id AND prev.asset_id = tp.asset_id AND prev.turn_no = p_turn - 1
        JOIN asset a ON a.id = tp.asset_id
        JOIN asset_group g ON g.id = a.asset_group_id
        LEFT JOIN asset_month_attention ama ON ama.asset_id = a.id AND ama.month_block_id = v_block
        LEFT JOIN turn_price pp ON pp.season_id = p_season_id AND pp.asset_id = a.id AND pp.turn_no = p_turn - 1
        LEFT JOIN turn_price pp0 ON pp0.season_id = p_season_id AND pp0.asset_id = a.id AND pp0.turn_no = p_turn - 2
        WHERE tp.season_id = p_season_id AND tp.turn_no = p_turn
    )
    SELECT jsonb_agg(jsonb_build_object('asset_id', id, 'asset_name', name,
                                        'group_id', group_id, 'group_code', group_code, 'group_name', group_name,
                                        'market', market, 'ticker', ticker, 'ret', ret,
                                        'prev_ret', prev_ret, 'streak', streak,
                                        'attention_index', attention_index, 'attention_rank', attention_rank, 'attention_n', attention_n,
                                        'prev_attention', prev_attention,
                                        'prev_attention_rank', CASE WHEN prev_attention IS NULL THEN NULL ELSE prev_attention_rank END,
                                        'prev_story_direction', prev_story_direction, 'prev_echo_repeat', prev_echo_repeat,
                                        'focus_name', focus_name)
                     ORDER BY market COLLATE "C", ticker COLLATE "C")
    INTO v_assets
    FROM asset_rows;
    SELECT jsonb_agg(jsonb_build_object('group_id', x.group_id, 'group_code', x.group_code, 'group_name', x.group_name,
                                        'group_ret', x.group_ret, 'attention_index', x.attention_index, 'prev_attention', x.prev_attention)
                     ORDER BY x.group_code COLLATE "C") INTO v_groups
    FROM (SELECT (z ->> 'group_id')::bigint group_id, z ->> 'group_code' group_code, max(z ->> 'group_name') group_name,
                 round(avg((z ->> 'ret')::numeric), 8) group_ret,
                 avg((z ->> 'attention_index')::numeric) attention_index,
                 avg((z ->> 'prev_attention')::numeric) prev_attention
          FROM jsonb_array_elements(coalesce(v_assets, '[]')) z GROUP BY z ->> 'group_id', z ->> 'group_code') x;
    RETURN jsonb_build_object('season_id', p_season_id, 'turn_no', p_turn, 'seed', v_seed,
                              'assets', coalesce(v_assets, '[]'::jsonb), 'groups', coalesce(v_groups, '[]'::jsonb),
                              'events', coalesce(v_events, '[]'::jsonb));
END $$;

CREATE OR REPLACE FUNCTION pg_temp.tf_generate(p_input jsonb)
RETURNS TABLE(seq smallint, kind varchar(6), asset_id bigint, asset_group_id bigint, month_event_id bigint,
              category_hidden boolean, direction varchar(4), strength smallint, repeat_count smallint,
              template_id varchar(40), template_vars jsonb, body varchar(300))
LANGUAGE plpgsql VOLATILE AS $$
#variable_conflict use_column
-- 위 지시어의 이유: 반환 열(kind·template_id 등)과 CTE 열의 이름이 같다. 이 함수의 SQL 문 안에서 이름은 모두 열을 뜻하고,
-- 반환 열(OUT 변수)은 마지막 반복의 대입문(seq := …)에서만 쓴다.
DECLARE
    v_n integer := 5 + (pg_temp.tf_pick_hash(p_input, 'count', '') % 11)::integer;
    v_event_count integer;
    v_noise_count integer;
    v_slot integer := 0;
    r jsonb;  -- FOR … IN SELECT x 의 단일 jsonb 값(레코드면 r ->> 가 안 된다)
    v_round integer;
    v_template text;
    v_text text;
    v_vars jsonb;
    v_key text;
    v_pool jsonb;
    v_candidates jsonb;
    v_rows jsonb := '[]'::jsonb;
BEGIN
    /* 후보는 함수 지역 JSON으로만 만든다. 호출 사이에 남는 임시 표를 만들지 않는다. */
    WITH assets AS (
        SELECT a,
               (a ->> 'ret')::numeric ret, (a ->> 'prev_ret')::numeric prev_ret, (a ->> 'streak')::integer streak,
               (a ->> 'attention_index')::numeric att, (a ->> 'prev_attention')::numeric patt,
               (a ->> 'prev_attention_rank')::integer prank, (a ->> 'attention_rank')::integer arank, (a ->> 'attention_n')::integer an,
               a ->> 'prev_story_direction' pstory, (a ->> 'prev_echo_repeat')::smallint prep, a ->> 'focus_name' focus_name,
               a ->> 'market' market, a ->> 'ticker' ticker
        FROM jsonb_array_elements(coalesce(p_input -> 'assets', '[]')) a
    ), groups AS (
        SELECT g, (g ->> 'group_ret')::numeric gret, (g ->> 'attention_index')::numeric att, (g ->> 'prev_attention')::numeric patt,
               CASE WHEN (g ->> 'attention_index') IS NULL THEN NULL
                    ELSE rank() OVER (PARTITION BY ((g ->> 'attention_index') IS NULL) ORDER BY (g ->> 'attention_index')::numeric DESC) END::integer arank,
               count(g ->> 'attention_index') OVER ()::integer an, g ->> 'group_code' code
        FROM jsonb_array_elements(coalesce(p_input -> 'groups', '[]')) g
    ), raw AS (
        -- 사건: 현재 블록의 편집 완료 태그 전부
        SELECT 'EVENT'::text kind, NULL::bigint asset_id, NULL::bigint asset_group_id, (e ->> 'event_id')::bigint month_event_id,
               (e ->> 'hidden')::boolean hidden, NULL::text direction, (e ->> 'strength')::smallint strength, 0::smallint repeat_count,
               'event:' || (e ->> 'event_key') ckey, 'NONE'::text target, NULL::integer r,
               jsonb_build_object('reaction', e -> 'reaction') vars, e ->> 'category' category
        FROM jsonb_array_elements(coalesce(p_input -> 'events', '[]')) e
        UNION ALL
        -- 종목 RISE·FALL
        SELECT CASE WHEN ret > 0 THEN 'RISE' ELSE 'FALL' END, (a ->> 'asset_id')::bigint, NULL, NULL, false,
               CASE WHEN ret > 0 THEN 'UP' ELSE 'DOWN' END, pg_temp.tf_grade(arank, an), 0, 'asset:' || market || ':' || ticker, 'ASSET',
               round(ret * 100, 0)::integer,
               jsonb_strip_nulls(jsonb_build_object('asset_name', a -> 'asset_name', 'group_name', a -> 'group_name', 'ret', a -> 'ret',
                                                    'prev_ret', a -> 'prev_ret', 'streak', CASE WHEN streak >= 2 THEN a -> 'streak' END,
                                                    'attention_rank', a -> 'attention_rank')), NULL
        FROM assets WHERE ret <> 0
        UNION ALL
        -- 그룹 RISE·FALL
        SELECT CASE WHEN gret > 0 THEN 'RISE' ELSE 'FALL' END, NULL, (g ->> 'group_id')::bigint, NULL, false,
               CASE WHEN gret > 0 THEN 'UP' ELSE 'DOWN' END, pg_temp.tf_grade(arank, an), 0, 'group:' || code, 'GROUP',
               round(gret * 100, 0)::integer,
               jsonb_build_object('group_name', g -> 'group_name', 'group_ret', g -> 'group_ret'), NULL
        FROM groups WHERE gret <> 0
        UNION ALL
        -- SPREAD: 그룹 관심 지수가 직전 시즌 턴보다 올랐다 (비교 대상이 없으면 자격 없음)
        SELECT 'SPREAD', NULL, (g ->> 'group_id')::bigint, NULL, false,
               CASE WHEN gret > 0 THEN 'UP' ELSE 'DOWN' END, pg_temp.tf_grade(arank, an), 0, 'spread:' || code, 'GROUP',
               round(gret * 100, 0)::integer,
               jsonb_build_object('group_name', g -> 'group_name', 'group_ret', g -> 'group_ret'), NULL
        FROM groups WHERE gret <> 0 AND att IS NOT NULL AND patt IS NOT NULL AND att > patt
        UNION ALL
        -- ECHO: 직전 시즌 턴에 같은 종목의 같은 방향 이야기가 있었고 지금도 같은 방향이며 관심이 꺾이지 않았다
        SELECT 'ECHO', (a ->> 'asset_id')::bigint, NULL, NULL, false,
               CASE WHEN ret > 0 THEN 'UP' ELSE 'DOWN' END, pg_temp.tf_grade(arank, an), coalesce(prep, 1)::smallint, 'echo:' || market || ':' || ticker, 'ASSET',
               round(ret * 100, 0)::integer,
               jsonb_strip_nulls(jsonb_build_object('asset_name', a -> 'asset_name', 'group_name', a -> 'group_name', 'ret', a -> 'ret',
                                                    'prev_ret', a -> 'prev_ret', 'streak', CASE WHEN streak >= 2 THEN a -> 'streak' END,
                                                    'repeat', to_jsonb(coalesce(prep, 1)))), NULL
        FROM assets
        WHERE ret <> 0 AND prev_ret IS NOT NULL AND sign(prev_ret) = sign(ret)
          AND pstory = CASE WHEN ret > 0 THEN 'UP' ELSE 'DOWN' END
          AND NOT (att IS NOT NULL AND patt IS NOT NULL AND att < patt)
        UNION ALL
        -- FADE: 직전 시즌 턴에 관심 상위(5위 이내)였던 종목의 관심이 내려갔다. focus_name 은 현재 턴 관심 1위다.
        SELECT 'FADE', (a ->> 'asset_id')::bigint, NULL, NULL, false,
               NULL, pg_temp.tf_grade(arank, an), 0, 'fade:' || market || ':' || ticker, 'ASSET', NULL,
               jsonb_strip_nulls(jsonb_build_object('asset_name', a -> 'asset_name', 'focus_name', a -> 'focus_name',
                                                    'attention_rank', a -> 'attention_rank')), NULL
        FROM assets
        WHERE att IS NOT NULL AND patt IS NOT NULL AND att < patt AND prank <= 5
          AND focus_name IS NOT NULL AND focus_name <> (a ->> 'asset_name')
    )
    SELECT jsonb_agg(jsonb_build_object('kind', c.kind, 'asset_id', c.asset_id, 'asset_group_id', c.asset_group_id,
                                        'month_event_id', c.month_event_id, 'hidden', c.hidden, 'direction', c.direction,
                                        'strength', c.strength, 'weight', greatest(1, coalesce(c.strength, 1)),
                                        'repeat_count', c.repeat_count, 'ckey', c.ckey, 'vars', c.vars, 'pool', c.pool))
    INTO v_candidates
    FROM (SELECT raw.*,
                 (SELECT jsonb_agg(p.template_id ORDER BY p.template_id COLLATE "C")
                  FROM pg_temp.tf_template_pool(raw.kind, raw.target, raw.direction, raw.r, ARRAY(SELECT jsonb_object_keys(raw.vars)),
                                                coalesce(raw.hidden, false), raw.category) p) AS pool
          FROM raw) c
    WHERE c.kind = 'EVENT' OR c.pool IS NOT NULL;  -- 쓸 수 있는 템플릿이 없는 소음 후보는 후보가 아니다
    SELECT count(*) FILTER (WHERE x ->> 'kind' = 'EVENT'), count(*) FILTER (WHERE x ->> 'kind' <> 'EVENT')
      INTO v_event_count, v_noise_count FROM jsonb_array_elements(coalesce(v_candidates, '[]')) x;
    IF v_event_count + v_noise_count = 0 THEN RETURN; END IF;
    -- 사건: 현재 블록 사건을 모두 넣는다. 템플릿은 풀 안에서 시드로 고른다.
    FOR r IN SELECT x FROM jsonb_array_elements(v_candidates) x WHERE x ->> 'kind' = 'EVENT' ORDER BY x ->> 'ckey' COLLATE "C" LOOP
        v_slot := v_slot + 1;
        v_pool := r -> 'pool';
        IF v_pool IS NULL THEN RAISE EXCEPTION 'P0-18 피드: 사건 카드 템플릿이 없다' USING HINT = 'FEED_RENDER'; END IF;
        v_key := (r ->> 'ckey') || ':1';
        v_template := v_pool ->> (pg_temp.tf_pick_hash(p_input, 'tpl', v_key) % jsonb_array_length(v_pool))::integer;
        v_text := (SELECT t.text FROM tf_templates t WHERE t.template_id = v_template AND t.status = 'ACTIVE');
        v_vars := (SELECT jsonb_object_agg(m[1], (r -> 'vars') -> m[1]) FROM regexp_matches(v_text, '\{([a-z][a-z0-9_]*)\}', 'g') AS m);
        v_rows := v_rows || jsonb_build_array(jsonb_build_object(
            'sort_key', pg_temp.tf_pick_hash(p_input, 'order', v_key), 'kind', r ->> 'kind', 'ckey', r ->> 'ckey', 'round', 1,
            'asset_id', NULL, 'asset_group_id', NULL, 'month_event_id', r ->> 'month_event_id', 'category_hidden', r ->> 'hidden',
            'direction', NULL, 'strength', r ->> 'strength', 'repeat_count', 0,
            'template_id', v_template, 'template_vars', v_vars, 'body', pg_temp.tf_render(v_text, v_vars)));
    END LOOP;
    -- 소음: 후보를 한 바퀴(round) 다 쓴 뒤에만 두 번째 바퀴로 간다. 한 바퀴 안의 순서는 가중 비복원 추출이다 —
    -- 후보마다 가중치 w 장의 표를 만들고 가장 작은 표 해시가 그 후보의 키다 (정수와 해시만 쓴다).
    FOR r, v_round IN
        SELECT c.x, c.g
        FROM (SELECT x, g,
                     (SELECT min(pg_temp.tf_pick_hash(p_input, 'pick', (x ->> 'ckey') || ':' || g || ':' || i))
                      FROM generate_series(1, (x ->> 'weight')::integer) i) AS k
              FROM jsonb_array_elements(v_candidates) x CROSS JOIN generate_series(1, v_n) g
              WHERE x ->> 'kind' <> 'EVENT') c
        ORDER BY c.g, c.k, c.x ->> 'ckey' COLLATE "C"
        LIMIT greatest(0, v_n - v_event_count)
    LOOP
        v_slot := v_slot + 1;
        v_pool := r -> 'pool';
        v_key := (r ->> 'ckey') || ':' || v_round;
        v_template := v_pool ->> (pg_temp.tf_pick_hash(p_input, 'tpl', v_key) % jsonb_array_length(v_pool))::integer;
        v_text := (SELECT t.text FROM tf_templates t WHERE t.template_id = v_template AND t.status = 'ACTIVE');
        v_vars := (SELECT jsonb_object_agg(m[1], (r -> 'vars') -> m[1]) FROM regexp_matches(v_text, '\{([a-z][a-z0-9_]*)\}', 'g') AS m);
        v_rows := v_rows || jsonb_build_array(jsonb_build_object(
            'sort_key', pg_temp.tf_pick_hash(p_input, 'order', v_key), 'kind', r ->> 'kind', 'ckey', r ->> 'ckey', 'round', v_round,
            'asset_id', r ->> 'asset_id', 'asset_group_id', r ->> 'asset_group_id', 'month_event_id', NULL, 'category_hidden', false,
            'direction', r ->> 'direction', 'strength', r ->> 'strength', 'repeat_count', coalesce((r ->> 'repeat_count')::smallint, 0),
            'template_id', v_template, 'template_vars', v_vars, 'body', pg_temp.tf_render(v_text, v_vars)));
    END LOOP;
    v_slot := 0;
    FOR r IN SELECT x FROM jsonb_array_elements(v_rows) x
             ORDER BY (x ->> 'sort_key')::bigint, x ->> 'kind', (x ->> 'ckey') COLLATE "C", (x ->> 'round')::integer LOOP
        v_slot := v_slot + 1;
        seq := v_slot; kind := r ->> 'kind'; asset_id := (r ->> 'asset_id')::bigint; asset_group_id := (r ->> 'asset_group_id')::bigint;
        month_event_id := (r ->> 'month_event_id')::bigint; category_hidden := (r ->> 'category_hidden')::boolean;
        direction := r ->> 'direction'; strength := (r ->> 'strength')::smallint; repeat_count := (r ->> 'repeat_count')::smallint;
        template_id := r ->> 'template_id'; template_vars := r -> 'template_vars'; body := r ->> 'body'; RETURN NEXT;
    END LOOP;
    IF v_slot > 15 THEN RAISE EXCEPTION 'P0-18 피드: 행 수가 15를 넘었다' USING HINT = 'FEED_RENDER'; END IF;
END $$;

CREATE OR REPLACE FUNCTION pg_temp.tf_append(p_season_id bigint, p_turn smallint)
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE v_input jsonb; v_count integer;
BEGIN
    IF EXISTS (SELECT 1 FROM turn_feed WHERE season_id = p_season_id AND turn_no = p_turn) THEN
        RAISE EXCEPTION 'P0-18 피드: 이미 저장된 턴이다' USING HINT = 'FEED_ALREADY_STORED';
    END IF;
    v_input := pg_temp.tf_input(p_season_id, p_turn);
    INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, asset_group_id, month_event_id, category_hidden,
                           direction, strength, repeat_count, template_id, template_vars, body)
    SELECT p_season_id, p_turn, g.seq, g.kind, g.asset_id, g.asset_group_id, g.month_event_id, g.category_hidden,
           g.direction, g.strength, g.repeat_count, g.template_id, g.template_vars, g.body
    FROM pg_temp.tf_generate(v_input) g ORDER BY g.seq;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    IF v_count > 15 THEN RAISE EXCEPTION 'P0-18 피드: 행 수가 15를 넘었다' USING HINT = 'FEED_RENDER'; END IF;
    RETURN v_count;
END $$;

-- 저장 = 재생성: 그 턴에 저장된 피드가 입력 조립부·생성기를 다시 돌린 결과와 같은가 (읽기 전용; 턴이 0이면 NULL)
CREATE OR REPLACE FUNCTION pg_temp.tf_regen_matches(p_season_id bigint, p_turn smallint)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN p_turn < 1 THEN NULL ELSE
        (SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY s.seq), '[]'::jsonb)
         FROM (SELECT f.seq, f.kind, f.asset_id, f.asset_group_id, f.month_event_id, f.category_hidden, f.direction, f.strength,
                      f.repeat_count, f.template_id, f.template_vars, f.body
               FROM turn_feed f WHERE f.season_id = p_season_id AND f.turn_no = p_turn) s)
        IS NOT DISTINCT FROM
        (SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY g.seq), '[]'::jsonb) FROM pg_temp.tf_generate(pg_temp.tf_input(p_season_id, p_turn)) g)
    END
$$;
