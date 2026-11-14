-- =====================================================================
-- my Fund Manager Phase 0 — 소음·알림 템플릿 표본 자동 검사 (P0-13)
-- 대상: feed/kinds.csv, feed/variables.csv, feed/templates.csv, feed/denylist.csv (DB 테이블이 아니라 손 편집 파일이다)
-- 근거: docs/erd.md 6.3 turn_feed(kind·template_id·template_vars), 6.4 ck_turn_feed_kind·ck_turn_feed_target_kind,
--       D27, 9절 P0-13·P0-18, docs/PLAN.md 4.11(사건 카드)·4.13(소음의 원천은 월 블록의 숫자뿐)
-- 실행: 저장소 루트에서 (\copy의 파일 경로가 psql의 현재 디렉터리 기준이다)
--       psql -X -q -v ON_ERROR_STOP=1 -d postgres -f feed/check_templates.sql
--       스키마가 필요 없다. 임시 객체만 만들고 ROLLBACK한다. 임시 소켓 인스턴스의 postgres DB나 antlab_test 어디서나 돈다.
-- 판정: 마지막 줄 `p0_13_check`가 `PASS`이고 종료 코드가 0이면 통과다. `PASS`는 0–8절이 모두 통과하고 9절 흔적 확인까지 끝났을 때만
--       나온다 (zz_verdict: 1 = 미완료 또는 실패, 2 = 0–8절 통과·흔적 확인 대기, 0 = 최종 통과).
--       0절 전제나 1절 적재가 FAIL이면 그 결과를 보여 준 뒤 오류로 멈춘다. 그 밖에 하나라도 어긋나면 8절이 FAIL 행을 보여 주고
--       오류로 끝난다 (종료 코드 3). ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 절:   0 전제 → 1 적재 → 2 종류 코드(E1) → 3 변수(E2) → 4 템플릿 속성(E3) → 5 문장 규칙·금지어(E4·E5) → 6 적용 범위
--       → 7 음성 픽스처(규칙 커버리지) → 8 판정 → ROLLBACK → 9 흔적 확인
-- 규칙 코드 29개: 2 KINDS_ERD / 3 VAR_FUTURE VAR_TYPE / 4 ID_FORMAT ID_KIND ID_DUP ID_ACTIVE KIND TARGET_KIND DIRECTION HIDDEN
--       EVENT_ATTR LENGTH / 5 CHARSET NFC PERCENT UNIT BRACE VAR_UNKNOWN VAR_KIND VAR_TARGET PARTICLE DENY_DATE DENY_FUTURE
--       DENY_PROPER DENY_ADVICE DENY_FACT SHAPE / 6 COVERAGE. 규칙 함수 pg_temp.zz_violations()가 (출처, 대상, 규칙 코드) 행을
--       돌려주고, 실제 파일에는 0행, 7절 픽스처에는 사례마다 정확히 그 코드 하나를 기대한다.
-- P0-05 검증 SQL(db/01_schema_history_check.sql)과 같은 판정 형식을 쓴다. 복사한 묶음: ① 맨 앞 CREATE TEMP SEQUENCE zz_verdict + pg_temp 존재 단언 + (모든 참조는 pg_temp. 한정)
--       setval(…, 1)  ② 결과 표 zz_check_result  ④ 프로시저 check_eq + 함수 val()  ⑤ 멈춤과 판정(→ 표식 2 또는 1, RAISE)
--       ⑥ 흔적 확인(→ 표식 0)과 마지막 CASE. 복사하지 않은 것: ③ 카탈로그 기대 목록 표(테이블이 없다)와 check_err·check_ok
--       (DML 음성 사례가 없다). 일부만 복사하면 헛 PASS 길이 다시 열린다.
-- P0-05와 다른 점: P0-05 검증 파일은 psql 메타 명령(\set, \echo, \i)을 쓰지 않는다. 이 파일은 CSV 4개를 읽어야 해서
--       결정 10이 적재 도구로 승인한 \copy 4줄만 메타 명령으로 쓴다 (1절). 그 밖의 메타 명령은 없다.
-- 새 문법 안내: 정규식 연산자 ~ / !~ 와 한글 범위 [가-힣], regexp_replace·regexp_matches(…, 'g'), IS NFC NORMALIZED와 normalize(),
--       string_to_array·unnest로 ';' 목록 펼치기, 함수 안의 WITH(CTE), \copy … WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8').
-- 명령줄(psql)이 기준이다. VS Code에서는 통합 터미널에서 실행한다. SQL 확장의 실행 기능은 오류 처리 방식이 달라 판정을 믿지 않는다.
-- =====================================================================

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 0–8절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '0 전제' '1 적재' '2 종류' '3 변수' '4 속성' '5 문장' '6 범위' '7 음성' '8 판정'
    item     text    NOT NULL,   -- 항목 설명
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 입력 표: \copy가 채운다. 열 이름·순서가 CSV 첫 줄과 같아야 한다 (HEADER MATCH — 열 목록이 곧 계약이다)
CREATE TEMP TABLE zz_kinds (kind text, name_ko text, screen text, target text);
CREATE TEMP TABLE zz_var   (name text, type text, source text, turn_offset integer, kinds text, max_len integer);
CREATE TEMP TABLE zz_tpl   (template_id text, kind text, status text, target text, direction text, strength smallint,
                            event_category text, event_type text, outcome text, category_hidden boolean, text text);
CREATE TEMP TABLE zz_deny  (word text, reason text, note text);
-- Fixture-only banned words. Fixtures never read denylist.csv, so editing it cannot make them fail. Section 5 checks that denylist.csv still contains these.
CREATE TEMP TABLE zz_deny_fx (word text PRIMARY KEY, reason text NOT NULL);
INSERT INTO zz_deny_fx VALUES
    ('작년', 'DATE'), ('연말', 'DATE'), ('유월', 'DATE'),
    ('다음 달', 'FUTURE'), ('다음 주', 'FUTURE'), ('향후', 'FUTURE'), ('예정', 'FUTURE'),
    ('앞두', 'FUTURE'), ('앞둘', 'FUTURE'), ('앞뒀', 'FUTURE'), ('다가오', 'FUTURE'), ('다가온', 'FUTURE'),
    ('연준', 'PROPER'), ('매수하세요', 'ADVICE'), ('인수', 'FACT');

-- 규칙 검사용 합본: 실제 파일('real')과 7절 픽스처 묶음이 같은 규칙 함수를 지난다
CREATE TEMP TABLE zz_all (
    rid integer GENERATED ALWAYS AS IDENTITY, src text NOT NULL,
    template_id text, kind text, status text, target text, direction text, strength smallint,
    event_category text, event_type text, outcome text, category_hidden boolean, text text
);
CREATE TEMP TABLE zz_var_all (src text NOT NULL, name text, type text, turn_offset integer, kinds text, max_len integer);
CREATE TEMP TABLE zz_cov_src (src text PRIMARY KEY);   -- 적용 범위(COVERAGE)를 보는 묶음
INSERT INTO zz_cov_src VALUES ('real'), ('cov_fx');

-- 규칙 목록: 코드와 보고 절
CREATE TEMP TABLE zz_rule (code text PRIMARY KEY, sect text NOT NULL, descr text NOT NULL);
INSERT INTO zz_rule VALUES
    ('KINDS_ERD',   '2 종류', 'kinds.csv 종류 코드가 erd 6.4 ck_turn_feed_kind와 바이트 단위로 같다'),
    ('VAR_FUTURE',  '3 변수', '변수의 turn_offset ≤ 0이고 이름에 next·future·forward·fwd·lead·t1·plus가 없다'),
    ('VAR_TYPE',    '3 변수', '변수 타입은 name·pct_signed·pct_unsigned·int 넷뿐이다'),
    ('ID_FORMAT',   '4 속성', 'template_id 형식과 길이 ≤ 40'),
    ('ID_KIND',     '4 속성', 'template_id 앞 조각 = lower(kind)'),
    ('ID_DUP',      '4 속성', 'template_id 중복 없음'),
    ('ID_ACTIVE',   '4 속성', '같은 kind.slug의 ACTIVE는 하나'),
    ('KIND',        '4 속성', 'kind는 kinds.csv에 있다'),
    ('TARGET_KIND', '4 속성', 'target은 kinds.csv.target에 든다 (ck_turn_feed_target_kind)'),
    ('DIRECTION',   '4 속성', 'RISE = UP, FALL = DOWN, FADE·NUDGE = 빈 칸, 그 밖에는 UP/DOWN/빈 칸'),
    ('HIDDEN',      '4 속성', '범주 비공개 ⇒ EVENT, 범주·유형·결과·방향 빈 칸, 자리 = {reaction}만'),
    ('EVENT_ATTR',  '4 속성', '범주·유형·결과는 EVENT에서만, 형식·범주 7개 안, 공개 카드는 범주 필수'),
    ('LENGTH',      '4 속성', '리터럴 글자 수 + Σ 자리 max_len ≤ 300 (body VARCHAR(300))'),
    ('CHARSET',     '5 문장', '리터럴은 한글 음절·공백·허용 문장 부호만 (숫자·라틴·한자·전각·이모지·NBSP 금지)'),
    ('NFC',         '5 문장', '문장은 NFC 정규화 상태'),
    ('PERCENT',     '5 문장', '리터럴에 % 없음 (렌더러가 붙인다)'),
    ('UNIT',        '5 문장', '자리 뒤 금액·수준 단위(원·억·조·달러·포인트·선) 없음(공백 한 칸 건너도), 한글 수사 + 년·월·원·달러·포인트 없음'),
    ('BRACE',       '5 문장', '자리를 지운 뒤 { } 가 남지 않음'),
    ('VAR_UNKNOWN', '5 문장', '자리 이름이 variables.csv에 있다'),
    ('VAR_KIND',    '5 문장', '자리의 kinds에 템플릿 종류가 있다'),
    ('VAR_TARGET',  '5 문장', 'asset_name은 target ASSET, group_name은 target GROUP'),
    ('PARTICLE',    '5 문장', '자리 바로 뒤에 붙은 한글은 허용 목록(의·에·에서·도·만·까지·부터·달·위·건·턴·번째·째로)으로 시작할 때만 허용'),
    ('DENY_DATE',   '5 문장', '금지어 DATE 0건'),
    ('DENY_FUTURE', '5 문장', '금지어 FUTURE 0건'),
    ('DENY_PROPER', '5 문장', '금지어 PROPER 0건'),
    ('DENY_ADVICE', '5 문장', '금지어 ADVICE 0건'),
    ('DENY_FACT',   '5 문장', '금지어 FACT 0건'),
    ('SHAPE',       '5 문장', 'ACTIVE 리터럴 12–60자, ACTIVE EVENT의 문장 전체 틀(자리는 타입으로 바꿈)을 UP 소음과 DOWN 소음이 각각 하나 이상 씀'),
    ('COVERAGE',    '6 범위', '종류별 ACTIVE ≥ 3, 필수 조합 표의 조합마다 ≥ 1');

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
-- 기대값은 NULL로 쓰지 않는다 (실제값 NULL과 같아져 헛 PASS가 난다).
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

-- 종류 코드 목록이 erd 6.4 ck_turn_feed_kind와 같은가.
-- 기대값은 erd 6.4의 목록을 글자 그대로 옮긴 것이다: kind IN ('RISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE')
-- 집합 일치(양쪽 차집합 0) + 7개·중복 0 + 글자 수 = 바이트 수(전각·NBSP·제로폭 문자 차단) + ^[A-Z]{1,6}$.
CREATE FUNCTION pg_temp.zz_kinds_erd_ok(p_kinds text[]) RETURNS boolean
LANGUAGE sql AS $$
    SELECT p_kinds IS NOT NULL
       AND cardinality(p_kinds) = 7
       AND (SELECT count(DISTINCT k) FROM unnest(p_kinds) AS k) = 7
       AND NOT EXISTS (SELECT 1 FROM unnest(p_kinds) AS k
                       WHERE k IS NULL OR k <> ALL (ARRAY['RISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE']))
       AND NOT EXISTS (SELECT 1 FROM unnest(ARRAY['RISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE']) AS e
                       WHERE e <> ALL (p_kinds))
       AND NOT EXISTS (SELECT 1 FROM unnest(p_kinds) AS k
                       WHERE octet_length(k) <> char_length(k) OR k !~ '^[A-Z]{1,6}$')
$$;

-- 규칙 함수: (출처 묶음, 대상 id 또는 변수 이름, 규칙 코드) 행을 돌려준다. 같은 (출처, 대상, 코드)가 여러 번 나올 수 있다.
-- 템플릿 규칙은 zz_all, 변수 규칙은 zz_var_all을 본다. 템플릿의 자리 검사는 항상 실제 변수('real')와 대조한다.
-- 문장 검사 대상은 자리를 뺀 리터럴(lit)이다. 자리 = {소문자_이름}.
CREATE FUNCTION pg_temp.zz_violations() RETURNS TABLE (src text, target_id text, rule text)
LANGUAGE sql AS $f$
WITH t AS (
    SELECT a.*,
           regexp_replace(a.text, '\{[a-z][a-z0-9_]*\}', '', 'g') AS lit,
           split_part(a.template_id, '.', 1)                      AS id_kind,
           regexp_replace(a.template_id, '\.v[0-9]+$', '')        AS slug
    FROM zz_all a
), vref AS (   -- 자리 기준표: 실제 파일('real')은 variables.csv, 픽스처('fx')는 SQL 안의 고정 상수 (variables.csv를 고쳐도 픽스처가 흔들리지 않게)
    SELECT 'real' AS scope, name, type, kinds, max_len FROM zz_var_all WHERE src = 'real'
    UNION ALL
    SELECT 'fx', c.name, c.type, c.kinds, c.max_len
    FROM (VALUES ('asset_name', 'name', 'RISE;FALL;ECHO;FADE;NUDGE', 100), ('group_name', 'name', 'RISE;FALL;ECHO;SPREAD;FADE;NUDGE', 50),
                 ('focus_name', 'name', 'FADE', 100), ('ret', 'pct_signed', 'RISE;FALL;ECHO;NUDGE', 8), ('prev_ret', 'pct_signed', 'ECHO', 8),
                 ('group_ret', 'pct_signed', 'RISE;FALL;ECHO;SPREAD', 8), ('rank', 'int', 'RISE;FALL;NUDGE', 3), ('streak', 'int', 'RISE;FALL;ECHO', 3),
                 ('repeat', 'int', 'ECHO', 3), ('attention_rank', 'int', 'FADE', 3), ('cash_weight', 'pct_unsigned', 'NUDGE', 4),
                 ('trade_count', 'int', 'NUDGE', 4), ('idle_turns', 'int', 'NUDGE', 3), ('reaction', 'pct_signed', 'EVENT', 8))
         AS c(name, type, kinds, max_len)
), tvj AS (   -- 템플릿 × 자리 × 변수 정의
    SELECT t.rid, t.src, t.template_id, t.kind, t.target, m[1] AS var, v.name AS v_name, v.kinds AS v_kinds, v.max_len
    FROM t
    CROSS JOIN LATERAL regexp_matches(t.text, '\{([a-z][a-z0-9_]*)\}', 'g') AS m
    LEFT JOIN vref v ON v.scope = CASE WHEN t.src = 'real' THEN 'real' ELSE 'fx' END AND v.name = m[1]
), tshape AS (   -- 문장 전체 틀: 자리를 {타입}으로 바꾼다 ({rank}의 "3"과 {reaction}의 "+3%"는 렌더 모양이 다르다). 모르는 변수는 {?} (VAR_UNKNOWN이 따로 잡는다)
    SELECT t.rid, t.src, sc.scope,
           (SELECT string_agg(p.piece || CASE WHEN m.name IS NULL THEN '' ELSE '{' || coalesce(v.type, '?') || '}' END, '' ORDER BY p.n)
              FROM unnest(regexp_split_to_array(t.text, '\{[a-z][a-z0-9_]*\}')) WITH ORDINALITY AS p(piece, n)
              LEFT JOIN (SELECT mm.m[1] AS name, mm.k
                           FROM regexp_matches(t.text, '\{([a-z][a-z0-9_]*)\}', 'g') WITH ORDINALITY AS mm(m, k)) m ON m.k = p.n
              LEFT JOIN vref v ON v.scope = sc.scope AND v.name = m.name) AS whole
    FROM t CROSS JOIN LATERAL (SELECT CASE WHEN t.src = 'real' THEN 'real' ELSE 'fx' END AS scope) sc
), noise_ref AS (   -- "같은 옷" 기준: 소음(ACTIVE RISE…FADE)이 쓰는 문장 전체 틀과 방향. 실제 파일은 templates.csv에서, 픽스처는 아래 고정 상수
    SELECT 'real' AS scope, ts.whole AS val, t.direction
    FROM tshape ts JOIN t ON t.rid = ts.rid
    WHERE t.src = 'real' AND t.status = 'ACTIVE' AND t.kind IN ('RISE', 'FALL', 'ECHO', 'SPREAD', 'FADE')
    UNION ALL
    SELECT 'fx', c.val, c.direction
    FROM (VALUES ('시장 전반 급변 — 이달 반응 {pct_signed}', 'UP'), ('시장 전반 급변 — 이달 반응 {pct_signed}', 'DOWN'),
                 ('시장 전체가 크게 흔들렸다 — 이달 관련 반응 {pct_signed}', 'UP'), ('시장 전체가 크게 흔들렸다 — 이달 관련 반응 {pct_signed}', 'DOWN'),
                 ('시장 전반 급변 — 이달 반응이 매우 크다', 'UP'), ('시장 전반 급변 — 이달 반응이 매우 크다', 'DOWN'),
                 ('주요 교역국 통상 소식 — 이달 반응 {pct_signed}', 'UP'), ('주요 교역국 통상 소식 — 이달 반응 {pct_signed}', 'DOWN'),
                 ('주요 교역국 통상 소식 — 이달 반응 {name}', 'UP'), ('주요 교역국 통상 소식 — 이달 반응 {name}', 'DOWN'),
                 ('주요국 정치 이슈 부각 — 이달 관련 반응 {pct_signed}', 'UP'), ('주요국 정치 이슈 부각 — 이달 관련 반응 {pct_signed}', 'DOWN'),
                 ('시장 전반 급변 — 이달 순위 {int}', 'UP'), ('시장 전반 급변 — 이달 순위 {int}', 'DOWN'),
                 ('원자재 가격 관련 소식 — 이달 반응 {pct_signed}', 'UP'),                                   -- UP only
                 ('금융권 신용 이슈 부각 — 이달 반응 {pct_signed}', 'DOWN'),                                  -- DOWN only
                 ('경제 정책 관련 소식 — 이달 관련 반응 {pct_signed}', 'UP'), ('경제 정책 관련 소식 — 이달 관련 반응 {pct_signed}', 'UP')) AS c(val, direction)   -- UP twice
), need (label, kind, target, direction, hidden, cat) AS (   -- 필수 조합 표
    VALUES ('RISE ASSET UP',    'RISE',   'ASSET', 'UP',   false, NULL),
           ('RISE GROUP UP',    'RISE',   'GROUP', 'UP',   false, NULL),
           ('FALL ASSET DOWN',  'FALL',   'ASSET', 'DOWN', false, NULL),
           ('FALL GROUP DOWN',  'FALL',   'GROUP', 'DOWN', false, NULL),
           ('ECHO ASSET UP',    'ECHO',   'ASSET', 'UP',   false, NULL),
           ('ECHO ASSET DOWN',  'ECHO',   'ASSET', 'DOWN', false, NULL),
           ('SPREAD GROUP UP',  'SPREAD', 'GROUP', 'UP',   false, NULL),
           ('SPREAD GROUP DOWN','SPREAD', 'GROUP', 'DOWN', false, NULL),
           ('FADE ASSET',       'FADE',   'ASSET', NULL,   false, NULL),
           ('EVENT GEOPOLITICS','EVENT',  'NONE',  NULL,   false, 'GEOPOLITICS'),
           ('EVENT TRADE',      'EVENT',  'NONE',  NULL,   false, 'TRADE'),
           ('EVENT POLITICS',   'EVENT',  'NONE',  NULL,   false, 'POLITICS'),
           ('EVENT SUPPLY_SHOCK','EVENT', 'NONE',  NULL,   false, 'SUPPLY_SHOCK'),
           ('EVENT FINANCIAL',  'EVENT',  'NONE',  NULL,   false, 'FINANCIAL'),
           ('EVENT POLICY',     'EVENT',  'NONE',  NULL,   false, 'POLICY'),
           ('EVENT HEALTH',     'EVENT',  'NONE',  NULL,   false, 'HEALTH'),
           ('EVENT 범주 비공개', 'EVENT',  'NONE',  NULL,   true,  NULL),
           ('NUDGE ASSET',      'NUDGE',  'ASSET', NULL,   false, NULL),
           ('NUDGE NONE',       'NUDGE',  'NONE',  NULL,   false, NULL)
)
-- 5 문장 규칙 ---------------------------------------------------------------
SELECT src, template_id, 'CHARSET' FROM t
 WHERE normalize(translate(lit, '{}%', ''), NFC) !~ '^[가-힣 ,.·—…!?()~''"/-]*$'      -- { } % 는 BRACE·PERCENT가 따로 본다
UNION ALL SELECT src, template_id, 'NFC' FROM t WHERE NOT (text IS NFC NORMALIZED)
UNION ALL SELECT src, template_id, 'PERCENT' FROM t WHERE position('%' in lit) > 0
UNION ALL SELECT src, template_id, 'UNIT' FROM t                                       -- 자리 뒤 단위: 붙으면 뒤가 무엇이든, 공백 한 칸 건너면 단위가 낱말로 끝날 때나 '까지' 같은 꼬리가 올 때
 WHERE text ~ '\}(원|억|조|달러|포인트)'
    OR text ~ '\}\s+(원|억|조|달러|포인트)([^가-힣]|$|까지|이상|이하|대|쯤|가량|안팎)'
    OR text ~ '\}선([^가-힣]|$)'
    OR lit  ~ '[영일이삼사오육칠팔구십백천만억조]{2,}(년|월|원|달러|포인트)'          -- 한글 수사: 이천팔년, 십만원, 삼조원 (한 음절 수사는 '구원'·'일원' 오탐이라 걸지 않는다)
    OR lit  ~ '[십백천만억조](원|달러|포인트)'
UNION ALL SELECT src, template_id, 'BRACE' FROM t WHERE lit ~ '[{}]'
UNION ALL SELECT src, template_id, 'VAR_UNKNOWN' FROM tvj WHERE v_name IS NULL
UNION ALL SELECT src, template_id, 'VAR_KIND' FROM tvj
 WHERE v_name IS NOT NULL AND kind IN (SELECT k.kind FROM zz_kinds k) AND NOT (kind = ANY (string_to_array(v_kinds, ';')))
UNION ALL SELECT src, template_id, 'VAR_TARGET' FROM tvj                              -- 종류가 맞을 때만 대상을 본다 (VAR_KIND와 겹치지 않게)
 WHERE v_name IS NOT NULL AND kind = ANY (string_to_array(v_kinds, ';'))
   AND ((var = 'asset_name' AND target <> 'ASSET') OR (var = 'group_name' AND target <> 'GROUP'))
UNION ALL SELECT src, template_id, 'PARTICLE' FROM t WHERE text ~ '\}(?!(의|에|에서|도|만|까지|부터|달|위|건|턴|번째|째로))[가-힣]'   -- allow list: Hangul glued to a slot must start with one of these
UNION ALL SELECT t.src, t.template_id, 'DENY_' || d.reason FROM t
  JOIN (SELECT 'real' AS scope, word, reason FROM zz_deny UNION ALL SELECT 'fx', word, reason FROM zz_deny_fx) d
    ON d.scope = CASE WHEN t.src = 'real' THEN 'real' ELSE 'fx' END AND position(d.word in t.lit) > 0
UNION ALL SELECT src, template_id, 'SHAPE' FROM t WHERE status = 'ACTIVE' AND char_length(lit) NOT BETWEEN 12 AND 60
UNION ALL SELECT t.src, t.template_id, 'SHAPE' FROM t JOIN tshape ts ON ts.rid = t.rid     -- 사건 카드의 문장 전체 틀(자리는 타입)을 오른 달 소음과 내린 달 소음이 각각 하나 이상 쓴다
 WHERE t.kind = 'EVENT' AND t.status = 'ACTIVE'
   AND (SELECT count(DISTINCT r.direction) FROM noise_ref r
         WHERE r.scope = ts.scope AND r.val = ts.whole AND r.direction IN ('UP', 'DOWN')) < 2
-- 4 템플릿 속성 -------------------------------------------------------------
UNION ALL SELECT src, template_id, 'ID_FORMAT' FROM t
 WHERE template_id !~ '^(rise|fall|echo|spread|fade|event|nudge)\.[a-z][a-z0-9_]*\.v[1-9][0-9]*$' OR char_length(template_id) > 40
UNION ALL SELECT src, template_id, 'ID_KIND' FROM t
 WHERE template_id ~ '^(rise|fall|echo|spread|fade|event|nudge)\.[a-z][a-z0-9_]*\.v[1-9][0-9]*$'
   AND kind IN (SELECT k.kind FROM zz_kinds k) AND id_kind <> lower(kind)
UNION ALL SELECT src, template_id, 'ID_DUP' FROM (SELECT src, template_id, count(*) OVER (PARTITION BY src, template_id) AS c FROM t) x WHERE c > 1
UNION ALL SELECT src, template_id, 'ID_ACTIVE' FROM (SELECT src, template_id, count(*) OVER (PARTITION BY src, slug) AS c FROM t WHERE status = 'ACTIVE') x WHERE c > 1
UNION ALL SELECT src, template_id, 'KIND' FROM t WHERE kind NOT IN (SELECT k.kind FROM zz_kinds k)
UNION ALL SELECT t.src, t.template_id, 'TARGET_KIND' FROM t JOIN zz_kinds k ON k.kind = t.kind
 WHERE NOT (t.target = ANY (string_to_array(k.target, ';')))
UNION ALL SELECT src, template_id, 'DIRECTION' FROM t
 WHERE (kind = 'RISE' AND direction IS DISTINCT FROM 'UP')
    OR (kind = 'FALL' AND direction IS DISTINCT FROM 'DOWN')
    OR (kind IN ('FADE', 'NUDGE') AND direction IS NOT NULL)
    OR (direction IS NOT NULL AND direction NOT IN ('UP', 'DOWN'))
UNION ALL SELECT t.src, t.template_id, 'HIDDEN' FROM t
 WHERE coalesce(t.category_hidden, false)
   AND (t.kind <> 'EVENT' OR t.event_category IS NOT NULL OR t.event_type IS NOT NULL OR t.outcome IS NOT NULL OR t.direction IS NOT NULL
        OR (SELECT string_agg(DISTINCT m[1], ',' ORDER BY m[1]) FROM regexp_matches(t.text, '\{([a-z][a-z0-9_]*)\}', 'g') AS m)
           IS DISTINCT FROM 'reaction')
UNION ALL SELECT src, template_id, 'EVENT_ATTR' FROM t
 WHERE (kind <> 'EVENT' AND (event_category IS NOT NULL OR event_type IS NOT NULL OR outcome IS NOT NULL))
    OR (event_category IS NOT NULL AND event_category NOT IN ('GEOPOLITICS','TRADE','POLITICS','SUPPLY_SHOCK','FINANCIAL','POLICY','HEALTH'))
    OR (event_type IS NOT NULL AND event_type !~ '^[A-Z][A-Z0-9_]*$')
    OR (outcome IS NOT NULL AND outcome !~ '^[A-Z][A-Z0-9_]*$')
    OR (kind = 'EVENT' AND NOT coalesce(category_hidden, false) AND event_category IS NULL)
UNION ALL SELECT t.src, t.template_id, 'LENGTH' FROM t
 WHERE char_length(t.lit) + coalesce((SELECT sum(j.max_len) FROM tvj j WHERE j.rid = t.rid), 0) > 300
-- 3 변수 ----------------------------------------------------------------------
UNION ALL SELECT src, name, 'VAR_FUTURE' FROM zz_var_all WHERE turn_offset > 0 OR name ~ '(next|future|forward|fwd|lead|t1|plus)'
UNION ALL SELECT src, name, 'VAR_TYPE' FROM zz_var_all WHERE type IS NULL OR type NOT IN ('name', 'pct_signed', 'pct_unsigned', 'int')
-- 6 적용 범위 -----------------------------------------------------------------
UNION ALL
SELECT s.src, n.label, 'COVERAGE' FROM zz_cov_src s CROSS JOIN need n
 WHERE NOT EXISTS (SELECT 1 FROM t
                   WHERE t.src = s.src AND t.status = 'ACTIVE' AND t.kind = n.kind AND t.target = n.target
                     AND (n.direction IS NULL OR t.direction IS NOT DISTINCT FROM n.direction)
                     AND coalesce(t.category_hidden, false) = n.hidden
                     AND (n.cat IS NULL OR t.event_category = n.cat))
UNION ALL
SELECT s.src, 'ACTIVE ' || k.kind || ' < 3', 'COVERAGE' FROM zz_cov_src s CROSS JOIN zz_kinds k
 WHERE (SELECT count(*) FROM t WHERE t.src = s.src AND t.status = 'ACTIVE' AND t.kind = k.kind) < 3
$f$;

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL이면 1절 이후 결과를 믿지 않는다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17, HEADER MATCH는 15+)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('client_encoding (\copy ENCODING UTF8와 맞춘다)', 'UTF8', current_setting('client_encoding')),
    ('IS NFC NORMALIZED: 풀어 쓴 자모(NFD)는 false', 'true', (NOT (normalize('한', NFD) IS NFC NORMALIZED))::text),
    ('IS NFC NORMALIZED: 완성형 한글은 true', 'true', ('한' IS NFC NORMALIZED)::text),
    ('정규식 [가-힣]이 완성형 한글을 받는다', 'true', ('한글' ~ '^[가-힣]+$')::text),
    ('정규식 [가-힣]이 풀어 쓴 자모를 받지 않는다', 'false', (normalize('한', NFD) ~ '[가-힣]')::text),
    ('정규식 [가-힣]이 라틴·숫자·한자를 받지 않는다', 'false', ('A1美' ~ '[가-힣]')::text),
    ('정규식 문장 부호 묶음이 em 대시·가운뎃점·말줄임표를 받는다', 'true', ('—·…' ~ '^[,.·—…!?()~''"/-]+$')::text)
) AS x(item, expected, actual);

-- 정보 행: 현재 DB 이름 (FAIL 아님)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('0 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);

-- 0절 결과를 먼저 보여 주고, FAIL이 있으면 CSV를 읽기 전에 멈춘다 (표식은 1로 남아 ON_ERROR_STOP 없이도 마지막 줄이 FAIL이다).
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
        RAISE EXCEPTION 'P0-13 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 전제를 맞춘 뒤 다시 실행한다)', v_fail;
    END IF;
END $$;

-- 1 적재 -------------------------------------------------------------------
-- 저장소 루트에서 실행한다 (상대 경로). HEADER MATCH: 첫 줄의 열 이름이 위 임시 테이블의 열과 이름·순서까지 같아야 한다.
-- BOM이 있으면 첫 열 이름이 달라져 여기서 오류가 난다. P0-18은 이 4줄과 위의 CREATE TEMP TABLE 정의를 그대로 복사한다.
\copy zz_kinds FROM 'feed/kinds.csv' WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8')
\copy zz_var   FROM 'feed/variables.csv' WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8')
\copy zz_tpl   FROM 'feed/templates.csv' WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8')
\copy zz_deny  FROM 'feed/denylist.csv' WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8')

INSERT INTO zz_all (src, template_id, kind, status, target, direction, strength, event_category, event_type, outcome, category_hidden, text)
SELECT 'real', template_id, kind, status, target, direction, strength, event_category, event_type, outcome, category_hidden, text FROM zz_tpl;
INSERT INTO zz_var_all (src, name, type, turn_offset, kinds, max_len)
SELECT 'real', name, type, turn_offset, kinds, max_len FROM zz_var;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 적재', x.item, '>= 1', x.n::text, x.n >= 1
FROM (VALUES ('kinds.csv 행 수', (SELECT count(*) FROM zz_kinds)),
             ('variables.csv 행 수', (SELECT count(*) FROM zz_var)),
             ('templates.csv 행 수', (SELECT count(*) FROM zz_tpl)),
             ('denylist.csv 행 수', (SELECT count(*) FROM zz_deny))) AS x(item, n);

SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
FROM zz_check_result
WHERE section = '1 적재'
ORDER BY seq;

DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM zz_check_result WHERE section = '1 적재';
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-13 적재 실패: 1절 FAIL % 건 (빈 CSV)', v_fail;
    END IF;
END $$;

-- 실제 파일의 규칙 위반을 한 번 모은다 (7절 픽스처가 들어가기 전의 zz_all·zz_var_all 전부가 'real'이다)
CREATE TEMP TABLE zz_viol_real AS SELECT * FROM pg_temp.zz_violations();

-- 2 종류 코드 (E1) ---------------------------------------------------------
CALL pg_temp.check_eq('2 종류', 'kinds.csv 행 수 (7)', '7', pg_temp.val($q$SELECT count(*)::text FROM zz_kinds$q$));
CALL pg_temp.check_eq('2 종류', 'kind 중복 없음', '7', pg_temp.val($q$SELECT count(DISTINCT kind)::text FROM zz_kinds$q$));
CALL pg_temp.check_eq('2 종류', 'KINDS_ERD: 종류 코드 7개가 erd 6.4 ck_turn_feed_kind와 바이트 단위로 같다',
    'true', pg_temp.val($q$SELECT pg_temp.zz_kinds_erd_ok(array_agg(kind))::text FROM zz_kinds$q$));
-- erd 6.4 ck_turn_feed_target_kind를 옮긴 기대값 (정렬한 ';' 목록으로 비교)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '2 종류', 'target: ' || e.kind || ' (erd 6.4 ck_turn_feed_target_kind)', e.target, a.target, e.target IS NOT DISTINCT FROM a.target
FROM (VALUES ('RISE', 'ASSET;GROUP'), ('FALL', 'ASSET;GROUP'), ('ECHO', 'ASSET;GROUP'), ('FADE', 'ASSET;GROUP'),
             ('SPREAD', 'GROUP'), ('EVENT', 'NONE'), ('NUDGE', 'ASSET;GROUP;NONE')) AS e(kind, target)
LEFT JOIN (SELECT kind, (SELECT string_agg(x, ';' ORDER BY x) FROM unnest(string_to_array(target, ';')) AS x) AS target FROM zz_kinds) a
       ON a.kind = e.kind;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '2 종류', 'screen: ' || kind || ' (NUDGE → ALERT, 나머지 → FEED)', CASE WHEN kind = 'NUDGE' THEN 'ALERT' ELSE 'FEED' END, screen,
       screen IS NOT DISTINCT FROM CASE WHEN kind = 'NUDGE' THEN 'ALERT' ELSE 'FEED' END
FROM zz_kinds;
CALL pg_temp.check_eq('2 종류', 'name_ko는 한글(NFC)이다', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_kinds WHERE name_ko IS NULL OR name_ko !~ '^[가-힣 ]+$' OR NOT (name_ko IS NFC NORMALIZED)$q$));

-- 3 변수 (E2) --------------------------------------------------------------
CALL pg_temp.check_eq('3 변수', 'variables.csv 행 수 (14)', '14', pg_temp.val($q$SELECT count(*)::text FROM zz_var$q$));
CALL pg_temp.check_eq('3 변수', '이름 형식 ^[a-z][a-z0-9_]{0,29}$ 와 중복 없음', '0',
    pg_temp.val($q$SELECT (count(*) FILTER (WHERE name IS NULL OR name !~ '^[a-z][a-z0-9_]{0,29}$') + (count(*) - count(DISTINCT name)))::text FROM zz_var$q$));
CALL pg_temp.check_eq('3 변수', 'kinds 목록은 kinds.csv의 종류만 담는다', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_var v CROSS JOIN LATERAL unnest(string_to_array(v.kinds, ';')) AS k
                   WHERE k NOT IN (SELECT kind FROM zz_kinds)$q$));
CALL pg_temp.check_eq('3 변수', 'turn_offset·max_len이 비어 있지 않고 max_len ≥ 1', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_var WHERE turn_offset IS NULL OR max_len IS NULL OR max_len < 1 OR source IS NULL OR btrim(source) = ''$q$));
CALL pg_temp.check_eq('3 변수', 'type·kinds가 비어 있지 않다 (빈 type은 VAR_TYPE, 빈 kinds는 종류 제한이 사라진다)', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_var WHERE type IS NULL OR btrim(type) = '' OR kinds IS NULL OR btrim(kinds) = ''$q$));

-- 4 템플릿 속성 (E3) -------------------------------------------------------
-- 규칙 코드로 잡히지 않는 형식 검사: 값 집합과 빈 칸
CALL pg_temp.check_eq('4 속성', 'status는 ACTIVE/RETIRED', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_tpl WHERE status IS DISTINCT FROM 'ACTIVE' AND status IS DISTINCT FROM 'RETIRED'$q$));
CALL pg_temp.check_eq('4 속성', 'strength는 1–3 또는 빈 칸', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_tpl WHERE strength IS NOT NULL AND strength NOT BETWEEN 1 AND 3$q$));
CALL pg_temp.check_eq('4 속성', 'category_hidden이 비어 있지 않고 target은 ASSET/GROUP/NONE', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_tpl WHERE category_hidden IS NULL OR target IS NULL OR target NOT IN ('ASSET', 'GROUP', 'NONE')$q$));
CALL pg_temp.check_eq('4 속성', 'template_id·kind·text가 비어 있지 않고 text는 한 줄이며 앞뒤 공백이 없고 공백이 두 칸 이어지지 않는다', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_tpl
                   WHERE template_id IS NULL OR kind IS NULL OR text IS NULL OR text <> btrim(text) OR text ~ '[\n\r\t]' OR text ~ '  ' OR text = ''$q$));
CALL pg_temp.check_eq('4 속성', '고정 문구 금지: 사건 카드 문장에 "범주 비공개"가 없다 (결정 5-B, 소음은 이 말을 쓰지 않는다)', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_tpl WHERE position('범주 비공개' in text) > 0$q$));

-- 5 문장 규칙·금지어 (E4·E5) ------------------------------------------------
CALL pg_temp.check_eq('5 문장', 'denylist reason은 DATE/FUTURE/PROPER/ADVICE/FACT', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_deny WHERE reason IS NULL OR reason NOT IN ('DATE', 'FUTURE', 'PROPER', 'ADVICE', 'FACT')$q$));
CALL pg_temp.check_eq('5 문장', 'denylist word는 한글(NFC)·공백뿐, 앞뒤 공백 없음, 중복 없음 (한 단어가 두 이유에 걸리지 않게)', '0',
    pg_temp.val($q$SELECT (count(*) FILTER (WHERE word IS NULL OR word !~ '^[가-힣]([가-힣 ]*[가-힣])?$' OR NOT (word IS NFC NORMALIZED))
                         + (count(*) - count(DISTINCT word)))::text FROM zz_deny$q$));

CALL pg_temp.check_eq('5 문장', 'denylist.csv가 픽스처용 고정 금지어(단어·이유)를 모두 담는다', '0',
    pg_temp.val($q$SELECT count(*)::text FROM zz_deny_fx f WHERE NOT EXISTS (SELECT 1 FROM zz_deny d WHERE d.word = f.word AND d.reason = f.reason)$q$));

-- 규칙별 위반 0행 (KINDS_ERD는 2절에서 이미 봤다). 위반이 있으면 실제값에 개수와 대상 id를 적는다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT r.sect, r.code || ': ' || r.descr, '0',
       count(v.*)::text || CASE WHEN count(v.*) > 0 THEN ' (' || string_agg(DISTINCT v.target_id, ', ') || ')' ELSE '' END,
       count(v.*) = 0
FROM zz_rule r
LEFT JOIN zz_viol_real v ON v.rule = r.code AND v.src = 'real'
WHERE r.code <> 'KINDS_ERD'
GROUP BY r.sect, r.code, r.descr
ORDER BY r.sect, r.code;

-- 6 적용 범위 --------------------------------------------------------------
-- COVERAGE 위반 0행은 위에서 봤다. 아래는 눈으로 보는 개수 (정보 행, FAIL 아님).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 범위', 'ACTIVE 템플릿 수 (정보)', '(정보)',
       string_agg(k.kind || ' ' || (SELECT count(*) FROM zz_tpl t WHERE t.kind = k.kind AND t.status = 'ACTIVE'), ', ' ORDER BY k.kind)
       || ' / 합 ' || (SELECT count(*) FROM zz_tpl WHERE status = 'ACTIVE'), true
FROM zz_kinds k;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 범위', '범주 비공개 ACTIVE 사건 카드 수 (>= 1)', '>= 1', count(*)::text, count(*) >= 1
FROM zz_tpl WHERE status = 'ACTIVE' AND kind = 'EVENT' AND category_hidden;

-- 7 음성 픽스처 (실패해야 정상) ------------------------------------------------
-- 사례마다 정확히 그 규칙 코드 하나만 걸려야 한다 (한 사례에 조건 하나). 예외: 자리 바로 뒤에 붙은 단위(원·포인트)는 허용 목록 밖이라 PARTICLE도 함께 걸린다(기대값 'PARTICLE,UNIT'). 묶음(grp)이 같은 행은 한 묶음으로 규칙 함수를 지난다.
-- 기본 행은 모든 규칙을 통과하는 RISE·ASSET·UP 템플릿이고, 사례는 바꿀 열만 적는다.
CREATE TEMP TABLE zz_fixture (
    fx_seq integer GENERATED ALWAYS AS IDENTITY,
    grp text NOT NULL, expect text NOT NULL, descr text NOT NULL,
    template_id text NOT NULL DEFAULT 'rise.zz.v1',
    kind text NOT NULL DEFAULT 'RISE',
    status text NOT NULL DEFAULT 'ACTIVE',
    target text NOT NULL DEFAULT 'ASSET',
    direction text DEFAULT 'UP',
    strength smallint, event_category text, event_type text, outcome text,
    category_hidden boolean NOT NULL DEFAULT false,
    text text NOT NULL DEFAULT '{asset_name} 이달 {ret} — 자금 몰려'
);
CREATE TEMP TABLE zz_var_fixture (
    grp text NOT NULL, expect text NOT NULL, descr text NOT NULL,
    name text NOT NULL DEFAULT 'zz_ok', type text DEFAULT 'int', turn_offset integer NOT NULL DEFAULT 0,
    kinds text NOT NULL DEFAULT 'RISE', max_len integer NOT NULL DEFAULT 3
);

-- 7.1 문장 규칙
INSERT INTO zz_fixture (grp, expect, descr, text) VALUES
    ('s01', 'CHARSET', '2008년 (연도 숫자)',           '2008년 이후 최대 상승 — {asset_name} {ret}'),
    ('s02', 'CHARSET', 'Fed (영문 고유명사)',           '{asset_name} 이달 {ret} — Fed 영향 확산'),
    ('s03', 'CHARSET', '美 (한자)',                    '美 증시 훈풍에 {asset_name} 이달 {ret}'),
    ('s04', 'CHARSET', '이모지',                       '{asset_name} 이달 {ret} — 자금 몰려' || U&'\+01F600'),
    ('s05', 'CHARSET', 'NBSP (보이지 않는 공백)',       '{asset_name}' || U&'\00A0' || '이달 {ret} — 자금 몰려'),
    ('s06', 'CHARSET', '전각 문자',                    '{asset_name} 이달 {ret} — 자금 몰려' || U&'\FF01'),
    ('s07', 'NFC',     'NFD 한글 (풀어 쓴 자모)',       normalize('{asset_name} 이달 {ret} — 자금 몰려', NFD)),
    ('s08', 'PERCENT', '{ret}% (렌더러가 붙이는 %)',    '{asset_name} 이달 {ret}% 급등, 자금 몰려'),
    ('s09', 'PARTICLE,UNIT',    '{ret}원 (자리 뒤 단위)',        '{asset_name} 이달 {ret}원 급등 — 자금 몰려'),
    ('s10', 'PARTICLE,UNIT',    '{rank}포인트 (자리 뒤 단위)',   '상승률 {rank}포인트 {asset_name}, 추가 상승 기대'),
    ('s11', 'BRACE',   '{종목} (한글 이름의 자리)',      '{종목} 이달 {ret} — 자금 몰려'),
    ('s11b', 'BRACE',  '{ (여는 괄호만 남음)',          '{asset_name} 이달 { {ret} — 자금 몰려'),
    ('s12', 'VAR_UNKNOWN', '{price} (없는 변수)',       '{asset_name} 이달 {price} — 자금 몰려'),
    ('s13', 'PARTICLE', '{asset_name}이 (받침 의존 조사)', '{asset_name}이 급등 소식에 시장 술렁 — 이달 {ret}'),
    ('s14', 'PARTICLE', '{asset_name}을 (받침 의존 조사)', '{asset_name}을 향한 시선 — 이달 {ret} 강세'),
    ('s39', 'PARTICLE', '{asset_name}이다 (목록 밖: 서술격 조사)',  '{asset_name}이다 — 이달 {ret} 강세, 자금 몰려'),
    ('s40', 'PARTICLE', '{asset_name}이며 (목록 밖: 연결 어미)',    '{asset_name}이며 이달 {ret} 강세 — 자금 몰려'),
    ('s41', 'PARTICLE', '{asset_name}과의 (목록 밖: 받침 의존 조사 + 의)', '{asset_name}과의 격차 확대 — 이달 {ret} 강세'),
    ('s16', 'DENY_DATE',   '작년',                     '작년 대비 {asset_name} 이달 {ret} — 자금 몰려'),
    ('s17', 'DENY_FUTURE', '다음 달',                  '{asset_name} 이달 {ret} — 다음 달에도 자금 몰려'),
    ('s18', 'DENY_FUTURE', '향후',                     '{asset_name} 이달 {ret} — 향후 흐름도 밝다'),
    ('s19', 'DENY_FUTURE', '예정',                     '{asset_name} 이달 {ret} — 추가 자금 유입 예정'),
    ('s20', 'DENY_PROPER', '연준',                     '연준 발언 여파에 {asset_name} 이달 {ret} 급등'),
    ('s21', 'DENY_ADVICE', '매수하세요',               '지금 {asset_name} 매수하세요 — 이달 {ret} 강세'),
    ('s22', 'DENY_FACT',   '인수',                     '{asset_name} 인수 소식에 이달 {ret} — 자금 몰려'),
    ('s28', 'DENY_FUTURE', '다음 주 (빠졌던 구체 시점)',    '{asset_name} 이달 {ret} — 다음 주 자금 몰려'),
    ('s29', 'DENY_DATE',   '연말 (달력 위치)',              '{asset_name} 이달 {ret} — 연말 자금 몰려'),
    ('s30', 'DENY_DATE',   '유월 (한글 월 이름)',            '{asset_name} 이달 {ret} — 유월 자금 몰려'),
    ('s31', 'UNIT',        '이천팔년 (한글 수사 + 년)',       '{asset_name} 이달 {ret} — 이천팔년 이후 최대'),
    ('s32', 'UNIT',        '십만원 (한글 수사 + 원)',         '{asset_name} 이달 {ret} — 십만원 돌파'),
    ('s33', 'UNIT',        '{rank} 포인트 (공백 건넌 단위)',   '상승률 {rank} 포인트 {asset_name}, 추가 상승 기대'),
    ('s34', 'PARTICLE,UNIT',        '{rank}원까지 (단위 뒤에 한글)',    '상승률 {rank}원까지 {asset_name}, 추가 상승 기대'),
    ('s42', 'DENY_FUTURE', '앞두며 (줄기 앞두)',             '{asset_name} 이달 {ret} — 발표를 앞두며 자금 몰려'),
    ('s43', 'DENY_FUTURE', '앞두고 (줄기 앞두)',             '{asset_name} 이달 {ret} — 발표를 앞두고 자금 몰려'),
    ('s44', 'DENY_FUTURE', '앞뒀다 (줄기 앞뒀)',             '{asset_name} 이달 {ret} — 발표를 앞뒀다는 소식'),
    ('s45', 'DENY_FUTURE', '앞둘 (줄기 앞둘)',               '{asset_name} 이달 {ret} — 발표를 앞둘 때까지 강세'),
    ('s46', 'DENY_FUTURE', '다가오는 (줄기 다가오)',          '{asset_name} 이달 {ret} — 다가오는 장세에 자금 몰려'),
    ('s47', 'DENY_FUTURE', '다가온다 (줄기 다가온)',          '{asset_name} 이달 {ret} — 반등이 다가온다는 기대');
INSERT INTO zz_fixture (grp, expect, descr, text, status) VALUES
    ('s23', 'SHAPE', '7자 문장 (너무 짧음)',  '{asset_name} 급등', 'ACTIVE');
INSERT INTO zz_fixture (grp, expect, descr, text) VALUES
    ('s24', 'LENGTH', '이름 자리 4개 (4 × 100 + 8 > 300)', '{asset_name} {asset_name} {asset_name} {asset_name} 이달 {ret} 급등 소식에 자금 몰려');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, direction, text) VALUES
    ('s15', 'PARTICLE', '{focus_name}로 (받침 의존 조사)', 'fade.zz.v1', 'FADE', NULL, '관심 식어 거래 한산 — 시선은 {focus_name}로 이동');
-- the only difference from the fixed whole-sentence shape is one '~'
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, text) VALUES
    ('s25', 'SHAPE', '고정 틀과 물결표 하나만 다른 EVENT (전체 틀 불일치)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'TRADE', '주요 교역국 통상 소식 — 이달 반응~ {reaction}');

INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, text) VALUES
    ('s26', 'SHAPE', '전체 틀이 소음에 없는 공개 EVENT (꼬리가 다름)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'TRADE', '주요 교역국 통상 소식 — 시장은 {reaction}');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, category_hidden, text) VALUES
    ('s27', 'SHAPE', '꼬리는 소음과 같아도 전체 틀이 소음에 없는 범주 비공개 EVENT', 'event.zz.v1', 'EVENT', 'NONE', NULL, true, '시장 전반 급변 — 이달 관련 반응 {reaction}');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, text) VALUES
    ('s35', 'SHAPE', '문장 전체 틀을 UP 소음만 쓰는 공개 EVENT (방향 짝 없음)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'SUPPLY_SHOCK', '원자재 가격 관련 소식 — 이달 반응 {reaction}'),
    ('s36', 'SHAPE', '글자는 같아도 자리 타입이 다른 EVENT ({reaction}의 +3% 대 {int}의 3)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'TRADE', '시장 전반 급변 — 이달 순위 {reaction}'),
    ('s37', 'SHAPE', '문장 전체 틀을 DOWN 소음만 쓰는 공개 EVENT (UP 짝 없음)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'FINANCIAL', '금융권 신용 이슈 부각 — 이달 반응 {reaction}'),
    ('s38', 'SHAPE', '문장 전체 틀을 UP 소음 두 개가 쓰는 공개 EVENT (같은 방향 두 행)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'POLICY', '경제 정책 관련 소식 — 이달 관련 반응 {reaction}');

-- 7.2 속성 규칙
INSERT INTO zz_fixture (grp, expect, descr, template_id) VALUES
    ('a01', 'ID_FORMAT', 'Rise.zz.v1 (대문자)', 'Rise.zz.v1'),
    ('a02', 'ID_FORMAT', 'rise.zz.v0 (버전 0)',  'rise.zz.v0'),
    ('a03', 'ID_FORMAT', '40자 초과',            'rise.' || repeat('a', 40) || '.v1');
INSERT INTO zz_fixture (grp, expect, descr, kind, direction, text) VALUES
    ('a04', 'ID_KIND', 'id는 rise인데 kind는 FALL', 'FALL', 'DOWN', '{asset_name} 이달 {ret} 급락, 공포 확산');
INSERT INTO zz_fixture (grp, expect, descr, status) VALUES
    ('a05', 'ID_DUP', '같은 id 둘 (하나는 RETIRED)', 'ACTIVE'),
    ('a05', 'ID_DUP', '같은 id 둘 (하나는 RETIRED)', 'RETIRED');
INSERT INTO zz_fixture (grp, expect, descr, template_id) VALUES
    ('a06', 'ID_ACTIVE', '같은 slug의 ACTIVE 둘 (v1·v2)', 'rise.zz.v1'),
    ('a06', 'ID_ACTIVE', '같은 slug의 ACTIVE 둘 (v1·v2)', 'rise.zz.v2');
INSERT INTO zz_fixture (grp, expect, descr, kind) VALUES
    ('a07', 'KIND', 'RALLY (없는 종류)', 'RALLY');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, text) VALUES
    ('a08', 'TARGET_KIND', 'SPREAD + ASSET', 'spread.zz.v1', 'SPREAD', 'ASSET', '업종 전반으로 매수세 번져, 평균 {group_ret} 상승');
INSERT INTO zz_fixture (grp, expect, descr, direction) VALUES
    ('a09', 'DIRECTION', 'RISE + DOWN', 'DOWN'),
    ('a10', 'DIRECTION', 'RISE + 빈 칸', NULL),
    ('a11', 'DIRECTION', 'RISE + SIDEWAYS (UP/DOWN 밖)', 'SIDEWAYS');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, category_hidden, text) VALUES
    ('a12', 'HIDDEN', '비공개 + 범주', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'TRADE', true, '시장 전반 급변 — 이달 반응 {reaction}');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, category_hidden, text) VALUES
    ('a13', 'HIDDEN', '비공개 + 자리가 reaction이 아님 (자리 없음)', 'event.zz.v1', 'EVENT', 'NONE', NULL, true, '시장 전반 급변 — 이달 반응이 매우 크다');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, category_hidden, text) VALUES
    ('a14', 'HIDDEN', '비공개인데 EVENT가 아님', 'rise.zz.v1', 'RISE', 'ASSET', 'UP', true, '{asset_name} 이달 {ret} — 자금 몰려');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, direction, event_category, text) VALUES
    ('a15', 'EVENT_ATTR', 'FALL의 event_category', 'fall.zz.v1', 'FALL', 'DOWN', 'TRADE', '{asset_name} 이달 {ret} 급락, 공포 확산');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, text) VALUES
    ('a16', 'EVENT_ATTR', 'EVENT의 범주가 7개 밖', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'WEATHER', '주요 교역국 통상 소식 — 이달 반응 {reaction}'),
    ('a17', 'EVENT_ATTR', '공개 EVENT인데 범주 없음', 'event.zz.v1', 'EVENT', 'NONE', NULL, NULL, '주요 교역국 통상 소식 — 이달 반응 {reaction}');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, event_type, text) VALUES
    ('a18', 'EVENT_ATTR', 'event_type 형식 위반 (소문자)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'TRADE', 'tariff', '주요 교역국 통상 소식 — 이달 반응 {reaction}');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, text) VALUES
    ('v01', 'VAR_KIND', 'EVENT에 {asset_name}', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'TRADE', '주요 교역국 통상 소식 — 이달 반응 {asset_name}');
INSERT INTO zz_fixture (grp, expect, descr, target, text) VALUES
    ('v02', 'VAR_TARGET', 'GROUP 템플릿에 {asset_name}', 'GROUP', '{asset_name} 관련주 동반 상승, 평균 {group_ret} 안팎'),
    ('v03', 'VAR_TARGET', 'ASSET 템플릿에 {group_name}', 'ASSET', '{group_name} 관련주 동반 상승, 평균 {group_ret} 안팎');

-- 7.3 변수 규칙
INSERT INTO zz_var_fixture (grp, expect, descr, name, turn_offset) VALUES
    ('w01', 'VAR_FUTURE', 'turn_offset = 1 (다음 블록)', 'zz_ok', 1),
    ('w02', 'VAR_FUTURE', '이름에 next (next_ret)',      'next_ret', 0),
    ('w03', 'VAR_FUTURE', '이름에 fwd (fwd_ret)',        'fwd_ret', 0);
INSERT INTO zz_var_fixture (grp, expect, descr, name, type) VALUES
    ('w04', 'VAR_TYPE', '타입 amount (금액)', 'zz_ok', 'amount'),
    ('w05', 'VAR_TYPE', '타입 price (가격)',  'zz_ok', 'price'),
    ('w06', 'VAR_TYPE', '타입 빈 칸 (NULL)',  'zz_ok', NULL);

-- 7.4 허용해야 하는 문장 (과잉 제약이 없다는 증거). 위반 0행이어야 한다.
INSERT INTO zz_fixture (grp, expect, descr, text) VALUES
    ('p01', '(위반 없음)', '기본 행 (규칙을 모두 통과하는 문장)',             '{asset_name} 이달 {ret} — 자금 몰려'),
    ('p02', '(위반 없음)', '{asset_name}의 (받침과 무관한 조사)',               '{asset_name}의 이달 {ret} — 자금 몰려'),
    ('p03', '(위반 없음)', '{asset_name}도 (받침과 무관한 조사)',               '{asset_name}도 이달 {ret} — 자금 몰려'),
    ('p04', '(위반 없음)', '{ret}에 (받침과 무관한 조사)',                      '{asset_name} 이달 {ret}에 자금 몰려 — 시장 술렁'),
    ('p05', '(위반 없음)', '이달·지난달·추가 상승 기대 (분위기 말은 허용)',      '{asset_name} 지난달에 이어 이달 {ret} — 추가 상승 기대'),
    ('p06', '(위반 없음)', '미국·국내 (시장 표기는 허용)',                      '미국 증시 훈풍에 {asset_name} 이달 {ret} — 국내 자금 몰려'),
    ('p07', '(위반 없음)', '막연한 원인 (고유명사·숫자 없음, 결정 6-B)',          '금리 부담과 수요 둔화 우려에 {asset_name} 이달 {ret}'),
    ('p08', '(위반 없음)', '{streak}달 연속 (단위는 문장 쪽)',                   '{asset_name} 이달 {ret}, {streak}달 연속 상승세 이어가'),
    ('p09', '(위반 없음)', '전각 아닌 문장 부호 ( ) ~ ! ? / - … 모두',            '{asset_name} 이달 {ret}! (자금 몰려?) 오름세/강세 - 계속 … 소음~');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, event_category, text) VALUES
    ('p10', '(위반 없음)', '사건 카드 (소음도 쓰는 범주 틀, 시장 반응 자리)', 'event.zz.v1', 'EVENT', 'NONE', NULL, 'POLITICS', '주요국 정치 이슈 부각 — 이달 관련 반응 {reaction}');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, target, direction, category_hidden, text) VALUES
    ('p11', '(위반 없음)', '범주 비공개 카드 (중립 문체, 자리 reaction만)', 'event.zz.v1', 'EVENT', 'NONE', NULL, true, '시장 전반 급변 — 이달 반응 {reaction}');
INSERT INTO zz_fixture (grp, expect, descr, template_id, kind, direction, text) VALUES
    ('p12', '(위반 없음)', 'ECHO 지난 턴 수익률 자리', 'echo.zz.v1', 'ECHO', 'UP', '{asset_name} 또 올랐다 — 지난달 {prev_ret}에 이어 이달 {ret}');
INSERT INTO zz_fixture (grp, expect, descr, status, text) VALUES
    ('p13', '(위반 없음)', 'RETIRED 행은 짧아도 SHAPE를 보지 않는다', 'RETIRED', '{asset_name} 급등');

INSERT INTO zz_fixture (grp, expect, descr, text) VALUES
    ('p14', '(위반 없음)', '구원·일원 (한 음절 수사 + 원은 일반 낱말이라 허용)', '{asset_name} 이달 {ret} — 구원 투수 기대, 일원 체제'),
    ('p15', '(위반 없음)', '곧바로 (결정 D3: 곧은 금지하지 않는다)',         '{asset_name} 이달 {ret} — 곧바로 자금 몰려'),
    ('p16', '(위반 없음)', '{ret} 원자재 (공백 뒤 단위가 아니라 낱말의 앞 글자)', '{asset_name} {ret} — 원자재 가격 부담에도 강세');
INSERT INTO zz_fixture (grp, expect, descr, text) VALUES
    ('p17', '(위반 없음)', '{asset_name}에서 (허용 목록)',  '{asset_name}에서 이달 {ret} 강세 — 자금 몰려'),
    ('p18', '(위반 없음)', '{asset_name}만 (허용 목록)',    '{asset_name}만 이달 {ret} 강세 — 자금 몰려'),
    ('p19', '(위반 없음)', '{ret}까지 (허용 목록)',         '{asset_name} 이달 {ret}까지 급등 — 자금 몰려'),
    ('p20', '(위반 없음)', '{ret}부터 (허용 목록)',         '{asset_name} 이달 {ret}부터 강세 — 자금 몰려'),
    ('p21', '(위반 없음)', '{rank}위 (허용 목록)',          '상승률 {rank}위 {asset_name}, 추가 상승 기대'),
    ('p22', '(위반 없음)', '{rank}건 (허용 목록)',          '{asset_name} 이달 {ret} — 거래 {rank}건 자금 몰려'),
    ('p23', '(위반 없음)', '{rank}턴 (허용 목록)',          '{asset_name} 이달 {ret} — {rank}턴 연속 자금 몰려'),
    ('p24', '(위반 없음)', '{rank}번째 (허용 목록)',        '{asset_name} 이달 {ret} — 상승 {rank}번째 이어져'),
    ('p25', '(위반 없음)', '{rank}째로 (허용 목록)',        '{asset_name} 이달 {ret} — {rank}째로 자금 몰려');

-- 픽스처를 합본에 넣는다
INSERT INTO zz_all (src, template_id, kind, status, target, direction, strength, event_category, event_type, outcome, category_hidden, text)
SELECT grp, template_id, kind, status, target, direction, strength, event_category, event_type, outcome, category_hidden, text
FROM zz_fixture ORDER BY fx_seq;
INSERT INTO zz_var_all (src, name, type, turn_offset, kinds, max_len)
SELECT grp, name, type, turn_offset, kinds, max_len FROM zz_var_fixture;
-- COVERAGE fixture: a fixed sample (not templates.csv) that has every required combination except SPREAD·GROUP·DOWN.
-- Only the attributes matter here; event texts repeat shapes the fixed noise list already pairs UP and DOWN.
INSERT INTO zz_all (src, template_id, kind, status, target, direction, category_hidden, event_category, text)
SELECT 'cov_fx', c.id, c.kind, 'ACTIVE', c.target, c.direction, c.hidden, c.cat, c.text
FROM (VALUES
    ('rise.c1.v1', 'RISE', 'ASSET', 'UP', false, NULL, '{asset_name} 이달 {ret} — 자금 몰려'),
    ('rise.c2.v1', 'RISE', 'ASSET', 'UP', false, NULL, '{asset_name} 이달 {ret} — 매수세 뚜렷'),
    ('rise.c3.v1', 'RISE', 'GROUP', 'UP', false, NULL, '{group_name} 관련주 동반 상승, 평균 {group_ret}'),
    ('fall.c1.v1', 'FALL', 'ASSET', 'DOWN', false, NULL, '{asset_name} 이달 {ret} 급락, 공포 확산'),
    ('fall.c2.v1', 'FALL', 'ASSET', 'DOWN', false, NULL, '{asset_name} 이달 {ret} — 매물 쏟아져'),
    ('fall.c3.v1', 'FALL', 'GROUP', 'DOWN', false, NULL, '{group_name} 전반 약세 — 평균 {group_ret}, 투자 심리 위축'),
    ('echo.c1.v1', 'ECHO', 'ASSET', 'UP', false, NULL, '{asset_name} 또 올랐다 — 지난달 {prev_ret}에 이어 이달 {ret}'),
    ('echo.c2.v1', 'ECHO', 'ASSET', 'DOWN', false, NULL, '{asset_name} 또 내렸다 — 지난달 {prev_ret}에 이어 이달 {ret}'),
    ('echo.c3.v1', 'ECHO', 'ASSET', 'UP', false, NULL, '{asset_name} 이달 {ret}, 상승 소식 {repeat}번째 이어져'),
    ('spread.c1.v1', 'SPREAD', 'GROUP', 'UP', false, NULL, '{group_name} 전반으로 매수세 번져, 평균 {group_ret}'),
    ('spread.c2.v1', 'SPREAD', 'GROUP', 'UP', false, NULL, '{group_name} 업종 전체 관심 집중 — 평균 {group_ret}'),
    ('fade.c1.v1', 'FADE', 'ASSET', NULL, false, NULL, '{asset_name} 관심 식어 거래 한산 — 시선은 {focus_name} 쪽으로'),
    ('fade.c2.v1', 'FADE', 'ASSET', NULL, false, NULL, '{asset_name} 주목도 하락 — 관심은 {focus_name} 쪽으로 이동'),
    ('fade.c3.v1', 'FADE', 'ASSET', NULL, false, NULL, '{asset_name} 열기 가라앉아, 관심 순위 {attention_rank}위로 밀려'),
    ('event.c1.v1', 'EVENT', 'NONE', NULL, false, 'GEOPOLITICS', '주요 교역국 통상 소식 — 이달 반응 {reaction}'),
    ('event.c2.v1', 'EVENT', 'NONE', NULL, false, 'TRADE', '주요 교역국 통상 소식 — 이달 반응 {reaction}'),
    ('event.c3.v1', 'EVENT', 'NONE', NULL, false, 'POLITICS', '주요국 정치 이슈 부각 — 이달 관련 반응 {reaction}'),
    ('event.c4.v1', 'EVENT', 'NONE', NULL, false, 'SUPPLY_SHOCK', '주요 교역국 통상 소식 — 이달 반응 {reaction}'),
    ('event.c5.v1', 'EVENT', 'NONE', NULL, false, 'FINANCIAL', '주요국 정치 이슈 부각 — 이달 관련 반응 {reaction}'),
    ('event.c6.v1', 'EVENT', 'NONE', NULL, false, 'POLICY', '주요 교역국 통상 소식 — 이달 반응 {reaction}'),
    ('event.c7.v1', 'EVENT', 'NONE', NULL, false, 'HEALTH', '주요국 정치 이슈 부각 — 이달 관련 반응 {reaction}'),
    ('event.c8.v1', 'EVENT', 'NONE', NULL, true, NULL, '시장 전반 급변 — 이달 반응 {reaction}'),
    ('event.c9.v1', 'EVENT', 'NONE', NULL, true, NULL, '시장 전체가 크게 흔들렸다 — 이달 관련 반응 {reaction}'),
    ('nudge.c1.v1', 'NUDGE', 'ASSET', NULL, false, NULL, '{asset_name} 이달 {ret}, 변동 상위 {rank}위 — 지금 보기'),
    ('nudge.c2.v1', 'NUDGE', 'NONE', NULL, false, NULL, '현금 비중 {cash_weight} — 기회를 놓치고 있지 않나요'),
    ('nudge.c3.v1', 'NUDGE', 'NONE', NULL, false, NULL, '{idle_turns}번째 쉬는 턴 — 지켜만 보고 계신가요')
) AS c(id, kind, target, direction, hidden, cat, text);

CREATE TEMP TABLE zz_viol_fx AS SELECT * FROM pg_temp.zz_violations();

-- 묶음마다 한 행: 기대 = 규칙 코드 하나, 실제 = 그 묶음에 걸린 규칙 코드 목록
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '7 음성', c.expect || ': ' || c.descr, c.expect,
       COALESCE((SELECT string_agg(DISTINCT v.rule, ',' ORDER BY v.rule) FROM zz_viol_fx v WHERE v.src = c.grp), '(위반 없음)'),
       COALESCE((SELECT string_agg(DISTINCT v.rule, ',' ORDER BY v.rule) FROM zz_viol_fx v WHERE v.src = c.grp), '(위반 없음)') = c.expect
FROM (SELECT DISTINCT grp, expect, descr FROM zz_fixture
      UNION SELECT DISTINCT grp, expect, descr FROM zz_var_fixture
      UNION SELECT 'cov_fx', 'COVERAGE', '고정 표본에서 필수 조합 하나(SPREAD GROUP DOWN)를 뺀 집합') AS c(grp, expect, descr)
ORDER BY c.grp;

-- 코드 목록 픽스처 (KINDS_ERD): 실제 kinds.csv는 건드리지 않고 배열로 대조 함수를 시험한다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '7 음성', 'KINDS_ERD: ' || x.descr, 'KINDS_ERD', CASE WHEN pg_temp.zz_kinds_erd_ok(x.arr) THEN '(위반 없음)' ELSE 'KINDS_ERD' END,
       CASE WHEN pg_temp.zz_kinds_erd_ok(x.arr) THEN '(위반 없음)' ELSE 'KINDS_ERD' END = 'KINDS_ERD'
FROM (VALUES
    ('전각 문자 (ＲISE)',           ARRAY[U&'\FF32ISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE']),
    ('NBSP가 붙은 코드 (RISE␣)',   ARRAY[U&'RISE\00A0','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE']),
    ('소문자 코드 (rise)',          ARRAY['rise','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE']),
    ('하나 빠짐 (6개)',             ARRAY['RISE','FALL','ECHO','SPREAD','FADE','EVENT']),
    ('하나 더 있음 (8개)',          ARRAY['RISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE','RALLY']),
    ('erd에 없는 코드로 교체 (RALLY)', ARRAY['RALLY','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE']),
    ('중복 (RISE 둘, NUDGE 빠짐)',  ARRAY['RISE','RISE','ECHO','SPREAD','FADE','EVENT','FALL'])
) AS x(descr, arr);
CALL pg_temp.check_eq('7 음성', 'KINDS_ERD 양성: erd 6.4 목록과 같은 배열 (순서만 다름)', 'true',
    pg_temp.val($q$SELECT pg_temp.zz_kinds_erd_ok(ARRAY['NUDGE','EVENT','FADE','SPREAD','ECHO','FALL','RISE'])::text$q$));

-- 규칙 커버리지: 29개 규칙 코드가 각각 1건 이상 자기 코드 하나만으로 보고되어야 한다 (기대값이 규칙 코드인 성공한 음성 사례)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '7 음성', '규칙 커버리지: 자기 코드로 보고된 규칙 수', (SELECT count(*) FROM zz_rule)::text || '/' || (SELECT count(*) FROM zz_rule)::text,
       (SELECT count(*) FROM zz_rule r WHERE EXISTS (SELECT 1 FROM zz_check_result z WHERE z.section = '7 음성' AND z.ok AND z.expected = r.code))::text
       || '/' || (SELECT count(*) FROM zz_rule)::text
       || COALESCE(' 빠짐: ' || (SELECT string_agg(r.code, ', ' ORDER BY r.code) FROM zz_rule r
                                 WHERE NOT EXISTS (SELECT 1 FROM zz_check_result z WHERE z.section = '7 음성' AND z.ok AND z.expected = r.code)), ''),
       (SELECT count(*) FROM zz_rule r WHERE EXISTS (SELECT 1 FROM zz_check_result z WHERE z.section = '7 음성' AND z.ok AND z.expected = r.code))
         = (SELECT count(*) FROM zz_rule) AND (SELECT count(*) FROM zz_rule) = 29;

-- 8 판정 ---------------------------------------------------------------------
-- 8.1 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 8.2 규칙 커버리지 행 (N/N)
SELECT item, expected, actual FROM zz_check_result WHERE item LIKE '규칙 커버리지%';

-- 8.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 8.4 판정 행
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result;

-- 8.5 판정을 종료 코드로: FAIL이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1이면 종료 코드 3).
--     판정 표식(zz_verdict)은 트랜잭션이 되돌아가도 남는다. 여기서는 통과해도 2(흔적 확인 대기)까지만 올리고,
--     9절의 어느 조회가 오류로 끊겨도 표식이 2로 남아 ON_ERROR_STOP 없이 돌려도 마지막 줄이 PASS로 나오지 않는다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    -- 2 = 0–8절 통과 (결과 표가 비어 있으면 통과로 치지 않는다), 1 = 실패. 최종 통과(0)는 9절 끝에서만 만든다.
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-13 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 9 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 임시 표·함수가 모두 사라졌다: pg_temp와 public에 zz_verdict(판정 표식) 외의 zz_ 객체가 0개.
DO $$
DECLARE
    v_zz integer;
BEGIN
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE (n.nspname = 'public' OR n.nspname LIKE 'pg\_temp\_%') AND c.relname LIKE 'zz\_%' AND c.relname <> 'zz_verdict')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE (n.nspname = 'public' OR n.nspname LIKE 'pg\_temp\_%') AND p.proname LIKE 'zz\_%') INTO v_zz;
    IF v_zz <> 0 THEN
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION 'P0-13 흔적 확인 실패: zz_ 객체 % 개 (기대 0)', v_zz;
    END IF;
    -- 단언을 통과했고 0–8절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_13_check;
