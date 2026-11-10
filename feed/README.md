# feed/ — 소음·알림 템플릿 표본 (P0-13)

시즌 중 턴 결과 화면의 피드(상승·하락·재생산·확대·소모·사건 카드)와 독려 알림에 쓰는 **문장의 원천**이다. 손으로 편집하는 데이터이고 DB 테이블이 아니다(erd Q10). P0-18이 이 파일을 읽어 `turn_feed`에 "보인 그대로" 한 행씩 넣는다.

**소음의 원천은 월 블록의 숫자뿐이다**(PLAN §4.13). 템플릿은 이미 보인 숫자(수익률·순위·연속 수)를 기사 문장으로 포장할 뿐이고, 다음 달에 대한 정보를 하나도 담지 않는다. 날짜·절대 수치·종목 풀 밖의 고유명사·구체 시점이 문장에 **실수로** 들어오지 못하도록 자동 검사(`check_templates.sql`)가 막는다. 템플릿은 소유자와 빌더가 손으로 쓰므로 검사의 목적은 실수 방지이고, 일부러 피해 가는 사람(글자 사이를 띄운 `다 음 달` 등)은 3차 방어(리뷰)의 몫이다.

## 파일

| 파일 | 내용 | 행 수 |
|---|---|---|
| `kinds.csv` | 종류 코드 7개 — `turn_feed.kind` CHECK와 글자 단위로 같다 | 7 |
| `variables.csv` | 문장에 쓸 수 있는 변수(자리) — `turn_feed.template_vars`의 키 | 14 |
| `templates.csv` | 템플릿(문장 + 선택 속성) | 53 |
| `denylist.csv` | 금지어와 이유 코드 | 82 |
| `check_templates.sql` | 위 네 파일을 읽어 규칙을 판정하는 검사 | — |
| `README.md` | 이 문서 | — |

편집은 VS Code에서 한다. 확장 "Rainbow CSV"를 설치하면 열이 색으로 구분되고 열 정렬 보기가 된다. **정렬용 공백을 넣지 않는다**(공백도 값이 된다).

## 파일 형식 (네 CSV 공통)

- UTF-8(BOM 없음), 줄 끝 LF, RFC 4180 따옴표. 쉼표가 든 칸은 `"…"`로 감싸고 안의 `"`는 `""`로 쓴다.
- 한 행이 한 항목이다. 여러 줄 칸 금지.
- 빈 칸 = NULL(따옴표 없는 빈 값).
- 첫 줄은 열 이름이고 **순서까지 `check_templates.sql`의 임시 테이블과 같아야 한다**(`HEADER MATCH`). 열을 더하거나 순서를 바꾸면 검사의 적재 단계에서 멈춘다.

## kinds.csv — 종류 코드

| kind | 이름 | 화면 | 허용 대상(target) |
|---|---|---|---|
| `RISE` | 상승 | FEED | ASSET, GROUP |
| `FALL` | 하락 | FEED | ASSET, GROUP |
| `ECHO` | 재생산 | FEED | ASSET, GROUP |
| `SPREAD` | 확대 | FEED | GROUP |
| `FADE` | 소모 | FEED | ASSET, GROUP |
| `EVENT` | 사건 카드 | FEED | NONE |
| `NUDGE` | 독려 알림 | ALERT | ASSET, GROUP, NONE |

- 종류 코드는 erd 6.4 `ck_turn_feed_kind`(`'RISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE'`)와 **바이트 단위로** 같다. 검사가 집합 일치, 글자 수 = 바이트 수(전각·NBSP·제로폭 문자 차단), `^[A-Z]{1,6}$`를 본다.
- `target`은 erd 6.4 `ck_turn_feed_target_kind`를 옮긴 것이다. `EVENT`만 대상이 없다.
- 화면 2분류(`NUDGE` → 알림, 나머지 → 피드)는 DTO에서 파생한다. 시즌 중 DTO에는 `kind`·`template_id`를 내보내지 않는다(erd 6.3 `kind` 코멘트, 6.3 `template_id`는 `[내부]`).

## variables.csv — 변수(자리)

문장 안의 `{이름}`이 자리다. 이름은 `^[a-z][a-z0-9_]{0,29}$`이고, `template_vars`의 키와 **같은 이름**이다. 고른 템플릿의 자리 집합과 `template_vars`의 키 집합은 정확히 같아야 한다(남는 키 금지 — 범주·유형·월 블록 키를 넣지 않는다, erd D27).

| name | type | 출처 (그 턴 t 기준) | 오프셋 | 쓸 수 있는 종류 | 최대 글자 |
|---|---|---|---|---|---|
| `asset_name` | name | 대상 종목 `asset.name` | 0 | RISE FALL ECHO FADE NUDGE | 100 |
| `group_name` | name | 대상 그룹 `asset_group.name` | 0 | RISE FALL ECHO SPREAD FADE NUDGE | 50 |
| `focus_name` | name | 그 턴 관심 지수 1위 풀 종목 이름("다음"이 아니라 그 턴의 값) | 0 | FADE | 100 |
| `ret` | pct_signed | 대상 종목의 원화 턴 수익률 `turn_price(t) / turn_price(t-1) - 1` | 0 | RISE FALL ECHO NUDGE | 8 |
| `prev_ret` | pct_signed | 같은 식, 턴 t−1 | −1 | ECHO | 8 |
| `group_ret` | pct_signed | 그룹 소속 풀 종목 `ret`의 동일 가중 평균 | 0 | RISE FALL ECHO SPREAD | 8 |
| `rank` | int | 그 턴 풀 안 `ret` 순위(상승은 내림차순, 하락은 오름차순) | 0 | RISE FALL NUDGE | 3 |
| `streak` | int | **시즌 경로의** 같은 방향 연속 턴 수(`turn_price` 부호로 센다 — `asset_month_attention.streak`이 아니다) | 0 | RISE FALL ECHO | 3 |
| `repeat` | int | `turn_feed.repeat_count` | 0 | ECHO | 3 |
| `attention_rank` | int | 그 턴 관심 지수 순위(조회, 저장 안 함) | 0 | FADE | 3 |
| `cash_weight` | pct_unsigned | 그 턴 현금 / 총평가액 | 0 | NUDGE | 4 |
| `trade_count` | int | 직전 매매 구간의 체결 수 | −1 | NUDGE | 4 |
| `idle_turns` | int | 체결 없는 턴이 이어진 수 | 0 | NUDGE | 3 |
| `reaction` | pct_signed | 그 턴 블록 사건 태그의 `month_event.reaction_change` | 0 | EVENT | 8 |

- **타입은 네 개뿐이다**: `name`(문자열) · `pct_signed`(부호 있는 %) · `pct_unsigned`(%) · `int`(정수). 가격·금액·지수 수준 타입은 없다(PLAN §4.4·§4.13 절대 수치 금지).
- **오프셋은 0 또는 음수만**이다(0 = 그 턴, −1 = 지난 턴). 양수는 다음 블록을 참조하는 것이라 검사가 막는다. 변수 이름에도 `next`·`future`·`forward`·`fwd`·`lead`·`t1`·`plus`를 쓰지 못한다.
- 값은 숫자 타입이면 JSON number(`jsonb`의 `numeric`이라 정확), `name`이면 JSON string으로 `template_vars`에 넣는다.
- `ret`은 **원화 `turn_price` 기준**이다(화면 가격과 같은 숫자, PLAN §5.2). 현지 통화 수익률이 아니다.
- 연속 수·순위·건수에 붙는 단위(`달`·`위`·`건`·`턴`·`번째`)는 문장 쪽에 쓴다. `%`는 렌더러가 붙이므로 문장에 `%` 글자를 쓰지 않는다.
- `cash_weight`의 출처 열 이름은 P0-06 DDL 기준이다. 이름이 다르면 `source` 설명만 고친다(키 이름은 그대로).

## templates.csv — 템플릿

| 열 | 값 | 규칙 |
|---|---|---|
| `template_id` | `^(rise\|fall\|echo\|spread\|fade\|event\|nudge)\.[a-z][a-z0-9_]*\.v[1-9][0-9]*$`, 40자 이하 | 앞 조각 = `lower(kind)`. 같은 `kind.slug`의 `ACTIVE`는 하나 |
| `kind` | `kinds.csv`의 코드 | |
| `status` | `ACTIVE` / `RETIRED` | P0-18은 `ACTIVE`만 고른다 |
| `target` | `ASSET` / `GROUP` / `NONE` | 그 종류의 허용 대상 안 |
| `direction` | `UP` / `DOWN` / 빈 칸 | RISE = `UP`, FALL = `DOWN`, FADE·NUDGE·범주 비공개 = 빈 칸 |
| `strength` | 1–3 / 빈 칸 | 빈 칸 = 모든 단계 |
| `event_category` | `GEOPOLITICS`·`TRADE`·`POLITICS`·`SUPPLY_SHOCK`·`FINANCIAL`·`POLICY`·`HEALTH` / 빈 칸 | 공개 사건 카드(`EVENT`)만 |
| `event_type`·`outcome` | `^[A-Z][A-Z0-9_]*$` / 빈 칸 | 이번에는 모두 빈 칸. P0-09가 목록을 정한 뒤 더 구체적인 카드를 새 id로 더하는 자리 |
| `category_hidden` | `true` / `false` | `true`면 `EVENT`, 범주·유형·결과·방향 빈 칸, 자리는 `{reaction}`만 |
| `text` | 한국어 문장, 자리는 `{이름}` | 아래 문장 규칙 |

### id 규칙 — 불변

- **한 번 머지된 id의 문장·속성은 고치지 않는다.** 지난 `turn_feed`가 그 id를 가리키고, P0-18의 "다시 렌더 검사"(`render(template, template_vars) = body`)가 이 규칙의 데이터 쪽 경보다.
- 문장을 고치려면 `v` 번호를 올린 **새 행**을 더하고(`rise.inflow.v2`) 옛 행의 `status`를 `RETIRED`로 바꾼다. 같은 `kind.slug`의 `ACTIVE`는 하나뿐이므로 새 행을 `ACTIVE`로 더하는 같은 변경에서 옛 행을 `RETIRED`로 둔다.
- **행을 지우지 않는다.**

### 표본 구성 (합 53)

| 종류 | 수 | 구성 |
|---|---|---|
| RISE | 14 | ASSET·UP 3, GROUP·UP 11 |
| FALL | 13 | ASSET·DOWN 3, GROUP·DOWN 10 |
| ECHO | 4 | ASSET·UP 2, ASSET·DOWN 2 |
| SPREAD | 5 | GROUP·UP 2, GROUP·DOWN 3 |
| FADE | 3 | ASSET 3 |
| EVENT | 9 | 공개 범주 7개 각 1, 범주 비공개 2 |
| NUDGE | 5 | ASSET 2, NONE 3 |

사건 카드는 이번에 **범주 단위** 7개와 범주 비공개 2개뿐이다. 유형·결과별 문장은 P0-09가 `event_type`·`outcome` 목록을 정한 뒤 새 id로 더한다.

- **범주 카드는 그 범주의 모든 유형·결과·단계에서 참인 말만 쓴다.** 범주 단위 카드는 그 범주의 어떤 사건 태그에도 쓰이므로(`US_MIDTERM`이든 `US_PRESIDENTIAL_ELECTION`이든, 결과 `AGREED`든 `CEASEFIRE`든, 단계 `START`든 `END`든) "미국 대선 결과"·"관세 갈등 격화"·"무력 분쟁 발발"처럼 유형·결과·단계를 단정하면 틀린 사실을 보이게 된다. "정치 이슈 부각"·"통상 소식"처럼 단계에 중립인 말을 쓰고, 구체 문장은 새 id로 더한다.
- **사건 카드 9개의 문장 전체를 오른 달 소음과 내린 달 소음이 각각 하나 이상 그대로 쓴다**(소음 18개, 모두 GROUP·RISE·FALL·SPREAD). 소음의 앞말에는 늘 다른 말이 붙기 때문에 꼬리나 어휘만 맞추면 사건 카드의 문장이 딱 그 모양인 소음이 없어 가려진다. 그래서 공개 카드 7개도 비공개 카드 2개와 같은 규칙을 쓴다: `주요 지역 지정학 이슈 부각 — 이달 관련 시장 {reaction}`은 `rise.geo`(UP)와 `fall.geo`(DOWN)가 자리만 `{group_ret}`로 바꿔 똑같이 쓴다. 문장은 고유명사·숫자 없이 사건 범주를 막연하게만 말한다(지정학·통상·정치·원자재 가격·금융권 신용·경제 정책·보건). `event.policy`는 통화·재정을 모두 덮도록 `경제 정책 관련 소식`이라 쓴다. 범주 비공개 카드 2개의 소음은 `rise.sweep`·`fall.sweep`·`rise.sweepwide`·`fall.sweepwide`다. 사건 카드 문장을 고치면 그 틀을 쓰는 소음 두 개(UP·DOWN)도 같은 문장으로 고친다(머지된 뒤에는 새 id가 필요하다, 위 id 규칙).

## 렌더 규칙 — P0-18의 SQL과 MVP의 Java가 같은 결과를 낸다

| 타입 | 렌더 | 예 |
|---|---|---|
| `name` | 값 그대로 | `삼성전자` |
| `pct_signed` | `r = round(v × 100, 0)` (PostgreSQL `numeric`의 `round`는 0.5를 0에서 먼 쪽으로 보낸다 = Java `RoundingMode.HALF_UP`). `r > 0`이면 `+r%`, `r < 0`이면 `-\|r\|%`(ASCII 하이픈), `r = 0`이면 `0%` | 0.18234567 → `+18%`, −0.004 → `0%` |
| `pct_unsigned` | `round(v × 100, 0)` + `%` | 0.35 → `35%` |
| `int` | 10진 정수, 천 단위 구분 없음 | `3` |

- 자리 치환은 **한 번, 왼쪽부터** 한다. 값 안의 `{`는 다시 치환하지 않는다.
- **값이 NULL인 자리는 렌더하지 않는다.** 그 자리를 가진 템플릿은 후보에서 뺀다. 0이나 빈 문자열로 채우지 않는다(0%를 지어내면 "보인 그대로"와 "관측하지 않은 값 금지"를 함께 어긴다). 사건 카드의 `{reaction}`은 `month_event.reaction_change`(NULL 허용)에서 오므로, **카드로 내는 사건 태그는 `reaction_change`를 채운다**(P0-09 약속). 그래도 NULL이 들어오면 P0-18은 후보 없음을 조용히 넘기지 않고 생성 오류로 멈춘다.
- 소수 자리는 정수 %로 고정한다. 바꾸려면 렌더 규칙을 고치지 말고 **새 타입**을 더한다(지난 `body`와 다시 렌더한 값이 어긋나지 않게).

## 문장 규칙과 금지 규칙 코드

검사 대상은 자리를 뺀 문장(리터럴)이다. 자리를 `{[a-z][a-z0-9_]*}`로 뽑아 지우고 남은 글자를 본다. 검사 결과에는 아래 규칙 코드가 붙는다.

| 코드 | 규칙 | 근거 |
|---|---|---|
| `CHARSET` | 리터럴은 한글 음절(`가-힣`), 공백, 문장 부호 `, . · — … ! ? ( ) ~ ' " / -`만. 숫자·라틴·한자·전각·이모지·NBSP 금지 | 숫자 금지가 연도·날짜·절대 수치를, 라틴 금지가 티커·영문 고유명사(Fed 등)를 한 번에 막는다 |
| `NFC` | 문장이 NFC 정규화 상태 | macOS 붙여넣기의 풀어 쓴 자모(NFD) 차단 |
| `PERCENT` | 리터럴에 `%` 없음 | `%`는 렌더러가 붙인다 |
| `UNIT` | 자리 뒤에 `원`·`억`·`조`·`달러`·`포인트` 없음 — 붙으면 뒤가 무엇이든, 공백 한 칸을 건너면 단위가 낱말로 끝날 때나 `까지`·`이상`·`이하`·`대`·`쯤`·`가량`·`안팎`이 올 때(`{x} 원자재`는 허용). `선`은 붙어 있을 때만. 한글 수사 둘 이상 + `년`·`월`·`원`·`달러`·`포인트`(`이천팔년`·`십만원`), 크기 수사 + `원`·`달러`·`포인트`(`만원`·`삼조원`) 없음. 한 음절 수사는 걸지 않는다(`구원`·`일원`) | 절대값·날짜 금지(PLAN §4.4) |
| `BRACE` | 자리를 지운 뒤 `{`·`}`가 남지 않음 | 깨진 자리 |
| `VAR_UNKNOWN` | 자리 이름이 `variables.csv`에 있음 | |
| `VAR_KIND` | 자리의 "쓸 수 있는 종류"에 템플릿 종류가 있음 | `EVENT`에 `asset_name` 금지 등 |
| `VAR_TARGET` | `asset_name`은 target `ASSET`, `group_name`은 target `GROUP` | 대상과 문장 일치 |
| `VAR_FUTURE` | 변수의 오프셋 ≤ 0, 이름에 금지 패턴 없음 | 다음 블록 참조 차단 |
| `VAR_TYPE` | 변수 타입이 네 개 안(빈 칸도 위반) | 절대 수치 타입 차단 |
| `PARTICLE` | 자리 바로 뒤에 받침에 따라 바뀌는 조사(`이`·`가`·`을`·`를`·`은`·`는`·`과`·`와`·`으로`·`로`·`이나`·`나`)가 단어로 붙지 않음 | 아래 "조사" |
| `DENY_DATE`·`DENY_FUTURE`·`DENY_PROPER`·`DENY_ADVICE`·`DENY_FACT` | `denylist.csv` 단어 0건 | 아래 "금지어" |
| `ID_FORMAT`·`ID_KIND`·`ID_DUP`·`ID_ACTIVE` | id 형식·길이, 앞 조각 = kind, 중복 0, 같은 slug의 ACTIVE ≤ 1 | |
| `KIND` | `kind`가 `kinds.csv`에 있음 | |
| `TARGET_KIND` | `target`이 그 종류의 허용 대상 안 | erd `ck_turn_feed_target_kind` |
| `DIRECTION` | RISE = UP, FALL = DOWN, FADE·NUDGE = 빈 칸, 그 밖에는 UP/DOWN/빈 칸 | |
| `HIDDEN` | 범주 비공개 ⇒ EVENT, 범주·유형·결과·방향 빈 칸, 자리 = `{reaction}`만 | erd `ck_turn_feed_category_hidden` |
| `EVENT_ATTR` | 범주·유형·결과는 EVENT에서만, 범주는 7개 안, 공개 카드는 범주 필수 | erd `ck_month_event_category` |
| `LENGTH` | 리터럴 글자 수 + Σ 자리 최대 글자 ≤ 300 | `body VARCHAR(300)` |
| `SHAPE` | `ACTIVE` 리터럴 12–60자. `ACTIVE` EVENT의 **문장 전체 틀**(자리를 이름이 아니라 **타입**으로 바꾼 것: `{pct_signed}`·`{int}`·`{name}` …)을 `ACTIVE` 소음(RISE…FADE) 가운데 `UP` 소음 하나 이상과 `DOWN` 소음 하나 이상이 쓴다. 자리 타입을 보는 까닭은 `{rank}`의 `3`과 `{reaction}`의 `+3%`가 다른 모양이기 때문이다 | 사건 카드만 가진 표식이 없게("같은 옷"). 문장 부호와 꼬리는 전체 틀이 같으면 같다 |
| `COVERAGE` | 종류별 `ACTIVE` ≥ 3, 아래 필수 조합 각 ≥ 1 | |
| `KINDS_ERD` | `kinds.csv`와 erd 6.4 `ck_turn_feed_kind`의 바이트 일치 | |

**필수 조합 표:** (RISE, ASSET, UP), (RISE, GROUP, UP), (FALL, ASSET, DOWN), (FALL, GROUP, DOWN), (ECHO, ASSET, UP), (ECHO, ASSET, DOWN), (SPREAD, GROUP, UP), (SPREAD, GROUP, DOWN), (FADE, ASSET), (EVENT, NONE, 범주 7개 각각), (EVENT, NONE, 비공개), (NUDGE, ASSET), (NUDGE, NONE). P0-18이 "고를 템플릿이 없다"로 멈추지 않을 최소 조건이다.

규칙 코드로 잡히지 않는 형식 검사(값 집합·빈 칸·`status`·`strength`·한 줄 문장·공백 두 칸 이어짐 금지·변수 `type`·`kinds` 빈 칸 금지 등)는 3·4·5절에 코드 없이 한 줄씩 있다. 공백이 두 칸 이어지면(`다음  달`) 금지어 비교 전에 걸린다.

### 조사 (`PARTICLE`)

`{asset_name}이` / `{asset_name}가`처럼 받침에 따라 바뀌는 조사를 자리 뒤에 직접 붙이지 않는다. 종목 이름이 `KODEX 200`·`QQQ`처럼 숫자·라틴으로 끝나면 읽는 법을 몰라 받침을 알 수 없기 때문이다. 기사 제목 문체로 쓴다(`{asset_name} 이달 {ret} — 자금 몰려`). 받침과 무관한 `의`·`에`·`에서`·`도`·`만`·`까지`·`부터`는 쓸 수 있다.

### 금지어 (`denylist.csv`)

| reason | 뜻 | 예 |
|---|---|---|
| `DATE` | 연도·달력 고유값 | 작년, 올해, 년대, 월요일 |
| `FUTURE` | 다음 달·예정 같은 **구체 시점** | 다음 달, 내달, 향후, 예정 |
| `PROPER` | 종목 풀 밖의 고유명사 | 연준, 중국, 러시아, 삼성(이름은 변수로만) |
| `ADVICE` | 직접 매수·매도 권유 | 매수하세요, 추천 |
| `FACT` | 지어낸 구체 사건 | 실적 발표, 인수, 소송 |

- **허용:** `미국`·`국내`(종목 풀의 `asset.market`이 `[노출]`이고 PLAN §4.11 카드 예시가 "미국 대선"을 쓴다), 분위기 말(`기대`·`전망`·`우려`) — 템플릿은 고정 문장이라 분위기 말에는 정보가 없고, 뉴스가 지나간 가격을 설명하면서 미래를 말하는 것처럼 들린다는 PLAN §4.13의 교훈 그대로다.
- **막연한 원인은 허용한다**(결정 6-B): 고유명사·숫자가 없는 원인(`금리 부담`, `수요 둔화 우려`, `규제 우려`, `지정학 불안`)을 문장에 붙일 수 있다. 소음이 가격·분위기만 말하면 사건 카드가 내용만으로 구별되기 때문이다. 구체 사건은 `FACT` 금지어가 계속 막는다.
- 금지어는 완전할 수 없다. 1차 방어는 글자 허용 목록(`CHARSET`: 숫자·라틴·한자 0), 2차는 금지어와 한글 수사 규칙(`UNIT`)·공백 두 칸 금지(한글로 쓴 고유명사·시점 표현), 3차는 리뷰다. 3차(리뷰)가 보는 예: **한글로 쓴 외국 고유명사**(`골드만삭스`·`도요타`·`마이크로소프트`), 글자 사이를 띄운 `다 음 달`, `매수 바랍니다` 같은 돌려 말한 권유. 변수 대신 문장에 직접 쓴 풀 종목 이름도 이 검사로 잡지 못한다(P0-18 약속 참고).
- **금지어 편집 규칙:** 비교는 글자 그대로의 부분 일치다. 여러 낱말로 된 시점어·사건어는 **띄어 쓴 형태와 붙여 쓴 형태를 둘 다** 올린다(`다음 달`/`다음달`, `다음 분기`/`다음분기`, `실적 발표`/`실적발표`, `공급 계약`/`공급계약`, `확정 발표`/`확정발표`). 공백을 지우고 비교하지는 않는다(`장중 국내` → `중국`, `한 은행` → `한은`처럼 낱말 경계를 넘는 오탐이 생긴다). 월 이름은 한글(`일월`…`시월`)로 올리고, `십일월`·`십이월`처럼 수사가 둘 이상인 것은 `UNIT`이 잡는다. `이월`(2월)은 자금 이월(`이월 자금`)도 막는다. 그 뜻이면 `넘어온 자금`처럼 다른 말로 쓴다. 부분 일치로는 두 뜻을 가를 수 없다.
- **경계에 있는 말은 금지하지 않는다:** `곧`(한 음절이라 `곧바로`·`곧장` 오탐이 크다), `이후`(과거 기준 비교 "지난달 이후"에 쓴다). 날짜를 붙인 `…년 이후`는 `UNIT`이 잡는다.
- 한 단어는 한 이유에만 둔다(`내년`은 `DATE`에 둔다).

### 문장 품질 기준

- 소음(RISE·FALL·ECHO·SPREAD·FADE)과 사건 카드(EVENT)와 알림 문장은 **같은 옷**을 입는다: 같은 문장 부호, 비슷한 길이, 기사 제목 문체. 사건 카드만 가진 표식(`SHAPE`)이 없어야 한다. 검사가 보는 것은 사건 카드의 문장 전체 틀(자리는 타입으로 바꾼다)을 오른 달·내린 달 소음이 각각 쓰는지다. 앞말의 어휘도 문장 전체에 들어 있으므로 따로 보지 않아도 된다. 자동으로 보지 못하는 것은 대상 이름 자리의 유무다 — 이름 자리는 MVP DTO 몫이다(EVENT 0/9, 소음은 이름 자리가 없는 18개를 빼고 모두 있다. 사건 카드와 같은 틀의 소음이 늘어 이 단서는 약하다).
- 소음은 그 턴의 숫자와 방향만 말하고 다음 달을 말하지 않는다. 사건 카드는 범주와 시장 반응(`{reaction}`)만 말하고 이름·날짜를 말하지 않는다.
- **범주 비공개 카드는 "범주 비공개"라고 말하지 않는다**(결정 5-B). 소음은 그런 말을 쓰지 않으므로, 그 글자가 화면에 나오면 "이 항목은 실제 사건"이 드러나고 희소 유형(표본 3회 미만)이라는 사실이 날짜를 가리킨다. 비공개는 `category_hidden`이라는 내부 판정으로만 두고, 화면 문장은 소음도 쓰는 중립 문체(`시장 전반 급변 — 이달 반응 {reaction}`)로 쓴다. 같은 모양의 소음 문장이 FALL·RISE에 함께 있다(`rise.sweep`·`fall.sweep` 등).
- 문장에 숫자를 직접 쓰지 않는다. 숫자는 자리로 들어온다.

## 실행 — 임시 인스턴스에서 검증한다

저장소 루트에서 실행한다(`\copy`의 파일 경로가 psql의 현재 디렉터리 기준이다). 스키마가 필요 없으므로 임시 인스턴스의 `postgres` DB에서 돈다. 상시 DB 서버를 건드리지 않는다. 앞 줄에서 오류가 나면 멈추고 마지막 두 줄(정리)만 실행한다.

```bash
D=$(mktemp -d /tmp/antlab-pg.XXXXXX)
initdb -D "${D:?}/data" -U check -A trust -E UTF8
pg_ctl -D "${D:?}/data" -o "-k ${D:?} -c listen_addresses='' -c timezone=Asia/Seoul" -l "${D:?}/data/log" start
psql -X -q -v ON_ERROR_STOP=1 -h "${D:?}" -U check -d postgres -f feed/check_templates.sql
pg_ctl -D "${D:?}/data" -m fast stop
rm -rf "${D:?}"
```

### 결과 읽는 법

- **마지막 출력이 `p0_13_check` 열의 `PASS`이고 종료 코드가 0이면 통과다.** `PASS`는 모든 절이 통과하고 끝의 흔적 확인까지 끝났을 때만 나온다.
- 먼저 0절(전제)과 1절(적재) 결과 표가 나온다. FAIL이 있으면 거기서 멈춘다.
- 이어서 ① 절별 집계(`total`/`pass`/`fail`), ② 규칙 커버리지(`29/29`), ③ FAIL 행 목록(0행이어야 정상), ④ 판정 행이 나온다. 규칙 위반 행의 `actual`에는 위반 개수와 대상 id가 적힌다(예: `1 (rise.inflow.v1)`).
- FAIL이 하나라도 있으면 오류 메시지와 함께 종료 코드 3으로 끝나고 `PASS`는 나오지 않는다. `-v ON_ERROR_STOP=1` 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다(마지막 줄은 `FAIL`이다).
- 검사는 임시 객체만 만들고 `ROLLBACK`한다. 어떤 DB에도 흔적을 남기지 않는다.
- 7절은 규칙마다 일부러 어긋난 문장(픽스처)을 넣어 **정확히 그 규칙 코드 하나**가 걸리는지 본다. 규칙이 29개이고 모두 자기 코드로 한 번 이상 보고돼야 `29/29`다. `29/29`는 **코드별 대표 사례**가 걸린다는 뜻이지 분기·금지어 하나하나의 커버리지가 아니다(금지어 82개는 대표 몇 개만 시험한다). 허용해야 하는 문장(`{asset_name}의`, `미국`, `추가 상승 기대`, `구원`·`일원`, `곧바로` …)이 위반 0행인지도 함께 본다.

### 파일 형식 실측 (PostgreSQL 17.11, UTF8 DB)

| 입력 | 결과 |
|---|---|
| BOM이 붙은 CSV | 1절 `\copy`가 `column name mismatch in header line field 1`로 멈춘다 (종료 코드 3) |
| 열 이름 대소문자가 다름 | 같은 오류로 멈춘다 (이름은 대소문자까지 구분) |
| 열 순서가 바뀜 | 같은 오류로 멈춘다 |
| 맨 끝 빈 줄 | `missing data for column "kind"`로 멈춘다 — 파일 끝에 빈 줄을 더하지 않는다 (마지막 줄 끝의 LF 하나는 정상) |
| 줄 끝 CRLF | PostgreSQL은 받아들여 PASS. 그래도 저장소는 LF로 둔다 |
| 마지막 줄 끝에 LF 없음 | PASS |
| `category_hidden`에 `true`·`false` 아닌 값 | `invalid input syntax for type boolean`으로 멈춘다 |
| NFC가 아닌(풀어 쓴 자모) 문장 | `NFC`로 걸린다 (`CHARSET`은 정규화 뒤에 보므로 따로 걸리지 않는다) |
| 저장소 루트가 아닌 곳에서 실행 | `feed/kinds.csv: No such file or directory`로 멈춘다 |
| `-v ON_ERROR_STOP=1` 없이 적재가 실패 | 이어지는 문장이 모두 실패하고 마지막 줄이 `FAIL` |

## P0-18이 지킬 것 (하류 약속)

SQL 생성기는 `db/24_turn_feed_def.sql`에서 `tf_input → tf_generate → tf_append` 순서로 동작한다. 관심 지수 값은 `asset_month_attention`에 저장된 역사값을 사용하며, 연속·지난달 비교만 시즌에서 실제 지나온 `turn_price` 순서로 계산한다. `ta_apply`는 턴 스냅샷 뒤 피드를 같은 트랜잭션에 넣고, 저장한 뒤에는 `template_id`·`template_vars`·`body`를 수정하지 않는다. Phase 0 API는 이 피드를 생성하거나 읽지 않는다.

구현 상태(P0-18, 규칙 버전 `p0-18:v2`): `tf_template_pool`은 아래 약속 2(후보를 `template_id` 순으로 세워 시드로 고름)·9(NULL 자리 제외)·10(r = 0 예외)·12(같은 옷)를 한 함수로 구현한다. 선택 경로와 `db/24_turn_feed_check.sql`의 같은 옷 시험은 모두 이 함수를 거친다. 관심 지수 기준은 위 단락과 같다.

1. **적재:** `check_templates.sql` 1절의 임시 테이블 정의와 `\copy … HEADER MATCH`를 그대로 복사한다. 고르는 것은 `status = 'ACTIVE'`만.
2. **선택:** (kind, target, direction, strength 일치 또는 빈 칸, EVENT면 `category_hidden`·범주) 후보를 `template_id` 오름차순으로 세우고 시즌 시드 + 턴 번호의 난수로 고른다. EVENT는 가장 구체적인 행이 이긴다(`event_type`+`outcome` > `event_category` > 범주 비공개). `category_hidden = TRUE` 카드는 비공개 템플릿만.
3. `template_vars` 키 = 고른 템플릿의 자리 집합 정확히(`jsonb_object_keys`로 검사). 범주·유형·월 블록 키 금지(erd D27).
4. `body` = 위 렌더 결과, 300자 이하. **다시 렌더 검사:** 모든 `turn_feed` 행에서 `render(template(template_id), template_vars) = body`.
5. 생성된 `body`에서 변수 값 부분을 뺀 나머지가 위 문장 규칙을 통과하고, 변수로 들어오지 않은 풀 종목 이름이 `body`에 없다.
6. 변수는 오프셋 ≤ 0의 데이터만 채운다("다음 블록 차단 테스트"가 이것을 고정한다). `streak`은 시즌 `turn_price` 부호로 센다.
7. **종류 코드 고리:** `kinds.csv` ↔ `pg_get_constraintdef(ck_turn_feed_kind)`에서 `'…'` 리터럴을 뽑아 직접 비교한다(erd 6.4와 DDL의 일치는 P0-06 검증이 보장한다).
8. 결정성 테스트("같은 시드 → 같은 행")는 `feed/` 내용을 고정한 상태(커밋)에서 돌리고 그 사실을 적는다. 템플릿이 늘면 후보 목록이 바뀐다. 저장된 `turn_feed`는 스냅샷이라 영향이 없다.
9. **NULL 자리:** 고른 템플릿의 자리 값이 NULL이면 그 템플릿을 후보에서 뺀다(0·빈 문자열로 채우지 않는다). 사건 카드는 `reaction_change`가 NULL이면 후보 없음을 넘기지 않고 생성 오류로 멈춘다(위 렌더 규칙, P0-09 약속).
10. **방향:** 렌더한 정수 % `r`의 부호가 템플릿 방향과 반대이면 고르지 않는다. `r = 0`이면 방향이 있는 문장(`급락`, `강세`)을 고르지 않는다(예: `fall.panic` "이달 0% 급락", `echo.again`(UP) "이달 -1%" 방지). **예외:** 문장 전체 틀이 `ACTIVE` 사건 카드의 틀과 같은 소음(틀은 `check_templates.sql`의 `SHAPE`와 같은 방식으로 구한다)은 `r = 0`이어도 고를 수 있다. 사건 카드는 방향이 없어 `0%`로 렌더될 수 있으므로, 같은 문장을 소음도 낼 수 있어야 문장만으로 실제 사건이 드러나지 않는다. 남는 한계: 범주 비공개 카드가 `0%`이면 문장(`시장 전반 급변 — 이달 반응 0%`)이 어색하지만 소음도 똑같이 어색하므로 구별 단서는 아니다.
11. **필수 조합 밖:** `kinds.csv`가 허용해도 필수 조합 표 밖(`ECHO`·`FADE`·`NUDGE`의 GROUP 대상 등)은 템플릿이 없다. 생성하지 않거나 템플릿을 먼저 더한다.
12. **같은 옷 시험:** 사건 카드 템플릿마다 `reaction`이 양수·0·음수로 렌더되는 값 세 개로 `body`를 만들고, 그 `body`를 소음도 위 규칙 1–11로 낼 수 있는지 시험한다(사건 `body` 집합 ⊆ 소음 `body` 집합).

P0-09: 카드로 내는 사건 태그는 `reaction_change`를 채운다(NULL이면 P0-09 검사가 보고한다). `event_type`·`outcome` 목록이 정해지면 유형·결과별 EVENT 템플릿을 새 id로 더한다. `month_event.reaction_change`가 **무엇의** 변화율인지 코드가 없어(erd 5.3) 공개 카드 문장은 "관련 시장 {reaction}"처럼 막연하게 쓴다. 대상을 말하려면 P0-09가 코드를 정하고 erd를 바꿔야 한다.

MVP: Java 렌더러는 위 렌더 규칙과 같은 결과(`HALF_UP`, 정수 %, ASCII 하이픈)를 내고 같은 CSV를 읽는다(열 목록 불변). 시즌 중 DTO에 `template_id`·`kind`를 내지 않는다.
