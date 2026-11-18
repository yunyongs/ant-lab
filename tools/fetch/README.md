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

## Incremental batch fetch (MVP-04) / 증분 배치 내려받기

**English.** `fetch_batch.py` fetches the full-history prices in small unattended daily batches. It reads `db/full/fetch_targets.csv` (columns `market,ticker,kis_excd,kis_basis,kis_from,yf_symbol,yf_canonical_to,order_no`; exact header, `order_no` strictly increasing). Run it from the **main checkout** with the repository `.env`; it refuses to send from a git worktree.

```
PY=tools/fetch/.venv/bin/python
$PY tools/fetch/fetch_batch.py status                   # no network: states, failed assets, requests today
$PY tools/fetch/fetch_batch.py sweep --daily-cap 400    # monthly bars + base-date close, one asset at a time
$PY tools/fetch/fetch_batch.py drip                     # daily closes + base-date close (daily cap 600)
$PY tools/fetch/fetch_batch.py yf                       # one full-range yfinance call per symbol (cap 50)
```
Run `drip` and `yf` one after the other, not together (they share one lock).

Folders. `--raw-dir` (default `data/raw/mvp-04/`) must be inside `data/` (git-ignored) and is never the P0-08 cache (`data/raw/p0-08/`, compared by real folder, not spelling). The ledger `ledger.csv` (`day_kst,source,batch_no,requests,stop_reason,first_target,last_target`), `token_log.csv` and `batch.lock` always live in `data/raw/mvp-04/`, whatever `--raw-dir` says, so a second raw dir cannot bypass the cap or the one-token rule.

Rules enforced in code: KIS 1 request per second (fixed, no flag); daily cap 600 by default, never above 1,500 (yfinance 50), read from the ledger before anything is sent and counting token requests and retries; one batch per KST day and source unless the owner types `--same-day` (a batch that sent 0 requests does not use up the day); run window 21:00–23:00 KST unless the owner types `--outside-window`, checked **before every request** and for the same KST day; an asset that cannot finish before 23:00 (estimate plus 30 s) is not started, and an asset cut at 23:00 is not used: the next night its pages count as an earlier fetch day, are moved to `stale/<day>/` and the asset is fetched whole; whole assets only (the batch stops before an asset whose estimated pages do not fit); one `tokenP` per KST day, no token retry; EGW00201 → wait 1, 2, 4 s, then the batch ends; any non-200, redirect, non-JSON answer or `rt_cd` ≠ 0 ends the batch; pages and yfinance files are written to a temporary name and renamed, and a file that does not parse counts as missing; a re-run sends 0 requests for finished assets (`done/*.json`).

Failed assets. A failed data check (KIS) or an empty yfinance answer does **not** end the batch: the asset is written to `failed/<kind>_<market>_<ticker>.json`, the batch goes on, ends as `DONE_WITH_FAILED`, and the asset is not tried again; `status` lists it. Delete that file to retry. Three failed assets in a row end the batch as `FAILED_RUN` (a failing source, not failing assets) and are **not** marked.

Crash safety. Before every request one line is appended (and flushed to the drive) to `batch.lock`. If a batch dies (kill, power loss), the next start books those requests as a `CRASH` ledger row and frees the lock once the process is gone; a lock of a live process is never taken. SIGTERM and SIGHUP still write a ledger row. **Never delete `batch.lock` (or `batch.lock.dead.*`) by hand**: it holds the count of requests already sent, and deleting it can let a second batch exceed the daily cap.

Exit codes: 0 finished or stopped cleanly (`DONE`, `DONE_WITH_FAILED`, `BUDGET`, `WINDOW_END`); 1 stopped by an error (`RATE_LIMIT`, `HTTP`, `REDIRECT`, `NON_JSON`, `RT_CD`, `TOKEN`, `CONNECTION`, `FAILED_RUN`, `YF_ERROR`, `CRASH`, `SIGTERM`, `SIGHUP`); 2 bad input; 3 refused before sending.

**한국어.** `fetch_batch.py`는 전체 이력 가격을 무인 일일 소량 배치로 받는다. `db/full/fetch_targets.csv`를 읽고 **본 체크아웃**에서 저장소 `.env`로 돌리며, git 워크트리에서는 보내기를 거부한다. 명령은 위 영어 블록과 같다(`status`는 네트워크 없음). `drip`과 `yf`는 동시에 말고 차례로 돌린다(잠금 하나를 공유).

폴더: `--raw-dir`(기본 `data/raw/mvp-04/`)는 `data/` 안이어야 하고 P0-08 캐시(`data/raw/p0-08/`)는 거부한다(철자가 아니라 실제 폴더로 비교). 장부 `ledger.csv`, `token_log.csv`, `batch.lock`은 `--raw-dir`와 상관없이 항상 `data/raw/mvp-04/`에 있어 다른 폴더로 상한·토큰 규칙을 피할 수 없다.

코드가 강제하는 규칙: KIS 초당 1건(고정), 하루 상한 기본 600(최대 1,500, yfinance 50)을 보내기 전에 장부로 확인(토큰·재시도 포함), KST 하루 출처별 배치 1회(`--same-day`는 소유자만), 실행 창 21:00–23:00 KST(`--outside-window`는 소유자만)를 **요청마다** 확인하고 같은 KST 날짜여야 함, 23:00 전에 끝낼 수 없는 종목은 시작하지 않고 23:00에 잘린 종목은 쓰지 않음(다음 날 `stale/`로 옮기고 처음부터 다시), 종목 단위로만 받기, 토큰 하루 1회(재시도 없음), `EGW00201`은 1·2·4초 후 배치 종료, 200 아님·리다이렉트·JSON 아님·`rt_cd`≠0은 배치 종료, 임시 이름에 쓰고 이름 바꾸기(읽히지 않는 파일은 없는 것), 끝난 종목은 재실행 시 요청 0건.

실패 종목: KIS 자료 검사 실패나 yfinance 빈 응답은 배치를 끝내지 않는다. `failed/` 아래에 표시하고 계속하며 `DONE_WITH_FAILED`로 끝나고 다시 시도하지 않는다(`status`에 표시, 파일을 지우면 재시도). 연달아 3종목이 실패하면 `FAILED_RUN`으로 끝내고 그 3종목은 표시하지 않는다.

비정상 종료 안전: 요청마다 `batch.lock`에 한 줄을 먼저 적는다(디스크까지 flush). 강제 종료·정전이면 다음 시작에서 `CRASH` 행으로 장부에 올리고, 살아 있는 프로세스의 잠금은 건드리지 않는다. SIGTERM·SIGHUP도 장부 행을 남긴다. **`batch.lock`(과 `batch.lock.dead.*`)을 손으로 지우지 않는다.** 이미 보낸 요청 수가 들어 있어, 지우면 하루 상한을 넘길 수 있다.

종료 코드: 0 정상 종료(`DONE`, `DONE_WITH_FAILED`, `BUDGET`, `WINDOW_END`), 1 오류 중단(`RATE_LIMIT`, `HTTP`, `REDIRECT`, `NON_JSON`, `RT_CD`, `TOKEN`, `CONNECTION`, `FAILED_RUN`, `YF_ERROR`, `CRASH`, `SIGTERM`, `SIGHUP`), 2 입력 오류, 3 보내기 전 거부.

Tests: `tests/test_batch_*.py` (fake KIS in `tests/fake_kis.py`, fake clock, fake yfinance; no network, no `.env`).

## Full-history compare, judgement and norm (MVP-04 PR B) / 전체 이력 대조·판정·합본

**English.** After the batches have fetched an asset (`status` shows `DONE` for `sweep`, `drip` and, where `yf_symbol` is set, `yf`), three offline commands work on the cached pages only. They send nothing (a page that is not cached is a problem for that asset, never a request), refuse a git worktree and a `--raw-dir` outside `data/`, and need no `.env`.

```
PY=tools/fetch/.venv/bin/python
$PY tools/fetch/fetch_batch.py compare                  # compare/compare_report.csv, compare/refetch.csv; exit 1 while anything blocks
$PY tools/fetch/fetch_batch.py compare --refetch-diff   # also move assets with an INTERNAL DIFF or a missing bar to stale/ (takes the batch lock)
$PY tools/fetch/fetch_batch.py judge                    # RULE rows into judgments.csv, the rest into compare/human_queue.csv
$PY tools/fetch/fetch_batch.py norm                     # norm/{daily,dividends,shares}.csv and MANIFEST.csv; refuses unless everything is done and clean
```

Segments come from `fetch_targets.csv`: KIS from `kis_from`; for US assets yfinance is canonical for return months up to `yf_canonical_to` (segment 1) and KIS after it (segment 2); KR assets and US assets without a cut are one KIS segment; an asset without KIS data is one yfinance segment. The first return month of a segment is the month after its source's first month, the last is December of the year before the base date (the base date itself is a price only). `compare` reads the same pages the batches wrote (sweep window `month_window(1995-12-01, base)`, drip range `drip_range`, one full-range yfinance file), so it makes 0 requests.

Rows. `CROSS`: canonical vs the other source, monthly return difference, ≤ 1 bp `MATCH`, ≤ 10 bp `WARN`, above `STOP`; `GAP` = the other source lacks a month inside its own range, `GAP_CANONICAL` = the canonical source lacks a month (not judgeable: refetch); months before the other source's first month are not compared. `INTERNAL`: KIS daily month-end close vs the sweep's monthly bar: `EQUAL` / `ROUND` (KR, ≤ 1 won) pass, `DIFF` means the adjustment changed between the sweep day and the drip day, `NO_BAR` = no bar for a month that has daily data. One bar per month is used (the latest-dated one: a KIS monthly page boundary repeats a partial bar of that month). An asset with `DIFF` or `NO_BAR` is listed in `compare/refetch.csv`; `--refetch-diff` moves its pages and `done/` markers to `stale/<day>/` (nothing is deleted) and the next sweep and drip fetch it whole.

Judgements (`data/raw/mvp-04/judgments.csv`, B9). Header = the P0-08 columns plus `signed_diff_bp,kis_month_end,yf_month_end,proposed_by` (`RULE` / `HUMAN`). `signed_diff_bp` = KIS return minus yfinance return. A row is valid only if `diff_bp`, `signed_diff_bp`, `canonical` and both month-end dates equal the report; otherwise the cell stays open as a stale judgement. `judge` proposes `RULE` rows for three mechanical classes, always with `canonical` = the cell's fixed source: `YF_MISSING_DAY` / `YF_EXTRA_DAY` (the two month-end dates of the cell differ; yfinance's is earlier / later) and `ONE_DAY` (an adjacent month has the opposite-sign difference and the two-month product is within 10 bp). A difference above 500 bp, a `STEP` (same-sign neighbour above 10 bp), an unclassified cell, `GAP` and `GAP_CANONICAL` are never proposed: they go to `compare/human_queue.csv` with a `hint`. `judge` re-derives stale or unused `RULE` rows and never changes or deletes a `HUMAN` row. A judgement records that the fixed canonical source was kept; it never changes a value. `judge` exits 0 only when the queue is empty.

`norm` writes `daily.csv` (same 7 columns as P0-08): per segment only its canonical source, from the month before the first return month; KIS rows run to the base date and carry the base-date actual close. Where two segments meet (the cut month) both sources hold rows of the same dates: rows are told apart by `source`. A second run gives the same SHA-256 and sends nothing; files are written to a temporary name and renamed after all of them are complete.

**한국어.** 배치가 종목을 받은 뒤(`status`에서 `sweep`·`drip`, `yf_symbol`이 있으면 `yf`도 `DONE`) 세 명령이 원본 캐시만 읽는다. 요청을 보내지 않고(캐시에 없는 페이지는 그 종목의 문제일 뿐 요청이 아니다), git 워크트리와 `data/` 밖 `--raw-dir`을 거부하며 `.env`가 필요 없다. 명령은 위 영어 블록과 같다.

구간은 `fetch_targets.csv`에서 나온다: KIS는 `kis_from`부터, 미국은 `yf_canonical_to`까지의 수익률 월이 yfinance 정본(구간 1)이고 그 뒤가 KIS(구간 2), 국내와 컷이 없는 미국은 KIS 한 구간, KIS 자료가 없는 종목은 yfinance 한 구간이다. 구간의 첫 수익률 월은 그 출처의 첫 달 다음 달, 마지막은 기준일 전년 12월이다. `compare`는 배치가 쓴 페이지(sweep 월봉 구간, drip 일별 구간, yfinance 전체 구간 파일)를 그대로 읽어 요청이 0건이다.

행 종류. `CROSS`: 정본과 다른 출처의 월 수익률 차이(≤ 1bp `MATCH`, ≤ 10bp `WARN`, 초과 `STOP`), `GAP`(대조 쪽이 자기 범위 안에서 한 달을 빠뜨림), `GAP_CANONICAL`(정본이 빠뜨림 — 판정 불가, 다시 받기). `INTERNAL`: KIS 일별 월말 종가 대 sweep 월봉: `EQUAL`/`ROUND`(국내 1원 이하)는 통과, `DIFF`는 sweep일과 drip일 사이 수정주가가 바뀐 것, `NO_BAR`는 일별은 있는데 월봉이 없는 달. 달마다 가장 늦은 날짜의 봉 하나만 쓴다(KIS 월봉 페이지 경계는 그 달의 부분 봉을 한 번 더 준다). `DIFF`·`NO_BAR` 종목은 `compare/refetch.csv`에 오르고, `--refetch-diff`가 페이지와 `done/` 표시를 `stale/<날짜>/`로 옮긴다(지우지 않음). 다음 sweep·drip이 종목 전체를 다시 받는다.

판정(`data/raw/mvp-04/judgments.csv`). 머리글 = P0-08 열 + `signed_diff_bp,kis_month_end,yf_month_end,proposed_by`(`RULE`/`HUMAN`). `diff_bp`·`signed_diff_bp`·`canonical`·두 월말 날짜가 보고서와 같아야 유효하고, 다르면 낡은 판정이라 칸이 다시 열린다. `judge`는 기계적인 세 가지에 `RULE` 행을 제안한다(정본은 항상 그 칸의 고정 출처): `YF_MISSING_DAY`/`YF_EXTRA_DAY`(두 월말 날짜가 다르고 yfinance 쪽이 이르다/늦다), `ONE_DAY`(인접 달 차이가 반대 부호이고 두 달 곱이 10bp 이내). 500bp 초과, `STEP`, 분류 불가, `GAP`·`GAP_CANONICAL`은 제안하지 않고 `hint`와 함께 `compare/human_queue.csv`로 간다. `judge`는 낡거나 쓰이지 않은 `RULE` 행을 다시 만들고 `HUMAN` 행은 바꾸거나 지우지 않는다. 판정은 값을 바꾸지 않는다. 큐가 비었을 때만 종료 코드 0이다.

`norm`은 `daily.csv`(P0-08과 같은 7열)를 쓴다: 구간마다 정본 출처의 행만, 첫 수익률 월의 전달부터. KIS 행은 기준일까지 이어지고 기준일 실제 종가가 붙는다. 두 구간이 만나는 달(컷 달)은 두 출처의 같은 날짜 행이 함께 있으므로 `source`로 구분한다. 두 번째 실행은 같은 SHA-256이고 요청이 없으며, 파일은 임시 이름에 모두 쓴 뒤 이름을 바꾼다.

**For the full build (06-A) / 전체 빌드(06-A)용.** English: in the cut month `norm/daily.csv` holds rows of both sources. The last yfinance return month (the cut month, e.g. December) must be computed from yfinance's previous and own month-end; the first KIS return month (the next January) from KIS's own December and January month-ends. Never take a month-end from the other source, and never de-duplicate by date alone. A yfinance canonical series must start no later than 2006-12 (`compare` reports a front-truncated cache as an asset problem). `compare` and `judge` exit 1 while any asset is not done. A `RULE` judgement is valid only while its class still derives from the current neighbours; `judge` re-derives every `RULE` row each run and keeps `HUMAN` rows. 한국어: 컷 달의 마지막 yfinance 수익률은 yfinance의 전월·당월 월말로, 첫 KIS 수익률(다음 1월)은 KIS의 12월·1월 월말로 계산한다(날짜만으로 중복 제거 금지). yfinance 정본 시리즈는 2006-12 이전에 시작해야 하고, 미완료 종목이 있으면 `compare`·`judge`는 종료 코드 1이며, `RULE` 판정은 현재 이웃에서 같은 분류가 나올 때만 유효하고 `judge`는 매번 모든 `RULE` 행을 다시 만든다(`HUMAN` 행 유지).
