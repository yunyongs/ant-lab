-- =====================================================================
-- my Fund Manager Phase 0 — 사건 태그 적재 검증 SQL (P0-09)
-- 대상: db/11_load_events.sql 이 채운 month_event 와 month_block.events_reviewed
-- 근거: docs/erd.md 5.3(정의서), 5.5(검증 규칙 H5·H6), 9절 P0-09, events/README.md, db/10_load_sample_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/11_load_events_check.sql
--       적재(db/10_load_sample.sql → db/11_load_events.sql) 뒤에 실행한다. INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아
--       실습 DB(antlab)에서도 돌릴 수 있다 (01_schema_history_check.sql 은 검증용 DB 전용 — 차이).
-- 판정: 마지막 줄 `p0_09_check` 가 `PASS` 이고 종료 코드가 0이면 통과다.
--       0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 7절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 7절 뒤에 ROLLBACK 한다. 8절이 행 수·identity 마지막 값·편집 완료 달 수가 실행 전과 같은지 확인한다.
-- 절:   0 전제(FAIL 이면 여기서 멈춤) → 1 행 수 → 2 대조·형식 → 3 H5 → 4 반응·강도 → 5 편집 완료 → 6 보고·음성 → 7 판정 → ROLLBACK → 8 흔적 확인
-- 규칙 코드 21개 (규칙마다 양성 쿼리 1개 + 음성 사례 1개 이상, 7절이 커버리지 N/N 을 확인한다):
--       ROWS_EVENT ROWS_REVIEWED / MATCH KEY_FORMAT EPISODE MONTH_CAP H6_TYPE H6_OUTCOME TYPE_CATEGORY DIRECTION_RULE /
--       H5_DIGIT H5_TOKEN2 H5_DENY / REACTION REACTION_NULL STRENGTH / REVIEWED_ALL TAG_REVIEWED NONE_ZERO TAGGED_SOME / HIDDEN_LIST
-- P0-08 판정 계약에서 복사한 것: ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_err·check_ok·check_eq + val()
--       ⑤ 0절 뒤 멈춤과 7.5 판정(표식 2 또는 1) ⑥ 8절 흔적 확인(표식 0)과 마지막 CASE.
--       갱신 규칙: val() 은 정확히 한 행, 기대값을 NULL 로 쓰지 않는다, NULL 안전 비교(IS NOT DISTINCT FROM), 사례 하나에 조건 하나.
-- 음성 사례: 서브트랜잭션 안에서 DELETE/UPDATE 로 위반을 1건 만들어 그 규칙의 검사 쿼리가 1건으로 잡는지 센 뒤 되돌린다.
--       사례마다 단언하는 규칙 코드는 하나뿐이다 (한 변이가 다른 규칙도 어길 수 있으나 다른 규칙은 그 사례에서 보지 않는다). INSERT 는 쓰지 않는다.
-- 파일 읽기: events/types.csv, tags.csv, reviewed.csv 를 \copy 로 읽는다 (H6 기대값, 반응·표본 수의 독립 계산, 편집 완료 3값 대조).
--       저장소 루트에서 실행한다. 파일이 없거나 비어 있으면 0절이 FAIL 이다 (헛 PASS 방지).
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 1–7절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 흔적 확인용 스냅샷: month_event·month_block 의 행 수와 identity 마지막 값, events_reviewed 가 TRUE 인 달 수.
-- 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['month_event', 'month_block'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            v := v || t || '=없음;';
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
        s := COALESCE(pg_sequence_last_value(pg_get_serial_sequence('public.' || t, 'id')::regclass)::text, 'null');
        v := v || t || '=' || n || '/' || s || ';';
    END LOOP;
    IF to_regclass('public.month_block') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.month_block WHERE events_reviewed' INTO n;
        v := v || 'reviewed_true=' || n || ';';
    END IF;
    RETURN v;
END $$;
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '0 전제' '1 행 수' '2 대조·형식' '3 H5' '4 반응·강도' '5 편집 완료' '6 보고·음성' '7 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 판정 프로시저 ------------------------------------------------------------

-- 값 비교. NULL끼리는 같다고 본다.
CREATE PROCEDURE pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 스칼라 질의 한 개를 실행해 값을 text로 돌려준다. CALL 인자에는 서브쿼리를 쓸 수 없어 check_eq의 실제값을 만드는 데 쓴다.
-- 정확히 한 행이어야 한다. 0행은 NULL, 여러 행은 기대값과 같을 수 없는 'ERROR too_many_rows'를 돌려 FAIL 행으로 남긴다.
CREATE FUNCTION pg_temp.val(p_sql text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_out text;
BEGIN
    EXECUTE p_sql INTO STRICT v_out;
    RETURN v_out;
EXCEPTION
    WHEN no_data_found THEN RETURN NULL;
    WHEN too_many_rows THEN RETURN 'ERROR too_many_rows';
END $$;

-- 파일 읽기 -------------------------------------------------------------------
-- events/ 의 CSV 3개 (git 이 추적하는 파일). 열 목록이 곧 계약이라 HEADER MATCH 로 머리글을 확인한다.
CREATE TEMP TABLE zz_types (event_type text, category text, name_ko text, outcomes text, direction_rule text, reaction_refs text);
CREATE TEMP TABLE zz_tags (event_key text, month_start text, category text, event_type text, phase text, outcome text,
                           direction text, reaction_ref text, title text, hindsight text, source text, checked_on text);
CREATE TEMP TABLE zz_rev (month_start text, result text);
\! test -f events/types.csv -a -f events/tags.csv -a -f events/reviewed.csv
\if :SHELL_ERROR
  \echo 'events/ 의 types.csv, tags.csv, reviewed.csv 중 없는 것이 있다 — 저장소 루트에서 실행한다. 0절이 FAIL 이 된다'
\else
  \copy zz_types FROM 'events/types.csv' WITH (FORMAT csv, HEADER MATCH)
  \copy zz_tags FROM 'events/tags.csv' WITH (FORMAT csv, HEADER MATCH)
  \copy zz_rev FROM 'events/reviewed.csv' WITH (FORMAT csv, HEADER MATCH)
\endif

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL 이면 1절 이후 결과를 믿지 않는다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('5개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '5',
        (SELECT count(*)::text FROM unnest(ARRAY['month_block', 'month_event', 'asset', 'asset_month_return', 'fx_month']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('events/ 파일 3개를 읽었다 (types·tags·reviewed 모두 1행 이상)', 'ok',
        CASE WHEN (SELECT count(*) FROM zz_types) > 0 AND (SELECT count(*) FROM zz_tags) > 0 AND (SELECT count(*) FROM zz_rev) > 0
             THEN 'ok' ELSE '파일 없음 또는 빈 파일' END),
    -- MVP: 100 → 재생 표본 달 수, REVIEWED_ALL 은 재생 표본 조건(erd 6.5 S1)으로 한정한다. "태그 달만 TRUE"로 완화하지 않는다
    ('10 적재가 끝나 있다 (month_block 100행)', '100', (SELECT count(*)::text FROM month_block)),
    ('10 적재가 끝나 있다 (asset_month_return 이 비어 있지 않다)', 'true', (SELECT (count(*) > 0)::text FROM asset_month_return))
) AS x(item, expected, actual);

-- 정보 행: 현재 DB 이름 (FAIL 아님)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('0 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);

-- 0절 결과를 먼저 보여 주고, FAIL 이 있으면 멈춘다 (표식은 1로 남아 ON_ERROR_STOP 없이도 마지막 줄이 FAIL 이다).
SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
FROM zz_check_result
WHERE section = '0 전제'
ORDER BY seq;

DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM zz_check_result WHERE section = '0 전제';
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-09 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 01 스키마와 10·11 적재를 먼저 실행했는지, 저장소 루트인지 확인한다)', v_fail;
    END IF;
END $$;

-- H5 금지 토큰: 노출 코드(event_type·outcome)의 `_` 로 나눈 토큰 중 아래에 있으면 실패한다.
-- 이유: 코드가 나라·기관·회사·인물·사건 이름이면 사건 카드가 날짜와 같은 단서가 된다 (PLAN §4.11, §2 안티목표).
--       나라는 US·KR 두 글자 토큰만 허용하고(풀의 시장 코드) 그 밖의 두 글자 토큰은 H5_TOKEN2 가 막는다.
--       이 목록은 완전하지 않다 — 실수 방지용 그물이고, 일부러 피해 가는 이름은 편집 리뷰의 몫이다 (PLAN §13-5 "우연히만 막는다").
CREATE TEMP TABLE zz_deny (tok text PRIMARY KEY, why text NOT NULL);
INSERT INTO zz_deny (tok, why) VALUES
    ('CHINA', '나라·지역'), ('JAPAN', '나라·지역'), ('RUSSIA', '나라·지역'), ('UKRAINE', '나라·지역'), ('ISRAEL', '나라·지역'),
    ('IRAN', '나라·지역'), ('IRAQ', '나라·지역'), ('GAZA', '나라·지역'), ('LIBYA', '나라·지역'), ('EGYPT', '나라·지역'),
    ('GREECE', '나라·지역'), ('ITALY', '나라·지역'), ('SPAIN', '나라·지역'), ('EUROPE', '나라·지역'), ('EURO', '나라·지역'),
    ('KOREA', '나라·지역'), ('NORTH', '나라·지역'), ('SOUTH', '나라·지역'), ('CANADA', '나라·지역'), ('MEXICO', '나라·지역'),
    ('GERMANY', '나라·지역'), ('FRANCE', '나라·지역'), ('BRITAIN', '나라·지역'), ('TAIWAN', '나라·지역'),
    ('FED', '기관'), ('ECB', '기관'), ('IMF', '기관'), ('OPEC', '기관'), ('NATO', '기관'), ('FDIC', '기관'), ('WHO', '기관'),
    ('LEHMAN', '회사'), ('STEARNS', '회사'), ('AIG', '회사'), ('JPMORGAN', '회사'), ('SVB', '회사'), ('UBS', '회사'),
    ('SUISSE', '회사'), ('PARIBAS', '회사'), ('BNP', '회사'), ('FANNIE', '회사'), ('FREDDIE', '회사'),
    ('TRUMP', '인물'), ('OBAMA', '인물'), ('BUSH', '인물'), ('BIDEN', '인물'), ('YOON', '인물'), ('HARRIS', '인물'),
    ('DEMOCRATIC', '정당'), ('REPUBLICAN', '정당'), ('DEMOCRAT', '정당'),
    ('GFC', '사건 이름'), ('TOHOKU', '사건 이름'), ('FUKUSHIMA', '사건 이름'), ('CHEONAN', '사건 이름'), ('YEONPYEONG', '사건 이름'),
    ('SWINE', '사건 이름'), ('COVID', '사건 이름'), ('SARS', '사건 이름'), ('MERS', '사건 이름'), ('EBOLA', '사건 이름'),
    ('SUBPRIME', '사건 이름'), ('BREXIT', '사건 이름'), ('DOTCOM', '사건 이름'), ('TARP', '사건 이름'),
    ('HAMAS', '단체'), ('HEZBOLLAH', '단체'), ('SYRIA', '나라·지역'), ('GREEK', '나라·지역'), ('SWISS', '나라·지역'),
    ('CRIMEA', '나라·지역'), ('FITCH', '회사'), ('MOODYS', '회사'), ('SILICON', '회사'), ('REPUBLIC', '회사'),
    ('LIBERATION', '사건 이름'), ('WUHAN', '사건 이름'), ('OMICRON', '사건 이름'), ('PIIGS', '사건 이름');

-- 노출 코드 목록: DB 의 event_type·outcome 과 types.csv 의 event_type·outcomes. H5 검사가 같은 뷰를 본다.
CREATE TEMP VIEW zz_codes AS
SELECT 'db' AS src, event_type AS code FROM month_event
UNION ALL SELECT 'db', outcome FROM month_event WHERE outcome IS NOT NULL
UNION ALL SELECT 'types', event_type FROM zz_types
UNION ALL SELECT 'types', unnest(string_to_array(outcomes, ';')) FROM zz_types WHERE outcomes IS NOT NULL;

-- 검사 쿼리 목록: 위반 개수를 돌려주는 쿼리. 양성(0건)과 음성(위반 1건을 만든 뒤 1건)에 같은 쿼리를 쓴다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);
INSERT INTO zz_q (code, section, item, sql) VALUES
    ('ROWS_EVENT', '1 행 수', 'month_event 행 수와 tags.csv 행 수의 차이',
     $q$SELECT abs((SELECT count(*) FROM month_event) - (SELECT count(*) FROM zz_tags))$q$),
    ('ROWS_REVIEWED', '1 행 수', 'events_reviewed 가 TRUE 인 달 집합과 reviewed.csv 의 달 집합에서 한쪽에만 있는 달 수 + 파일의 중복 달 수 + result 가 TAGGED/NONE 밖인 행 수',
     $q$SELECT (SELECT count(*) FROM zz_rev r
                FULL JOIN (SELECT month_start FROM month_block WHERE events_reviewed) m ON m.month_start = r.month_start::date
                WHERE r.month_start IS NULL OR m.month_start IS NULL)
             + (SELECT count(*) - count(DISTINCT month_start) FROM zz_rev)
             + (SELECT count(*) FROM zz_rev WHERE result IS NULL OR result NOT IN ('TAGGED', 'NONE'))$q$),
    ('MATCH', '2 대조·형식', 'DB 와 tags.csv 가 다른 행 (달·범주·유형·단계·결과·방향·제목·출처·사후 평가, 한쪽에만 있는 행 포함)',
     $q$SELECT count(*) FROM zz_tags z
        FULL JOIN (month_event e JOIN month_block m ON m.id = e.month_block_id) ON e.event_key = z.event_key
        WHERE z.event_key IS NULL OR e.event_key IS NULL
           OR (z.month_start::date, z.category, z.event_type, z.phase, z.outcome, z.direction, z.title, z.source, z.hindsight)
              IS DISTINCT FROM (m.month_start, e.category, e.event_type, e.phase, e.outcome, e.direction, e.title, e.source, e.hindsight)$q$),
    ('KEY_FORMAT', '2 대조·형식', 'event_key 가 ^ev[0-9]{4}\.[0-9]{2}$ 가 아닌 행 (날짜·이름이 키에 없다)',
     $q$SELECT count(*) FROM month_event WHERE event_key !~ '^ev[0-9]{4}\.[0-9]{2}$'$q$),
    ('EPISODE', '2 대조·형식', '에피소드 규칙 위반 행 (유형·범주 혼합, 순번 빈틈, 달 비증가, START 가 01 아님, END 가 마지막 아님)',
     $q$WITH t AS (
            SELECT e.event_key, left(e.event_key, 6) AS ep, substr(e.event_key, 8)::integer AS seq, m.month_start, e.category, e.event_type, e.phase,
                   row_number() OVER w AS rn, count(*) OVER (PARTITION BY left(e.event_key, 6)) AS ep_n, lag(m.month_start) OVER w AS prev_month
            FROM month_event e JOIN month_block m ON m.id = e.month_block_id
            WINDOW w AS (PARTITION BY left(e.event_key, 6) ORDER BY e.event_key COLLATE "C"))
        SELECT (SELECT count(*) FROM (SELECT ep FROM t GROUP BY ep HAVING count(DISTINCT event_type) > 1 OR count(DISTINCT category) > 1) a)
             + (SELECT count(*) FROM t WHERE seq <> rn)
             + (SELECT count(*) FROM t WHERE prev_month IS NOT NULL AND month_start <= prev_month)
             + (SELECT count(*) FROM t WHERE phase = 'START' AND seq <> 1)
             + (SELECT count(*) FROM t WHERE phase = 'END' AND rn <> ep_n)$q$),
    ('H6_TYPE', '2 대조·형식', 'H6 event_type 이 types.csv 목록 밖인 행',
     $q$SELECT count(*) FROM month_event e WHERE NOT EXISTS (SELECT 1 FROM zz_types t WHERE t.event_type = e.event_type)$q$),
    ('H6_OUTCOME', '2 대조·형식', 'H6 outcome 이 그 유형의 허용 목록 밖인 행 (빈 목록이면 NULL 만 허용)',
     $q$SELECT count(*) FROM month_event e JOIN zz_types t ON t.event_type = e.event_type
        WHERE e.outcome IS NOT NULL AND NOT (e.outcome = ANY (COALESCE(string_to_array(t.outcomes, ';'), ARRAY[]::text[])))$q$),
    ('TYPE_CATEGORY', '2 대조·형식', 'category 가 types.csv 의 그 유형 범주와 다른 행',
     $q$SELECT count(*) FROM month_event e JOIN zz_types t ON t.event_type = e.event_type WHERE e.category <> t.category$q$),
    ('DIRECTION_RULE', '2 대조·형식', 'direction 이 유형 규칙과 다른 행 (UP_DOWN 은 필수, NONE 은 빈 칸)',
     $q$SELECT count(*) FROM month_event e JOIN zz_types t ON t.event_type = e.event_type
        WHERE (t.direction_rule = 'UP_DOWN' AND e.direction IS NULL) OR (t.direction_rule = 'NONE' AND e.direction IS NOT NULL)$q$),
    ('H5_DIGIT', '3 H5', 'H5 노출 코드(DB 와 types.csv 전체)에 숫자가 든 코드 (연도 차단)',
     $q$SELECT count(*) FROM zz_codes WHERE code ~ '[0-9]'$q$),
    ('H5_TOKEN2', '3 H5', 'H5 노출 코드에 US·KR 밖의 두 글자 이하 토큰이 든 코드 (나라·기관 약어 차단)',
     $q$SELECT count(*) FROM zz_codes c
        WHERE EXISTS (SELECT 1 FROM regexp_split_to_table(c.code, '_') AS tok WHERE length(tok) <= 2 AND tok NOT IN ('US', 'KR'))$q$),
    ('H5_DENY', '3 H5', 'H5 노출 코드에 금지 토큰(나라·기관·회사·인물·사건 이름)이 든 코드',
     $q$SELECT count(*) FROM zz_codes c
        WHERE EXISTS (SELECT 1 FROM regexp_split_to_table(c.code, '_') AS tok WHERE tok IN (SELECT d.tok FROM zz_deny d))$q$),
    ('REACTION', '4 반응·강도', 'reaction_change 가 reaction_ref 가 가리키는 그 달 값과 다른 행 (정확히 일치해야 한다)',
     $q$SELECT count(*) FROM month_event e
        JOIN month_block m ON m.id = e.month_block_id
        LEFT JOIN zz_tags z ON z.event_key = e.event_key
        CROSS JOIN LATERAL (
            SELECT CASE WHEN split_part(z.reaction_ref, ':', 1) = 'FX'
                        THEN (SELECT f.fx_change FROM fx_month f WHERE f.month_block_id = m.id AND f.currency = split_part(z.reaction_ref, ':', 2))
                        ELSE (SELECT r.local_return FROM asset_month_return r JOIN asset a ON a.id = r.asset_id
                              WHERE r.month_block_id = m.id AND a.market = split_part(z.reaction_ref, ':', 1) AND a.ticker = split_part(z.reaction_ref, ':', 2))
                   END AS val) v
        WHERE z.event_key IS NULL OR e.reaction_change IS DISTINCT FROM v.val$q$),
    ('REACTION_NULL', '4 반응·강도', 'reaction_change 가 NULL 인 행 (카드로 내는 태그는 반응값을 채운다)',
     $q$SELECT count(*) FROM month_event WHERE reaction_change IS NULL$q$),
    ('STRENGTH', '4 반응·강도', 'strength 가 경계 표(5%·10%)의 단계와 다른 행',
     $q$SELECT count(*) FROM month_event
        WHERE strength IS DISTINCT FROM (CASE WHEN abs(reaction_change) >= 0.10 THEN 3 WHEN abs(reaction_change) >= 0.05 THEN 2 ELSE 1 END)$q$),
    -- MVP: 이 쿼리는 재생 표본 조건(erd 6.5 S1)으로 한정한다. "태그 달만 TRUE"로 완화하지 않는다 (PLAN §4.11)
    ('REVIEWED_ALL', '5 편집 완료', '편집 완료가 아닌 표본 달 (Phase 0 은 100개 달 전부 TRUE)',
     $q$SELECT count(*) FROM month_block WHERE NOT events_reviewed$q$),
    ('TAG_REVIEWED', '5 편집 완료', '태그가 있는데 events_reviewed 가 FALSE 인 달 (태그 ⇒ 편집 완료)',
     $q$SELECT count(*) FROM month_block m WHERE NOT m.events_reviewed AND EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = m.id)$q$),
    ('NONE_ZERO', '5 편집 완료', 'reviewed.csv 가 NONE 인데 태그가 있는 달 (NONE ⇔ 태그 0건)',
     $q$SELECT count(*) FROM zz_rev r JOIN month_block m ON m.month_start = r.month_start::date
        WHERE r.result = 'NONE' AND EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = m.id)$q$),
    ('TAGGED_SOME', '5 편집 완료', 'reviewed.csv 가 TAGGED 인데 태그가 없는 달 (TAGGED ⇔ 태그 1건 이상)',
     $q$SELECT count(*) FROM zz_rev r JOIN month_block m ON m.month_start = r.month_start::date
        WHERE r.result = 'TAGGED' AND NOT EXISTS (SELECT 1 FROM month_event e WHERE e.month_block_id = m.id)$q$),
    ('MONTH_CAP', '2 대조·형식', '한 달에 태그가 3개를 넘는 달 (1차 편집 규칙 3 — 카드 수가 위기 달 표식이 되지 않게)',
     $q$SELECT count(*) FROM (SELECT month_block_id FROM month_event GROUP BY month_block_id HAVING count(*) > 3) x$q$),
    ('HIDDEN_LIST', '6 보고·음성', 'n < 3 유형 목록(DB, 정의 N1)이 tags.csv 에서 독립으로 센 목록과 다른 유형 수',
     $q$SELECT (SELECT count(*) FROM (
                    SELECT event_type FROM month_event GROUP BY event_type HAVING count(DISTINCT left(event_key, 6)) < 3
                    EXCEPT
                    SELECT event_type FROM zz_tags GROUP BY event_type HAVING count(DISTINCT left(event_key, 6)) < 3) a)
              + (SELECT count(*) FROM (
                    SELECT event_type FROM zz_tags GROUP BY event_type HAVING count(DISTINCT left(event_key, 6)) < 3
                    EXCEPT
                    SELECT event_type FROM month_event GROUP BY event_type HAVING count(DISTINCT left(event_key, 6)) < 3) b)$q$);

-- 1~6 양성: 위 쿼리 모두 위반 0건 ---------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, '[' || q.code || '] ' || q.item || ' — 위반 0건', '0', pg_temp.val(q.sql), pg_temp.val(q.sql) IS NOT DISTINCT FROM '0'
FROM zz_q q
ORDER BY q.section, q.code;

-- 정보 행 (FAIL 아님): 규모와 편집 완료 3값
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('1 행 수', 'month_event 행 수 (정보)', '(정보)', pg_temp.val('SELECT count(*) FROM month_event'), true),
       ('1 행 수', '태그가 있는 달 수 = reviewed.csv TAGGED (정보)', '(정보)',
        pg_temp.val($$SELECT count(DISTINCT month_block_id) FROM month_event$$) || ' = ' || pg_temp.val($$SELECT count(*) FROM zz_rev WHERE result = 'TAGGED'$$), true),
       ('1 행 수', '사건 없음으로 확인한 달 수 = reviewed.csv NONE (정보)', '(정보)', pg_temp.val($$SELECT count(*) FROM zz_rev WHERE result = 'NONE'$$), true),
       ('1 행 수', '아직 안 본 달 수 (events_reviewed FALSE, 정보)', '(정보)', pg_temp.val('SELECT count(*) FROM month_block WHERE NOT events_reviewed'), true),
       ('1 행 수', 'event_summary 가 채워진 달 수 (정보, 단언하지 않음 — 이 기능의 소유 컬럼이 아니다)', '(정보)',
        pg_temp.val('SELECT count(*) FROM month_block WHERE event_summary IS NOT NULL'), true);

-- 6.2 음성: 일부러 위반 1건을 만들면 그 규칙의 검사 쿼리가 기대한 개수로 잡는지 (서브트랜잭션 안의 DELETE/UPDATE, 끝에 되돌린다) ----------
-- 음성 전제: 아래 변이가 1건을 만들려면 파일이 이 모양이어야 한다. 전제가 깨지면 커버리지보다 먼저 여기서 FAIL 이 난다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 보고·음성', '음성 전제: ' || x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('2010-11 의 태그 수 (MONTH_CAP 변이 대상)', '3',
     pg_temp.val($$SELECT count(*)::text FROM month_event e JOIN month_block m ON m.id = e.month_block_id WHERE m.month_start = DATE '2010-11-01'$$)),
    ('ev0001 의 행 수 (한 행짜리 에피소드)', '1', pg_temp.val($$SELECT count(*)::text FROM month_event WHERE left(event_key, 6) = 'ev0001'$$)),
    ('ev0021 유형의 에피소드 수 (HIDDEN_LIST 변이 대상)', '2',
     pg_temp.val($$SELECT count(DISTINCT left(event_key, 6))::text FROM month_event WHERE event_type = (SELECT event_type FROM month_event WHERE event_key = 'ev0021.02')$$))
) AS x(item, expected, actual);
CREATE TEMP TABLE zz_neg (n serial, q text NOT NULL, label text NOT NULL, mutate text NOT NULL, expect text NOT NULL DEFAULT '1');
INSERT INTO zz_neg (q, label, mutate) VALUES
    ('ROWS_EVENT', '태그 행 하나를 지우면', $m$DELETE FROM month_event WHERE event_key = 'ev0001.01'$m$),
    ('ROWS_REVIEWED', '편집 완료 표시 하나를 FALSE 로 되돌리면', $m$UPDATE month_block SET events_reviewed = FALSE WHERE month_start = DATE '2007-01-01'$m$),
    ('MATCH', '제목 한 줄을 바꾸면', $m$UPDATE month_event SET title = title || ' x' WHERE event_key = 'ev0001.01'$m$),
    ('KEY_FORMAT', '키 하나를 ev7.1 로 바꾸면 (UNIQUE 는 안 걸린다)', $m$UPDATE month_event SET event_key = 'ev7.1' WHERE event_key = 'ev0001.01'$m$),
    ('EPISODE', '에피소드 두 번째 행을 START 로 바꾸면', $m$UPDATE month_event SET phase = 'START' WHERE event_key = 'ev0002.02'$m$),
    ('H6_TYPE', 'types.csv 밖의 유형으로 바꾸면 (DDL 은 통과)', $m$UPDATE month_event SET event_type = 'NOT_IN_LIST' WHERE event_key = 'ev0001.01'$m$),
    ('H6_OUTCOME', '유형의 허용 목록 밖 결과로 바꾸면 (DDL 은 통과)', $m$UPDATE month_event SET outcome = 'BOGUS' WHERE event_key = 'ev0002.01'$m$),
    ('TYPE_CATEGORY', '범주를 유형의 범주와 다르게 바꾸면 (DDL 은 통과)', $m$UPDATE month_event SET category = 'TRADE' WHERE event_key = 'ev0001.01'$m$),
    ('DIRECTION_RULE', 'UP_DOWN 유형의 방향을 비우면', $m$UPDATE month_event SET direction = NULL WHERE event_key = 'ev0006.01'$m$),
    ('H5_DIGIT', '유형 코드에 연도를 넣으면 (DDL 의 코드 형식은 숫자를 허용한다)', $m$UPDATE month_event SET event_type = 'GFC_2008' WHERE event_key = 'ev0001.01'$m$),
    ('H5_TOKEN2', '유형 코드에 나라 약어 토큰을 넣으면', $m$UPDATE month_event SET event_type = 'CN_TARIFF' WHERE event_key = 'ev0026.01'$m$),
    ('H5_DENY', '유형 코드에 회사 이름 토큰을 넣으면', $m$UPDATE month_event SET event_type = 'LEHMAN_FAILURE' WHERE event_key = 'ev0007.01'$m$),
    ('REACTION', '반응 변화율을 1bp 바꾸면', $m$UPDATE month_event SET reaction_change = reaction_change + 0.00010000 WHERE event_key = 'ev0001.01'$m$),
    ('REACTION_NULL', '반응 변화율을 NULL 로 만들면 (DDL 은 NULL 을 허용한다)', $m$UPDATE month_event SET reaction_change = NULL WHERE event_key = 'ev0001.01'$m$),
    ('STRENGTH', '강도 단계를 바꾸면', $m$UPDATE month_event SET strength = strength % 3 + 1 WHERE event_key = 'ev0001.01'$m$),
    ('REVIEWED_ALL', '태그가 없는 달의 편집 완료를 FALSE 로 되돌리면', $m$UPDATE month_block SET events_reviewed = FALSE WHERE month_start = DATE '2007-01-01'$m$),
    ('TAG_REVIEWED', '태그가 있는 달의 편집 완료를 FALSE 로 되돌리면', $m$UPDATE month_block SET events_reviewed = FALSE WHERE month_start = DATE '2007-08-01'$m$),
    ('NONE_ZERO', '사건 없음(NONE)인 달로 다른 달의 태그 하나를 옮기면', $m$UPDATE month_event SET month_block_id = (SELECT id FROM month_block WHERE month_start = DATE '2007-01-01') WHERE event_key = 'ev0001.01'$m$),
    ('TAGGED_SOME', 'TAGGED 달의 태그를 지우면', $m$DELETE FROM month_event WHERE event_key = 'ev0001.01'$m$),
    ('MONTH_CAP', '태그 3개인 달(2010-11)로 한 행짜리 에피소드의 태그를 옮기면 4개', $m$UPDATE month_event SET month_block_id = (SELECT id FROM month_block WHERE month_start = DATE '2010-11-01') WHERE event_key = 'ev0001.01'$m$),
    ('HIDDEN_LIST', '2건짜리 유형에 에피소드 하나를 더 붙이면 (n 이 3 이 되어 숨김 목록에서 빠진다)', $m$UPDATE month_event SET event_key = 'ev9999.01' WHERE event_key = 'ev0021.02'$m$);

DO $$
DECLARE
    r record;
    v_sql text;
    v_n   text;
BEGIN
    FOR r IN SELECT n.q, n.label, n.mutate, n.expect, q.section FROM zz_neg n JOIN zz_q q ON q.code = n.q ORDER BY n.n LOOP
        SELECT sql INTO v_sql FROM zz_q WHERE code = r.q;
        v_n := NULL;
        BEGIN
            EXECUTE r.mutate;
            EXECUTE v_sql INTO v_n;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN
                v_n := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
            END IF;
        END;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (r.section, '음성 [' || r.q || '] ' || r.label || ' 위반 ' || r.expect || '건', r.expect, v_n, v_n IS NOT DISTINCT FROM r.expect);
    END LOOP;
END $$;

-- 6.1 보고: 유형별 표본 수 n (정의 N1: 태그가 하나 이상 있는 에피소드의 수)와 범주를 말하지 않는 카드 대상(n < 3).
--       Phase 0 은 month_block 전체(표본 100개 달)가 재생 표본이다. 정보 행이며 단언은 HIDDEN_LIST 가 한다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 보고·음성', '유형별 표본 수 (정보) ' || e.event_type, '(정보)',
       'n=' || count(DISTINCT left(e.event_key, 6)) || ' 태그=' || count(*) || CASE WHEN count(DISTINCT left(e.event_key, 6)) < 3 THEN ' 범주 비공개 대상' ELSE '' END, true
FROM month_event e
GROUP BY e.event_type
ORDER BY e.event_type COLLATE "C";

INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('6 보고·음성', 'n < 3 유형 목록 (정보)', '(정보)',
        COALESCE((SELECT string_agg(event_type, ' ' ORDER BY event_type COLLATE "C") FROM (
                      SELECT event_type FROM month_event GROUP BY event_type HAVING count(DISTINCT left(event_key, 6)) < 3) h), '(없음)'), true);

SELECT substr(item, length('유형별 표본 수 (정보) ') + 1) AS event_type, actual AS sample_size
FROM zz_check_result
WHERE section = '6 보고·음성' AND item LIKE '유형별 표본 수 (정보) %'
ORDER BY seq;

-- 7 판정 ---------------------------------------------------------------------
-- 7.1 규칙 커버리지: 음성 사례가 통과한 규칙 코드 수 / 규칙 코드 수 (21/21 이어야 한다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '7 판정', '규칙 커버리지 (음성 사례가 통과한 규칙 코드 / 전체 규칙 코드)',
       (SELECT count(*) FROM zz_q) || '/' || (SELECT count(*) FROM zz_q),
       cov.n || '/' || (SELECT count(*) FROM zz_q),
       cov.n = (SELECT count(*) FROM zz_q)
FROM (SELECT count(DISTINCT n.q) AS n
      FROM zz_neg n
      JOIN zz_check_result r ON r.item LIKE '음성 [' || n.q || ']%' AND r.ok) cov;

-- 7.2 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 7.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 7.4 판정 행
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result;

-- 7.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--     판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-09 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 8 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- month_event·month_block 의 행 수와 identity 마지막 값, events_reviewed TRUE 개수가 실행 전과 같고, public 에 zz_ 객체가 없다.
-- 검증은 INSERT 를 하지 않으므로 번호표를 소비하지 않는다.
DO $$
DECLARE
    v_before text;
    v_after  text;
    v_zz     integer;
BEGIN
    SELECT snap INTO v_before FROM zz_before;
    v_after := pg_temp.zz_snap();
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%') INTO v_zz;
    IF v_before IS DISTINCT FROM v_after OR v_zz <> 0 THEN
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION E'P0-09 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 1–7절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_09_check;
