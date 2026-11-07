# tools/fetch — P0-08 가격 내려받기 도구

KIS Open API(기본)와 yfinance(보조)에서 표본 20종목의 일별 가격을 받아 `data/raw/p0-08/` 아래 파일로 쓴다.
**DB에 접속하지 않는다.** 계산(월말 선택·수익률·반올림)은 `db/sample/build_sample.sql`이 한다. 이 도구는 받기·형식 맞추기·합치기만 한다.
금액·비율은 `Decimal`만 쓴다(float 금지).

## 안전 규칙

- 비밀값(앱 키·시크릿·토큰)은 **저장소 루트 `.env`** 에만 둔다. `.env`는 git이 무시한다. 채팅·이슈·커밋·로그에 붙이지 않는다.
- 실전 도메인 하나만 쓴다(모의 도메인·`KIS_ENV` 전환 없음). 부르는 요청은 **정확히 3개**다: `POST /oauth2/tokenP`, `GET …/quotations/inquire-daily-itemchartprice`(TR `FHKST03010100`), `GET …/quotations/dailyprice`(TR `HHDFS76240000`). 메서드·경로·TR ID가 이 표와 한 글자라도 다르면 요청을 보내기 전에 코드가 거부한다(접두사 허용 없음, 경로 문자는 영문·숫자·`/`·`_`·`-`뿐). 실전 키로 주문·계좌·해시키 요청이 나가지 않는다는 보장이 이 검사다. 리다이렉트도 따라가지 않는다: 요청은 `allow_redirects=False`로 보내고 3xx 응답은 `Location`을 읽지 않은 채 오류로 멈춘다(앱 키·시크릿 머리글이 다른 호스트나 경로로 가지 못하게 한다). 요청 세션은 환경 변수 프록시와 `.netrc`를 읽지 않는다(`trust_env=False`). `compare`·`norm`은 토큰 파일도 열지 않는다.
- 토큰 캐시·원본·norm 경로는 현재 작업 디렉터리가 아니라 **저장소 루트** 기준이다(`KIS_TOKEN_CACHE_DIR`도 상대 경로는 저장소 루트 기준이며 저장소 밖은 거부).
- 로그와 오류 메시지의 키·토큰은 `****`로 가린다.
- yfinance는 키가 없고 개인 사용·최소 범위(미국 배당, 대조용 종가)만 쓰며 요청 사이 2초를 둔다. 출력은 `data/raw/p0-08/yf/`(추적 안 함)에만 둔다.

## 설치 (VS Code 통합 터미널, 저장소 루트)

```
python3 -m venv tools/fetch/.venv
tools/fetch/.venv/bin/pip install -r tools/fetch/requirements-dev.txt   # 실행 의존성(requirements.txt) + pytest
```

검증: Python 3.13.9. 실행만 하려면 `requirements.txt`, 시험까지 하려면 `requirements-dev.txt`(둘 다 `==` 고정).

VS Code: `Cmd+Shift+P` → "Python: Select Interpreter" → `tools/fetch/.venv`.

## `.env` 작성

1. `cp .env.example .env`
2. 편집기에서 `.env`를 열어 `KIS_APP_KEY=`, `KIS_APP_SECRET=` 뒤에 KIS Developers 앱 키·시크릿을 직접 입력한다(따옴표 없이). `KIS_ENV` 변수는 없다(실전 도메인 고정).
3. 계좌번호·HTS ID는 넣지 않는다(시세 조회에 필요 없다).
4. 확인(값은 출력하지 않는다):
   ```
   tools/fetch/.venv/bin/python tools/fetch/fetch_p0_08.py check-env
   ```
   변수 이름별 `있음`/`없음`만 나온다.

워크트리에서 돌릴 때는 `--env-file`로 본 저장소 `.env`를 가리킨다(복사하지 않는다). 하위 명령 앞·뒤 어느 쪽에 써도 된다(`--asset-csv`·`--rps`도 같다):

```
tools/fetch/.venv/bin/python tools/fetch/fetch_p0_08.py check-env --env-file <본 저장소 경로>/.env
```

인터프리터 경로가 상대 경로라 저장소 루트(워크트리 루트)에서 실행하면 편하다. 파일은 어디서 실행해도 저장소 루트 밑에 쓴다.

## 실행 순서

```
PY=tools/fetch/.venv/bin/python
$PY tools/fetch/fetch_p0_08.py probe          # 1) KIS 과거 깊이 실측: 국내 2종목(2006-12부터)·미국 2종목(2007-08-20부터), 행 수와 가장 이른 날짜만 출력
$PY tools/fetch/fetch_p0_08.py fetch --yf --rps 2   # 2) KIS 일별·월봉 전체 + yfinance 일별(Close·Adj Close)·미국 배당
$PY tools/fetch/fetch_p0_08.py compare        # 3) 출처 대조 (원본 캐시만 읽는다. 네트워크 없음) — 판정 없는 10bp 초과가 있으면 종료 코드 1
                                              #    멈추면 "판정 기록"(아래)에 칸마다 판정을 적고 compare를 다시 돌린다
$PY tools/fetch/fetch_p0_08.py norm           # 4) 정규화 합본 3개 + MANIFEST.csv (네트워크 없음) — 대조가 통과한 뒤
```

환율 원본은 이 도구가 받지 않는다. FRED는 키가 필요 없어 `curl` 한 줄로 받는다(저장소 루트에서, `fetch` 전후 아무 때나 한 번):

```
mkdir -p data/raw/p0-08 && curl -fsSL -o data/raw/p0-08/fred_dexkous.csv 'https://fred.stlouisfed.org/graph/fredgraph.csv?id=DEXKOUS'
```

그다음 `psql -X -q -v ON_ERROR_STOP=1 -d <임시 DB> -f db/sample/build_sample.sql`. 전체 순서(손 대조, 적재까지)는 `db/sample/README.md`의 "표본 만들기".

**저장소에 올리지 않는 것.** KIS 약관의 시세 재배포 조항을 공개 자료에서 확인하지 못해 보수적으로 적용한다: 받은 시세 원본과 시세·환율에서 만든 값(CSV 포함)은 저장소에 올리지 않고, 만드는 방법만 문서로 안내한다.

| 구분 | 파일 | git |
|---|---|---|
| 추적(3개, 시세·환율 값 없음) | `db/sample/asset_group.csv`, `asset.csv`, `month_block.csv` | 올린다 |
| 무시(4개, 로컬 생성) | `db/sample/asset_position.csv`, `asset_month_return.csv`, `fx_month.csv`, `hand_check.csv` | `.gitignore` |
| 무시(원본·중간 산출물) | `data/raw/p0-08/` 전체(KIS·yfinance·FRED 원본, norm, compare, judgments) | `.gitignore` |

- 같은 요청의 응답 원본 파일(`data/raw/p0-08/kis/*.json`)이 있으면 **네트워크를 부르지 않는다**. 다시 받으려면 그 파일을 지운다. 그래서 `fetch`를 두 번째 돌리면 요청이 0건이고 `norm` 결과의 SHA-256이 같다.
- 호출 간격: 기본 초당 4건(`fetch --rps 2`처럼 하위 명령 뒤에 쓰거나 `KIS_RPS`로 바꾼다). 호출 한도 오류(`EGW00201`)면 1·2·4초 쉬고 다시, 3번 넘게 실패하면 멈춘다. 토큰은 `data/cache/kis/token_real.json`(권한 600)에 두고 남은 시간이 1시간 넘으면 다시 쓴다.
- `compare`는 |차이| ≤ 0.0001 MATCH, ≤ 0.0010 WARN, 그 위 STOP으로 `data/raw/p0-08/compare/compare_report.csv`에 쓰고, 판정 기록이 설명하지 못하는 STOP·DIFF가 하나라도 있으면 종료 코드 1이다. 행 종류: `CROSS`(KIS 일별 월 수익률 ↔ yfinance: 국내 Close, 미국 Adj Close), `INTERNAL`(KIS 일별 월말 종가 ↔ KIS 월봉 종가: EQUAL/ROUND/DIFF — ROUND는 국내 가격이 1원 이하로 어긋난 칸만(국내 가격은 정수라 사실상 정확히 1원), 분할 조정 가격을 정수 원으로 반올림하는 자리라 허용한다. 1원을 넘거나 미국이면 DIFF로 멈춘다), `NO_OVERLAP`(KIS에 그 구간 일별이 없는 종목). 두 출처의 월 수익률이 **다 있는 달만 견주지 않는다**: 구간 안에서 직전 달 월말이 있어 수익률이 만들어져야 하는 표본 달마다 행을 남긴다. 대조 쪽(정본이 아닌 출처)만 빈 달은 `GAP`(대조 불가, 판정 기록의 `CMP_GAP`으로만 풀린다), 정본 쪽이 빈 달은 `GAP_CANONICAL`(판정으로 풀 수 없는 차단)이다. 판정 기록이 설명한 STOP·GAP은 `JUDGED`로 나온다.
- 출처 규칙(한 종목 × 구간은 한 출처): 국내 주식 전 구간은 KIS 수정주가, 국내 ETF 4종은 KIS **원주가**(아래 "ETF 원주가"), 미국 구간 ②는 KIS(`MODP=1`), 미국 구간 ①(2006-12–2012-04)은 KIS가 2007-08-20 이전을 주지 않아 통째로 yfinance(총수익률 = Adj Close). KIS가 주는 겹치는 기간(2007-08-20 이후)은 대조에만 쓴다. 미국 KIS 구간의 수익률은 `MODP=1` 종가 그대로이며 **배당을 더하지 않는다**(`MODP=1`이 배당락일에 이미 배당을 반영한다 — P1 실측).
- 거래소 코드(KIS는 지금의 상장 거래소로 조회): SPY·GLD `AMS`, TLT·SHY `NAS`(실측: `AMS`·`NYS`는 전 기간 0행), AAPL·MSFT·NVDA·QQQ `NAS`, JNJ·KO·XOM `NYS`. TLT·SHY는 KIS에 2015년 이전 일별이 없어 구간 ①을 대조할 수 없다.
- 부분 응답 검사: 일별 응답의 첫 날짜가 요청 시작 + 5영업일보다 늦거나 끝 날짜가 요청 끝 − 5영업일보다 이르면 오류로 멈춘다(원본 캐시는 남지만 그대로 받아들이지 않는다). `fetch`·`compare`·`norm` 모두 같은 검사를 한다(KIS 구간 일별, yfinance가 정본인 구간 일별). 월봉은 빠진 달이 있으면 멈춘다. yfinance가 행을 0개 주면 캐시에 쓰지 않고 멈춘다.

## ETF 원주가

국내 ETF 4종(069500·091160·360750·411060)은 `FID_ORG_ADJ_PRC=1`(원주가)로 받는다(주식은 `0` = 수정주가, 이름과 값이 반대임에 주의). KIS 수정주가는 ETF 분배금을 가격에 반영해, 월 수익률이 "배당 미포함 가격 수익률"(`return_basis = PRICE`)이라는 선언과 어긋나기 때문이다. 원주가로 받으면 분배월의 어긋남이 사라진다(실측). 일별·월봉 내부 대조·`norm`이 모두 같은 규칙을 쓴다. 코드의 표시는 `Target.kis_adjusted`다.

원주가는 분할·병합으로 끊길 수 있으므로 받은 뒤 확인했다: yfinance `Close`(분할 조정)와 같은 날짜끼리 견준 비율이 전 기간 0.5bp 미만이고, 응답의 권리락 구분 코드에 분배락·배당락 외 값이 없다. 어떤 ETF에 분할·병합이 생기면 그 전후로 비율이 계단식으로 바뀌므로 이 확인을 다시 해야 한다.

## 판정 기록 (`judgments.csv`)

`compare`가 10bp를 넘는 칸(STOP)을 찾으면 도구는 어느 쪽이 맞는지 가리지 못한다. 사람이 칸마다 어느 출처 값을 정본으로 쓸지 결정하고 `data/raw/p0-08/judgments.csv`(git 무시)에 적는다. 파일이 없으면 판정이 없는 것이다.

- 머리글(순서 고정): `market,ticker,segment,month,diff_bp,canonical,reason_code,reason,decision`. 한 행이 한 칸이다. `segment`는 1(구간 ①)·2(구간 ②), `month`는 `compare_report.csv`의 달, `diff_bp`는 보고서의 차이(소수 2자리 그대로), `canonical`은 채택할 출처(`KIS` 또는 `YF`), `reason_code`는 사유 분류(예: `ONE_DAY` 하루짜리 종가 불일치, `YF_MISSING_DAY` yfinance가 월말 거래일을 빠뜨림, `YF_EXTRA_DAY` yfinance에만 있는 날짜, `STEP` 월 중 단계 변화), `reason`은 한 줄 설명(비면 오류), `decision`은 `CANONICAL`(그 칸의 정본 출처 값을 채택)만 허용된다. 같은 칸을 두 번 적으면 오류다.
- 판정은 (시장, 종목, 구간, 달)이 같고 **`diff_bp`와 `canonical`이 보고서와 같을 때만** 그 칸의 STOP을 `JUDGED`로 바꾼다. 데이터가 바뀌어 차이가 달라지면 낡은 판정이라 다시 STOP이 된다.
- **STOP 동작.** 판정 없는 STOP·DIFF가 있거나, 낡은 판정·쓰이지 않은 판정이 있으면 `compare`는 종료 코드 1이고 `norm`은 "norm 중단"을 출력하며 **정규화 파일과 MANIFEST를 쓰지 않는다.** `norm`은 먼저 대조를 다시 돌리므로 판정을 고친 뒤 `norm`만 다시 돌려도 된다.
- 대조 불가 칸(`GAP`)은 `reason_code`가 `CMP_GAP`이고 `diff_bp` 칸이 문자열 `GAP`인 행으로만 풀린다(둘은 함께여야 하며, 하나만 있으면 읽을 때 오류다). 정본 쪽이 빈 달(`GAP_CANONICAL`)은 어떤 판정으로도 풀리지 않는다 — 원본을 다시 받는다.
- 판정은 구간의 고정 정본 출처를 확인한 기록이다. 값을 바꾸지 않고, `canonical`이 구간의 정본 출처와 다르면 낡은 판정으로 멈춘다. 제3 출처(예: KRX 공식 종가)로 정본 쪽 종가가 틀린 것으로 나와도 칸 단위로 출처를 바꾸지 않는다 — 구간 출처를 바꾸고 다시 대조하거나, 오차를 `db/sample/README.md`의 "편향·근사"에 남긴다(그때 소유자가 정한다).

## 출력 파일 (모두 git 무시)

| 경로 | 내용 |
|---|---|
| `data/raw/p0-08/kis/*.json` | KIS 응답 원본(요청 매개변수로 만든 이름) |
| `data/raw/p0-08/yf/*.csv` | yfinance 원본 (`yf_px_*` 일별 Close·Adj Close, `yf_div_*` 배당). 값이 없는(NaN) 행은 읽을 때 건너뛴다 |
| `data/raw/p0-08/norm/daily.csv` | `market,ticker,trade_date,close,close_price,close_raw,source` — `close` 수익률 계산 기준 종가(국내 수정 종가 / 미국 KIS 구간 `MODP=1` 종가 / 미국 yfinance 구간 Adj Close), `close_price` 배당 미조정(분할만 조정) 종가(`source=YF` 행에서만, 대체 수익률의 기초 SPY·GLD용), `close_raw` 기준일 실제 종가 |
| `data/raw/p0-08/norm/dividends.csv` | `market,ticker,ex_date,amount,source` — 미국 분할 조정 주당 배당. **참고·대조용이며 `build_sample.sql`은 읽지 않는다**(KIS 가격에 배당을 더하는 경로 없음) |
| `data/raw/p0-08/norm/shares.csv` | `market,ticker,as_of,shares,source` — 상장주식수(머리글만 있으면 시가총액 순위는 빈 칸) |
| `data/raw/p0-08/MANIFEST.csv`, `compare/` | 파일 목록·해시, 대조 결과(`compare_report.csv`) |
| `data/raw/p0-08/judgments.csv` | 사람 판정 기록(위 "판정 기록"). `fetch`·`compare`·`norm`이 읽기만 하고 쓰지 않는다 |

## 시험

```
tools/fetch/.venv/bin/python -m pytest -q tools/fetch
```

네트워크·`.env` 없이 돈다(가짜 응답): 연속 조회, 정규화, 마스킹, 허용 목록(메서드·경로·TR ID 정확 일치) 거부, 다른 작업 디렉터리에서도 저장소 루트 밑에만 쓰기, 캐시 히트 시 요청 0, 리다이렉트 거부(가짜 응답과 실제 `requests.Session` 위의 메모리 어댑터), 대조 범위(빈 달 `GAP`·`GAP_CANONICAL`, compare·norm의 부분 응답 검사, yfinance 0행), 국내 ETF 원주가가 실제 요청 매개변수에 실리는지, 판정 기록(일치하면 `JUDGED`, 차이가 다르면 낡은 판정, 판정 없으면 `norm` 중단), 내부 대조 `ROUND`(1원 이하)·`DIFF`(2원).
`tests/synthetic.py`는 파이프라인 시험용 **합성(가짜) 가격**을 만든다(`source=SYN`). `build_sample.sql`은 `-v allow_synthetic=1`일 때만 받으며, 이렇게 만든 CSV는 실제 DB에 적재하지 않는다.
