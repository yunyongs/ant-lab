# db/sample — 역사 데이터 표본 (P0-08)

실제 공개 데이터에서 만든 표본(종목 20 × 월 블록 100)을 P0-05 스키마에 적재하기 위한 CSV와 변환 스크립트다. 적재 스크립트는 `db/10_load_sample.sql`, 검증은 `db/10_load_sample_check.sql`이다.

> **상태: 가격 수집·변환·적재·검증 완료 (2026-11-07 기준).** 국내·미국 가격은 KIS(기본)와 yfinance(보조)에서 받아 만들었고, 환율은 FRED에서 받았다. 아래 "출처 칸 표"가 어느 종목·구간을 어느 출처에서 받았는지 보여 준다. KIS 약관의 시세 재배포 조항은 공개 자료에서 확인하지 못해 보수적으로 적용한다(아래 "출처와 약관 확인"): 시세·환율에서 만든 CSV는 추적하지 않고 만드는 방법만 안내한다. 남은 것은 하루짜리 종가 불일치를 제3 출처로 확인하는 일이다(아래 "대조 요약"). "확인 필요" 표시는 아직 출처로 확인하지 못한 값이다. 가격 값 자체는 이 문서에 쓰지 않는다. 행 수와 SHA-256, 건수, 차이의 크기(bp)만 적는다.

## 파일

| 파일 | 종류 | 추적 | 설명 |
|------|------|------|------|
| `asset_group.csv` | 손 편집 | 예 | 종목 그룹 12개 (섹터 8, 자산군 4) |
| `asset.csv` | 손 편집 | 예 | 종목 20개 (상장일·수익률 기준·대체 지수명 포함) |
| `month_block.csv` | 변환 출력 | 예 | 월 블록 100개 (매월 1일) |
| `fx_month.csv` | 변환 출력 | 아니오 | 달 환율 변화 (USD) 100개 (FRED 원본에서 만든 파생 값이라 보수적으로 추적하지 않는다. 아래 "표본 만들기"로 로컬 생성) |
| `asset_position.csv` | 변환 출력 | 아니오 | 기준일 가격·환율 20행(시가총액 순위 열은 빈 칸) (아래 "표본 만들기"로 로컬 생성) |
| `asset_month_return.csv` | 변환 출력 | 아니오 | 종목 달 수익률 2,000행 (아래 "표본 만들기"로 로컬 생성) |
| `hand_check.csv` | 손 대조 기대값 | 아니오 | 손 대조 기대값 7행 `code,expected` (`10_load_sample_check.sql` 6절이 읽는다). 손으로 계산해 만들며 `build_sample.sql`이 만들지 않는다 |
| `build_sample.sql` | 변환 스크립트 | 예 | 원본 → 위 변환 출력 CSV 4개 (psql만). 평소 실행 순서에 없다 |

원본 시세 파일(`data/raw/p0-08/`)은 `.gitignore`가 무시한다(`**/data/raw/`). 추적하지 않는다. 대조 중 사람이 판정한 기록(`data/raw/p0-08/judgments.csv`)도 그 폴더에 있어 추적하지 않는다.

**추적하지 않는 이유.** 이 저장소는 공개다. KIS 약관의 시세 재배포 조항은 공개 자료(KIS Developers 공개 페이지, 공식 GitHub 저장소 `koreainvestment/open-trading-api` — 저장소는 포털에서 약관을 확인하라고만 안내한다, 2026-11-07 확인)에서 확인하지 못했고, yfinance(Yahoo)는 자동 수집과 재배포를 허락 없이 금지한다. 그래서 보수적으로 적용한다: 받은 시세 원본과 시세·환율에서 만든 값(CSV 포함)은 저장소에 올리지 않고, 만드는 방법만 이 문서로 안내한다. `asset_position.csv`·`asset_month_return.csv`·`fx_month.csv`·`hand_check.csv`는 `.gitignore`가 무시하고, 각자 원본을 내려받아 아래 "표본 만들기" 순서로 만든다. 추적하는 CSV 3개에는 시세·환율에서 나온 값이 없다: 손 편집 둘(`asset_group.csv`, `asset.csv`)과 달력(`month_block.csv`).

**키가 없는 사람.** 무시 CSV 3개(`asset_month_return.csv`·`asset_position.csv`·`fx_month.csv`)가 없으면 `db/10_load_sample.sql`이 첫 줄에서 멈춘다(`db/README.md`). 키 없이 적재를 시험하려면 합성 표본이 필요하며, MVP의 CI는 "가짜 데이터"로 표시한 합성 표본을 테스트 픽스처로 쓰도록 MVP 계획에 넘긴다(`docs/erd.md` §9 P0-08 행).

## 표본 만들기 (추적하지 않는 CSV 만들기)

VS Code 통합 터미널에서 저장소 루트에서 실행한다(인터프리터 경로가 상대 경로이고, 파일은 어디서 실행해도 저장소 루트 밑에 쓴다). 키(`.env`)와 설치는 `tools/fetch/README.md`를 따른다. 약어: `PY=tools/fetch/.venv/bin/python`.

1. **받기.** `$PY tools/fetch/fetch_p0_08.py fetch --yf --rps 2` (KIS 일별·월봉 + yfinance). 같은 요청의 응답 원본이 `data/raw/p0-08/`에 있으면 네트워크를 부르지 않는다. 처음 한 번은 FRED `DEXKOUS` 파일도 키 없이 받는다: `mkdir -p data/raw/p0-08 && curl -fsSL -o data/raw/p0-08/fred_dexkous.csv 'https://fred.stlouisfed.org/graph/fredgraph.csv?id=DEXKOUS'` (요청 1개, 머리글 `observation_date,DEXKOUS`, 해시는 받은 날짜마다 달라진다 — 아래 "내려받은 원본"). (`probe`는 KIS 과거 깊이 실측용이라 평소에는 건너뛴다.)
2. **대조.** `$PY tools/fetch/fetch_p0_08.py compare`. 두 출처의 월 수익률을 견주어 `compare_report.csv`를 쓴다. 차이가 10bp를 넘는 칸이 판정 없이 남아 있으면 종료 코드 1로 멈춘다.
3. **판정.** 멈춘 칸이 있으면 `compare_report.csv`를 보고 칸마다 `data/raw/p0-08/judgments.csv`에 판정(정본 출처와 사유)을 적는다. 쓰는 법과 `norm`이 멈추는 조건은 `tools/fetch/README.md`의 "판정 기록". 다시 `compare`를 돌려 멈춤이 없는지 확인한다.
4. **정규화.** `$PY tools/fetch/fetch_p0_08.py norm`. 판정 없는 초과가 있으면 파일을 쓰지 않고 멈춘다. 통과하면 `data/raw/p0-08/norm/{daily,dividends,shares}.csv`와 `MANIFEST.csv`가 생긴다.
5. **변환.** 임시 DB에서 `psql -X -q -v ON_ERROR_STOP=1 -d <임시 DB> -f db/sample/build_sample.sql` → `db/sample/` 아래 CSV 4개(`month_block`·`fx_month`·`asset_position`·`asset_month_return`)가 만들어진다. 모든 검사를 통과한 뒤에만 CSV를 쓰므로 중간에 멈추면 기존 CSV는 그대로다. 같은 원본이면 같은 파일이 나오므로 아래 SHA-256과 견주어 재현을 확인한다.
6. **손 대조 기대값.** `hand_check.csv`(`code,expected`)를 "손 대조"의 계산법대로 원본에서 직접 계산해 만든다. 이 파일이 없으면 검증이 FAIL이다.
7. **적재와 검증.** `antlab`에 `db/10_load_sample.sql` 적재, `db/10_load_sample_check.sql` 검증(`db/README.md`).

합성 입력(`tools/fetch/tests/synthetic.py`, `source=SYN`)은 기계 동작 시험 전용이다. `build_sample.sql`은 `-v allow_synthetic=1`일 때만 받으며, 그렇게 만든 CSV는 실제 DB에 적재하지 않는다.

## CSV 열 계약

공통: UTF-8(BOM 없음), LF, 첫 줄 머리글, 쉼표 구분, 날짜 `YYYY-MM-DD`, 소수점 `.`, 천 단위 구분 없음. **NULL은 따옴표 없는 빈 칸**이고 빈 문자열(`""`)은 쓰지 않는다(적재가 거부한다). `id`·`created_at` 열은 없다(erd D12). 행 순서는 자연 키 오름차순(바이트 순서).

| 파일 | 열 (순서 고정) | 규칙 |
|------|----------------|------|
| `asset_group.csv` | `code,name,kind` | `kind`는 `SECTOR`/`ASSET_CLASS` |
| `asset.csv` | `market,ticker,name,asset_type,currency,group_code,listed_on,return_basis,proxy_index_name` | KR 코드는 앞자리 0을 보존한 6자리 문자열. `proxy_index_name`은 대체 지수를 쓰는 ETF만 |
| `month_block.csv` | `month_start` | 달의 1일 |
| `asset_position.csv` | `market,ticker,base_date,base_price,base_fx_rate,market_cap_rank` | 가격·환율은 소수 정확히 4자리(원화 종목의 환율은 `1.0000`), 순위는 정수 또는 빈 칸(ETF) |
| `asset_month_return.csv` | `market,ticker,month_start,local_return,return_kind` | `local_return`은 소수 정확히 8자리 비율(0.01 = 1%), 현지 통화 기준 |
| `fx_month.csv` | `month_start,currency,fx_change` | `currency = USD`, 소수 정확히 8자리 비율 |

## 표본 선정

규칙: (1) 개별 주식은 2007-01 이전 상장 + 시장 시가총액 상위(PLAN §7). (2) ETF의 상장 전 달은 대체 수익률(`PROXY_INDEX`)로 채우되 그런 ETF는 2개로 줄이고, 이미 내려받는 데이터만으로 계산할 수 있는 것을 고른다. (3) PLAN §4.10의 포지션이 고루 나오도록 고른다(판정은 P0-10 몫이며 아래 "예상"은 선정 근거일 뿐이다). (4) 시장 지수 대용은 `069500`(국내)과 `SPY`(미국).

| # | 시장 | 유형 | 코드 | 이름 | 그룹 | 상장일 | 기준 | 상장일 확인 | 선정 이유 |
|---|------|------|------|------|------|--------|------|-------------|-----------|
| 1 | KR | STOCK | 005930 | 삼성전자 | SEMICONDUCTOR | 1975-06-11 | PRICE | 확인 필요 | 시총 1위 |
| 2 | KR | STOCK | 000660 | SK하이닉스 | SEMICONDUCTOR | 1996-12-26 | PRICE | 확인 필요 | 고변동 |
| 3 | KR | STOCK | 005380 | 현대차 | AUTO | 1974-06-28 | PRICE | 확인 필요 | 중간 변동 |
| 4 | KR | STOCK | 033780 | KT&G | CONSUMER_STAPLES | 1999-10-08 | PRICE | 확인 필요 | 필수소비재 |
| 5 | KR | STOCK | 017670 | SK텔레콤 | TELECOM | 1989-11-07 | PRICE | 확인 필요 | 통신 |
| 6 | KR | STOCK | 055550 | 신한지주 | FINANCIALS | 2001-09-10 | PRICE | 확인 필요 | 금융, 2008 하락 |
| 7 | KR | ETF | 069500 | KODEX 200 | EQUITY_INDEX | 2002-10-14 | PRICE | 설정일 2002-10-14 확인(2026-11-07, 데이터 사이트 2곳). 거래 시작일은 확인 필요 | 시장 지수 대용(국내) |
| 8 | KR | ETF | 091160 | KODEX 반도체 | SEMICONDUCTOR | 2006-06-27 | PRICE | 운용사 자료의 최초 설정일 2006-06-26. 상장일 2006-06-27은 확인 필요 | 국내 섹터 ETF |
| 9 | KR | ETF | 360750 | TIGER 미국S&P500 | EQUITY_INDEX | 2020-08-07 | PRICE | 2020-08 확인. 일자는 자료마다 08-06/08-07로 다름 — 확인 필요 | 대체 지수 ETF (기초 SPY) |
| 10 | KR | ETF | 411060 | ACE KRX금현물 | GOLD | 2021-12-15 | PRICE | 2021-12-15 확인(데이터 사이트 1곳, 2026-11-07) | 대체 지수 ETF (기초 GLD) |
| 11 | US | STOCK | AAPL | Apple Inc. | TECH | 1980-12-12 | TOTAL | 확인 필요 | 대형 기술주 |
| 12 | US | STOCK | MSFT | Microsoft Corp. | TECH | 1986-03-13 | TOTAL | 확인 필요 | 대형 기술주 |
| 13 | US | STOCK | NVDA | NVIDIA Corp. | SEMICONDUCTOR | 1999-01-22 | TOTAL | 확인 필요 | 고변동 |
| 14 | US | STOCK | JNJ | Johnson & Johnson | HEALTHCARE | 1944-09-25 | TOTAL | 확인 필요(근사 가능) | 배당, 방어 |
| 15 | US | STOCK | KO | Coca-Cola Co. | CONSUMER_STAPLES | 1919-09-05 | TOTAL | 확인 필요(근사 가능) | 배당, 방어 |
| 16 | US | STOCK | XOM | Exxon Mobil Corp. | ENERGY | 1920-03-01 | TOTAL | 확인 필요(근사 가능) | 에너지, 배당 달 손 대조 |
| 17 | US | ETF | SPY | SPDR S&P 500 ETF Trust | EQUITY_INDEX | 1993-01-29 | TOTAL | 설정일 1993-01-22 확인(자료 1곳). 거래 시작일 1993-01-29는 확인 필요 | 시장 지수 대용(미국) |
| 18 | US | ETF | QQQ | Invesco QQQ Trust | EQUITY_INDEX | 1999-03-10 | TOTAL | 확인 필요 | 성장 지수 |
| 19 | US | ETF | TLT | iShares 20+ Year Treasury Bond ETF | BOND_LONG | 2002-07-26 | TOTAL | 설정일 2002-07-22 확인(자료 다수). 거래 시작일 07-26은 확인 필요 | 장기 국채 |
| 20 | US | ETF | SHY | iShares 1-3 Year Treasury Bond ETF | BOND_SHORT | 2002-07-26 | TOTAL | 설정일 2002-07-22 확인(자료 다수). 거래 시작일 07-26은 확인 필요 | 단기 국채(현금 대용) |

- 합계: KR STOCK 6, KR ETF 4, US STOCK 6, US ETF 4. 대체 수익률 행은 128개(2 종목 × 64개월), 나머지 1,872개는 실제 수익률이다.
- **상장일은 기억에 근거한 값이 남아 있다.** "확인 필요"는 공개 페이지로 아직 맞춰 보지 못했다는 뜻이다. `listed_on`은 복기 전용이고 쓰임은 "2007-01 이전" 판정과 H7 경계(대체 구간의 끝)뿐이다. 설정일과 상장일(거래 시작일)이 다를 수 있어, ETF의 대체 구간 경계(069500·091160·SPY 등은 표본 구간 밖이라 영향이 없다)에서는 360750·411060의 상장월만 결과에 영향을 준다. 두 종목의 상장월은 2020-08과 2021-12로 확인했다.
- 그룹 12개: 섹터 8(`SEMICONDUCTOR` `TECH` `AUTO` `CONSUMER_STAPLES` `TELECOM` `FINANCIALS` `HEALTHCARE` `ENERGY`), 자산군 4(`EQUITY_INDEX` `BOND_LONG` `BOND_SHORT` `GOLD`). 그룹은 시장을 가로지른다(erd D13). 그룹명은 화면에 노출되므로 연도·고유명사를 쓰지 않는다.
- 골키퍼: 원화 단기채·CD ETF는 2007 이전 상장이 없고 대체 지수가 금리 계산이라 추가 출처가 든다. 현금이 골키퍼이므로(PLAN §4.10) 표본에서는 `SHY` 하나로 둔다.

**대체 지수 ETF (PROXY_INDEX).** 상장월과 그 이전 달은 `r = (1 + 기초 ETF 가격 수익률)(1 + 환율 변화) − 1 − 연 총보수/12`로 채운다. 기초는 `360750` → `SPY` 가격(배당 미조정), `411060` → `GLD` 종가다. 상장 다음 달부터는 실제 수익률이다.

| 종목 | 연 총보수 (변환에 쓴 값) | 확인 |
|------|--------------------------|------|
| 360750 | 0.07% (`0.0007`) | 확인 필요. 자료마다 0.0068%·0.06%·0.07%로 엇갈린다(합성총보수 기준 0.07%가 가장 많다). 운용사 공시로 맞춘다 |
| 411060 | 0.19% (`0.0019`) | 확인 필요. 2025-07 인하 뒤 값이며 이전에는 0.50%였다. 2007–2012 대체 구간에 현행 값을 쓰는 것이 맞는지는 소유자 확인 사항이다 |

값은 `build_sample.sql`의 `p_proxy` 표 한 곳에 있다.

## 월 블록 100개와 기준일

| 구간 | 달 | 개수 |
|------|----|------|
| 본 구간 앞 | 2007-01 – 2012-04 | 64 |
| 본 구간 뒤 (판정 창) | 2023-01 – 2025-12 | 36 |
| 기준일 | 2026-01-02 | — |

준비 구간은 없다. 각 구간 첫 달의 수익률에 필요한 직전 월말 가격(2006-12, 2022-12)은 원본에만 있고 월 블록 행이 아니다.

## 변환 규칙 (`build_sample.sql`)

| 항목 | 규칙 |
|------|------|
| 월말 가격 | 그 시장의 그 달 마지막 거래일 종가 |
| 미국 수익률 (`TOTAL`) | 분할·배당 조정 종가의 비: `Padj(월말) / Padj(직전 월말) − 1`. 구간 ①은 yfinance `Adj Close`, 구간 ②는 KIS `MODP=1` 종가(`MODP=1`이 배당락일에 배당을 이미 반영한다고 실측으로 확인했으므로 **배당을 따로 더하지 않는다** — 더하면 배당을 두 번 센다) |
| 국내 주식 수익률 (`PRICE`) | 분할·병합 조정 종가(수정주가)의 비. 배당 미포함 |
| 국내 ETF 수익률 (`PRICE`) | **원주가**(`FID_ORG_ADJ_PRC=1`)의 비. KIS 수정주가는 ETF 분배금을 반영해 가격 수익률 선언과 어긋나므로 원주가를 쓴다. 원주가가 분할·병합으로 끊기지 않았음은 yfinance `Close`와의 같은 날짜 비율 편차(전 기간 0.5bp 미만)와 응답의 권리락 구분 코드로 확인했다 |
| 환율 변화 | FRED `DEXKOUS`(₩/$, 뉴욕 정오)의 그 달 마지막 관측값. 결측(빈 칸)은 건너뛴다. `X(월말) / X(직전 월말) − 1` |
| 기준일 가격 | 기준일의 실제 거래 종가(배당 미조정), 소수 4자리 |
| 기준일 환율 | 기준일의 `DEXKOUS` 관측값, 원화 종목은 1. 소수 4자리 |
| 시가총액 순위 | 주식만. 기준일 시가총액(상장주식수 × 기준일 실제 종가)의 같은 시장 안 순위. ETF는 빈 칸. **현재 전부 빈 칸**이다 — 상장주식수 입력(`norm/shares.csv`)이 아직 없어서이며 후속 작업이다 |
| 반올림 | 모든 비율은 반올림 전 값으로 끝까지 계산하고 CSV에 쓰기 직전 한 번 `ROUND(x, 8)`. 적재는 다시 반올림하지 않는다 |
| 결측·이상치 | 값을 만들지 않는다. 거래일이 없는 달, 직전 월말이 없는 달, 0 이하 가격은 변환이 오류로 멈춘다 |

시각 차이: 국내 월말 종가(서울 15:30)와 `DEXKOUS`(뉴욕 정오)의 시점이 다르다. 대체 수익률의 월말 값이 실제 ETF와 하루 안쪽으로 어긋날 수 있다.

## 원본 입력 계약

`build_sample.sql`은 `tools/fetch`의 `norm`이 만든 정규화 파일 3개와 FRED 파일을 `data/raw/p0-08/`에서 읽는다. 머리글 이름과 순서가 다르면 `HEADER MATCH`로 중단한다. 공통: UTF-8, 쉼표, 날짜 `YYYY-MM-DD`, 소수점 `.`, 천 단위 구분 없음, 한 파일 안 `(market, ticker, 날짜)` 중복 금지.

| 파일 | 머리글 | 내용 |
|------|--------|------|
| `fred_dexkous.csv` | `observation_date,DEXKOUS` | FRED가 내려주는 그대로. 결측은 빈 칸 또는 `.`. 날짜 빈 칸·날짜 중복·0 이하 값은 오류 |
| `norm/daily.csv` | `market,ticker,trade_date,close,close_price,close_raw,source` | 일별. `close` = 수익률 계산 기준 종가(국내 주식 수정주가 / 국내 ETF 원주가 / 미국 KIS 구간 `MODP=1` 종가 / 미국 yfinance 구간 `Adj Close`), `close_price` = 배당 미조정(분할만 조정) 종가(`source = YF` 행에서만 필수, 대체 수익률의 기초 SPY·GLD용), `close_raw` = 그날 실제 종가(기준일 행만 필수, 나머지 빈 칸 허용), `source` ∈ `KIS`/`YF`. `market`·`ticker`·`trade_date`·`close`·`source`의 빈 칸(NULL)은 오류 |
| `norm/dividends.csv` | `market,ticker,ex_date,amount,source` | 미국 종목의 분할 조정 주당 배당. **참고·대조용이며 `build_sample.sql`은 읽지 않는다.** 없으면 머리글만 |
| `norm/shares.csv` | `market,ticker,as_of,shares,source` | 기준일 상장주식수(시가총액 순위용). 머리글만 있으면 순위는 빈 칸. 어느 열이든 빈 칸이면 오류 |

- `source = SYN`(합성 시험 입력)은 `-v allow_synthetic=1`일 때만 받는다. 이때 합성 알림(NOTICE)이 출력된다.
- 필요한 구간: 2006-12-01 – 2012-04-30(구간 ①), 2022-12-01 – 2026-01-02(구간 ②, 기준일까지). 상장이 늦은 두 ETF(`360750`·`411060`)는 구간 ②만, 대체 수익률의 기초 `GLD`는 구간 ①만 받는다.
- 열을 만드는 쪽(`tools/fetch/pipeline.py`)과 읽는 쪽(`build_sample.sql`)의 머리글은 시험으로 맞춰 둔다.

## 출처와 약관 확인

확인한 날짜: 2026-11-07(공개 페이지만 읽음, 법률 판단이 아님). 약관 항목: ① 개인 학습 사용 ② 파생값(월 수익률)을 공개 저장소에 게시 ③ 자동 내려받기(약관·robots, 요청 간격) ④ 출처 표기 요구 ⑤ 상업적 이용 제한(게임에 경제가 생기면 다시 확인) ⑥ 약관 URL. 이 저장소는 공개다.

| 출처 | 쓰임 | ① | ② | ③ | 결과 |
|------|------|---|---|---|------|
| **KIS Open API** (한국투자증권 KIS Developers) | **기본.** 국내 일별(`FHKST03010100`, 주식은 수정주가, ETF는 원주가)과 월봉, 미국 구간 ②의 일별(`HHDFS76240000`, `MODP=1`), 기준일 실제 종가(국내 `FID_ORG_ADJ_PRC=1`, 미국 `MODP=0`) | 불명 | **확인하지 못했다.** KIS 약관의 시세 재배포 조항은 공개 자료(KIS Developers 공개 페이지, 공식 GitHub 저장소 `koreainvestment/open-trading-api` — 저장소는 포털에서 약관을 확인하라고만 안내, 2026-11-07 확인)에서 찾지 못했다. 그래서 보수적으로 적용한다: 받은 시세 원본과 시세·환율에서 만든 값(CSV 포함)은 저장소에 올리지 않고, 만드는 방법만 이 문서로 안내한다 | API 자체가 자동 호출용. 도구가 호출 간격을 두고 요청 3개(토큰 1, 일별 시세 2)만 보냄 | **받았다** (실전 키 사용, `.env`). 원본은 추적하지 않음. 시세에서 만든 CSV도 같은 이유로 추적하지 않음 |
| **yfinance** (Yahoo Finance) | **보조.** 미국 구간 ①의 총수익률(`Adj Close`)과 대체 수익률의 기초 SPY·GLD 가격, 겹치는 기간의 대조, 미국 배당(참고). 상장주식수는 후속 단계 | 상업 목적만 명시 금지 | **금지**(재배포) | 허락 없는 자동 수집을 약관이 금지 | **받았다.** 소유자 결정(2026-11-07, yfinance 최소 범위): 개인 사용·최소 범위·요청 사이 2초이며, yfinance가 섞인 파생 값도 추적 파일에 넣지 않는다(위 "추적하지 않는 이유") |
| **FRED** `DEXKOUS` (연준 H.10) | 환율 일별 | 허용(개인·비영리 목적) | 허용 — 시리즈 분류 "Public Domain: Citation Requested" | robots.txt가 `/graph/fredgraph.csv`를 막지 않음(Crawl-delay 1초), 파일 1개를 한 번 내려받음 | **내려받음** (2026-11-07). 원본 자체는 공개 영역이지만 환율 CSV(`fx_month.csv`)도 시세와 같은 규칙으로 추적하지 않는다(만드는 방법만 안내). https://fred.stlouisfed.org/legal/ · https://fred.stlouisfed.org/series/DEXKOUS |
| KRX 정보데이터시스템(수동), Stooq, Nasdaq.com | — | — | — | 자동화 불가이거나 약관 불분명 | **쓰지 않는다** |
| SEC EDGAR | 미국 발행주식수 | — | — | 요청 머리글에 연락처가 필요 | 쓰지 않는다 (상장주식수는 후속 단계) |

**출처 표기 (FRED).** Board of Governors of the Federal Reserve System (US), South Korean Won to U.S. Dollar Spot Exchange Rate [DEXKOUS], retrieved from FRED, Federal Reserve Bank of St. Louis; https://fred.stlouisfed.org/series/DEXKOUS, 2026-11-07.

FRED 시리즈 페이지에는 "Data in this graph are copyrighted"라는 일반 안내 문구가 같이 보이지만, 같은 페이지의 시리즈 저작권 분류는 "Public Domain: Citation Requested"다. 분류를 따랐다.

## 출처 칸 표 (종목 20 × 구간 2)

한 수익률은 한 출처에서 만든다. 월 수익률의 두 가격은 같은 출처·같은 조정 기준이어야 하므로 출처는 (종목 × 구간) 칸마다 하나다(칸 안에서 섞지 않는다). 구간 ① = 2006-12 – 2012-04(본 구간 앞), 구간 ② = 2022-12 – 2026-01-02(판정 창과 기준일).

| 종목 | 구간 ① | 구간 ② |
|------|--------|--------|
| 005930 000660 005380 033780 017670 055550 (KR 주식) | KIS 수정주가 | KIS 수정주가 |
| 069500 091160 (KR ETF) | KIS **원주가** | KIS **원주가** |
| 360750 411060 (KR ETF) | 상장 전이라 대체 수익률(기초 SPY·GLD의 yfinance 가격) | KIS **원주가** |
| AAPL MSFT NVDA QQQ JNJ KO XOM SPY (US) | **yfinance** `Adj Close`. KIS `MODP=1`을 대조에만 씀 | KIS `MODP=1` |
| TLT SHY (US ETF) | **yfinance** `Adj Close`. KIS에 이 구간이 없어 대조 불가 | KIS `MODP=1` (거래소 코드 `NAS`) |
| GLD (대체 수익률의 기초일 뿐 `asset`에 없음) | yfinance `Close`(분배금 없음). KIS 대조 일치 | — |

- **미국 구간 ①이 yfinance인 이유.** KIS 미국 일별은 2007-08-20 이전을 주지 않는다(오류가 아니라 빈 응답). 구간 ①은 2006-12에 시작하므로 통째로 yfinance를 정본으로 하고, KIS가 주는 2007-09 이후를 대조에 쓴다.
- **미국 배당.** KIS `MODP=1` 종가는 배당락일에 배당을 이미 반영한다(XOM·SPY 배당락일 5곳에서 비율 단계가 배당 수익률과 1bp 안에서 일치, 실측). 그래서 KIS 구간의 미국 `TOTAL`은 `MODP=1` 종가의 비를 그대로 쓰고 배당을 더하지 않는다. 배당은 두 번 세지 않는다.
- **국내 ETF 원주가.** 위 "변환 규칙"의 국내 ETF 행. 수정주가로 받으면 분배금이 가격에 섞여 `PRICE` 선언과 어긋난다.
- **정확한 요청 매개변수와 거래소 코드**(SPY·GLD `AMS`, TLT·SHY·AAPL·MSFT·NVDA·QQQ `NAS`, JNJ·KO·XOM `NYS`)는 `tools/fetch/README.md`.

## 내려받은 원본

데이터 스냅샷 날짜: **2026-11-07**(KIS·yfinance·FRED 모두 이날 받음. P0-14 `season.data_snapshot_date`의 값). 해시는 같은 원본으로 다시 변환했을 때 같은 파일이 나오는지 보는 재현 확인용이다.

| 파일 | 출처 | 행 수(머리글 제외) | SHA-256 |
|------|------|--------------------|---------|
| `data/raw/p0-08/fred_dexkous.csv` | FRED `DEXKOUS`, `https://fred.stlouisfed.org/graph/fredgraph.csv?id=DEXKOUS` | 11,885 (관측값 11,386 + 결측 499, 마지막 관측일 2026-10-30) | `9930e69d70d98edd279bd30e947ad91f8b61319438a2356b1e649133f5b6a49f` |
| `data/raw/p0-08/norm/daily.csv` | KIS 26,046행 + yfinance 14,982행 | 41,028 | `18c35cee3a59bbf3976ca0ef88582869f4766b4eafbfaa3eb31440d00798b451` |
| `data/raw/p0-08/norm/dividends.csv` | yfinance (참고용) | 1,818 | `959a097bab5a7ef6e9b5f4e54c9cc19ee252c8baefe0aebda9234cbd85251626` |
| `data/raw/p0-08/norm/shares.csv` | 없음 (머리글만 — 시가총액 순위 빈 칸) | 0 | `c894987ca53df39085c569602f49ad54281c204d6558bf419e57048df2140529` |
| `data/raw/p0-08/MANIFEST.csv` | 응답 원본 목록(KIS 441 + yfinance 49. 폴더의 모든 응답 원본이며, 그중 2개는 첫 시도의 폐기본이라 `fetch`는 읽지 않는다: 거래소 코드 정정 전 SHY 빈 응답, 달이 안 찬 달을 요구해 멈춘 첫 월봉) | 490 | `4b964a5e8399c931af7c29b3da66447a1660c38fefd71ab96d207a22b96b4114` |

생성 CSV (같은 원본이면 `build_sample.sql`을 두 번 돌려도 바이트가 같다):

| 파일 | 추적 | 행 수 | SHA-256 |
|------|------|-------|---------|
| `month_block.csv` | 예 | 100 | `e799600b3a697ea8ebc80727fc1fa1033ec552d275bd781eea669a44f40324ce` |
| `fx_month.csv` | 아니오 | 100 | `9851bd98702cd5421dfa7eeb7ff8d038276ff15cd610451be85675eefa985cc7` |
| `asset_position.csv` | 아니오 | 20 | `fa1824a7c6f7dd6508270b49674211e20775eaa95110725cf1b283dceaad8d4d` |
| `asset_month_return.csv` | 아니오 | 2,000 (실제 1,872 + 대체 128) | `5caeb0fe8275c4150681ebd010056dd70f998c57bed603cf44c1461ae1aa6c14` |

손 편집 CSV의 해시: `asset_group.csv`(12행) `75d6530ea907555ee690a65e7bc3bd00af278745ca88185b348db90512cc81ba`, `asset.csv`(20행) `2803a3ff176661d4644a2dd921e02e73a77ffa91bd90aebe7bc425b69a5682df`.

적재 결과(임시 PostgreSQL 17에서 01 → 02 → `10_load_sample.sql`): 첫 실행의 새 행은 asset_group 12 · asset 20 · month_block 100 · asset_position 20 · asset_month_return 2,000 · fx_month 100이고, 두 번째 실행은 모두 0이다. 검증 SQL은 57행 전부 PASS(FAIL 0)였다.

## 대조 요약

두 출처의 월 수익률을 견준 결과다(`compare`). 허용치는 |차이| ≤ 1bp 일치, ≤ 10bp 경고(채택하고 기록), 그 위는 멈춤이다. 가격 값은 없고 건수와 차이(bp)만 적는다.

| 종류 | 건수 | 일치 | 경고 | 초과 → 사람 판정 |
|------|------|------|------|------------------|
| CROSS (KIS 월 수익률 ↔ yfinance) | 1,682칸 | 1,584 | 43 (최대 8.8bp) | 55 (차이 10.8–483.0bp), 판정 기록 55 · 미판정 0 |
| INTERNAL (KIS 일별 월말 ↔ KIS 월봉, 같은 출처) | 1,745칸 | 1,677 (EQUAL) | — | ROUND 68 (국내 가격이 정확히 1원 어긋난 칸, 최대 1.11bp), DIFF 0 |
| GAP (한쪽 수익률이 없어 대조 불가) | 54칸 | — | — | 069500·091160 구간 ① 각 27칸(yfinance 원본에 월말 거래일이 없는 달), 사유 `CMP_GAP`, 판정 기록 54 · 미판정 0 |
| NO_OVERLAP | 2칸 | — | — | TLT·SHY 구간 ①(yfinance 단독, 대조 불가) |

초과 55칸의 판정 사유:

| 사유 | 칸 수 | 뜻 | 정본 채택 |
|------|-------|----|-----------|
| `ONE_DAY` | 37 | 월말 종가 하루만 두 출처가 어긋남(국내 주식 35칸, XOM 2칸). 인접한 두 달에서 부호가 반대로 나타나 누적은 상쇄 | KIS(XOM 구간 ①은 yfinance) |
| `YF_MISSING_DAY` | 9 | yfinance가 월말 거래일을 빠뜨려 두 출처의 월말 날짜가 다름(국내 ETF 8칸, 091160 1칸) | KIS |
| `YF_EXTRA_DAY` | 7 | yfinance에만 있는 월말 날짜(연말 휴장일로 의심, 국내 주식 7칸) | KIS |
| `STEP` | 2 | 배당 조정 처리 차이로 보이는 월 중 단계 변화(QQQ 2칸) | 구간 ① yfinance, 구간 ② KIS |

- 모든 초과 칸은 그 칸의 정본 출처 값을 채택했다. 정본 규칙: KIS가 준 칸은 KIS, KIS가 못 준 칸은 yfinance.
- 내부 대조의 100%는 EQUAL 96.1% + ROUND 3.9%다. ROUND는 분할 조정 가격을 정수 원으로 반올림하는 자리의 1원 이하 차이만 허용하며(국내 가격은 정수라 사실상 정확히 1원), 1원을 넘거나 미국이면 멈춘다.
- **한계.** `ONE_DAY`와 `YF_EXTRA_DAY` 44칸은 어느 쪽 종가가 맞는지 이 도구로 가리지 못한다. 제3 출처(예: KRX 공식 종가)로 대표 날짜를 확인하기 전까지는 정본 채택이 가설이다. 이 날짜에 월말이 걸린 달은 수익률이 최대 약 ±4.7% 틀릴 수 있고 인접한 달이 반대로 틀려 누적은 맞는다. 확인할 대표 날짜는 2008-10-31, 2023-01-31(국내 주식), 2008-01-31(XOM), 2008-12-31(국내 주식, 휴장 여부), 2023-07-31(국내 ETF)이다.

## 손 대조

검증 SQL 6절은 SQL이 만든 값과 손으로 계산한 기대값(`hand_check.csv`)이 같은지 본다. 기대값은 원본 파일(KIS 응답, yfinance, FRED)을 `build_sample.sql`을 거치지 않고 직접 읽어 `Decimal`로 계산한다. 반올림은 마지막에 한 번(수익률 소수 8자리, 가격 소수 4자리). 가격 값은 추적 파일에 쓰지 않으므로 아래는 "어느 칸을 어떤 공식으로"만 적는다.

| 코드 | 칸 | 공식 |
|------|----|------|
| 1 | 005930 2008-10 `local_return` | 당월 월말 수정 종가 ÷ 직전 월말 수정 종가 − 1 (KIS) |
| 2 | XOM 2011-08 `local_return` | 같은 식을 yfinance `Adj Close`(분할·배당 조정)로 |
| 3 | 360750 2008-10 `local_return`(대체 수익률) | (1 + SPY 가격 수익률)(1 + 환율 변화) − 1 − 연 총보수 ÷ 12. SPY 가격 수익률은 분할만 조정한 종가의 월말 비 − 1, 환율 변화는 `DEXKOUS` 월말 관측값의 비 − 1 |
| 4 | `fx_month` 2008-10 `fx_change` | `DEXKOUS` 월말 관측값 ÷ 직전 월말 관측값 − 1 |
| 5 | 069500 2023-01 `local_return`(구간 사이를 건너 처음 계산하는 달) | 당월 월말 원주가 ÷ 직전 월말 원주가 − 1 (KIS) |
| base_SPY | SPY 기준일 `base_price` | 2026-01-02 실제 종가(수정 아님, `MODP=0`)를 소수 4자리로 |
| base_SPY_fx | SPY 기준일 `base_fx_rate` | 2026-01-02 `DEXKOUS` 관측값(없으면 직전 관측값)을 소수 4자리로 |

- 코드 1과 5의 당월 월말은 위 "대조 요약"의 제3 출처 확인 대상 날짜다. 그 확인이 끝나면 이 두 칸을 다시 본다.
- 코드 3의 총보수는 `build_sample.sql`의 설정값이다. 이 칸은 값이 계산에 맞게 쓰였는지를 보지 값이 맞는지(운용사 공시)는 보지 않는다.

## 편향·근사 (고정 문구)

- 생존 편향: 표본은 지금 상장되어 있는 종목만이다.
- 국내 종목은 가격 수익률이라 배당이 빠져 연 1–2%p 낮다(PLAN §4.6). 국내 ETF는 원주가를 써서 분배금도 빠진다.
- 대체 구간의 이력 비율이 높은 ETF가 있다(PLAN §4.7).
- **하루짜리 종가 불일치는 정본 출처를 채택했다.** 두 출처 중 어느 쪽이 맞는지 제3 출처로 확인하지 못한 달이 있다(위 "대조 요약"의 한계). 해당 달의 수익률은 최대 약 ±4.7% 틀릴 수 있고 인접 달에서 상쇄된다.
- 미국 구간 ①은 yfinance가 정본이다. KIS와 겹치는 2007-09 이후만 대조했고, TLT·SHY는 구간 ①을 대조하지 못했다. 국내 ETF 069500·091160의 구간 ①은 yfinance가 월말 거래일을 빠뜨려 **대조하지 못한 54칸**이 있다(각 27칸, 판정 기록 `CMP_GAP` — KIS 정본을 유지했고 제3 출처로는 확인하지 못했다).
- 구간 ① 미국 수익률은 yfinance 값의 표현 한계(소수 15–17자리)로 소수 8자리의 끝 자리가 ±1 흔들릴 수 있다.
- 시가총액 순위는 전부 빈 칸이다(상장주식수 후속, 수집 후 채움). 포지션 판정은 시가총액을 쓰지 않으므로(PLAN §4.10, 2026-11-08) 빈 칸이어도 판정에 영향이 없다.
- 대체 수익률은 국내 월말 종가(서울 15:30)와 환율(뉴욕 정오)의 시점 차이, 운용사 공시로 맞추지 못한 총보수 때문에 실제 ETF와 어긋날 수 있다.

## 남은 것

- (선택) KIS 약관의 시세 재배포 조항을 포털에서 읽어 허용이 확인되면 파생 CSV의 추적 여부를 다시 정한다. 그때까지는 보수적 적용(위)이 유지된다.
- 제3 출처로 대표 날짜 확인(소유자). 판정은 값을 바꾸지 않는 확인 기록이므로, 정본 쪽 종가가 틀린 것으로 나오면 칸 단위로 출처를 바꾸지 않는다 — 그 구간의 출처를 바꾸고 다시 대조하거나, 오차를 위 "편향·근사"에 남긴다(그때 소유자가 정한다). 그러면 위 해시와 행 수가 바뀔 수 있어 이 문서를 갱신한다.
- 상장일·총보수의 "확인 필요" 값, 상장주식수(시가총액 순위).
