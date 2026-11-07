-- =====================================================================
-- my Fund Manager Phase 0 — 사건 태그 적재 (P0-09): events/*.csv 3개 → month_event, month_block.events_reviewed
-- 근거: docs/erd.md 5.3(month_event · events_reviewed), 5.5(H5·H6), 9절 P0-09, events/README.md(형식·규칙), docs/SPRING_JPA.md 7절
-- 대상: db/01_schema_history.sql 과 db/10_load_sample.sql 을 실행한 DB (PostgreSQL 17, DB timezone Asia/Seoul)
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/11_load_events.sql
--       \copy 의 파일 경로가 psql 을 실행한 디렉터리 기준이라 반드시 저장소 루트에서 실행한다.
-- 멱등: 같은 events/ 파일로 다시 실행하면 새 행이 없고 갱신된 달도 없다. 기존 행(id, created_at 포함)은 그대로다.
--       이미 있는 태그의 값이 파일(또는 계산값)과 다르면 조용히 두지 않고 오류로 멈춘다 (값 정정은 하지 않는다). 전부 되돌아간다.
--       DB 에만 있는 태그(파일에서 지워진 키)도, DB 에서 TRUE 인데 reviewed.csv 에 없는 달도 멈춘다.
-- 쓰는 곳: month_event(INSERT)와 month_block.events_reviewed(FALSE → TRUE 만). 그 밖의 열(event_summary 포함)과 다른 테이블은 읽기만 한다.
--       TRUE 를 FALSE 로 되돌리지 않는다. 아무것도 지우지 않는다. 01 스키마 파일을 다시 실행하지 않는다 (데이터가 지워진다).
-- 계산: reaction_change = reaction_ref 가 가리키는 풀 종목(또는 환율)의 그 달 값을 반올림 없이 복사,
--       strength = |reaction_change| 가 0.05 미만이면 1, 0.10 미만이면 2, 그 이상이면 3 (events/README.md "강도 경계").
--       파일에는 시세에서 나온 숫자가 없다. 값은 10 적재가 넣은 DB 의 행에서 온다.
-- 입력 거부: 파일 없음·빈 파일(머리글만), 빈 문자열(""), 코드 칸의 공백, 제목·사후 평가·유형 이름의 앞뒤 공백, 형식 위반, 정렬 어긋남, 키 중복, types.csv 규칙(유형·범주·결과·방향·참조) 위반,
--       month_block 에 없는 달, reviewed.csv 와 tags.csv 의 어긋남(NONE 인데 태그, TAGGED 인데 태그 없음, 태그 달이 표에 없음),
--       에피소드 규칙, 달당 태그 3개 초과, 노출 코드의 숫자·두 글자 토큰(US·KR 제외)은 DB 에 닿기 전에 모두 거부한다.
--       고치지 않고 거부한다 (조용한 수정보다 실패가 낫다).
-- 구조: BEGIN → 이름 해석·전제 확인 → TEMP 스테이징 3개(모든 열 text) → \copy → 입력 검사 → 계산
--       → INSERT … SELECT(자연 키 조인) → 차이 검사 → events_reviewed UPDATE → 사후 단언 → 보고 → COMMIT
-- 새 문법: \copy 의 열 목록 + HEADER MATCH, GENERATED ALWAYS AS IDENTITY 로 파일 줄 순서(ord) 보존, lag() 로 정렬 검사,
--       WITH u AS (UPDATE … RETURNING 1) 로 갱신 행 수 세기, string_to_array·unnest 로 ';' 목록 펼치기,
--       regexp_split_to_table 로 코드 토큰 검사, LATERAL 조인으로 참조 값 찾기.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- [파일 확인] events/ 의 CSV 3개가 없으면 DB 에 닿기 전에 분명한 메시지로 멈춘다 (10 적재와 같은 방식).
\! test -f events/types.csv -a -f events/tags.csv -a -f events/reviewed.csv
\if :SHELL_ERROR
  \echo 'events/ 의 types.csv, tags.csv, reviewed.csv 중 없는 것이 있다 — 저장소 루트에서 실행한다'
  DO $$ BEGIN RAISE EXCEPTION 'P0-09 적재: events/ 파일이 없다. 저장소 루트에서 실행한다'; END $$;
\endif

BEGIN;

-- [이름 해석 확인] 5개 이름이 public 으로 해석될 때만 계속한다 (01 스키마 파일의 블록과 같은 방식)
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['month_block', 'month_event', 'asset', 'asset_month_return', 'fx_month']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-09 적재: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
    -- 전제: 10 적재가 끝나 있어야 한다 (반응 변화율의 원천이 asset_month_return·fx_month 이다)
    IF NOT EXISTS (SELECT 1 FROM month_block) OR NOT EXISTS (SELECT 1 FROM asset_month_return) OR NOT EXISTS (SELECT 1 FROM fx_month) THEN
        RAISE EXCEPTION 'P0-09 적재: month_block, asset_month_return, fx_month 가 비어 있다. db/10_load_sample.sql 을 먼저 실행한다';
    END IF;
END $$;

-- 스테이징 (CSV 머리글과 같은 이름·순서. 모든 열 text — 형식은 아래 입력 검사가 판정한다)
-- ord 는 파일 줄 순서다 (GENERATED ALWAYS AS IDENTITY). \copy 에 열 목록을 주어 ord 를 건너뛴다.
CREATE TEMP TABLE stg_ev_types (ord integer GENERATED ALWAYS AS IDENTITY, event_type text, category text, name_ko text,
                                outcomes text, direction_rule text, reaction_refs text) ON COMMIT DROP;
CREATE TEMP TABLE stg_ev_tags (ord integer GENERATED ALWAYS AS IDENTITY, event_key text, month_start text, category text,
                               event_type text, phase text, outcome text, direction text, reaction_ref text, title text,
                               hindsight text, source text, checked_on text) ON COMMIT DROP;
CREATE TEMP TABLE stg_ev_reviewed (ord integer GENERATED ALWAYS AS IDENTITY, month_start text, result text) ON COMMIT DROP;

\copy stg_ev_types (event_type, category, name_ko, outcomes, direction_rule, reaction_refs) FROM 'events/types.csv' WITH (FORMAT csv, HEADER MATCH)
\copy stg_ev_tags (event_key, month_start, category, event_type, phase, outcome, direction, reaction_ref, title, hindsight, source, checked_on) FROM 'events/tags.csv' WITH (FORMAT csv, HEADER MATCH)
\copy stg_ev_reviewed (month_start, result) FROM 'events/reviewed.csv' WITH (FORMAT csv, HEADER MATCH)

-- [입력 검사 1] 빈 문자열·빈 칸·공백·형식. 하나라도 있으면 전부 되돌린다 --------------------------------------
CREATE TEMP TABLE ld_err (msg text) ON COMMIT DROP;

-- 머리글만 있는 빈 파일 (\copy 가 0행을 읽으면 새 DB 에서는 0·0 으로 COMMIT 하므로 막는다)
INSERT INTO ld_err
SELECT '파일이 비어 있다(머리글만): ' || t FROM unnest(ARRAY['types', 'tags', 'reviewed']) t
WHERE (t = 'types' AND NOT EXISTS (SELECT 1 FROM stg_ev_types))
   OR (t = 'tags' AND NOT EXISTS (SELECT 1 FROM stg_ev_tags))
   OR (t = 'reviewed' AND NOT EXISTS (SELECT 1 FROM stg_ev_reviewed));

-- 빈 문자열 (CSV 의 따옴표 친 "" 만 여기 걸린다. 따옴표 없는 빈 칸은 NULL)
INSERT INTO ld_err
SELECT 'types 빈 문자열: ' || row_to_json(t)::text FROM stg_ev_types t
    WHERE t.event_type = '' OR t.category = '' OR t.name_ko = '' OR t.outcomes = '' OR t.direction_rule = '' OR t.reaction_refs = ''
UNION ALL SELECT 'tags 빈 문자열: ' || row_to_json(t)::text FROM stg_ev_tags t
    WHERE t.event_key = '' OR t.month_start = '' OR t.category = '' OR t.event_type = '' OR t.phase = '' OR t.outcome = ''
       OR t.direction = '' OR t.reaction_ref = '' OR t.title = '' OR t.hindsight = '' OR t.source = '' OR t.checked_on = ''
UNION ALL SELECT 'reviewed 빈 문자열: ' || row_to_json(t)::text FROM stg_ev_reviewed t WHERE t.month_start = '' OR t.result = '';

-- NOT NULL 열의 빈 칸(NULL). 허용되는 빈 칸: types.outcomes, tags.outcome·direction·hindsight
INSERT INTO ld_err
SELECT 'types 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_ev_types t
    WHERE num_nulls(t.event_type, t.category, t.name_ko, t.direction_rule, t.reaction_refs) > 0
UNION ALL SELECT 'tags 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_ev_tags t
    WHERE num_nulls(t.event_key, t.month_start, t.category, t.event_type, t.phase, t.reaction_ref, t.title, t.source, t.checked_on) > 0
UNION ALL SELECT 'reviewed 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_ev_reviewed t WHERE num_nulls(t.month_start, t.result) > 0;

-- 코드 칸의 앞뒤·안쪽 공백 (고치지 않고 거부)
INSERT INTO ld_err
SELECT 'types 코드 칸 공백: ' || row_to_json(t)::text FROM stg_ev_types t
    WHERE t.event_type ~ '\s' OR t.category ~ '\s' OR t.outcomes ~ '\s' OR t.direction_rule ~ '\s' OR t.reaction_refs ~ '\s'
UNION ALL SELECT 'tags 코드 칸 공백: ' || row_to_json(t)::text FROM stg_ev_tags t
    WHERE t.event_key ~ '\s' OR t.month_start ~ '\s' OR t.category ~ '\s' OR t.event_type ~ '\s' OR t.phase ~ '\s'
       OR t.outcome ~ '\s' OR t.direction ~ '\s' OR t.reaction_ref ~ '\s'
UNION ALL SELECT 'reviewed 코드 칸 공백: ' || row_to_json(t)::text FROM stg_ev_reviewed t WHERE t.month_start ~ '\s' OR t.result ~ '\s';

-- 형식 (events/README.md "열 계약")
-- 따옴표류(" “ ” ' ‘ ’ 「 」)는 제목·사후 평가에 들어올 수 없다 (인용문을 옮기지 않는다는 규칙의 기계 검사)
INSERT INTO ld_err
SELECT 'types 형식: ' || row_to_json(t)::text FROM stg_ev_types t
    WHERE t.event_type !~ '^[A-Z][A-Z_]*$' OR length(t.event_type) > 30
       OR t.category NOT IN ('GEOPOLITICS', 'TRADE', 'POLITICS', 'SUPPLY_SHOCK', 'FINANCIAL', 'POLICY', 'HEALTH')
       OR (t.outcomes IS NOT NULL AND t.outcomes !~ '^[A-Z][A-Z_]*(;[A-Z][A-Z_]*)*$')
       OR t.direction_rule NOT IN ('NONE', 'UP_DOWN')
       OR t.reaction_refs !~ '^(KR:[0-9]{6}|US:[A-Z]+|FX:USD)(;(KR:[0-9]{6}|US:[A-Z]+|FX:USD))*$'
       OR t.name_ko ~ '^\s|\s$'
UNION ALL SELECT 'tags 형식: ' || row_to_json(t)::text FROM stg_ev_tags t
    WHERE t.event_key !~ '^ev[0-9]{4}\.[0-9]{2}$'
       OR t.month_start !~ '^\d{4}-(0[1-9]|1[0-2])-01$'
       OR t.category NOT IN ('GEOPOLITICS', 'TRADE', 'POLITICS', 'SUPPLY_SHOCK', 'FINANCIAL', 'POLICY', 'HEALTH')
       OR t.event_type !~ '^[A-Z][A-Z_]*$' OR length(t.event_type) > 30
       OR t.phase NOT IN ('START', 'ONGOING', 'END')
       OR (t.outcome IS NOT NULL AND (t.outcome !~ '^[A-Z][A-Z_]*$' OR length(t.outcome) > 20))
       OR (t.direction IS NOT NULL AND t.direction NOT IN ('UP', 'DOWN'))
       OR t.reaction_ref !~ '^(KR:[0-9]{6}|US:[A-Z]+|FX:USD)$'
       OR length(t.title) NOT BETWEEN 1 AND 100 OR t.title ~ $q$["“”'‘’「」]$q$ OR t.title ~ '^\s|\s$'
       OR (t.hindsight IS NOT NULL AND t.hindsight ~ '^\s|\s$')
       OR (t.hindsight IS NOT NULL AND (length(t.hindsight) NOT BETWEEN 1 AND 300 OR t.hindsight ~ $q$["“”'‘’「」]$q$))
       OR length(t.source) > 500 OR t.source !~ '^https://\S+( ; https://\S+){0,2}$' OR t.source ~* 'utm_'
       OR t.checked_on !~ '^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$'
UNION ALL SELECT 'reviewed 형식: ' || row_to_json(t)::text FROM stg_ev_reviewed t
    WHERE t.month_start !~ '^\d{4}-(0[1-9]|1[0-2])-01$' OR t.result NOT IN ('TAGGED', 'NONE');

-- 노출 코드의 H5 (적재 쪽 방어): 숫자 없음, 두 글자 토큰은 US·KR 만. 금지 토큰 목록은 11_load_events_check.sql 이 본다
INSERT INTO ld_err
SELECT 'types H5(숫자·두 글자 토큰): ' || x.code
FROM (SELECT event_type AS code FROM stg_ev_types
      UNION ALL SELECT unnest(string_to_array(outcomes, ';')) FROM stg_ev_types WHERE outcomes IS NOT NULL) x
WHERE x.code ~ '[0-9]' OR EXISTS (SELECT 1 FROM regexp_split_to_table(x.code, '_') AS tok WHERE length(tok) <= 2 AND tok NOT IN ('US', 'KR'))
UNION ALL
SELECT 'tags H5(숫자·두 글자 토큰): ' || x.code
FROM (SELECT event_type AS code FROM stg_ev_tags UNION ALL SELECT outcome FROM stg_ev_tags WHERE outcome IS NOT NULL) x
WHERE x.code ~ '[0-9]' OR EXISTS (SELECT 1 FROM regexp_split_to_table(x.code, '_') AS tok WHERE length(tok) <= 2 AND tok NOT IN ('US', 'KR'));

-- 형식을 통과하지 못한 값은 날짜·숫자 변환에서 오류가 나므로, 여기서 먼저 멈춘다
DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-09 적재: 입력 검사 실패 (처음 % 건)\n%', v_n, v_msg;
    END IF;
END $$;

-- [입력 검사 2] 정렬·키 중복·참조·에피소드 ---------------------------------------------------------------
-- 정렬: 바이트 순서(COLLATE "C"). 같은 키가 이어지면 "<=" 로 함께 걸리므로 중복도 여기서 보고된다
INSERT INTO ld_err
SELECT 'types 정렬·중복(category, event_type 오름차순): ' || event_type
FROM (SELECT event_type, category, lag(category) OVER (ORDER BY ord) AS pc, lag(event_type) OVER (ORDER BY ord) AS pt FROM stg_ev_types) s
WHERE pc IS NOT NULL AND (category COLLATE "C", event_type COLLATE "C") <= (pc COLLATE "C", pt COLLATE "C")
UNION ALL
SELECT 'tags 정렬·중복(event_key 오름차순): ' || event_key
FROM (SELECT event_key, lag(event_key) OVER (ORDER BY ord) AS pk FROM stg_ev_tags) s
WHERE pk IS NOT NULL AND event_key COLLATE "C" <= pk COLLATE "C"
UNION ALL
SELECT 'reviewed 정렬·중복(month_start 오름차순): ' || month_start
FROM (SELECT month_start, lag(month_start) OVER (ORDER BY ord) AS pm FROM stg_ev_reviewed) s
WHERE pm IS NOT NULL AND month_start <= pm;

-- 유형 중복은 정렬 검사가 이미 보고하지만, 정렬이 틀린 파일에서도 중복을 따로 알린다
INSERT INTO ld_err
SELECT 'types event_type 중복: ' || event_type FROM stg_ev_types GROUP BY event_type HAVING count(*) > 1
UNION ALL SELECT 'tags event_key 중복: ' || event_key FROM stg_ev_tags GROUP BY event_key HAVING count(*) > 1
UNION ALL SELECT 'reviewed month_start 중복: ' || month_start FROM stg_ev_reviewed GROUP BY month_start HAVING count(*) > 1;

-- tags 가 types.csv 규칙을 따르는가 (H6: 유형·결과가 목록 안, 범주 일치, 방향 규칙, 반응 참조 목록)
INSERT INTO ld_err
SELECT 'tags 의 유형이 types.csv 에 없다: ' || t.event_key || ' ' || t.event_type FROM stg_ev_tags t
    WHERE NOT EXISTS (SELECT 1 FROM stg_ev_types y WHERE y.event_type = t.event_type)
UNION ALL SELECT 'tags 범주가 유형의 범주와 다르다: ' || t.event_key || ' ' || t.category || ' ≠ ' || y.category
    FROM stg_ev_tags t JOIN stg_ev_types y ON y.event_type = t.event_type WHERE t.category <> y.category
UNION ALL SELECT 'tags 결과가 유형의 허용 목록 밖이다: ' || t.event_key || ' ' || t.outcome
    FROM stg_ev_tags t JOIN stg_ev_types y ON y.event_type = t.event_type
    WHERE t.outcome IS NOT NULL AND NOT (t.outcome = ANY (COALESCE(string_to_array(y.outcomes, ';'), ARRAY[]::text[])))
UNION ALL SELECT 'tags 방향이 유형 규칙과 다르다(UP_DOWN 이면 필수, NONE 이면 빈 칸): ' || t.event_key
    FROM stg_ev_tags t JOIN stg_ev_types y ON y.event_type = t.event_type
    WHERE (y.direction_rule = 'UP_DOWN' AND t.direction IS NULL) OR (y.direction_rule = 'NONE' AND t.direction IS NOT NULL)
UNION ALL SELECT 'tags 반응 참조가 유형의 허용 목록 밖이다: ' || t.event_key || ' ' || t.reaction_ref
    FROM stg_ev_tags t JOIN stg_ev_types y ON y.event_type = t.event_type
    WHERE NOT (t.reaction_ref = ANY (string_to_array(y.reaction_refs, ';')));

-- 날짜·종목 참조: month_block 에 없는 달, 풀에 없는 종목
INSERT INTO ld_err
SELECT 'tags 의 달이 month_block 에 없다: ' || t.event_key || ' ' || t.month_start FROM stg_ev_tags t
    WHERE NOT EXISTS (SELECT 1 FROM month_block m WHERE m.month_start = t.month_start::date)
UNION ALL SELECT 'reviewed 의 달이 month_block 에 없다: ' || r.month_start FROM stg_ev_reviewed r
    WHERE NOT EXISTS (SELECT 1 FROM month_block m WHERE m.month_start = r.month_start::date)
UNION ALL SELECT 'types.reaction_refs 의 종목이 asset 에 없다: ' || y.event_type || ' ' || ref
    FROM stg_ev_types y CROSS JOIN LATERAL unnest(string_to_array(y.reaction_refs, ';')) AS ref
    WHERE split_part(ref, ':', 1) IN ('KR', 'US')
      AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.market = split_part(ref, ':', 1) AND a.ticker = split_part(ref, ':', 2))
UNION ALL SELECT 'tags 의 반응 참조가 이 달의 값을 갖지 못한다: ' || t.event_key || ' ' || t.reaction_ref
    FROM stg_ev_tags t JOIN month_block m ON m.month_start = t.month_start::date
    WHERE CASE WHEN split_part(t.reaction_ref, ':', 1) = 'FX'
               THEN NOT EXISTS (SELECT 1 FROM fx_month f WHERE f.month_block_id = m.id AND f.currency = split_part(t.reaction_ref, ':', 2))
               ELSE NOT EXISTS (SELECT 1 FROM asset_month_return r JOIN asset a ON a.id = r.asset_id
                                WHERE r.month_block_id = m.id AND a.market = split_part(t.reaction_ref, ':', 1) AND a.ticker = split_part(t.reaction_ref, ':', 2))
          END;

-- 편집 완료 표와 태그의 교차 확인 (사건 없음 / 아직 안 봄 / 태그 있음)
INSERT INTO ld_err
SELECT 'tags 의 달이 reviewed.csv 에 없다(태그는 편집 완료 달에만): ' || t.event_key || ' ' || t.month_start FROM stg_ev_tags t
    WHERE NOT EXISTS (SELECT 1 FROM stg_ev_reviewed r WHERE r.month_start = t.month_start)
UNION ALL SELECT 'NONE 인 달에 태그가 있다: ' || r.month_start FROM stg_ev_reviewed r
    WHERE r.result = 'NONE' AND EXISTS (SELECT 1 FROM stg_ev_tags t WHERE t.month_start = r.month_start)
UNION ALL SELECT 'TAGGED 인 달에 태그가 없다: ' || r.month_start FROM stg_ev_reviewed r
    WHERE r.result = 'TAGGED' AND NOT EXISTS (SELECT 1 FROM stg_ev_tags t WHERE t.month_start = r.month_start);

-- 달당 태그 3개 이하 (1차 편집 규칙 3 — 카드 수가 위기 달의 표식이 되지 않게)
INSERT INTO ld_err
SELECT 'tags 한 달에 3개를 넘는다: ' || month_start || ' ' || count(*) FROM stg_ev_tags GROUP BY month_start HAVING count(*) > 3;

-- 에피소드 규칙 (event_key 앞 6글자 = 에피소드, 뒤 2자리 = 그 안의 달 순번)
CREATE TEMP TABLE ld_ep ON COMMIT DROP AS
SELECT t.event_key, left(t.event_key, 6) AS ep, substr(t.event_key, 8)::integer AS seq, t.month_start, t.category, t.event_type, t.phase,
       row_number() OVER w AS rn, count(*) OVER (PARTITION BY left(t.event_key, 6)) AS ep_n,
       lag(t.month_start) OVER w AS prev_month
FROM stg_ev_tags t
WINDOW w AS (PARTITION BY left(t.event_key, 6) ORDER BY t.event_key COLLATE "C");

INSERT INTO ld_err
SELECT 'EPISODE 범주·유형이 섞였다: ' || ep FROM ld_ep GROUP BY ep HAVING count(DISTINCT event_type) > 1 OR count(DISTINCT category) > 1
UNION ALL SELECT 'EPISODE 달 순번이 01 부터 빈틈없이 늘지 않는다: ' || event_key FROM ld_ep WHERE seq <> rn
UNION ALL SELECT 'EPISODE 달이 엄격히 늘지 않는다: ' || event_key FROM ld_ep WHERE prev_month IS NOT NULL AND month_start <= prev_month
UNION ALL SELECT 'EPISODE START 는 순번 01 이어야 한다: ' || event_key FROM ld_ep WHERE phase = 'START' AND seq <> 1
UNION ALL SELECT 'EPISODE END 는 마지막 행이어야 한다: ' || event_key FROM ld_ep WHERE phase = 'END' AND rn <> ep_n;

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-09 적재: 입력 검사 실패 (처음 % 건)\n%', v_n, v_msg;
    END IF;
END $$;

-- [계산] reaction_change 와 strength (events/README.md "반응 변화율과 강도 경계")
--   KR:·US: → asset_month_return.local_return, FX: → fx_month.fx_change. 반올림 없이 복사한다.
CREATE TEMP TABLE ld_calc ON COMMIT DROP AS
SELECT t.event_key, m.id AS month_block_id, v.val AS reaction_change,
       CASE WHEN abs(v.val) >= 0.10 THEN 3 WHEN abs(v.val) >= 0.05 THEN 2 ELSE 1 END::smallint AS strength
FROM stg_ev_tags t
JOIN month_block m ON m.month_start = t.month_start::date
CROSS JOIN LATERAL (
    SELECT CASE WHEN split_part(t.reaction_ref, ':', 1) = 'FX'
                THEN (SELECT f.fx_change FROM fx_month f
                      WHERE f.month_block_id = m.id AND f.currency = split_part(t.reaction_ref, ':', 2))
                ELSE (SELECT r.local_return FROM asset_month_return r JOIN asset a ON a.id = r.asset_id
                      WHERE r.month_block_id = m.id AND a.market = split_part(t.reaction_ref, ':', 1)
                        AND a.ticker = split_part(t.reaction_ref, ':', 2))
           END AS val) v;

INSERT INTO ld_err SELECT 'reaction_change 를 계산하지 못했다: ' || event_key FROM ld_calc WHERE reaction_change IS NULL;

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-09 적재: 입력 검사 실패 (처음 % 건)\n%', v_n, v_msg;
    END IF;
END $$;

-- [삽입] WHERE NOT EXISTS 로 이미 있는 행을 먼저 거르고, ON CONFLICT DO NOTHING 은 안전망이다
--        (ON CONFLICT 만 쓰면 충돌 행도 identity 번호를 소비한다)
CREATE TEMP TABLE ld_report (seq serial, tbl text, file_rows bigint, changed_rows bigint) ON COMMIT DROP;

WITH ins AS (
    INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction,
                             strength, reaction_change, title, source, hindsight)
    SELECT s.event_key, c.month_block_id, s.category, s.event_type, s.phase, s.outcome, s.direction,
           c.strength, c.reaction_change, s.title, s.source, s.hindsight
    FROM stg_ev_tags s JOIN ld_calc c ON c.event_key = s.event_key
    WHERE NOT EXISTS (SELECT 1 FROM month_event e WHERE e.event_key = s.event_key)
    ORDER BY s.event_key COLLATE "C"
    ON CONFLICT DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, file_rows, changed_rows)
SELECT 'month_event (새 행)', (SELECT count(*) FROM stg_ev_tags), (SELECT count(*) FROM ins);

-- [차이 검사] 이미 있던 행이 파일(·계산값)과 다르면 조용히 옛 값을 두지 않고 멈춘다 (NULL 안전 비교).
--             방금 넣은 행도 같은 쿼리를 지나므로 삽입 자체의 오류도 잡는다.
INSERT INTO ld_err
SELECT 'month_event 값이 파일과 다르다: ' || s.event_key
FROM stg_ev_tags s
JOIN ld_calc c ON c.event_key = s.event_key
JOIN month_event e ON e.event_key = s.event_key
JOIN month_block m ON m.id = e.month_block_id
WHERE (m.month_start, e.category, e.event_type, e.phase, e.outcome, e.direction, e.strength, e.reaction_change, e.title, e.source, e.hindsight)
      IS DISTINCT FROM
      (s.month_start::date, s.category, s.event_type, s.phase, s.outcome, s.direction, c.strength, c.reaction_change, s.title, s.source, s.hindsight)
UNION ALL
SELECT 'DB 에만 있는 태그(파일에서 지워진 키): ' || e.event_key
FROM month_event e WHERE NOT EXISTS (SELECT 1 FROM stg_ev_tags s WHERE s.event_key = e.event_key)
UNION ALL
SELECT 'DB 에서 events_reviewed 가 TRUE 인데 reviewed.csv 에 없는 달(파일이 뒤로 갔다): ' || m.month_start
FROM month_block m WHERE m.events_reviewed AND NOT EXISTS (SELECT 1 FROM stg_ev_reviewed r WHERE r.month_start::date = m.month_start);

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-09 적재: 파일과 DB 가 어긋난다 (처음 % 건). 값 정정은 하지 않는다. 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- [편집 완료 표시] FALSE → TRUE 만. 이미 TRUE 인 달은 건드리지 않는다
WITH u AS (
    UPDATE month_block m SET events_reviewed = TRUE
    FROM stg_ev_reviewed r
    WHERE m.month_start = r.month_start::date AND NOT m.events_reviewed
    RETURNING 1)
INSERT INTO ld_report (tbl, file_rows, changed_rows)
SELECT 'month_block.events_reviewed (FALSE → TRUE 로 바꾼 달)', (SELECT count(*) FROM stg_ev_reviewed), (SELECT count(*) FROM u);

-- [사후 단언] 이번 실행이 끝난 DB 기준: 태그 ⇒ 편집 완료, NONE ⇔ 태그 0건, TAGGED ⇔ 태그 1건 이상
INSERT INTO ld_err
SELECT 'DB: 태그가 있는 달인데 events_reviewed 가 FALSE: ' || m.month_start
FROM month_block m WHERE NOT m.events_reviewed AND EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = m.id)
UNION ALL
SELECT 'DB: NONE 인 달에 태그가 있다: ' || r.month_start
FROM stg_ev_reviewed r JOIN month_block m ON m.month_start = r.month_start::date
WHERE r.result = 'NONE' AND EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = m.id)
UNION ALL
SELECT 'DB: TAGGED 인 달에 태그가 없다: ' || r.month_start
FROM stg_ev_reviewed r JOIN month_block m ON m.month_start = r.month_start::date
WHERE r.result = 'TAGGED' AND NOT EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = m.id)
UNION ALL
SELECT 'DB: reviewed.csv 의 달인데 events_reviewed 가 FALSE: ' || r.month_start
FROM stg_ev_reviewed r JOIN month_block m ON m.month_start = r.month_start::date WHERE NOT m.events_reviewed;

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-09 적재: 적재 뒤 단언 실패 (처음 % 건). 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- 보고 1: file_rows = 파일 행 수, changed_rows = 이번에 새로 넣거나 바꾼 수 (다시 실행하면 0)
SELECT tbl, file_rows, changed_rows FROM ld_report ORDER BY seq;

-- 보고 2: 유형별 표본 수 n (정의 N1 — 태그가 하나 이상 있는 에피소드의 수)와 범주를 말하지 않는 카드 대상(n < 3)
--         Phase 0 은 month_block 전체(표본 100개 달)가 재생 표본이다. MVP 는 재생 범위 조건을 WHERE 에 더한다.
SELECT e.event_type, count(DISTINCT left(e.event_key, 6)) AS n, count(*) AS tag_rows,
       count(DISTINCT left(e.event_key, 6)) < 3 AS category_hidden
FROM month_event e JOIN month_block m ON m.id = e.month_block_id
GROUP BY e.event_type
ORDER BY e.event_type COLLATE "C";

COMMIT;
