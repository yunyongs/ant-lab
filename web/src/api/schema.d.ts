export interface paths {
    "/api/v1/pool": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Asset pool on the reference date / 기준일 종목 풀
         * @description Returns the whole pool of the server-configured reference date, ordered by market and asset code. Each asset carries its system-judged position and the judgment basis (participation rates, volatility, maximum drawdown; KRW, monthly, 8 decimal places).
         *
         *     서버 설정의 기준일 풀 전체를 시장·종목코드 순서로 준다. 시스템 판정 포지션과
         *     판정 근거(참여율·변동성·최대 낙폭, 원화·월 단위·소수 8자리)를 함께 준다.
         */
        get: operations["getPool"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/signup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sign up / 가입
         * @description Creates a member with the USER role from an email, a display name and a password. Open self-signup without email verification (v0.1). The password is stored only as a BCrypt hash.
         *
         *     이메일·표시 이름·비밀번호로 USER 역할의 회원을 만든다. 이메일 확인 없는 자유 가입이다(v0.1). 비밀번호는 BCrypt 해시로만 저장한다.
         */
        post: operations["signup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Log in / 로그인
         * @description Checks the email (in any letter case) and password and starts a login. The access token (JWT, 15 minutes) is in the body; keep it in memory only. The refresh token is set as an HttpOnly cookie for /api/v1/auth and is never in a body.
         *
         *     이메일(대소문자 무시)과 비밀번호를 확인하고 로그인을 시작한다. 접근 토큰(JWT, 15분)은 본문에 있으며 메모리에만 둔다. 갱신 토큰은 /api/v1/auth 경로의 HttpOnly 쿠키로 설정되고 본문에는 없다.
         */
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Refresh the access token / 접근 토큰 갱신
         * @description Rotates the refresh token from the antlab_refresh cookie: the presented token is retired, a new cookie and a new access token are returned. Presenting an already rotated token again ends that login on every device that shares it.
         *
         *     antlab_refresh 쿠키의 갱신 토큰을 회전한다: 제시한 토큰은 폐기하고 새 쿠키와 새 접근 토큰을 돌려준다. 이미 회전한 토큰을 다시 제시하면 그 로그인이 모든 기기에서 끝난다.
         */
        post: operations["refresh"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Log out / 로그아웃
         * @description Ends the login of the antlab_refresh cookie and clears the cookie. Always answers 204, also without a cookie.
         *
         *     antlab_refresh 쿠키의 로그인을 끝내고 쿠키를 지운다. 쿠키가 없어도 언제나 204로 답한다.
         */
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * My seasons / 내 시즌 목록
         * @description Returns all seasons of the current member, newest season number first. / 현재 회원의 시즌 전부를 시즌 번호 내림차순으로 준다.
         */
        get: operations["listSeasons"];
        put?: never;
        /**
         * Start a season / 시즌 시작
         * @description Starts a new season from a risk limit, a formation and a path length. Initial capital and rates come from the server configuration. The season, path, cash account, deposit and turn-0 prices are created in one transaction.
         *
         *     위험 선언·포메이션·경로 길이로 새 시즌을 시작한다. 초기 자금·요율은 서버 설정이 정한다.
         *     시즌·경로·현금 계좌·입금·0턴 가격이 한 트랜잭션으로 만들어진다.
         */
        post: operations["startSeason"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One season / 시즌 한 개
         * @description One season of the current member. 404 if it does not exist or belongs to another member. / 현재 회원의 시즌 한 개. 없거나 다른 회원의 시즌이면 404.
         */
        get: operations["getSeason"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/abandon": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Abandon a season / 시즌 포기
         * @description Marks the season as abandoned at the current turn, then runs the remaining turns with no trades through the normal turn path, one transaction per turn, until the season is finished or the per-request turn cap is reached. The same request continues an abandon that stopped midway: repeat it while the status is ABANDONING. A repeat on a finished abandoned season answers 200 and changes nothing. notStarted is true for a human member who abandoned before turn 3.
         *
         *     시즌을 현재 턴에서 포기로 표시하고, 남은 턴을 거래 없이 보통의 턴 전환 경로로 턴마다 한 트랜잭션씩 시즌이 끝나거나 요청당 턴 상한에 닿을 때까지 진행한다. 중간에 멈춘 포기는 같은 요청으로 이어 간다 — 상태가 ABANDONING 이면 반복한다. 이미 끝난 포기 시즌에 반복하면 200 이고 아무것도 바꾸지 않는다. 사람 회원이 3턴 전에 포기하면 notStarted 가 true 다.
         */
        post: operations["abandonSeason"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/prices": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Turn prices / 턴 가격
         * @description Returns the prices of the whole pool for one turn, ordered by asset number. If turnNo is omitted, returns prices for the current turn.
         *
         *     한 턴의 풀 전체 가격을 종목 번호 순으로 준다. turnNo 를 생략하면 현재 턴.
         */
        get: operations["getTurnPrices"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/projection": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Projection of the team / 팀의 예상 분포
         * @description Shows how the value of the season's team could spread over the coming turns: percentile bands per turn, and for a few horizons the end value, the worst drawdown and the chance of breaching the risk limit. phase CURRENT (default) is computed now for the current team. START is the team after the turn-0 team building and END the final team of a finished season; each is computed once and kept. Won values come from the engine's multiples with one rounding.
         *
         *     시즌 팀의 가치가 앞으로 어떻게 퍼질 수 있는지 보여 준다: 턴별 백분위 구간, 몇 개 기간의 끝 가치·최악의 낙폭·위험 선언을 넘을 확률. phase 가 CURRENT(기본)면 현재 팀을 지금 계산한다. START 는 0턴 팀 구성 뒤의 팀, END 는 끝난 시즌의 마지막 팀이며 각각 한 번 계산해 보관한다. 원화 값은 엔진의 배수에서 한 번만 반올림해 만든다.
         */
        get: operations["getSeasonProjection"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Order (market buy or sell) / 주문(시장가 매수·매도)
         * @description Fills one order immediately at the current turn price. Turn-0 team setup and buys from turn 1 onward use the same path. A buy takes the amount and fee out of cash and changes holdings and lineup together. A sell adds the amount to cash, deducts the fee, records the realized P&L, and closes the position span when everything is sold.
         *
         *     현재 턴 가격으로 한 건을 바로 체결한다. 0턴 팀 구성과 1턴 이후 매수가 같은 경로다. 매수는 대금과 수수료가 현금에서 빠지고 보유·라인업이 함께 바뀐다. 매도는 대금이 현금에 더해지고 수수료가 빠지며 실현 손익이 기록되고, 전량 매도면 구간이 닫힌다.
         */
        post: operations["placeOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/orders/quote": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Order preview (quote) / 주문 미리보기(견적)
         * @description Takes the same body as placeOrder and answers what that order would fill now: fill price, amount, fee, its FX part, cash after the fill and, for a buy, the largest quantity that fits. It runs the same checks in the same order and refuses with the same errors, but writes nothing and is not counted as a rejected order.
         *
         *     placeOrder 와 같은 본문을 받아 그 주문이 지금 어떻게 체결될지 답한다: 체결가, 대금, 수수료와 그중 환전 비용, 체결 뒤 현금, 매수면 들어갈 수 있는 최대 수량. 같은 검사를 같은 순서로 하고 같은 오류로 거절하지만 아무것도 쓰지 않으며 거절된 주문으로 세지 않는다.
         */
        post: operations["quoteOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/formation-changes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Formation change / 전술 변경
         * @description Changes the current formation to a new value and keeps the change history. It can be changed several times in the same turn and every change is recorded. Orders placed after a change in the same turn are tied to the latest change. It cannot be changed at turn 0 while the team is being built.
         *
         *     현재 포메이션을 새 값으로 바꾸고 변경 이력을 남긴다. 같은 턴에 여러 번 바꿀 수 있고 모두 기록된다. 변경 뒤 같은 턴의 주문은 가장 마지막 변경에 묶인다. 팀 구성 중인 0턴에는 바꿀 수 없다.
         */
        post: operations["changeFormation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/lineup-changes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Position-only change / 포지션 단독 변경
         * @description Changes the declared position of a held asset without any trade: the open span is closed at the current turn and a new span opens, so the next turn values the asset under the new position. No order, fee or formation change is created. If the position is already the requested one the answer is 200 with changed = false and nothing is written. It cannot be changed at turn 0 while the team is being built.
         *
         *     거래 없이 보유 종목의 선언 포지션만 바꾼다. 열린 구간을 현재 턴에 닫고 새 구간을 열어 다음 턴부터 새 포지션으로 평가한다. 주문·수수료·전술 변경은 만들지 않는다. 이미 그 포지션이면 changed = false 로 200 을 답하고 아무것도 쓰지 않는다. 팀 구성 중인 0턴에는 바꿀 수 없다.
         */
        post: operations["changeLineup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/turns": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Advance the turn (next month) / 턴 전환(다음 달)
         * @description Applies the next month block. In one transaction it builds the next-turn prices of the whole pool and the turn snapshot; if all 17 reconciliation checks match, it records them and advances the current turn. After the last turn the season ends. If any check mismatches, only the reconciliation log is kept and the answer is 409.
         *
         *     다음 달 블록을 적용한다. 한 트랜잭션에서 풀 전체의 다음 턴 가격과 턴 스냅샷을 만들고 대사 17개가 모두 일치하면 기록하고 현재 턴을 올린다. 마지막 턴이면 시즌이 끝난다. 대사가 하나라도 어긋나면 대사 기록만 남기고 409다.
         */
        post: operations["advanceTurn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/turns/{turnNo}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Turn result lookup / 턴 결과 조회
         * @description Returns the result of one turn (valuation, change, cash, cumulative drawdown, comparison with the risk limit, valuation and share per position, formation deviation). It has the same shape as the advance-turn response. status and pathLength are the season's values now, and the sum of the four shares can differ from 1 by at most 2e-8. It is the same for a season in progress and a finished one.
         *
         *     한 턴의 결과(평가액·변화·현금·누적 낙폭·위험 선언 대비·포지션별 평가액과 비중·포메이션 차이)를 준다. 턴 전환 응답과 같은 형이다. status·pathLength 는 시즌의 지금 값이고, 비중 4개의 합은 1과 최대 2e-8 다를 수 있다. 진행 중이든 끝난 시즌이든 같다.
         */
        get: operations["getTurn"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/turns/{turnNo}/ranking": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Ranking board of a turn / 턴의 순위판
         * @description Returns seven lists for one played turn, at most 5 rows each: biggest price rises and falls, most attention (rank and grade only), longest rising and falling streaks (2 turns or more), and the groups with the biggest mean rise and fall. The board is computed from the facts of that turn only, so a turn gives the same answer whenever it is asked, in a season in progress or a finished one.
         *
         *     진행한 턴 하나의 목록 일곱 개를 줄 5개 이하로 준다: 가격이 가장 많이 오르내린 종목, 관심이 가장 큰 종목(순위와 등급만), 가장 오래 오르내린 종목(2턴 이상), 평균 상승·하락이 가장 큰 그룹. 그 턴의 자료만으로 계산하므로 언제 물어도 같은 답이고 진행 중인 시즌과 끝난 시즌이 같다.
         */
        get: operations["getTurnRanking"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/holdings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Current team (holdings) lookup / 현재 팀(보유) 조회
         * @description Returns the holdings of the current turn × that turn's prices, the total including cash, valuation and share per position, and the difference from the current formation. It is a live value that reflects this turn's trades immediately. The sum of the four shares can differ from 1 by at most 2e-8.
         *
         *     현재 턴의 보유 × 그 턴 가격, 현금 포함 총액, 포지션별 평가액과 비중, 현재 포메이션 대비 차이를 준다. 이번 턴의 매매가 바로 반영되는 실시간 값이다. 비중 4개의 합은 1과 최대 2e-8 다를 수 있다.
         */
        get: operations["getHoldings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/report": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Season report lookup / 시즌 성적표 조회
         * @description Computes the season report of a finished season (results, benchmark, process, cost and reconciliation status, 38 items) on read, without storing it. A season that is in progress or being abandoned returns 409 SEASON_NOT_FINISHED. Money is a whole-won integer string; ratios are strings with 8 decimal places (−0.2 = −20%); a cell that cannot be computed is null (the key is never omitted). The benchmark buys and holds proxy ETFs (069500 and SPY, 50/50) and is biased because price returns and adjusted closes are mixed. Dates, month blocks, seeds, assets and change numbers are not included.
         *
         *     Excluded (outside the Phase 0 scope):
         *     - X1 Compound annual growth rate (CAGR): needs fractional powers (ln·exp) that Java BigDecimal has no exact counterpart for, so the golden 8-decimal match cannot be guaranteed. The value is inflated on short seasons. → MVP (with the M3 score)
         *     - X2 Sharpe: meaningless on a 10-turn sample, and the risk-free rate (cash earns no interest) must be defined with it. → MVP
         *     - X3 Path percentile and consistency: needs an expected distribution (thousands of paths). → M2
         *     - X4 60/40, my-formation (follows) and start-formation (fixed) benchmarks, the three-way split into player selection, formation change and structure effect, the 6-turn effect after a change: not in these 38 columns. Java computes them at GET …/report/analysis (MVP-17; the 6-turn effect MVP-19).
         *     - X5 Trading discipline (panic, chasing) and formation change right after a fall: not in these 38 columns. Defined in PLAN §6.2; Java computes them at GET …/report/analysis (MVP-17).
         *     - X6 Number of asset classes and average correlation under diversification: not in the feature table row P0-20, and correlation is unstable over 10 turns. → MVP
         *     - X7 Reaction to noise (direction): not in these 38 columns. Defined in PLAN §6.2; Java computes it at GET …/report/analysis (MVP-19).
         *     - X8 Following attention, defender goals, bench goals and signal discrimination: not in these 38 columns. They are review items and not part of the score (PLAN §6.2). Java computes following attention and defender goals at GET …/report/analysis (MVP-19). Bench goals come with the bench list (APP); signal discrimination is EXT.
         *     - X9 Tax, after-tax value on liquidation and deferred tax: tax is EXT (PLAN §4.6). The verification asserts that execution.tax is always 0. → EXT
         *     - X10 Currency-exchange portion of the fee: splitting out the displayed portion adds a new rounding point in whole won. PLAN §4.6 describes this item as "display only." → MVP
         *     - X11 Season score 0–100 and rating: PLAN §6.3 is a draft. → M3
         *     - X12 Storing the report (season_score): an MVP sketch (PLAN §5.1, SPRING_JPA §12-1). → MVP
         *
         *     끝난 시즌의 성적표(결과·기준선·과정·비용·대사 상태 38항목)를 저장 없이 조회로 계산해 준다. 진행 중이거나 포기 중인 시즌은 409 SEASON_NOT_FINISHED 다. 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열(−0.2 = −20%)이고 값을 만들 수 없는 칸은 null 이다(키는 생략하지 않는다). 기준선은 대용 ETF(069500·SPY 50/50) 매수 후 보유이며 가격 수익률과 수정 종가가 섞인 편향이 있다. 날짜·월 블록·시드·종목·변경 번호는 싣지 않는다.
         *
         *     제외(Phase 0 범위 밖):
         *     - X1 연평균 성장률(CAGR) 분수 거듭제곱(ln·exp)이 필요한데 Java BigDecimal 에 정확한 짝이 없어 골든 8자리 일치를 보장할 수 없다. 짧은 시즌에서는 값이 부풀려진다. → MVP(M3 점수와 함께)
         *     - X2 Sharpe 10턴 표본으로는 의미가 없고, 무위험 수익률(현금 무이자) 정의도 함께 정해야 한다. → MVP
         *     - X3 경로 백분위·일관성 예상 분포(수천 경로)가 필요하다. → M2
         *     - X4 60/40·내 포메이션(따라감)·시작 포메이션(고정) 기준선, 선수 선택·전술 변경·구조 효과 3분해, 변경 뒤 6턴 효과 이 38열에는 없다. Java 가 GET …/report/analysis 로 계산한다 (MVP-17, 6턴 효과는 MVP-19).
         *     - X5 매매 규율(패닉·추격)·하락 직후 전술 변경 이 38열에는 없다. 정의는 PLAN §6.2, Java 가 GET …/report/analysis 로 계산한다 (MVP-17).
         *     - X6 분산 중 자산군 수·평균 상관 기능 표 P0-20 행에 없고 상관은 10턴으로는 불안정하다. → MVP
         *     - X7 소음 반응(방향) 이 38열에는 없다. 정의는 PLAN §6.2, Java 가 GET …/report/analysis 로 계산한다 (MVP-19).
         *     - X8 관심 추종·수비수 득점·벤치 득점·신호 판별 이 38열에는 없다. 복기 항목이라 점수에 넣지 않는다 (PLAN §6.2). 관심 추종·수비수 득점은 Java 가 GET …/report/analysis 로 계산한다 (MVP-19). 벤치 득점은 벤치 목록이 생기는 APP, 신호 판별은 EXT.
         *     - X9 세금·청산 시 세후·이연 세금 세금은 EXT 다 (PLAN §4.6). execution.tax 가 항상 0 인지는 검증이 단언한다. → EXT
         *     - X10 수수료 중 환전 몫 표시 몫을 나누면 원 단위 반올림 지점이 새로 생긴다. PLAN §4.6 이 "표시만"이라 한 항목이다. → MVP
         *     - X11 시즌 점수 0–100·레이팅 PLAN §6.3 은 초안이다. → M3
         *     - X12 성적표 저장(season_score) MVP 스케치다 (PLAN §5.1, SPRING_JPA §12-1). → MVP
         */
        get: operations["getSeasonReport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/report/analysis": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Season analysis lookup / 시즌 분석 조회
         * @description Simulates four baselines (market, 60/40, my formation followed, start formation fixed) on the season's own turn prices with integer shares and fees, splits the excess over the market into player selection, formation change and structure, and measures trading discipline (panic selling and chase buying, PLAN §6.2), measures the reaction to noise (the share of what was traded that followed an up or down signal of the same turn's feed and ranking boards, PLAN §6.2), splits the fees by order source (the nudged part is RANKING and ALERT only), and gives three review facts (PLAN §6.2): how buys of top-attention assets and all other buys fared over the next 6 turns, the won gain of defender and goalkeeper holdings in turns where the market fell, and the 6-turn effect of each formation change, all on read and without storing anything. Complements the 38-column season report. A season that is in progress or being abandoned returns 409 SEASON_NOT_FINISHED. Money is a whole-won integer string; ratios are strings with 8 decimal places; the three won terms of the split add up to the total exactly, while their ratios may differ from the total ratio by rounding. A baseline whose representative asset is not in the season pool is unavailable (reason REPRESENTATIVE_MISSING, values null). The 60/40 bond leg uses a US Treasury ETF until the pool holds a Korean bond ETF (bondLegSubstituted). The representative table is a fixed rule, not a position judgement. Dates, month blocks, seeds and asset names are not included; an asset is only MARKET:TICKER.
         *
         *     끝난 시즌의 시즌 가격 위에서 기준선 네 개(시장, 60/40, 내 포메이션 따라감, 시작 포메이션 고정)를 정수 주식과 수수료로 시뮬레이션하고, 시장 대비 초과분을 선수 선택·전술 변경·구조로 나누고, 매매 규율(패닉 매도·추격 매수, PLAN §6.2)과 소음 반응(매매 가운데 같은 턴의 피드·순위판의 상승·하락 신호를 따른 몫, PLAN §6.2)을 재고 수수료를 주문 출발점별로 나누며(독려 몫은 RANKING·ALERT 뿐이다), 복기 사실 셋(PLAN §6.2)도 준다: 관심 상위 종목 매수와 그 밖의 매수의 뒤 6턴 성과, 시장이 내린 턴에 수비수·골키퍼 보유 종목이 낸 원 단위 이익, 전술 변경마다의 6턴 효과. 모두 저장 없이 조회로 계산한다. 38항목 시즌 성적표를 보완한다. 진행 중이거나 포기 중인 시즌은 409 SEASON_NOT_FINISHED 다. 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열이고, 3분해의 원 단위 세 항은 합계와 정확히 같지만 비율은 반올림 때문에 합계 비율과 다를 수 있다. 대표 종목이 시즌 풀에 없는 기준선은 만들 수 없다(reason REPRESENTATIVE_MISSING, 값 null). 60/40 의 채권 칸은 풀에 국내 채권 ETF가 들어오기 전까지 미국 국채 ETF를 쓴다(bondLegSubstituted). 대표 종목 표는 고정 규칙이며 포지션 판정이 아니다. 날짜·월 블록·시드·종목 이름은 싣지 않고 종목은 MARKET:TICKER 로만 가리킨다.
         */
        get: operations["getSeasonAnalysis"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/review": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Season review lookup / 시즌 복기 조회
         * @description Returns which real month each turn of a finished season was (year-month), the one-line summary and event tags of that month (real name, hindsight assessment, source), the formation-change history, and the reproducibility metadata (seed, engine version, data snapshot date). Each turn also lists the feed items as they were shown, with what each one was (noise kind, alert or real event card) and, for an event card, the event's real name. A season that is in progress or being abandoned returns 409 SEASON_NOT_FINISHED and the body carries no year-month, feed kind or event name. The seed is a 64-bit integer, so it is sent as a string. Month block numbers, event numbers and event keys are not included.
         *
         *     Excluded (outside the Phase 0 scope):
         *     - Regime path and axis direction → M2/MVP
         *     - Bench goals → APP; signal discrimination → EXT
         *     - Event card figures (result, direction, strength, reaction change rate) → MVP
         *     - Per-asset holdings of past turns and the per-turn valuation list → MVP/WEB
         *     - Event explanation (RAG) → AI
         *
         *     끝난 시즌의 각 턴이 실제로 어느 달이었는지(연월), 그 달의 한 줄 요약과 사건 태그(실제 이름·사후 평가·출처), 전술 변경 기록, 재현 정보(시드·엔진 버전·데이터 스냅샷 날짜)를 준다. 턴마다 보인 그대로의 피드 항목과 그 정체(소음 종류·알림·실제 사건 카드), 사건 카드라면 사건의 실제 이름도 준다. 진행 중이거나 포기 중인 시즌은 409 SEASON_NOT_FINISHED 이고 본문에 연월·피드 종류·사건 이름이 없다. 시드는 64비트 정수라 문자열로 보낸다. 월 블록 번호·사건 번호·사건 키는 싣지 않는다.
         *
         *     제외(Phase 0 범위 밖):
         *     - 국면 경로·축 방향 → M2/MVP
         *     - 벤치 득점 → APP, 신호 판별 → EXT
         *     - 사건 카드 수치(결과·방향·강도·반응 변화율) → MVP
         *     - 과거 턴 종목별 보유·턴별 평가액 목록 → MVP/WEB
         *     - 사건 설명(RAG) → AI
         */
        get: operations["getSeasonReview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/seasons/{seasonId}/score": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Season score lookup / 시즌 점수 조회
         * @description Returns the score of a finished season: 0.5 x the result score (the season's Sharpe difference against the market benchmark, ranked among engine paths of the season-start team held still) + 0.5 x the process score (the mean of the item scores that have a value). The score is computed on the first request after the season finished, in finish order together with the member's earlier seasons, and then kept; a later request returns the stored row. The result and the score are null when the history cannot carry a path distribution for the season; such a season, and a human season abandoned before turn 3, is left out of the rating (ratingExclusion). While the engine cannot answer, nothing is stored and the answer is 503 ENGINE_UNAVAILABLE; try again later. The luck percentile is shown and is not part of the score. The response has no money, month, seed or engine detail.
         *
         *     끝난 시즌의 점수를 준다: 0.5 × 결과 점수(시즌의 샤프 차이를 시장 기준선과 비교해, 시즌 시작 팀을 그대로 보유한 엔진 경로들 안에서 순위를 매긴 값) + 0.5 × 과정 점수(값이 있는 항목 점수의 평균). 점수는 시즌이 끝난 뒤 첫 요청에서 회원의 앞선 시즌과 함께 끝난 순서대로 계산해 보관하고, 이후 요청은 보관한 행을 준다. 이력이 시즌의 경로 분포를 받쳐 주지 못하면 결과 점수와 점수는 null 이고, 그런 시즌과 3턴 전에 포기한 사람의 시즌은 레이팅에서 빠진다(ratingExclusion). 엔진이 답하지 못하는 동안에는 아무것도 저장하지 않고 503 ENGINE_UNAVAILABLE 로 답하니 나중에 다시 시도한다. 운 백분위는 보여 주기만 하고 점수에 들어가지 않는다. 응답에는 금액·달·시드·엔진 세부가 없다.
         */
        get: operations["getSeasonScore"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/me/rating": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * My manager rating / 내 매니저 레이팅
         * @description Returns the manager rating of the signed-in member: the weighted mean of the last five counted season scores (weights 5, 4, 3, 2, 1 from the newest), seasons ordered by when they finished. The rating is null until three seasons are counted (seasonsUntilPublic says how many are missing). Seasons left out of the rating are only counted: notStartedCount (a human abandoned before turn 3) and noScoreCount (no path distribution). Finished seasons are scored first; if the engine cannot answer, the answer is still 200 and pendingSeasons counts the seasons scored on a later request. history lists the counted seasons newest first, at most 20.
         *
         *     로그인한 회원의 매니저 레이팅을 준다: 집계된 최근 다섯 시즌 점수의 가중 평균(최근부터 5·4·3·2·1)이며 시즌 순서는 끝난 순서다. 세 시즌이 집계되기 전에는 레이팅이 null 이다(seasonsUntilPublic 은 모자란 시즌 수). 레이팅에서 빠진 시즌은 횟수만 센다: notStartedCount(사람이 3턴 전에 포기), noScoreCount(경로 분포 없음). 끝난 시즌은 먼저 채점하며, 엔진이 답하지 못해도 200 으로 답하고 pendingSeasons 가 나중 요청에서 채점할 시즌 수를 센다. history 는 집계된 시즌을 최근 것부터 최대 20개 준다.
         */
        get: operations["getMyRating"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/leagues/{leagueId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * League table / 리그 표
         * @description Returns a league the signed-in member owns with its seats: the seat's season status and turn, the persona and model of an agent seat, and ownsAgentsHere for a human seat whose owner also has an agent seat here. valuationMultiple (final valuation divided by initial capital, 8 decimal places) and multipleRank are set only once the league is CLOSED, and the multiple only for a FINISHED seat; until then every seat shows only its status and turn. The seed, dates, engine version and path are never in the answer. Another member's league and a missing one both answer 404.
         *
         *     로그인한 회원이 소유한 리그와 그 자리를 준다: 자리의 시즌 상태와 턴, 에이전트 자리의 페르소나와 모델, 소유자의 에이전트도 여기 있는 사람 자리의 ownsAgentsHere. valuationMultiple(최종 평가액 ÷ 초기 자금, 소수 8자리)과 multipleRank 는 리그가 CLOSED 가 된 뒤에만 채우고, 배수는 FINISHED 자리에만 채운다. 그때까지 모든 자리는 상태와 턴만 보인다. 시드·날짜·엔진 버전·경로는 답에 없다. 다른 회원의 리그와 없는 리그는 똑같이 404 다.
         */
        get: operations["getLeagueTable"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/agents": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * My agents / 내 에이전트
         * @description Returns the agents the signed-in member owns, oldest first, each with raw season counts: startedSeasons (all), countedSeasons (FINISHED), voidSeasons (VOID), unfinishedAbandonSeasons (ABANDONING and not yet FINISHED, including a pause older than 7 days), inProgressSeasons, pausedSeasons (paused less than 7 days), haltedSeasons (stopped by a reconciliation mismatch) and failedTurnCount (turns whose final model call ended NO_RESPONSE or INVALID). It has no score, rank or minimum-season rule.
         *
         *     로그인한 회원이 소유한 에이전트를 오래된 순으로 준다. 각 에이전트에 시즌 집계 값이 붙는다: startedSeasons(전체), countedSeasons(FINISHED), voidSeasons(VOID), unfinishedAbandonSeasons(ABANDONING 이고 아직 FINISHED 가 아닌 시즌, 7일 넘은 정지 포함), inProgressSeasons, pausedSeasons(정지 7일 미만), haltedSeasons(대사 불일치로 멈춤), failedTurnCount(최종 모델 호출이 NO_RESPONSE 또는 INVALID 로 끝난 턴 수). 점수·순위·최소 시즌 규칙은 없다.
         */
        get: operations["listMyAgents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        PoolDto: {
            assets: components["schemas"]["PoolAssetDto"][];
        };
        PoolAssetDto: {
            /**
             * Format: int64
             * @description Asset identifier (used in order requests). Not shown to the player / 종목 식별자(주문 요청에 쓴다). 표시하지 않는다
             */
            assetId: number;
            market: components["schemas"]["Market"];
            ticker: string;
            name: string;
            assetType: components["schemas"]["AssetType"];
            returnBasis: components["schemas"]["ReturnBasis"];
            /** @description Generic name of the index that fills the months without an actual return, for any asset that has proxy months (ETF or stock) / 실제 수익률이 없는 달을 채우는 대체 지수의 일반 이름(대체 달이 있는 ETF·개별 주식) */
            proxyIndexName: string | null;
            /** Format: int64 */
            groupId: number;
            groupName: string;
            groupKind: components["schemas"]["AssetGroupKind"];
            systemPosition: components["schemas"]["Position"];
            /**
             * Format: decimal
             * @description Up-market participation rate (months the market rose 1% or more, in KRW) / 상승장 참여율(시장이 1% 이상 오른 달, 원화)
             */
            upParticipation: string;
            /**
             * Format: decimal
             * @description Down-market participation rate (months the market fell 1% or more, in KRW) / 하락장 참여율(시장이 1% 이상 빠진 달, 원화)
             */
            downParticipation: string;
            /**
             * Format: decimal
             * @description Sample standard deviation of monthly returns (not annualized) / 월 수익률 표본 표준편차(연율화 안 함)
             */
            volatility: string;
            /**
             * Format: decimal
             * @description Maximum drawdown, 0 or less (−0.35 = −35%) / 최대 낙폭, 0 이하(−0.35 = −35%)
             */
            maxDrawdown: string;
        };
        /**
         * @description Initial capital, rates and the seed are not taken from the request (server configuration). The 12/24/60 check on pathLength is a server rule
         *
         *     초기 자금·요율·시드는 요청에서 받지 않는다(서버 설정). pathLength 의 12·24·60 판정은 서버 규칙이다
         */
        StartSeasonRequest: {
            /**
             * Format: decimal
             * @description Risk limit (the maximum drawdown the player accepts): one of -0.10, -0.20, -0.30, -0.40 / 위험 선언(감수할 최대 낙폭): -0.10, -0.20, -0.30, -0.40 중 하나
             */
            riskLimit: string;
            formation: components["schemas"]["FormationDto"];
            /**
             * Format: int32
             * @description Path length (number of turns): 12, 24 or 60. 12 when omitted / 경로 길이(턴 수) 12, 24, 60. 생략하면 12
             * @default 12
             */
            pathLength: number;
            /**
             * Format: int64
             * @description Enter this league (only its owner and the owner's agents may). The league fixes the path; pathLength must then be omitted or equal the league's / 이 리그에 참가한다(리그 소유자와 소유자의 에이전트만). 경로는 리그가 정하므로 pathLength 는 생략하거나 리그와 같아야 한다
             */
            leagueId?: number;
        };
        /**
         * @description Email, display name and password. The role is never taken from the request.
         *
         *     이메일·표시 이름·비밀번호. 역할은 요청에서 받지 않는다
         */
        SignupRequest: {
            /**
             * Format: email
             * @description Login email, unique in any letter case (at most 255) / 로그인 이메일, 대소문자 무시 유일(최대 255자)
             */
            email: string;
            /** @description Display name (1–50) / 표시 이름(1–50자) */
            name: string;
            /** @description 12–64 characters and at most 72 UTF-8 bytes; no composition rules / 12–64자, UTF-8 72바이트 이하; 문자 조합 규칙 없음 */
            password: string;
        };
        /**
         * @description Email and password
         *
         *     이메일과 비밀번호
         */
        LoginRequest: {
            /** @description Login email, any letter case (at most 255) / 로그인 이메일, 대소문자 무시(최대 255자) */
            email: string;
            /** @description At most 64 characters and 72 UTF-8 bytes / 최대 64자, UTF-8 72바이트 */
            password: string;
        };
        /**
         * @description A short-lived access token
         *
         *     짧게 쓰는 접근 토큰
         */
        AccessTokenDto: {
            /** @description Signed JWT (HS256); send it as a Bearer token / 서명된 JWT(HS256). Bearer 토큰으로 보낸다 */
            accessToken: string;
            /** @description Always Bearer / 항상 Bearer */
            tokenType: string;
            /**
             * Format: int64
             * @description Seconds until the access token expires / 접근 토큰이 만료될 때까지 남은 초
             */
            expiresIn: number;
        };
        /**
         * @description The member who just signed up. Email, role, password and times are not included
         *
         *     방금 가입한 회원. 이메일·역할·비밀번호·시각은 싣지 않는다
         */
        MemberDto: {
            /**
             * Format: int64
             * @description Member id / 회원 번호
             */
            id: number;
            /** @description Display name / 표시 이름 */
            name: string;
        };
        /**
         * @description Share per position (integer percent). Sum 100, goalkeeper at least 1. Request and response are the same
         *
         *     포지션별 비중(정수 퍼센트). 합 100, 골키퍼 1 이상. 요청과 응답이 같다
         */
        FormationDto: {
            /**
             * Format: int32
             * @description Goalkeeper share (1–100) / 골키퍼 비중(1–100)
             */
            goalkeeper: number;
            /**
             * Format: int32
             * @description Defender share (0–100) / 수비수 비중(0–100)
             */
            defender: number;
            /**
             * Format: int32
             * @description Midfielder share (0–100) / 미드필더 비중(0–100)
             */
            midfielder: number;
            /**
             * Format: int32
             * @description Attacker share (0–100) / 공격수 비중(0–100)
             */
            forward: number;
        };
        /**
         * @description Formation change request. The server decides how the previous formation and the orders of the same turn are linked
         *
         *     전술 변경 요청. 변경 전 포메이션과 같은 턴 주문과의 연결은 서버가 정한다
         */
        ChangeFormationRequest: {
            /**
             * Format: int32
             * @description The turn the change applies to. 409 if it differs from the season's current turn. At turn 0 (team being built) the formation cannot be changed, so 409
             *
             *     변경이 보는 턴. 시즌의 현재 턴과 다르면 409. 0턴(팀 구성 중)에는 전술을 바꿀 수 없어 409
             */
            expectedTurnNo: number;
            /** @description The new formation. 400 if it equals the current one / 새 포메이션. 현재와 같으면 400 */
            formation: components["schemas"]["FormationDto"];
            /**
             * @description Optional one-line reason (up to 200 characters, counted in Unicode characters). Omitted and null are the same
             *
             *     선택 한 줄 사유(최대 200자, 유니코드 문자 수 기준). 생략과 null 은 같다
             */
            reason?: string | null;
        };
        /**
         * @description Formation change result. Change number, season number, recorded time and cost are not included
         *
         *     전술 변경 결과. 변경 번호·시즌 번호·기록 시각·비용은 싣지 않는다
         */
        FormationChangeDto: {
            /**
             * Format: int32
             * @description The turn that was changed / 변경한 턴
             */
            turnNo: number;
            /** @description Formation before the change (the current value just before it) / 변경 전 포메이션(변경 직전의 현재 값) */
            before: components["schemas"]["FormationDto"];
            /** @description Formation after the change (the requested value) / 변경 후 포메이션(요청한 값) */
            after: components["schemas"]["FormationDto"];
            /** @description Reason. null if there is none / 사유. 없으면 null */
            reason: string | null;
        };
        /**
         * @description Seed, engine version, reference date, snapshot date, member number, version and times are not included
         *
         *     시드·엔진 버전·기준일·스냅샷 날짜·회원 번호·버전·시각은 싣지 않는다
         */
        SeasonDto: {
            /**
             * Format: int64
             * @description Season identifier / 시즌 식별자
             */
            id: number;
            status: components["schemas"]["SeasonStatus"];
            /**
             * Format: int32
             * @description Current turn (0 = start) / 현재 턴(0 = 시작)
             */
            currentTurnNo: number;
            /**
             * Format: int32
             * @description Path length (number of turns) / 경로 길이(턴 수)
             */
            pathLength: number;
            /**
             * Format: decimal
             * @description Risk limit, 8 decimal places (-0.20000000 = -20%) / 위험 선언, 소수 8자리(-0.20000000 = -20%)
             */
            riskLimit: string;
            startFormation: components["schemas"]["FormationDto"];
            currentFormation: components["schemas"]["FormationDto"];
            /**
             * Format: decimal
             * @description Initial capital, whole KRW / 초기 자금, 원 단위 정수
             */
            initialCash: string;
            /**
             * Format: decimal
             * @description Cash balance, whole KRW / 현금 잔액, 원 단위 정수
             */
            cashBalance: string;
            /**
             * Format: decimal
             * @description Domestic fee rate, 8 decimal places / 국내 수수료율, 소수 8자리
             */
            feeRateKr: string;
            /**
             * Format: decimal
             * @description US fee rate, 8 decimal places / 미국 수수료율, 소수 8자리
             */
            feeRateUs: string;
            /**
             * Format: decimal
             * @description Currency exchange cost rate, 8 decimal places / 환전 비용률, 소수 8자리
             */
            fxSpreadRate: string;
            /**
             * Format: int32
             * @description The turn at which the season was abandoned. null if it was not abandoned / 포기한 턴. 포기하지 않았으면 null
             */
            abandonedTurnNo: number | null;
            /** @description True for a human member who abandoned before turn 3 (derived from the abandon turn and the member kind; not stored) / 사람 회원이 3턴 전에 포기하면 true(포기 턴과 회원 종류에서 계산, 저장하지 않는다) */
            notStarted: boolean;
            /**
             * Format: int64
             * @description The league this season entered. null for a season outside any league / 이 시즌이 참가한 리그. 리그 밖의 시즌이면 null
             */
            leagueId: number | null;
        };
        /** @description All seasons of the current member (newest season number first). No paging / 현재 회원의 시즌 전부(시즌 번호 내림차순). 쪽 나누기 없음 */
        SeasonListDto: {
            items: components["schemas"]["SeasonDto"][];
        };
        /** @description Prices of the whole pool for one turn (ascending asset number) / 한 턴의 풀 전체 가격(종목 번호 오름차순) */
        TurnPricesDto: {
            /** Format: int32 */
            turnNo: number;
            prices: components["schemas"]["TurnPriceDto"][];
        };
        TurnPriceDto: {
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)
             */
            assetId: number;
            /**
             * Format: decimal
             * @description Price, whole KRW / 가격, 원 단위 정수
             */
            price: string;
        };
        /**
         * @description Spread of a team's value over the coming turns. Seed, months, versions and the request are not included
         *
         *     팀 가치가 앞으로 어떻게 퍼질 수 있는지. 시드·달·버전·요청은 싣지 않는다
         */
        ProjectionDto: {
            /** @description CURRENT is computed on request; START and END are stored once / CURRENT 는 요청 때 계산하고 START·END 는 한 번 저장한다 */
            phase: components["schemas"]["ProjectionMoment"];
            /**
             * Format: int32
             * @description Turn the team is valued at (turn counts below are turns ahead of it) / 팀을 평가한 턴(아래 turn 은 이 턴으로부터 앞으로 센 턴)
             */
            turnNo: number;
            /**
             * Format: decimal
             * @description Total value at turnNo, whole KRW / turnNo 의 총 평가액, 원 단위 정수
             */
            totalValue: string;
            /**
             * Format: int32
             * @description Number of turns looked ahead / 내다본 턴 수
             */
            horizonTurns: number;
            /**
             * Format: int32
             * @description Number of simulated paths / 모의 경로 수
             */
            pathCount: number;
            /**
             * Format: decimal
             * @description Risk limit, 8 decimal places / 위험 선언, 소수 8자리
             */
            riskLimit: string;
            bands: components["schemas"]["ProjectionBand"][];
            summaries: components["schemas"]["ProjectionSummary"][];
        };
        ProjectionBand: {
            /**
             * Format: int32
             * @description Turns ahead / 앞으로 센 턴
             */
            turn: number;
            /**
             * Format: decimal
             * @description 5th percentile value, whole KRW / 하위 5% 가치, 원 단위 정수
             */
            p05: string;
            /**
             * Format: decimal
             * @description 25th percentile value, whole KRW / 하위 25% 가치, 원 단위 정수
             */
            p25: string;
            /**
             * Format: decimal
             * @description Median value, whole KRW / 중앙값, 원 단위 정수
             */
            p50: string;
            /**
             * Format: decimal
             * @description 75th percentile value, whole KRW / 상위 25% 가치, 원 단위 정수
             */
            p75: string;
            /**
             * Format: decimal
             * @description 95th percentile value, whole KRW / 상위 5% 가치, 원 단위 정수
             */
            p95: string;
        };
        ProjectionSummary: {
            /**
             * Format: int32
             * @description Turns ahead / 앞으로 센 턴
             */
            turn: number;
            end: components["schemas"]["ProjectionEnd"];
            mdd: components["schemas"]["ProjectionMdd"];
            /**
             * Format: decimal
             * @description Chance the risk limit is breached on the way, 8 decimal places / 도중에 위험 선언을 넘을 확률, 소수 8자리
             */
            breachProbability: string;
        };
        ProjectionEnd: {
            /**
             * Format: decimal
             * @description 5th percentile, whole KRW / 하위 5%, 원 단위 정수
             */
            p05: string;
            /**
             * Format: decimal
             * @description 25th percentile, whole KRW / 하위 25%, 원 단위 정수
             */
            p25: string;
            /**
             * Format: decimal
             * @description Median, whole KRW / 중앙값, 원 단위 정수
             */
            p50: string;
            /**
             * Format: decimal
             * @description 75th percentile, whole KRW / 상위 25%, 원 단위 정수
             */
            p75: string;
            /**
             * Format: decimal
             * @description 95th percentile, whole KRW / 상위 5%, 원 단위 정수
             */
            p95: string;
        };
        ProjectionMdd: {
            /**
             * Format: decimal
             * @description 5th percentile, 8 decimal places / 하위 5%, 소수 8자리
             */
            p05: string;
            /**
             * Format: decimal
             * @description 10th percentile, 8 decimal places / 하위 10%, 소수 8자리
             */
            p10: string;
            /**
             * Format: decimal
             * @description 25th percentile, 8 decimal places / 하위 25%, 소수 8자리
             */
            p25: string;
            /**
             * Format: decimal
             * @description Median, 8 decimal places / 중앙값, 소수 8자리
             */
            p50: string;
            /**
             * Format: decimal
             * @description 75th percentile, 8 decimal places / 상위 25%, 소수 8자리
             */
            p75: string;
            /**
             * Format: decimal
             * @description 95th percentile, 8 decimal places / 상위 5%, 소수 8자리
             */
            p95: string;
        };
        /**
         * @description One market buy or sell. The server decides the fill price, fee and rates; the origin is DIRECT unless a source is sent
         *
         *     시장가 매수·매도 한 건. 체결가·수수료·요율은 서버가 정하고, 출발점은 source 를 보내지 않으면 DIRECT 다
         */
        PlaceOrderRequest: {
            /**
             * Format: int32
             * @description The turn the order applies to. 409 if it differs from the season's current turn / 주문이 보는 턴. 시즌의 현재 턴과 다르면 409
             */
            expectedTurnNo: number;
            /** @description BUY or SELL (market order) / BUY 또는 SELL(시장가) */
            side: components["schemas"]["OrderSide"];
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)
             */
            assetId: number;
            /**
             * Format: int32
             * @description Whole shares (1 or more) / 정수 주(1 이상)
             */
            quantity: number;
            /**
             * @description Declared position (buys only). Required for an asset with no open span (first purchase, or after selling everything), optional otherwise. 400 if sent with a sell
             *
             *     선언 포지션(매수 전용). 열린 구간이 없는 종목(처음 사거나 전량 매도 뒤)이면 필수, 있으면 생략할 수 있다. 매도에 보내면 400
             */
            declaredPosition?: components["schemas"]["Position"];
            /**
             * @description Where the order started. Omitted or null means DIRECT. A FEED or ALERT origin must name a feed item that was shown to this season at or before the current turn, otherwise 422 SOURCE_NOT_VISIBLE
             *
             *     주문이 시작된 곳. 없거나 null이면 DIRECT. FEED·ALERT는 이 시즌의 현재 턴까지 보인 피드 항목을 가리켜야 하며 아니면 422 SOURCE_NOT_VISIBLE
             */
            source?: components["schemas"]["OrderSourceRequest"] | null;
        };
        /**
         * @description Where the order started. FEED and ALERT name the feed item that was shown (turn and display order); RANKING and DIRECT carry no reference; BENCH is reserved and refused. The server accepts a non-DIRECT origin only if that item was shown to this season at or before the current turn
         *
         *     주문이 시작된 곳. FEED·ALERT는 보인 피드 항목(턴과 표시 순서)을 가리키고, RANKING·DIRECT는 참조가 없으며, BENCH는 예약이라 거절한다. 서버는 그 항목이 이 시즌의 현재 턴까지 보인 경우에만 DIRECT 아닌 출발점을 받는다
         */
        OrderSourceRequest: {
            /** @description Origin of the order / 주문의 출발점 */
            kind: components["schemas"]["OrderSource"];
            /**
             * Format: int32
             * @description Turn in which the feed item was shown (FEED and ALERT only, with feedSeq) / 피드 항목이 보인 턴(FEED·ALERT 전용, feedSeq와 함께)
             */
            feedTurnNo?: number;
            /**
             * Format: int32
             * @description Display order of the feed item inside that turn (FEED and ALERT only, with feedTurnNo) / 그 턴 안에서 피드 항목의 표시 순서(FEED·ALERT 전용, feedTurnNo와 함께)
             */
            feedSeq?: number;
        };
        /**
         * @description Recorded time, origin, kind, status, feed number, seed, path and year-month are not included
         *
         *     기록 시각·출발점·유형·상태·피드 번호·시드·경로·연월은 싣지 않는다
         */
        OrderResultDto: {
            /**
             * Format: int64
             * @description Order identifier / 주문 식별자
             */
            orderId: number;
            /**
             * Format: int32
             * @description The turn of the order / 주문한 턴
             */
            turnNo: number;
            side: components["schemas"]["OrderSide"];
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)
             */
            assetId: number;
            /**
             * Format: int32
             * @description Filled quantity / 체결 수량
             */
            quantity: number;
            /**
             * Format: decimal
             * @description Fill price (that turn's price), whole KRW / 체결가(그 턴 가격), 원 단위 정수
             */
            fillPrice: string;
            /**
             * Format: decimal
             * @description Amount (fill price × quantity), whole KRW / 대금(체결가 × 수량), 원 단위 정수
             */
            notional: string;
            /**
             * Format: decimal
             * @description Fee, whole KRW / 수수료, 원 단위 정수
             */
            fee: string;
            /**
             * Format: decimal
             * @description Cash balance after the fill, whole KRW / 체결 뒤 현금 잔액, 원 단위 정수
             */
            cashBalance: string;
            /**
             * Format: int32
             * @description Quantity held after the fill / 체결 뒤 보유 수량
             */
            holdingQuantity: number;
            /**
             * Format: decimal
             * @description Average cost after the fill (fee excluded), 4 decimal places / 체결 뒤 평균단가(수수료 제외), 소수 4자리
             */
            avgPrice: string;
            /**
             * Format: decimal
             * @description Realized P&L (fill price − average cost just before the fill) × quantity, a string with 4 decimal places, can be negative. null for a buy
             *
             *     실현 손익(체결가 − 체결 직전 평균단가) × 수량, 소수 4자리 문자열, 음수 가능. 매수는 null
             */
            realizedPnl: string | null;
            /** @description Declared position of this asset's span (the span just closed if everything was sold) / 이 종목의 구간 선언 포지션(전량 매도면 방금 닫은 구간) */
            declaredPosition: components["schemas"]["Position"];
            /**
             * Format: int64
             * @description Identifier of the last formation change of that turn. null if there was no change / 그 턴의 마지막 전술 변경 식별자. 변경이 없으면 null
             */
            formationChangeId: number | null;
        };
        /**
         * @description Preview of one market order at the current turn price. Nothing is written; the amounts are what an order with the same body would fill now. Turn, date, seed and path are not included
         *
         *     현재 턴 가격으로 본 시장가 주문 한 건의 미리보기. 아무것도 쓰지 않으며, 금액은 같은 본문으로 지금 주문하면 체결될 값이다. 턴·날짜·시드·경로는 싣지 않는다
         */
        OrderQuoteDto: {
            /**
             * Format: decimal
             * @description Fill price (the current turn's price), whole KRW / 체결가(현재 턴 가격), 원 단위 정수
             */
            fillPrice: string;
            /**
             * Format: decimal
             * @description Amount (fill price × quantity), whole KRW / 대금(체결가 × 수량), 원 단위 정수
             */
            notional: string;
            /**
             * Format: decimal
             * @description Fee the fill would charge, FX cost included, whole KRW / 체결 때 매길 수수료(환전 비용 포함), 원 단위 정수
             */
            fee: string;
            /**
             * Format: decimal
             * @description FX cost part of the fee for a US asset, round(fill price × quantity × FX cost rate); 0 for a KR asset. Display only: it is already inside fee and is not added to it
             *
             *     미국 종목 수수료 중 환전 비용 몫, round(체결가 × 수량 × 환전 비용률). 국내 종목은 0. 표시용이며 fee 에 이미 들어 있어 더하지 않는다
             */
            fxCost: string;
            /**
             * Format: decimal
             * @description Cash balance after the fill, whole KRW / 체결 뒤 현금 잔액, 원 단위 정수
             */
            cashAfter: string;
            /**
             * Format: int32
             * @description Largest quantity a buy of this asset could fill now (cash with the fee, and the single-asset limit when it is set). null for a sell
             *
             *     이 종목을 지금 살 수 있는 최대 수량(수수료를 포함한 현금, 단일 종목 한도가 있으면 그 한도까지). 매도는 null
             */
            maxBuyQuantity: number | null;
        };
        /** @description The current turn before the advance is the idempotency key / 전환 전의 현재 턴이 멱등성 열쇠다 */
        AdvanceTurnRequest: {
            /**
             * Format: int32
             * @description The current turn before the advance. 409 if it differs from the season's current turn / 전환 전의 현재 턴. 시즌의 현재 턴과 다르면 409
             */
            expectedTurnNo: number;
        };
        /**
         * @description The current turn the member saw is checked only while the season is in progress
         *
         *     회원이 본 현재 턴은 시즌이 진행 중일 때만 확인한다
         */
        AbandonSeasonRequest: {
            /**
             * Format: int32
             * @description The current turn the member saw. 409 TURN_MISMATCH if the season is in progress at another turn; ignored when the abandon was already marked (repeat) / 회원이 본 현재 턴. 진행 중인 시즌의 현재 턴과 다르면 409 TURN_MISMATCH, 이미 포기가 표시된 시즌의 반복 요청에서는 무시한다
             */
            expectedTurnNo: number;
        };
        /**
         * @description State of the season after this abandon request; repeat the request while the status is ABANDONING
         *
         *     이번 포기 요청 뒤 시즌의 상태. 상태가 ABANDONING 이면 같은 요청을 반복한다
         */
        AbandonResultDto: {
            /**
             * Format: int64
             * @description Season identifier / 시즌 식별자
             */
            seasonId: number;
            /** @description ABANDONING (more turns remain) or FINISHED / ABANDONING(남은 턴이 있다) 또는 FINISHED */
            status: components["schemas"]["SeasonStatus"];
            /**
             * Format: int32
             * @description Current turn now / 지금의 현재 턴
             */
            currentTurnNo: number;
            /**
             * Format: int32
             * @description Path length (number of turns) / 경로 길이(턴 수)
             */
            pathLength: number;
            /**
             * Format: int32
             * @description The turn at which the season was abandoned / 포기한 턴
             */
            abandonedTurnNo: number;
            /** @description True for a human member who abandoned before turn 3 (derived from the abandon turn and the member kind) / 사람 회원이 3턴 전에 포기하면 true(포기 턴과 회원 종류에서 계산) */
            notStarted: boolean;
            /**
             * Format: int32
             * @description Auto turns this request advanced / 이번 요청이 진행한 자동 턴 수
             */
            advancedTurns: number;
            /**
             * Format: int32
             * @description Turns still to run: pathLength - currentTurnNo / 남은 턴: pathLength - currentTurnNo
             */
            remainingTurns: number;
        };
        /**
         * @description Changes the declared position of a held asset without any trade
         *
         *     거래 없이 보유 종목의 선언 포지션만 바꾼다
         */
        ChangeLineupRequest: {
            /**
             * Format: int32
             * @description The turn the change applies to. 409 TURN_MISMATCH if it differs from the season's current turn. At turn 0 (team being built) a position is set by the buy order, so 409 / 변경이 보는 턴. 시즌의 현재 턴과 다르면 409 TURN_MISMATCH. 0턴(팀 구성 중)의 포지션은 매수 주문으로 정하므로 409
             */
            expectedTurnNo: number;
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response). The asset must be held / 종목 식별자(풀 응답의 assetId 와 같다). 보유 중이어야 한다
             */
            assetId: number;
            /** @description The new declared position. If it equals the current one nothing is written / 새 선언 포지션. 현재와 같으면 아무것도 쓰지 않는다 */
            declaredPosition: components["schemas"]["Position"];
        };
        /**
         * @description Position-only change result: the open span after the call. Span number, season number and recorded time are not included
         *
         *     포지션 단독 변경 결과: 호출 뒤의 열린 구간. 구간 번호·시즌 번호·기록 시각은 싣지 않는다
         */
        LineupChangeDto: {
            /**
             * Format: int64
             * @description Asset identifier / 종목 식별자
             */
            assetId: number;
            /** @description Declared position of the open span now / 지금 열린 구간의 선언 포지션 */
            declaredPosition: components["schemas"]["Position"];
            /**
             * Format: int32
             * @description The turn the open span started / 열린 구간이 시작한 턴
             */
            startTurn: number;
            /** @description false when the position was already the requested one and no row was written / 이미 그 포지션이라 행을 쓰지 않았으면 false */
            changed: boolean;
        };
        /**
         * @description Values of the stored turn row and the ratios computed from them. The advance-turn response and the turn lookup have the same shape. The sum of the four shares can differ from 1 by at most 2e-8, and status and pathLength are the season's values now. targetFormation is the target at the start of that turn. Reconciliation attempt number, status, seed, path, month block, year-month and return kind are not included. feed is the feed stored for that turn and alerts are the nudge alerts stored for it. macro is the macro environment of that turn's month.
         *
         *     저장한 턴 행의 값과 거기서 계산한 비율. 턴 전환 응답과 턴 조회가 같은 형이다. 비중 4개의 합은 1과 최대 2e-8 다를 수 있고, status·pathLength 는 시즌의 지금 값이다. targetFormation 은 그 턴 시작 시점의 목표다. 대사 시도 번호·상태·시드·경로·월 블록·연월·수익률 종류는 싣지 않는다. feed 는 그 턴에 저장된 피드이고 alerts 는 저장된 독려 알림이다. macro 는 그 턴의 달의 거시 환경이다
         */
        TurnResultDto: {
            /**
             * Format: int64
             * @description Season identifier / 시즌 식별자
             */
            seasonId: number;
            /**
             * Format: int32
             * @description The turn of this result (in the advance response, the turn just applied = the new current turn)
             *
             *     이 결과의 턴(전환 응답에서는 방금 적용된 턴 = 새 현재 턴)
             */
            turnNo: number;
            /** @description FINISHED if it was the last turn / 마지막 턴이면 FINISHED */
            status: components["schemas"]["SeasonStatus"];
            /**
             * Format: int32
             * @description Path length (number of turns) / 경로 길이(턴 수)
             */
            pathLength: number;
            /**
             * Format: decimal
             * @description Cash balance, whole KRW / 현금 잔액, 원 단위 정수
             */
            cashBalance: string;
            /**
             * Format: decimal
             * @description Holdings valuation, whole KRW / 보유 평가액, 원 단위 정수
             */
            holdingsValue: string;
            /**
             * Format: decimal
             * @description Total valuation, whole KRW / 총 평가액, 원 단위 정수
             */
            totalValue: string;
            /**
             * Format: decimal
             * @description Goalkeeper valuation (cash included), whole KRW / 골키퍼 평가액(현금 포함), 원 단위 정수
             */
            goalkeeperValue: string;
            /**
             * Format: decimal
             * @description Defender valuation, whole KRW / 수비수 평가액, 원 단위 정수
             */
            defenderValue: string;
            /**
             * Format: decimal
             * @description Midfielder valuation, whole KRW / 미드필더 평가액, 원 단위 정수
             */
            midfielderValue: string;
            /**
             * Format: decimal
             * @description Attacker valuation, whole KRW / 공격수 평가액, 원 단위 정수
             */
            forwardValue: string;
            /**
             * Format: decimal
             * @description Cumulative drawdown (0 or less), 8 decimal places / 누적 낙폭(0 이하), 소수 8자리
             */
            drawdown: string;
            /**
             * Format: decimal
             * @description Total valuation of the previous turn (initial capital at turn 1), whole KRW / 직전 턴의 총 평가액(1턴이면 초기 자금), 원 단위 정수
             */
            previousTotalValue: string;
            /**
             * Format: decimal
             * @description Total valuation − previous total valuation (can be negative), whole KRW / 총 평가액 − 직전 총 평가액(음수 가능), 원 단위 정수
             */
            valueChange: string;
            /**
             * Format: decimal
             * @description Period return = change ÷ previous total valuation, 8 decimal places / 기간 수익률 = 변화 ÷ 직전 총 평가액, 소수 8자리
             */
            periodReturn: string;
            /**
             * Format: decimal
             * @description Risk limit (0 or less), 8 decimal places / 위험 선언(0 이하), 소수 8자리
             */
            riskLimit: string;
            /**
             * Format: decimal
             * @description Room to the risk limit = drawdown − limit, 8 decimal places. 0 or more means inside the limit, negative means it was crossed
             *
             *     위험 선언까지의 여유 = 낙폭 − 선언, 소수 8자리. 0 이상이면 선언 안, 음수면 넘었다
             */
            riskHeadroom: string;
            /** @description Whether the drawdown crossed the risk limit (drawdown < limit) / 낙폭이 위험 선언을 넘었는가(낙폭 < 선언) */
            riskBreached: boolean;
            /**
             * Format: decimal
             * @description Goalkeeper share = valuation ÷ total valuation, 8 decimal places / 골키퍼 비중 = 평가액 ÷ 총 평가액, 소수 8자리
             */
            goalkeeperWeight: string;
            /**
             * Format: decimal
             * @description Defender share, 8 decimal places / 수비수 비중, 소수 8자리
             */
            defenderWeight: string;
            /**
             * Format: decimal
             * @description Midfielder share, 8 decimal places / 미드필더 비중, 소수 8자리
             */
            midfielderWeight: string;
            /**
             * Format: decimal
             * @description Attacker share, 8 decimal places / 공격수 비중, 소수 8자리
             */
            forwardWeight: string;
            targetFormation: components["schemas"]["FormationDto"];
            /**
             * Format: decimal
             * @description Goalkeeper share − target (positive = more than the target), 8 decimal places / 골키퍼 비중 − 목표(양수 = 목표보다 많음), 소수 8자리
             */
            goalkeeperGap: string;
            /**
             * Format: decimal
             * @description Defender share − target, 8 decimal places / 수비수 비중 − 목표, 소수 8자리
             */
            defenderGap: string;
            /**
             * Format: decimal
             * @description Midfielder share − target, 8 decimal places / 미드필더 비중 − 목표, 소수 8자리
             */
            midfielderGap: string;
            /**
             * Format: decimal
             * @description Attacker share − target, 8 decimal places / 공격수 비중 − 목표, 소수 8자리
             */
            forwardGap: string;
            /**
             * Format: decimal
             * @description Formation deviation = sum of |share − target| over the positions, 8 decimal places / 포메이션 이탈 = 포지션별 |비중 − 목표|의 합, 소수 8자리
             */
            formationDeviation: string;
            /** @description The feed shown at this turn, in display order; empty when the turn has none / 이 턴에 보인 피드, 표시 순서. 없으면 빈 목록 */
            feed: components["schemas"]["FeedItemDto"][];
            /** @description The nudge alerts shown at this turn, in display order; empty when the turn has none. Their seq numbers continue after the feed's / 이 턴에 보인 독려 알림, 표시 순서. 없으면 빈 목록. seq 는 피드 뒤에서 이어진다 */
            alerts: components["schemas"]["FeedItemDto"][];
            /** @description Macro environment of this turn's month / 이 턴의 달의 거시 환경 */
            macro: components["schemas"]["MacroDto"];
        };
        /**
         * @description Macro environment of the month applied at this turn, as stored for that month: direction and strength of four axes and four regime probabilities. Transformed values only: no indicator level and no date. A part the engine could not evaluate is null, which means no data, not neutral.
         *
         *     이 턴에 적용된 달의 거시 환경(그 달에 저장된 값): 네 축의 방향·강도와 국면 확률 네 개. 변환값만 싣고 지표의 수준과 날짜는 싣지 않는다. 엔진이 평가하지 못한 부분은 null이며 중립이 아니라 자료 없음을 뜻한다
         */
        MacroDto: {
            /** @description The four axes / 네 축 */
            axes: components["schemas"]["MacroAxesDto"];
            /** @description Regime probabilities; null when the engine did not evaluate the month / 국면 확률. 엔진이 그 달을 평가하지 않았으면 null */
            probabilities: components["schemas"]["RegimeProbabilitiesDto"] | null;
        };
        /**
         * @description The four macro axes; an axis the engine could not evaluate is null
         *
         *     거시 축 네 개. 엔진이 평가하지 못한 축은 null
         */
        MacroAxesDto: {
            /** @description Rate axis; UP means rates are rising / 금리 축. 상승은 금리가 오르는 중 */
            rate: components["schemas"]["MacroAxisDto"] | null;
            /** @description Growth axis; UP means growth is improving / 성장 축. 상승은 성장이 좋아지는 중 */
            growth: components["schemas"]["MacroAxisDto"] | null;
            /** @description Exchange-rate axis; UP means the dollar is strong against the won / 환율 축. 상승은 달러 강세(원화 약세) */
            fx: components["schemas"]["MacroAxisDto"] | null;
            /** @description Market sentiment axis; UP means calmer markets and more risk appetite / 심리 축. 상승은 시장이 차분하고 위험 선호가 큼 */
            sentiment: components["schemas"]["MacroAxisDto"] | null;
        };
        /** @description One macro axis, direction and strength / 거시 축 하나, 방향과 강도 */
        MacroAxisDto: {
            /**
             * @description Direction of the axis: UP, DOWN or NEUTRAL
             *
             *     축의 방향: 상승·하락·중립
             */
            direction: components["schemas"]["AxisDirection"];
            /**
             * Format: decimal
             * @description Size of the axis score, 0 or more, 8 decimal places, no unit
             *
             *     축 점수의 크기, 0 이상, 소수 8자리, 단위 없음
             */
            strength: string;
        };
        /** @enum {string} */
        AxisDirection: "UP" | "DOWN" | "NEUTRAL";
        /**
         * @description Four regime probabilities; their sum can differ from 1 by at most 0.0002
         *
         *     국면 확률 네 개. 합은 1과 최대 0.0002 다를 수 있다
         */
        RegimeProbabilitiesDto: {
            /**
             * Format: decimal
             * @description Probability of rates down and growth down, 4 decimal places
             *
             *     금리 하락·성장 둔화 확률, 소수 4자리
             */
            p1: string;
            /**
             * Format: decimal
             * @description Probability of rates down and growth up, 4 decimal places
             *
             *     금리 하락·성장 확장 확률, 소수 4자리
             */
            p2: string;
            /**
             * Format: decimal
             * @description Probability of rates up and growth up, 4 decimal places
             *
             *     금리 상승·성장 확장 확률, 소수 4자리
             */
            p3: string;
            /**
             * Format: decimal
             * @description Probability of rates up and growth down, 4 decimal places
             *
             *     금리 상승·성장 둔화 확률, 소수 4자리
             */
            p4: string;
        };
        /**
         * @description One feed item as shown. Event cards and noise share this shape; what was a real event is revealed in the review.
         *
         *     보인 그대로의 피드 항목. 사건 카드와 소음은 같은 모양이며 무엇이 실제 사건이었는지는 복기에서 공개한다.
         */
        FeedItemDto: {
            /**
             * Format: int32
             * @description Turn the item was shown in / 보인 턴
             */
            turnNo: number;
            /**
             * Format: int32
             * @description Display order within the turn, from 1 / 턴 안의 표시 순서, 1부터
             */
            seq: number;
            /**
             * Format: int64
             * @description Asset the sentence is about, null when it names none / 문장이 다루는 종목, 없으면 null
             */
            assetId: number | null;
            /** @description The sentence as shown / 보인 문장 */
            body: string;
        };
        /** @description Ranking board of one played turn: seven lists of at most 5 rows each, computed from that turn's data only / 한 턴의 순위판: 그 턴의 자료만으로 계산한, 줄 5개 이하의 목록 일곱 개 */
        RankingBoardDto: {
            /** Format: int32 */
            turnNo: number;
            /** @description Biggest price rises, change above 0, largest first / 가격이 가장 많이 오른 종목, 변화 0 초과, 큰 순 */
            gainers: components["schemas"]["RankedAssetDto"][];
            /** @description Biggest price falls, change below 0, largest fall first / 가격이 가장 많이 내린 종목, 변화 0 미만, 많이 내린 순 */
            losers: components["schemas"]["RankedAssetDto"][];
            /** @description Most attention, by rank and grade; assets without an attention figure are left out / 관심이 가장 큰 종목, 순위와 등급. 관심 값이 없는 종목은 뺀다 */
            attention: components["schemas"]["AttentionRankDto"][];
            /** @description Longest runs of rising turns, at least 2 / 가장 오래 오른 종목, 2턴 이상 */
            risingStreaks: components["schemas"]["StreakRankDto"][];
            /** @description Longest runs of falling turns, at least 2 / 가장 오래 내린 종목, 2턴 이상 */
            fallingStreaks: components["schemas"]["StreakRankDto"][];
            /** @description Groups with the biggest mean rise / 평균 상승이 가장 큰 그룹 */
            groupGainers: components["schemas"]["RankedGroupDto"][];
            /** @description Groups with the biggest mean fall / 평균 하락이 가장 큰 그룹 */
            groupLosers: components["schemas"]["RankedGroupDto"][];
        };
        /** @description One row of a gainers or losers list / 상승·하락 목록의 한 줄 */
        RankedAssetDto: {
            /**
             * Format: int32
             * @description Row position in the list, from 1 / 목록 안의 줄 번호, 1부터
             */
            rank: number;
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)
             */
            assetId: number;
            /**
             * Format: decimal
             * @description Price change of the turn = price ÷ previous turn's price − 1, 8 decimal places / 그 턴의 가격 변화 = 가격 ÷ 직전 턴 가격 − 1, 소수 8자리
             */
            changeRate: string;
        };
        /** @description One row of the attention list; rank and grade only, the index value is not shown / 관심 목록의 한 줄. 순위와 등급만 보이고 지수 값은 보이지 않는다 */
        AttentionRankDto: {
            /**
             * Format: int32
             * @description Row position in the list, from 1 / 목록 안의 줄 번호, 1부터
             */
            rank: number;
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)
             */
            assetId: number;
            /**
             * Format: int32
             * @description Attention grade: 3 = top 10 %, 2 = top half, 1 = the rest / 관심 등급: 3 = 상위 10%, 2 = 상위 절반, 1 = 나머지
             */
            grade: number;
        };
        /** @description One row of a rising or falling streak list / 연속 상승·하락 목록의 한 줄 */
        StreakRankDto: {
            /**
             * Format: int32
             * @description Row position in the list, from 1 / 목록 안의 줄 번호, 1부터
             */
            rank: number;
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)
             */
            assetId: number;
            /**
             * Format: int32
             * @description Turns in a row with the same direction of price change, at least 2 / 가격이 같은 방향으로 이어진 턴 수, 2 이상
             */
            streak: number;
            /**
             * Format: decimal
             * @description Price change of the turn, 8 decimal places / 그 턴의 가격 변화, 소수 8자리
             */
            changeRate: string;
        };
        /** @description One row of a group gainers or losers list / 그룹 상승·하락 목록의 한 줄 */
        RankedGroupDto: {
            /**
             * Format: int32
             * @description Row position in the list, from 1 / 목록 안의 줄 번호, 1부터
             */
            rank: number;
            /**
             * Format: int64
             * @description Asset group identifier (the same as groupId in the pool response) / 종목 그룹 식별자(풀 응답의 groupId 와 같다)
             */
            groupId: number;
            /**
             * Format: decimal
             * @description Mean price change of the group's assets, rounded once to 8 decimal places / 그룹 종목들의 가격 변화 평균, 소수 8자리로 한 번 반올림
             */
            changeRate: string;
        };
        /** @description Season report / 시즌 성적표 */
        SeasonReportDto: {
            /** @description Season status (FINISHED, because only a finished season has a report) / 시즌 상태(끝난 시즌만 성적표를 내므로 FINISHED) */
            seasonStatus: components["schemas"]["SeasonStatus"];
            /**
             * @description Whether the report is final (true for a finished season; an interim report during play comes after the MVP)
             *
             *     확정 여부(끝난 시즌이면 true, 진행 중 잠정 성적표는 MVP 이후)
             */
            isFinal: boolean;
            /**
             * Format: int32
             * @description Number of reported turns = the last snapshot turn / 보고 턴 수 = 마지막 스냅샷 턴
             */
            reportTurns: number;
            /**
             * Format: int32
             * @description Path length (number of turns) / 경로 길이(턴 수)
             */
            pathLength: number;
            /**
             * Format: int32
             * @description Pending orders of the reported turn (0 for a finished season) / 보고 턴의 보류 주문 수(끝난 시즌은 0)
             */
            pendingOrders: number;
            /**
             * Format: int32
             * @description Pending formation changes of the reported turn (0 for a finished season) / 보고 턴의 보류 전술 변경 수(끝난 시즌은 0)
             */
            pendingChanges: number;
            /**
             * Format: decimal
             * @description Initial capital, whole KRW / 초기 자금, 원 단위 정수
             */
            initialCash: string;
            /**
             * Format: decimal
             * @description Final total valuation, whole KRW / 최종 총 평가액, 원 단위 정수
             */
            finalValue: string;
            /**
             * Format: decimal
             * @description Total return = (final − initial) ÷ initial, 8 decimal places / 총 수익률 = (최종 − 초기) ÷ 초기, 소수 8자리
             */
            totalReturn: string;
            /**
             * Format: decimal
             * @description Monthly volatility = sample standard deviation of monthly returns (n − 1), not annualized, 8 decimal places. null for one turn
             *
             *     월 변동성 = 월 수익률의 표본 표준편차(n − 1), 연율화하지 않음, 소수 8자리. 한 턴이면 null
             */
            monthlyVolatility: string | null;
            /**
             * Format: decimal
             * @description Maximum drawdown (0 or less), 8 decimal places / 최대 낙폭(0 이하), 소수 8자리
             */
            maxDrawdown: string;
            /**
             * Format: decimal
             * @description Final valuation of the benchmark (buy and hold of a proxy ETF for the market index), whole KRW
             *
             *     기준선(시장 지수 대용 ETF 매수 후 보유)의 최종 평가액, 원 단위 정수
             */
            baselineFinalValue: string;
            /**
             * Format: decimal
             * @description Benchmark return, 8 decimal places / 기준선 수익률, 소수 8자리
             */
            baselineReturn: string;
            /**
             * Format: decimal
             * @description Excess return = (final − benchmark final) ÷ initial, divided once — not the difference of two returns. 8 decimal places
             *
             *     초과 수익률 = (최종 − 기준선 최종) ÷ 초기, 한 번 나눔 — 두 수익률의 차가 아니다. 소수 8자리
             */
            excessReturn: string;
            /**
             * Format: decimal
             * @description Risk limit (0 or less), 8 decimal places / 위험 선언(0 이하), 소수 8자리
             */
            riskLimit: string;
            /**
             * Format: int32
             * @description Number of turns where the drawdown crossed the risk limit / 낙폭이 위험 선언을 넘은 턴 수
             */
            riskBreachTurns: number;
            /**
             * Format: decimal
             * @description Deepest excess beyond the risk limit (0 or less, 0 if never crossed), 8 decimal places / 위험 선언을 넘은 가장 깊은 정도(0 이하, 넘은 적 없으면 0), 소수 8자리
             */
            riskBreachDepth: string;
            /**
             * Format: decimal
             * @description Maximum single-asset share = the maximum over turns of (largest asset value ÷ total valuation) (0 without holdings), 8 decimal places
             *
             *     최대 단일 종목 비중 = 턴마다 (가장 큰 종목 가치 ÷ 총 평가액)의 최댓값(보유가 없으면 0), 소수 8자리
             */
            maxSingleWeight: string;
            /**
             * Format: decimal
             * @description Total traded amount (fill price × quantity, turn-0 team setup excluded), whole KRW / 매매 대금 합(체결가 × 수량, 0턴 팀 구성 제외), 원 단위 정수
             */
            tradeNotional: string;
            /**
             * Format: decimal
             * @description Annualized turnover = 12 × traded amount ÷ sum of per-turn total valuations, 8 decimal places
             *
             *     연 환산 회전율 = 12 × 매매 대금 ÷ 턴별 총 평가액 합, 소수 8자리
             */
            turnoverAnnual: string;
            /**
             * Format: decimal
             * @description Average formation deviation = exact average over turns of Σ|actual share − target share| (divided once by the common denominator), 8 decimal places. Declared positions; not scored from s63-v3 (see measuredFormationDeviation)
             *
             *     포메이션 이탈 평균 = 턴마다 Σ|실제 비중 − 목표 비중|의 정확한 평균(공통 분모로 한 번 나눔), 소수 8자리. 선언 포지션 기준이며 s63-v3부터 채점하지 않음(measuredFormationDeviation 참고)
             */
            formationDeviation: string;
            /**
             * Format: decimal
             * @description Average formation deviation on season-measured positions (the scored basis from score version s63-v3; seasons scored under s63-v1 or s63-v2 keep the declared basis): the same formula as formationDeviation, with the actual weights grouped by the position each player really played on this season's path; a player the season cannot measure counts by the base-date system judgement, never by the declaration; cash is goalkeeper. Declared positions do not enter, so relabelling cannot change it. Provisional while the season runs. 8 decimal places
             *
             *     시즌 실측 포지션 기준 포메이션 이탈 평균(점수 버전 s63-v3부터 채점 기준, s63-v1·s63-v2로 채점한 시즌은 선언 기준 그대로): 식은 formationDeviation과 같고 실제 비중을 이 시즌 경로에서 선수가 실제로 한 역할로 묶는다. 시즌으로 실측할 수 없는 선수는 기준일 시스템 판정으로 세고 선언으로는 세지 않으며 현금은 골키퍼다. 선언 포지션은 들어가지 않으므로 라벨을 바꿔도 값이 달라지지 않는다. 진행 중에는 잠정, 소수 8자리
             */
            measuredFormationDeviation: string;
            /**
             * Format: decimal
             * @description Role fit = the exposed value of players whose declared and measured season positions match ÷ the exposed value of measured players, 8 decimal places. null if there is no measured exposure.
             *
             *     역할 수행 = 선언과 시즌 실측 포지션이 같은 선수의 노출 가치 ÷ 실측된 선수의 노출 가치, 소수 8자리. 실측된 노출이 없으면 null
             */
            roleFit: string | null;
            /**
             * Format: int32
             * @description Number of exposed assets whose position was measured / 실측 포지션이 나온 노출 종목 수
             */
            rolePlayersJudged: number;
            /**
             * Format: int32
             * @description Number of exposed assets that could not be measured for lack of samples (counted by the base-date system judgement in measuredFormationDeviation) / 표본 부족으로 실측하지 못한 노출 종목 수(measuredFormationDeviation에서는 기준일 시스템 판정으로 센다)
             */
            rolePlayersUnjudged: number;
            /**
             * Format: decimal
             * @description Encouragement share = traded amount from trades initiated through rankings, feed and alerts ÷ traded amount, 8 decimal places. null if the traded amount is 0.
             *
             *     독려 비중 = 랭킹·피드·알림에서 시작된 매매 대금 ÷ 매매 대금, 소수 8자리. 매매 대금이 0이면 null
             */
            nudgeShare: string | null;
            /**
             * Format: decimal
             * @description Same-turn encouragement share = encouraged traded amount whose ranking or feed turn equals the order turn ÷ traded amount, 8 decimal places. null if the traded amount is 0
             *
             *     같은 턴 독려 비중 = 랭킹 또는 피드 턴과 주문 턴이 같은 독려 매매 대금 ÷ 매매 대금, 소수 8자리. 매매 대금이 0이면 null
             */
            nudgeSameTurnShare: string | null;
            /**
             * Format: decimal
             * @description Total fees (turn-0 team setup included), whole KRW / 수수료 합(0턴 팀 구성 포함), 원 단위 정수
             */
            totalFee: string;
            /**
             * Format: decimal
             * @description Turn-0 team setup fees (buys and sells), whole KRW / 0턴 팀 구성 수수료(매수·매도 모두), 원 단위 정수
             */
            teamFee: string;
            /**
             * Format: decimal
             * @description Total fees of fills linked to a formation change, whole KRW / 전술 변경에 연결된 체결의 수수료 합, 원 단위 정수
             */
            formationChangeFee: string;
            /**
             * Format: decimal
             * @description Encouragement portion of platform revenue = fees from trades initiated through rankings and alerts, whole KRW.
             *
             *     플랫폼 수익 중 독려 몫 = 랭킹·알림에서 시작된 매매의 수수료, 원 단위 정수
             */
            platformNudgeFee: string;
            /**
             * Format: int32
             * @description Number of formation changes (in the report range) / 전술 변경 수(보고 범위)
             */
            formationChanges: number;
            /**
             * Format: int32
             * @description Number of orders linked to a formation change / 전술 변경에 연결된 주문 수
             */
            formationChangeOrders: number;
            /**
             * Format: int32
             * @description Number of formation changes with no linked order / 연결된 주문이 없는 전술 변경 수
             */
            changesWithoutOrders: number;
            /**
             * Format: int32
             * @description Number of recorded reconciliation mismatch rows (recovered attempts included) / 기록된 대사 불일치 행 수(복구된 시도 포함)
             */
            reconMismatchRows: number;
            /**
             * Format: int32
             * @description Number of turn-advance reconciliation bundles (17 rows per turn and attempt) / 턴 전환 대사 묶음 수(턴·시도마다 17행)
             */
            reconTurnBundles: number;
            /**
             * Format: int32
             * @description Number of manual reconciliation bundles (15 rows per turn and attempt) / 수동 대사 묶음 수(턴·시도마다 15행)
             */
            reconManualBundles: number;
            /**
             * Format: int32
             * @description Number of reconciliation rows for turns beyond the path length / 경로 길이를 넘는 턴의 대사 행 수
             */
            reconOutOfRangeRows: number;
        };
        /** @description Season analysis: baselines, three-way split, trading discipline, noise reaction, platform revenue by order source / 시즌 분석: 기준선·3분해·매매 규율·소음 반응·출발점별 플랫폼 수익 */
        SeasonAnalysisDto: {
            /** @description Version of the analysis rules / 분석 규칙 버전 */
            analysisVersion: string;
            /** @description Version of the representative-asset table / 대표 자산 표 버전 */
            representativeVersion: string;
            /**
             * Format: int32
             * @description Number of reported turns = the last snapshot turn / 보고 턴 수 = 마지막 스냅샷 턴
             */
            reportTurns: number;
            /** @description The four baselines in a fixed order: MARKET, SIXTY_FORTY, FORMATION_FOLLOW, FORMATION_FIXED / 기준선 네 개, 순서 고정 */
            baselines: components["schemas"]["BaselineDto"][];
            /** @description Three-way split of the excess over the market; null when a formation baseline is unavailable / 시장 대비 초과분의 3분해. 포메이션 기준선이 없으면 null */
            decomposition: components["schemas"]["DecompositionDto"] | null;
            /** @description Trading discipline / 매매 규율 */
            discipline: components["schemas"]["DisciplineDto"];
            /** @description Reaction to noise (process item) / 소음 반응(과정 항목) */
            noise: components["schemas"]["NoiseDto"];
            /** @description Platform revenue split by order source / 출발점별 플랫폼 수익 */
            platform: components["schemas"]["PlatformDto"];
            /** @description Attention following: buys of top-attention assets against all other buys over the next 6 turns (review fact) / 관심 추종: 관심 상위 종목 매수와 그 밖의 매수의 뒤 6턴 성과(복기 사실) */
            attentionFollowing: components["schemas"]["AttentionFollowingDto"];
            /** @description Defender goals in down-market turns (review fact) / 시장이 내린 턴의 수비수 득점(복기 사실) */
            defenderGoals: components["schemas"]["DefenderGoalsDto"];
            /** @description 6-turn effect of each formation change in id order, empty when there is none; null when a formation baseline is unavailable / 전술 변경마다의 6턴 효과(변경 번호 순, 없으면 빈 목록). 포메이션 기준선이 없으면 null */
            changeEffects: components["schemas"]["ChangeEffectDto"][] | null;
        };
        /** @description One baseline portfolio simulated on the season prices / 시즌 가격으로 시뮬레이션한 기준선 하나 */
        BaselineDto: {
            /** @description Baseline key (MARKET, SIXTY_FORTY, FORMATION_FOLLOW, FORMATION_FIXED) / 기준선 키 */
            key: string;
            /** @description Whether the baseline could be built from the season pool / 시즌 풀로 기준선을 만들 수 있었는지 */
            available: boolean;
            /** @description Why it is unavailable (REPRESENTATIVE_MISSING), else null / 만들 수 없는 이유, 아니면 null */
            reason: string | null;
            /** @description Slots and legs of the baseline (empty when unavailable) / 기준선의 칸과 구성 종목(없으면 빈 목록) */
            legs: components["schemas"]["LegDto"][];
            /**
             * Format: decimal
             * @description Final value of the baseline, whole KRW / 기준선의 최종 평가액, 원 단위 정수
             */
            finalValue: string | null;
            /**
             * Format: decimal
             * @description Baseline return = (final − initial) ÷ initial, 8 decimal places / 기준선 수익률 = (최종 − 초기) ÷ 초기, 소수 8자리
             */
            totalReturn: string | null;
            /**
             * Format: decimal
             * @description Excess of the user's final value over the baseline's, whole KRW / 사용자 최종 평가액이 기준선보다 큰 금액, 원 단위 정수
             */
            excessWon: string | null;
            /**
             * Format: decimal
             * @description Excess ÷ initial capital, divided once, 8 decimal places / 초과분 ÷ 초기 자금, 한 번 나눔, 소수 8자리
             */
            excessReturn: string | null;
            /**
             * Format: decimal
             * @description Fees of every fill including the opening purchase, whole KRW / 시작 매수를 포함한 모든 체결의 수수료, 원 단위 정수
             */
            totalFee: string | null;
            /**
             * Format: int32
             * @description Turns with a rebalance that traded or was forced by a formation change / 매매가 있었거나 전술 변경으로 강제된 리밸런싱 턴 수
             */
            rebalanceCount: number | null;
            /**
             * Format: int32
             * @description Turns where the band was broken but integer shares allowed no trade / 밴드를 넘었지만 정수 주식 때문에 매매가 없던 턴 수
             */
            bandNoFillCount: number | null;
            /**
             * Format: int32
             * @description Turns with a rebalance forced by a formation change (only the following baseline has any) / 전술 변경으로 강제된 리밸런싱 턴 수(따라가는 기준선만 있다)
             */
            forcedChangeCount: number | null;
            /**
             * Format: decimal
             * @description Fees paid in forced rebalances, whole KRW / 강제 리밸런싱에서 낸 수수료, 원 단위 정수
             */
            forcedChangeFee: string | null;
            /** @description True when the bond leg is not a domestic bond ETF and a US Treasury ETF stands in / 채권 칸이 국내 채권 ETF가 아니라 미국 국채 ETF로 대신된 경우 true */
            bondLegSubstituted: boolean;
            /** @description Value of the baseline at turns 0..T, before the trades of each turn, whole KRW / 턴 0..T의 기준선 평가액(그 턴의 매매 전), 원 단위 정수 */
            turnValues: string[] | null;
        };
        /** @description One asset of a baseline slot / 기준선 칸의 종목 하나 */
        LegDto: {
            /** @description Asset as MARKET:TICKER / 종목, MARKET:TICKER 꼴 */
            asset: string;
            /** @description Slot name (MARKET, EQUITY, BOND, GK, DF, MF, FW) / 칸 이름 */
            slot: string;
            /**
             * Format: int32
             * @description Share of the slot in whole percent / 칸 안의 비중, 정수 퍼센트
             */
            sharePercent: number;
        };
        /** @description Three-way split of the excess over the market baseline / 시장 기준선 대비 초과분의 3분해 */
        DecompositionDto: {
            /**
             * Format: decimal
             * @description Player selection = user final − the following baseline's final, whole KRW / 선수 선택 = 사용자 최종 − 따라가는 기준선 최종, 원 단위 정수
             */
            selectionWon: string;
            /**
             * Format: decimal
             * @description Formation change = following baseline's final − fixed baseline's final, whole KRW / 전술 변경 = 따라가는 기준선 최종 − 고정 기준선 최종, 원 단위 정수
             */
            tacticWon: string;
            /**
             * Format: decimal
             * @description Structure = fixed baseline's final − market baseline's final, whole KRW / 구조 = 고정 기준선 최종 − 시장 기준선 최종, 원 단위 정수
             */
            structureWon: string;
            /**
             * Format: decimal
             * @description Total = user final − market baseline's final; the three terms add up to it exactly, whole KRW / 합계 = 사용자 최종 − 시장 기준선 최종, 세 항의 합과 정확히 같다, 원 단위 정수
             */
            totalWon: string;
            /**
             * Format: decimal
             * @description Selection ÷ initial capital, 8 decimal places / 선수 선택 ÷ 초기 자금, 소수 8자리
             */
            selectionReturn: string;
            /**
             * Format: decimal
             * @description Formation change ÷ initial capital, 8 decimal places / 전술 변경 ÷ 초기 자금, 소수 8자리
             */
            tacticReturn: string;
            /**
             * Format: decimal
             * @description Structure ÷ initial capital, 8 decimal places / 구조 ÷ 초기 자금, 소수 8자리
             */
            structureReturn: string;
            /**
             * Format: decimal
             * @description Total ÷ initial capital, divided once; the three ratios may differ from it by rounding, 8 decimal places / 합계 ÷ 초기 자금, 한 번 나눔. 세 비율의 합은 반올림 때문에 다를 수 있다, 소수 8자리
             */
            totalReturn: string;
        };
        /** @description Trading discipline: panic selling and chase buying / 매매 규율: 패닉 매도·추격 매수 */
        DisciplineDto: {
            /**
             * Format: decimal
             * @description A fall of this ratio or more in the month just shown is a drop, boundary included, 8 decimal places / 방금 본 달에 이 비율 이상 내리면 하락, 경계 포함, 소수 8자리
             */
            dropThreshold: string;
            /**
             * Format: decimal
             * @description A rise of this ratio or more in the month just shown is a rise, boundary included, 8 decimal places / 방금 본 달에 이 비율 이상 오르면 상승, 경계 포함, 소수 8자리
             */
            riseThreshold: string;
            /**
             * Format: decimal
             * @description All sells of turns 1..T-1, whole KRW / 턴 1..T-1의 모든 매도 대금, 원 단위 정수
             */
            sellNotional: string;
            /**
             * Format: decimal
             * @description Panic sells, whole KRW / 패닉 매도 대금, 원 단위 정수
             */
            panicSellNotional: string;
            /**
             * Format: decimal
             * @description Panic sells ÷ all sells, 8 decimal places; null when nothing was sold / 패닉 매도 ÷ 전체 매도, 소수 8자리. 판 것이 없으면 null
             */
            panicSellShare: string | null;
            /**
             * Format: decimal
             * @description All buys of turns 1..T-1, whole KRW / 턴 1..T-1의 모든 매수 대금, 원 단위 정수
             */
            buyNotional: string;
            /**
             * Format: decimal
             * @description Chase buys, whole KRW / 추격 매수 대금, 원 단위 정수
             */
            chaseBuyNotional: string;
            /**
             * Format: decimal
             * @description Chase buys ÷ all buys, 8 decimal places; null when nothing was bought / 추격 매수 ÷ 전체 매수, 소수 8자리. 산 것이 없으면 null
             */
            chaseBuyShare: string | null;
            /**
             * Format: decimal
             * @description Sells plus buys of turns 1..T-1, equal to the report's traded amount, whole KRW / 턴 1..T-1의 매도 + 매수, 성적표의 매매 대금과 같다, 원 단위 정수
             */
            tradeNotional: string;
            /**
             * Format: decimal
             * @description (Panic sells + chase buys) ÷ traded amount, 8 decimal places; null when nothing was traded / (패닉 매도 + 추격 매수) ÷ 매매 대금, 소수 8자리. 거래가 없으면 null
             */
            undisciplinedShare: string | null;
            /**
             * Format: int32
             * @description Formation change rows made in turns 1..T-1; a turn with two changes counts twice / 턴 1..T-1에 한 전술 변경 행 수. 한 턴에 두 번이면 둘을 센다
             */
            formationChanges: number;
            /**
             * Format: int32
             * @description Formation change rows that belong to a panic turn (team value fell 8% or more and the forward target ended lower than it started). Counted by row, not by turn / 패닉 턴(팀 평가액이 8% 이상 내렸고 공격 목표가 시작보다 낮게 끝난 턴)에 속한 전술 변경 행 수. 턴이 아니라 행 기준
             */
            panicFormationChanges: number;
        };
        /** @description Reaction to noise: how much of what was traded followed what the feed and the boards pointed at / 소음 반응: 매매 가운데 피드와 순위판이 가리킨 쪽을 따른 몫 */
        NoiseDto: {
            /**
             * Format: decimal
             * @description Mean over the turns that traded of (followed buys + followed sells) ÷ the turn's traded amount, 8 decimal places; null when no turn traded / 매매가 있던 턴마다 (따른 매수 + 따른 매도) ÷ 그 턴 매매 금액의 평균, 소수 8자리. 거래가 없으면 null
             */
            noiseShare: string | null;
            /**
             * Format: int32
             * @description Turns of 1..T-1 with at least one filled order / 턴 1..T-1 가운데 체결된 주문이 있는 턴 수
             */
            tradingTurns: number;
            /**
             * Format: decimal
             * @description Buys that followed the noise, whole KRW / 소음을 따른 매수 대금, 원 단위 정수
             */
            followedBuyNotional: string;
            /**
             * Format: decimal
             * @description Sells that followed the noise, whole KRW / 소음을 따른 매도 대금, 원 단위 정수
             */
            followedSellNotional: string;
            /**
             * Format: decimal
             * @description Sells plus buys of turns 1..T-1, equal to the report's traded amount, whole KRW / 턴 1..T-1의 매도 + 매수, 성적표의 매매 대금과 같다, 원 단위 정수
             */
            tradeNotional: string;
        };
        /** @description Platform revenue: fees split by where the order came from / 플랫폼 수익: 주문의 출발점별 수수료 */
        PlatformDto: {
            /**
             * Format: decimal
             * @description Fees of every fill including the team purchase, equal to the report's total fee, whole KRW / 팀 구성을 포함한 모든 체결의 수수료, 성적표의 총 수수료와 같다, 원 단위 정수
             */
            totalFee: string;
            /**
             * Format: decimal
             * @description Fees of the team purchase (turn 0), whole KRW / 팀 구성(0턴)의 수수료, 원 단위 정수
             */
            teamFee: string;
            /** @description Five rows in the fixed order RANKING, FEED, ALERT, BENCH, DIRECT, turns 1..T-1; a source with no order has zeros / 다섯 행, 순서 고정(RANKING, FEED, ALERT, BENCH, DIRECT), 턴 1..T-1. 주문이 없는 출발점은 0 */
            bySource: components["schemas"]["SourceFeeDto"][];
            /**
             * Format: decimal
             * @description Fees of RANKING and ALERT orders, the part the platform earned by nudging; FEED is not included, whole KRW / RANKING·ALERT 주문의 수수료, 플랫폼이 독려로 번 몫. FEED 는 넣지 않는다, 원 단위 정수
             */
            nudgedFee: string;
            /**
             * Format: decimal
             * @description Nudged fee ÷ total fee, 8 decimal places; null when the total fee is 0 / 독려 수수료 ÷ 총 수수료, 소수 8자리. 총 수수료가 0이면 null
             */
            nudgedFeeShare: string | null;
        };
        /** @description Orders, traded amount and fees of one order source / 한 출발점의 주문 수·매매 금액·수수료 */
        SourceFeeDto: {
            /** @description Order source / 주문 출발점 */
            source: components["schemas"]["OrderSource"];
            /**
             * Format: int32
             * @description Filled orders of turns 1..T-1 from this source / 이 출발점의 턴 1..T-1 체결 주문 수
             */
            orders: number;
            /**
             * Format: decimal
             * @description Buys plus sells from this source, whole KRW / 이 출발점의 매수 + 매도 대금, 원 단위 정수
             */
            tradeNotional: string;
            /**
             * Format: decimal
             * @description Fees of this source's orders, whole KRW / 이 출발점 주문의 수수료, 원 단위 정수
             */
            fee: string;
        };
        /** @description Attention following: how buys of top-attention assets and other buys fared over the next 6 turns / 관심 추종: 관심 상위 종목 매수와 그 밖의 매수가 뒤 6턴 동안 어떻게 됐는지 */
        AttentionFollowingDto: {
            /**
             * Format: int32
             * @description Window length in turns (6) / 창 길이, 턴 수(6)
             */
            windowTurns: number;
            /**
             * Format: int32
             * @description Buys of top-attention assets (top 10 %, ties included) at the buying turn with a complete window / 매수 턴에 관심 상위(10%, 동률 포함)였고 창이 끝난 종목의 매수 수
             */
            attentionBuys: number;
            /**
             * Format: decimal
             * @description Their amount, whole KRW / 그 매수 대금, 원 단위 정수
             */
            attentionBuyNotional: string;
            /**
             * Format: decimal
             * @description Amount-weighted mean of price(k+6) ÷ price(k) − 1 over those buys, 8 decimal places; null when there is none / 그 매수들의 price(k+6) ÷ price(k) − 1 의 금액 가중 평균, 소수 8자리. 없으면 null
             */
            attentionReturn: string | null;
            /**
             * Format: int32
             * @description All other buys with a complete window (an asset with no attention index is "other") / 창이 끝난 그 밖의 매수 수(관심 지수가 없는 종목은 "그 밖")
             */
            otherBuys: number;
            /**
             * Format: decimal
             * @description Their amount, whole KRW / 그 매수 대금, 원 단위 정수
             */
            otherBuyNotional: string;
            /**
             * Format: decimal
             * @description Amount-weighted mean return of the other buys, 8 decimal places; null when there is none / 그 밖의 매수의 금액 가중 평균 수익률, 소수 8자리. 없으면 null
             */
            otherReturn: string | null;
            /**
             * Format: int32
             * @description Buys of turns 1..T-1 whose 6-turn window ends after the last turn; counted, in neither group / 6턴 창이 마지막 턴 뒤로 넘어가는 턴 1..T-1의 매수 수. 세기만 하고 어느 쪽에도 넣지 않는다
             */
            incompleteBuys: number;
        };
        /** @description Defender goals: gains of holdings declared defender or goalkeeper in turns where the market fell / 수비수 득점: 시장이 내린 턴에 수비수·골키퍼로 선언한 보유 종목의 이익 */
        DefenderGoalsDto: {
            /**
             * Format: int32
             * @description Turns of 1..T where the market pair (069500 and SPY, 50/50) fell from the turn before / 시장 대용 두 종목(069500·SPY 50/50)이 직전 턴보다 내린 턴 1..T의 수
             */
            downMarketTurns: number;
            /**
             * Format: int32
             * @description Goals: holdings at the start of a down-market turn, declared defender or goalkeeper as of the turn before, whose price rose / 득점 수: 시장이 내린 턴 시작에 보유했고 직전 턴 기준 수비수·골키퍼로 선언했으며 가격이 오른 종목의 수
             */
            goals: number;
            /**
             * Format: decimal
             * @description Sum of quantity × price rise of the goals, whole KRW / 득점의 수량 × 가격 상승분 합계, 원 단위 정수
             */
            goalWon: string;
            /**
             * Format: decimal
             * @description Goal won ÷ initial capital, divided once, 8 decimal places / 득점 금액 ÷ 초기 자금, 한 번 나눔, 소수 8자리
             */
            goalReturn: string;
        };
        /** @description Effect of one formation change over the next 6 turns, net of simulated fees / 전술 변경 하나의 뒤 6턴 효과, 시뮬레이션 수수료를 뺀 값 */
        ChangeEffectDto: {
            /**
             * Format: int32
             * @description Turn of the change / 변경한 턴
             */
            turnNo: number;
            /**
             * Format: int32
             * @description Turns from the change to the measuring turn, 1 to 6 (shorter near the season end) / 변경부터 잰 턴까지의 턴 수, 1~6(시즌 끝 가까이에서는 짧다)
             */
            windowTurns: number;
            /**
             * Format: decimal
             * @description Following baseline with this change minus the same baseline without it, at the measuring turn, whole KRW / 이 변경을 따른 기준선에서 이 변경이 없는 같은 기준선을 뺀 값을 잰 턴에 잰 것, 원 단위 정수
             */
            effectWon: string;
            /**
             * Format: decimal
             * @description Effect ÷ initial capital, divided once, 8 decimal places / 효과 ÷ 초기 자금, 한 번 나눔, 소수 8자리
             */
            effectReturn: string;
        };
        /**
         * @description Season review for a finished season. Month block number, event number, event key, reference date, member number and times are not included
         *
         *     끝난 시즌의 복기. 월 블록 번호·사건 번호·사건 키·기준일·회원 번호·시각은 싣지 않는다
         */
        SeasonReviewDto: {
            /** @description Season status (FINISHED, because only a finished season has a season review) / 시즌 상태(끝난 시즌만 복기를 내므로 FINISHED) */
            seasonStatus: components["schemas"]["SeasonStatus"];
            /**
             * Format: int32
             * @description Path length (number of turns) / 경로 길이(턴 수)
             */
            pathLength: number;
            /**
             * Format: int32
             * @description The turn that was given up. null if the season was not given up / 포기한 턴. 포기하지 않았으면 null
             */
            abandonedTurnNo: number | null;
            /** @description Path seed (decimal string of a 64-bit integer) / 경로 시드(64비트 정수의 10진 문자열) */
            seed: string;
            /** @description Engine version that built the path / 경로를 만든 엔진 버전 */
            engineVersion: string;
            /**
             * Format: date
             * @description Data snapshot date (yyyy-MM-dd) / 데이터 스냅샷 날짜(yyyy-MM-dd)
             */
            dataSnapshotDate: string;
            /** @description In turn-number order (1..path length) / 턴 번호 순서(1..경로 길이) */
            turns: components["schemas"]["ReviewTurnDto"][];
            /** @description Formation-change history (in change order). An empty list if there is none / 전술 변경 기록(변경 순서). 없으면 빈 목록 */
            formationChanges: components["schemas"]["FormationChangeDto"][];
        };
        ReviewTurnDto: {
            /**
             * Format: int32
             * @description Turn number (from 1) / 턴 번호(1부터)
             */
            turnNo: number;
            /** @description The real year-month used in this turn (yyyy-MM) / 이 턴에 쓰인 실제 연월(yyyy-MM) */
            yearMonth: string;
            /** @description One-line summary of that month. null if there is none / 그 달의 한 줄 요약. 없으면 null */
            eventSummary: string | null;
            /** @description Event tags of that month (in event key order). An empty list if there are none / 그 달의 사건 태그(사건 키 순서). 없으면 빈 목록 */
            events: components["schemas"]["ReviewEventDto"][];
            /** @description Feed items of that turn as they were shown, in display order, each with what it really was. NUDGE items were shown in the alert list during play, numbered after the feed items. In an abandoned season, items of turns after abandonedTurnNo were generated by the automatic run and never shown. An empty list if the turn has none / 그 턴에 보인 그대로의 피드 항목(표시 순서)과 각 항목의 정체. NUDGE 항목은 알림 목록에 보였고 번호는 피드 항목 뒤에 이어진다. 포기한 시즌에서 abandonedTurnNo 뒤 턴의 항목은 자동 진행이 만든 것이라 보이지 않았다. 없으면 빈 목록 */
            feed: components["schemas"]["ReviewFeedItemDto"][];
        };
        /** @description One feed item of a turn as it was shown, with what it really was / 턴에 보인 피드 항목 하나와 그 정체 */
        ReviewFeedItemDto: {
            /**
             * Format: int32
             * @description Display order within the turn (from 1) / 턴 안의 표시 순서(1부터)
             */
            seq: number;
            kind: components["schemas"]["FeedKind"];
            /** @description True exactly when the item was a real event card (kind EVENT) / 실제 사건 카드(kind EVENT)일 때만 true */
            signal: boolean;
            /**
             * Format: int64
             * @description Asset the item named. null if it named none / 항목이 가리킨 종목. 없으면 null
             */
            assetId: number | null;
            /**
             * Format: int64
             * @description Asset group the item named. null if it named none / 항목이 가리킨 그룹. 없으면 null
             */
            groupId: number | null;
            /** @description The sentence as it was shown / 보인 그대로의 문장 */
            body: string;
            /** @description The real event card behind the item. null unless signal is true / 항목 뒤의 실제 사건 카드. signal 이 true 일 때만 있다 */
            event: components["schemas"]["ReviewEventDto"] | null;
            /**
             * Format: int32
             * @description Number of orders of the season placed from this item, whatever their outcome, including orders placed in a later turn / 이 항목을 보고 낸 주문 수(체결 여부와 무관, 뒤 턴에 낸 주문 포함)
             */
            ordersFromItem: number;
        };
        ReviewEventDto: {
            category: components["schemas"]["EventCategory"];
            /** @description Event kind / 사건 유형 */
            eventType: string;
            phase: components["schemas"]["EventPhase"];
            /** @description Real name / 실제 이름 */
            title: string;
            /** @description Name given later and hindsight assessment. null if there is none / 나중에 붙은 이름·사후 평가. 없으면 null */
            hindsight: string | null;
            /** @description URL or bibliography / URL 또는 서지 */
            source: string;
        };
        /**
         * @description Holdings of the current turn × that turn's prices (a live value that reflects this turn's trades immediately). In a turn without trades, the total, holdings valuation and valuation per position equal the turn result of that turn. currentFormation is the formation applied now. The sum of the four shares can differ from 1 by at most 2e-8. Seed, path, month block, year-month and return kind are not included.
         *
         *     현재 턴의 보유 × 그 턴 가격(이번 턴의 매매가 바로 반영되는 실시간 값). 매매가 없는 턴이면 총액·보유 평가액·포지션별 평가액이 그 턴의 턴 결과와 같다. currentFormation 은 지금 적용 중인 포메이션이다. 비중 4개의 합은 1과 최대 2e-8 다를 수 있다. 시드·경로·월 블록·연월·수익률 종류는 싣지 않는다
         */
        HoldingsDto: {
            /**
             * Format: int64
             * @description Season identifier / 시즌 식별자
             */
            seasonId: number;
            /**
             * Format: int32
             * @description The season's current turn (the turn the prices are based on) / 시즌의 현재 턴(가격 기준 턴)
             */
            turnNo: number;
            /**
             * Format: decimal
             * @description Cash balance (now), whole KRW / 현금 잔액(지금), 원 단위 정수
             */
            cashBalance: string;
            /**
             * Format: decimal
             * @description Holdings valuation = Σ quantity × current turn price, whole KRW / 보유 평가액 = Σ 수량 × 현재 턴 가격, 원 단위 정수
             */
            holdingsValue: string;
            /**
             * Format: decimal
             * @description Total valuation = cash + holdings valuation, whole KRW / 총 평가액 = 현금 + 보유 평가액, 원 단위 정수
             */
            totalValue: string;
            /**
             * Format: decimal
             * @description Goalkeeper valuation (cash included, total − the other three), whole KRW / 골키퍼 평가액(현금 포함, 총액 − 나머지 셋), 원 단위 정수
             */
            goalkeeperValue: string;
            /**
             * Format: decimal
             * @description Defender valuation, whole KRW / 수비수 평가액, 원 단위 정수
             */
            defenderValue: string;
            /**
             * Format: decimal
             * @description Midfielder valuation, whole KRW / 미드필더 평가액, 원 단위 정수
             */
            midfielderValue: string;
            /**
             * Format: decimal
             * @description Attacker valuation, whole KRW / 공격수 평가액, 원 단위 정수
             */
            forwardValue: string;
            /**
             * Format: decimal
             * @description Goalkeeper share = valuation ÷ total valuation, 8 decimal places / 골키퍼 비중 = 평가액 ÷ 총 평가액, 소수 8자리
             */
            goalkeeperWeight: string;
            /**
             * Format: decimal
             * @description Defender share, 8 decimal places / 수비수 비중, 소수 8자리
             */
            defenderWeight: string;
            /**
             * Format: decimal
             * @description Midfielder share, 8 decimal places / 미드필더 비중, 소수 8자리
             */
            midfielderWeight: string;
            /**
             * Format: decimal
             * @description Attacker share, 8 decimal places / 공격수 비중, 소수 8자리
             */
            forwardWeight: string;
            currentFormation: components["schemas"]["FormationDto"];
            /**
             * Format: decimal
             * @description Goalkeeper share − current formation (positive = more than the target), 8 decimal places / 골키퍼 비중 − 현재 포메이션(양수 = 목표보다 많음), 소수 8자리
             */
            goalkeeperGap: string;
            /**
             * Format: decimal
             * @description Defender share − current formation, 8 decimal places / 수비수 비중 − 현재 포메이션, 소수 8자리
             */
            defenderGap: string;
            /**
             * Format: decimal
             * @description Midfielder share − current formation, 8 decimal places / 미드필더 비중 − 현재 포메이션, 소수 8자리
             */
            midfielderGap: string;
            /**
             * Format: decimal
             * @description Attacker share − current formation, 8 decimal places / 공격수 비중 − 현재 포메이션, 소수 8자리
             */
            forwardGap: string;
            /**
             * Format: decimal
             * @description Formation deviation = sum of |share − current formation| over the positions, 8 decimal places
             *
             *     포메이션 이탈 = 포지션별 |비중 − 현재 포메이션|의 합, 소수 8자리
             */
            formationDeviation: string;
            /** @description Holdings with quantity 1 or more (ascending asset number) / 수량 1 이상인 보유(종목 번호 오름차순) */
            holdings: components["schemas"]["HoldingDto"][];
        };
        /**
         * @description One held asset. The asset name is not included — link it by assetId from the pool response
         *
         *     보유 한 종목. 종목 이름은 싣지 않는다 — 풀 응답의 assetId 로 잇는다
         */
        HoldingDto: {
            /**
             * Format: int64
             * @description Asset identifier (the same as assetId in the pool response) / 종목 식별자(풀 응답의 assetId 와 같다)
             */
            assetId: number;
            /**
             * Format: int32
             * @description Quantity held (1 or more) / 보유 수량(1 이상)
             */
            quantity: number;
            /**
             * Format: decimal
             * @description Average cost (fee excluded), 4 decimal places / 평균단가(수수료 제외), 소수 4자리
             */
            avgPrice: string;
            /** @description Declared position of the open span / 열린 구간의 선언 포지션 */
            declaredPosition: components["schemas"]["Position"];
            /**
             * Format: decimal
             * @description Current turn price, whole KRW / 현재 턴 가격, 원 단위 정수
             */
            price: string;
            /**
             * Format: decimal
             * @description Valuation = quantity × current turn price, whole KRW / 평가액 = 수량 × 현재 턴 가격, 원 단위 정수
             */
            value: string;
            /**
             * Format: decimal
             * @description Asset share = valuation ÷ total valuation (cash included), 8 decimal places / 종목 비중 = 평가액 ÷ 총 평가액(현금 포함), 소수 8자리
             */
            weight: string;
        };
        /** @enum {string} */
        OrderSide: "BUY" | "SELL";
        /** @enum {string} */
        ProjectionMoment: "CURRENT" | "START" | "END";
        /** @enum {string} */
        OrderSource: "RANKING" | "FEED" | "ALERT" | "BENCH" | "DIRECT";
        /** @enum {string} */
        SeasonStatus: "IN_PROGRESS" | "ABANDONING" | "FINISHED" | "HALTED" | "PAUSED" | "VOID";
        /** @enum {string} */
        EventCategory: "GEOPOLITICS" | "TRADE" | "POLITICS" | "SUPPLY_SHOCK" | "FINANCIAL" | "POLICY" | "HEALTH";
        /** @enum {string} */
        EventPhase: "START" | "ONGOING" | "END";
        /** @enum {string} */
        FeedKind: "RISE" | "FALL" | "ECHO" | "SPREAD" | "FADE" | "EVENT" | "NUDGE";
        /** @enum {string} */
        Market: "KR" | "US";
        /** @enum {string} */
        AssetType: "STOCK" | "ETF";
        /** @enum {string} */
        ReturnBasis: "PRICE" | "TOTAL";
        /** @enum {string} */
        AssetGroupKind: "SECTOR" | "ASSET_CLASS";
        /** @enum {string} */
        Position: "GOALKEEPER" | "DEFENDER" | "MIDFIELDER" | "FORWARD_TARGET" | "FORWARD_BREAKOUT" | "FORWARD_DEFENSIVE";
        /** @description RFC 9457 problem details + a top-level code. type is omitted because it is about:blank / RFC 9457 문제 상세 + 최상위 code. type은 about:blank라 생략된다 */
        Problem: {
            type?: string;
            title: string;
            /** Format: int32 */
            status: number;
            /** @description Fixed Korean sentence. It never carries the rejected value or amount / 고정 한국어 문장. 거부된 값·금액을 싣지 않는다 */
            detail: string;
            instance: string;
            code: components["schemas"]["ErrorCode"];
            /** @description Only for @Valid violations / @Valid 위반일 때만 */
            errors?: components["schemas"]["FieldViolation"][];
        };
        FieldViolation: {
            field: string;
            /** @description Simple name of the constraint annotation (for example Min) / 제약 애너테이션 단순 이름(예 Min) */
            reason: string;
        };
        /** @enum {string} */
        ErrorCode: "INVALID_ARGUMENT" | "NOT_FOUND" | "METHOD_NOT_ALLOWED" | "NOT_ACCEPTABLE" | "UNSUPPORTED_MEDIA_TYPE" | "MALFORMED_REQUEST" | "INTERNAL_ERROR" | "POOL_NOT_LOADED" | "POOL_NOT_JUDGED" | "PATH_TOO_LONG" | "ENGINE_UNAVAILABLE" | "SEASON_LIMIT" | "TURN_NOT_REACHED" | "SEASON_NOT_FINISHED" | "SEASON_NOT_ACTIVE" | "TURN_MISMATCH" | "LEDGER_MISMATCH" | "LINEUP_MISMATCH" | "ASSET_NOT_IN_POOL" | "SOURCE_NOT_VISIBLE" | "INSUFFICIENT_CASH" | "INSUFFICIENT_HOLDING" | "POSITION_CONFLICT" | "POSITION_REQUIRED" | "INVALID_FORMATION" | "EVENT_TIME_CONFLICT" | "RECONCILIATION_FAILED" | "SEASON_HALTED" | "FORBIDDEN" | "EMAIL_TAKEN" | "UNAUTHENTICATED" | "INVALID_CREDENTIALS" | "LEAGUE_CLOSED" | "LEAGUE_SEAT_TAKEN" | "LEAGUE_FULL" | "LEAGUE_STALE" | "LEAGUE_OPEN";
        /** @description Season score / 시즌 점수 */
        SeasonScoreDto: {
            /** @description Formula version the score was computed with / 점수를 계산한 공식 버전 */
            scoreVersion: string;
            /**
             * Format: decimal
             * @description Season score 0.00-100.00 = 0.5 x result + 0.5 x process; null when the season has no path distribution
             *
             *     시즌 점수 0.00~100.00 = 0.5 × 결과 + 0.5 × 과정. 경로 분포가 없으면 null
             */
            score: string | null;
            /**
             * Format: decimal
             * @description Result score = 100 x the mid-rank percentile of the Sharpe difference among the engine paths of the season-start team held still; null without a distribution
             *
             *     결과 점수 = 100 × (시즌 시작 팀을 그대로 보유한 엔진 경로들 안에서 샤프 차이의 중간 순위 백분위). 분포가 없으면 null
             */
            resultScore: string | null;
            /**
             * Format: decimal
             * @description Process score: the equal-weight mean of the item scores that have a value, 0.00-100.00 / 과정 점수: 값이 있는 항목 점수의 같은 가중 평균, 0.00~100.00
             */
            processScore: string;
            /**
             * Format: decimal
             * @description Where the end value of the start team, held still, sits among the engine paths, 0-1, 8 decimals; shown, not scored
             *
             *     시작 팀을 그대로 보유했을 때의 최종 가치가 엔진 경로 안에서 놓이는 자리, 0~1, 소수 8자리. 보여 주기만 하고 점수에는 넣지 않는다
             */
            luckPercentile: string | null;
            /**
             * Format: decimal
             * @description Mid-rank percentile of the Sharpe difference among the engine paths, 0-1, 8 decimals
             *
             *     샤프 차이의 경로 안 중간 순위 백분위, 0~1, 소수 8자리
             */
            sharpeDiffPercentile: string | null;
            /** @description The nine process item scores; all nine keys are always present, null where the input does not exist yet / 과정 항목 점수 아홉 개. 키는 항상 아홉 개이고 입력이 아직 없으면 null */
            items: components["schemas"]["SeasonScoreItemsDto"];
            /**
             * Format: int32
             * @description How many item scores have a value (1-9) / 값이 있는 항목 점수의 수(1~9)
             */
            processItems: number;
            /** @description Whether the season counts in the manager rating / 시즌이 매니저 레이팅에 집계되는지 */
            countedInRating: boolean;
            /**
             * @description Why the season is left out of the rating: NOT_STARTED (abandoned before turn 3 by a human) or NO_SCORE (no path distribution); null when it counts
             *
             *     레이팅에서 빠진 이유: NOT_STARTED(사람이 3턴 전에 포기), NO_SCORE(경로 분포 없음). 집계되면 null
             */
            ratingExclusion: components["schemas"]["RatingExclusion"] | null;
        };
        /** @description Process item scores / 과정 항목 점수 */
        SeasonScoreItemsDto: {
            /** Format: decimal */
            risk: string | null;
            /** Format: decimal */
            discipline: string | null;
            /** Format: decimal */
            diversification: string | null;
            /** Format: decimal */
            turnover: string | null;
            /** Format: decimal */
            consistency: string | null;
            /** Format: decimal */
            formation: string | null;
            /** Format: decimal */
            role: string | null;
            /** Format: decimal */
            noise: string | null;
            /** Format: decimal */
            nudge: string | null;
        };
        /** @description Manager rating / 매니저 레이팅 */
        RatingDto: {
            /** @description Rating pillar; only MANAGER exists now / 레이팅 기둥. 지금은 MANAGER 하나 */
            pillar: components["schemas"]["RatingPillar"];
            /**
             * Format: decimal
             * @description Current rating 0.00-100.00: the weighted mean of the last five counted season scores (weights 5, 4, 3, 2, 1 from the newest); null until three seasons are counted
             *
             *     현재 레이팅 0.00~100.00: 집계된 최근 다섯 시즌 점수의 가중 평균(최근부터 5·4·3·2·1). 세 시즌이 집계되기 전에는 null
             */
            rating: string | null;
            /**
             * Format: int32
             * @description Number of counted seasons / 집계된 시즌 수
             */
            countedSeasons: number;
            /**
             * Format: int32
             * @description Counted seasons still missing before the rating is shown (0 once shown) / 레이팅이 보이기까지 더 필요한 집계 시즌 수(보이면 0)
             */
            seasonsUntilPublic: number;
            /**
             * Format: int32
             * @description Seasons abandoned before turn 3 by a human, left out of the rating / 사람이 3턴 전에 포기해 레이팅에서 뺀 시즌 수
             */
            notStartedCount: number;
            /**
             * Format: int32
             * @description Seasons without a path distribution, left out of the rating / 경로 분포가 없어 레이팅에서 뺀 시즌 수
             */
            noScoreCount: number;
            /**
             * Format: int32
             * @description Finished seasons not scored yet because the engine could not answer; they are scored on a later request / 엔진이 답하지 못해 아직 점수가 없는 끝난 시즌 수. 다음 요청에서 채점한다
             */
            pendingSeasons: number;
            /** @description Counted seasons, newest first, at most 20 / 집계된 시즌, 최근 것부터 최대 20개 */
            history: components["schemas"]["RatingEntryDto"][];
        };
        /** @description Rating history entry / 레이팅 이력 한 줄 */
        RatingEntryDto: {
            /**
             * Format: int32
             * @description Position among the counted seasons, from 1 / 집계된 시즌 중 순번(1부터)
             */
            seqNo: number;
            /**
             * Format: int64
             * @description The season / 시즌 번호
             */
            seasonId: number;
            /**
             * Format: decimal
             * @description The season score / 시즌 점수
             */
            score: string;
            /**
             * Format: decimal
             * @description The rating after this season; null where seqNo is below 3 / 이 시즌 뒤의 레이팅. seqNo 가 3 미만이면 null
             */
            rating: string | null;
        };
        /** @enum {string} */
        RatingExclusion: "NOT_STARTED" | "NO_SCORE";
        /** @enum {string} */
        RatingPillar: "MANAGER";
        /** @enum {string} */
        LeagueStatus: "OPEN" | "CLOSED";
        /** @enum {string} */
        MemberKind: "HUMAN" | "AGENT";
        /** @enum {string} */
        ModelRoute: "OPENROUTER" | "CLAUDE_CLI" | "CODEX_CLI" | "OLLAMA";
        /** @enum {string} */
        AgentStatus: "ACTIVE" | "RETIRED";
        /** @description A league and its seats. No seed, date, engine or money setting / 리그와 그 자리들. 시드·날짜·엔진·금액 설정 없음 */
        LeagueTableDto: {
            /**
             * Format: int64
             * @description League identifier / 리그 식별자
             */
            id: number;
            /** @description League name / 리그 이름 */
            name: string;
            status: components["schemas"]["LeagueStatus"];
            /**
             * Format: int32
             * @description Path length (number of turns) / 경로 길이(턴 수)
             */
            pathLength: number;
            /**
             * Format: int32
             * @description Largest number of seats / 자리 수 상한
             */
            maxEntries: number;
            /** @description Seats, oldest season first / 자리, 오래된 시즌 먼저 */
            entries: components["schemas"]["LeagueTableEntryDto"][];
        };
        /** @description One seat of a league table. valuationMultiple and multipleRank are null until the league is CLOSED / 리그 표의 한 자리. valuationMultiple 과 multipleRank 는 리그가 CLOSED 가 되기 전에 null 이다 */
        LeagueTableEntryDto: {
            /**
             * Format: int64
             * @description Member identifier / 회원 식별자
             */
            memberId: number;
            memberKind: components["schemas"]["MemberKind"];
            /** @description Display name / 표시 이름 */
            displayName: string;
            /**
             * Format: int64
             * @description Season identifier / 시즌 식별자
             */
            seasonId: number;
            /** @description Effective season status; an overdue pause reads as ABANDONING / 효과 상태. 기한이 지난 정지는 ABANDONING 으로 읽힌다 */
            status: components["schemas"]["SeasonStatus"];
            /**
             * Format: int32
             * @description Current turn (0 = start) / 현재 턴(0 = 시작)
             */
            currentTurnNo: number;
            /** @description Persona and model for an agent seat. null for a human seat / 에이전트 자리의 페르소나와 모델. 사람 자리는 null */
            ai: components["schemas"]["LeagueTableAiDto"] | null;
            /** @description True for a human seat whose owner also has an agent seat in this league: the owner could watch the agents before playing. Always false for an agent seat / 소유자의 에이전트도 이 리그에 있는 사람 자리면 true: 소유자가 에이전트를 먼저 볼 수 있었다. 에이전트 자리는 항상 false */
            ownsAgentsHere: boolean;
            /**
             * Format: decimal
             * @description Final valuation divided by initial capital, 8 decimal places. null until the league is CLOSED and for a seat that is not FINISHED / 최종 평가액 ÷ 초기 자금, 소수 8자리. 리그가 CLOSED 가 되기 전과 FINISHED 가 아닌 자리는 null
             */
            valuationMultiple: string | null;
            /**
             * Format: int32
             * @description Rank by valuationMultiple (1 = highest; ties share the better rank). null until the league is CLOSED and for a seat without a multiple / valuationMultiple 순위(1 = 최고, 동률은 좋은 순위를 함께). 리그가 CLOSED 가 되기 전과 배수가 없는 자리는 null
             */
            multipleRank: number | null;
        };
        /** @description The persona and model an agent seat played with / 에이전트 자리가 쓴 페르소나와 모델 */
        LeagueTableAiDto: {
            /** @description Persona key / 페르소나 키 */
            personaKey: string;
            /**
             * Format: int32
             * @description Persona version this season was pinned to / 이 시즌이 고정된 페르소나 버전
             */
            personaVersion: number;
            modelRoute: components["schemas"]["ModelRoute"];
            /** @description Model identifier / 모델 식별자 */
            modelId: string;
            /**
             * Format: int32
             * @description Memory version. null while the agent has no memory (AG-05) / 메모리 버전. 메모리가 없으면 null
             */
            memoryVersion: number | null;
        };
        /** @description The agents of the signed-in member (oldest first). No paging / 로그인한 회원의 에이전트(오래된 순). 쪽 나누기 없음 */
        AgentListDto: {
            items: components["schemas"]["AgentSummaryDto"][];
        };
        /** @description One agent of the signed-in member with its season counts / 로그인한 회원의 에이전트 하나와 시즌 집계 */
        AgentSummaryDto: {
            /**
             * Format: int64
             * @description Agent member identifier / 에이전트 회원 식별자
             */
            agentId: number;
            /** @description Display name / 표시 이름 */
            displayName: string;
            /** @description Persona key / 페르소나 키 */
            personaKey: string;
            modelRoute: components["schemas"]["ModelRoute"];
            /** @description Model identifier / 모델 식별자 */
            modelId: string;
            status: components["schemas"]["AgentStatus"];
            /**
             * Format: int32
             * @description Newest persona version the server has recorded for the persona key. null if none / 서버가 기록한 페르소나 키의 최신 버전. 없으면 null
             */
            latestPersonaVersion: number | null;
            /**
             * Format: int32
             * @description Every season the agent started, in any status / 에이전트가 시작한 모든 시즌, 상태 무관
             */
            startedSeasons: number;
            /**
             * Format: int32
             * @description Seasons in status FINISHED / FINISHED 시즌
             */
            countedSeasons: number;
            /**
             * Format: int32
             * @description Seasons in status VOID (model gone or runner defect) / VOID 시즌(모델 소멸·러너 결함)
             */
            voidSeasons: number;
            /**
             * Format: int32
             * @description Seasons whose effective status is ABANDONING and not yet FINISHED: stored ABANDONING plus a pause older than 7 days. They are counted against the agent and have no score yet / 효과 상태가 ABANDONING 이고 아직 FINISHED 가 아닌 시즌: 저장된 ABANDONING 에 7일 넘은 정지를 더한 수. 에이전트에게 집계되며 아직 점수가 없다
             */
            unfinishedAbandonSeasons: number;
            /**
             * Format: int32
             * @description Seasons in status IN_PROGRESS / IN_PROGRESS 시즌
             */
            inProgressSeasons: number;
            /**
             * Format: int32
             * @description Seasons paused for less than 7 days / 정지 7일이 안 된 시즌
             */
            pausedSeasons: number;
            /**
             * Format: int32
             * @description Seasons in status HALTED (stopped by a reconciliation mismatch) / HALTED 시즌(대사 불일치로 멈춤)
             */
            haltedSeasons: number;
            /**
             * Format: int32
             * @description Turns whose final model call ended NO_RESPONSE or INVALID / 최종 모델 호출이 NO_RESPONSE 또는 INVALID 로 끝난 턴 수
             */
            failedTurnCount: number;
        };
    };
    responses: {
        /**
         * @description The request has no valid bearer token: missing, expired, wrongly signed, or naming no member — UNAUTHENTICATED
         *
         *     유효한 Bearer 토큰이 없다: 없거나 만료됐거나 서명이 틀렸거나 없는 회원의 토큰이다 — UNAUTHENTICATED
         */
        Unauthenticated: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /**
         * @description An unexpected server error — INTERNAL_ERROR. The message is a fixed sentence that carries no exception content (the content goes to the server log only)
         *
         *     예상하지 못한 서버 오류 — INTERNAL_ERROR. 예외 내용은 싣지 않는 고정 문장이다(내용은 서버 로그에만)
         */
        InternalError: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
    };
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getPool: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Pool on the reference date / 기준일 풀 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PoolDto"];
                };
            };
            /**
             * @description The pool is not ready — POOL_NOT_LOADED (no rows), POOL_NOT_JUDGED (some assets have no judgment)
             *
             *     풀이 준비되지 않았다 — POOL_NOT_LOADED(행 없음), POOL_NOT_JUDGED(판정 빈 종목 있음)
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    signup: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SignupRequest"];
            };
        };
        responses: {
            /** @description The new member / 새 회원 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MemberDto"];
                };
            };
            /** @description The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The email is already registered (in any letter case) — EMAIL_TAKEN / 이미 가입한 이메일이다(대소문자 무시) — EMAIL_TAKEN */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    login: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description Logged in / 로그인함 */
            200: {
                headers: {
                    /** @description The refresh token cookie (antlab_refresh) / 갱신 토큰 쿠키(antlab_refresh) */
                    "Set-Cookie"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccessTokenDto"];
                };
            };
            /** @description The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The email or password is wrong; the same answer for an unknown email — INVALID_CREDENTIALS / 이메일이나 비밀번호가 맞지 않다. 없는 이메일도 같은 답이다 — INVALID_CREDENTIALS */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    refresh: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: {
                /** @description The refresh token cookie set by login / 로그인이 설정한 갱신 토큰 쿠키 */
                antlab_refresh?: string;
            };
        };
        requestBody?: never;
        responses: {
            /** @description Refreshed / 갱신함 */
            200: {
                headers: {
                    /** @description The rotated refresh token cookie (antlab_refresh) / 회전한 갱신 토큰 쿠키(antlab_refresh) */
                    "Set-Cookie"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccessTokenDto"];
                };
            };
            /** @description The refresh token is missing, unknown, expired or revoked — UNAUTHENTICATED / 갱신 토큰이 없거나 모르거나 만료·폐기되었다 — UNAUTHENTICATED */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    logout: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: {
                /** @description The refresh token cookie set by login / 로그인이 설정한 갱신 토큰 쿠키 */
                antlab_refresh?: string;
            };
        };
        requestBody?: never;
        responses: {
            /** @description Logged out / 로그아웃함 */
            204: {
                headers: {
                    /** @description Clears the refresh token cookie / 갱신 토큰 쿠키를 지운다 */
                    "Set-Cookie"?: string;
                    [name: string]: unknown;
                };
                content?: never;
            };
            500: components["responses"]["InternalError"];
        };
    };
    listSeasons: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Season list / 시즌 목록 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeasonListDto"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            500: components["responses"]["InternalError"];
        };
    };
    startSeason: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StartSeasonRequest"];
            };
        };
        responses: {
            /** @description The season that was started / 시작한 시즌 */
            201: {
                headers: {
                    /** @description Address of the new season / 새 시즌의 주소 */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeasonDto"];
                };
            };
            /** @description The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The league does not exist or is not the caller's — NOT_FOUND / 리그가 없거나 호출자의 것이 아니다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description A season cannot be started now — POOL_NOT_LOADED, POOL_NOT_JUDGED, SEASON_LIMIT (3 in progress); with a leagueId: LEAGUE_CLOSED, LEAGUE_SEAT_TAKEN (one seat per member), LEAGUE_FULL, LEAGUE_STALE (the settings or the path no longer match the league's)
             *
             *     지금은 시작할 수 없다 — POOL_NOT_LOADED, POOL_NOT_JUDGED, SEASON_LIMIT(진행 중 3개). leagueId 가 있으면 LEAGUE_CLOSED, LEAGUE_SEAT_TAKEN(회원당 한 자리), LEAGUE_FULL, LEAGUE_STALE(설정이나 경로가 리그와 달라졌다)
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description A path of the requested length cannot be built — PATH_TOO_LONG / 요청한 길이의 경로를 만들 수 없다 — PATH_TOO_LONG */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
            /** @description The path service is busy or not reachable; try again later — ENGINE_UNAVAILABLE / 경로 서비스가 바쁘거나 연결되지 않는다. 잠시 뒤 다시 시도한다 — ENGINE_UNAVAILABLE */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getSeason: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Season / 시즌 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeasonDto"];
                };
            };
            /** @description The season number is not a number — INVALID_ARGUMENT / 시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    abandonSeason: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AbandonSeasonRequest"];
            };
        };
        responses: {
            /** @description State of the season after this request / 이번 요청 뒤 시즌의 상태 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AbandonResultDto"];
                };
            };
            /** @description The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description The season cannot be abandoned now — TURN_MISMATCH (the season is in progress at another turn), SEASON_NOT_ACTIVE (finished without an abandon), SEASON_HALTED (an administrator resumes it first), RECONCILIATION_FAILED (an auto turn mismatched; the mark and the earlier auto turns stay, repeat to continue)
             *
             *     지금은 포기할 수 없다 — TURN_MISMATCH(진행 중인 시즌의 현재 턴이 다르다), SEASON_NOT_ACTIVE(포기 없이 끝난 시즌), SEASON_HALTED(관리자가 먼저 재개), RECONCILIATION_FAILED(자동 턴의 대사가 어긋났다. 표시와 앞선 자동 턴은 남고 반복하면 이어 간다)
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getTurnPrices: {
        parameters: {
            query?: {
                turnNo?: number;
            };
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Prices of that turn / 그 턴의 가격 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TurnPricesDto"];
                };
            };
            /** @description turnNo or the season number is wrong — INVALID_ARGUMENT / turnNo 또는 시즌 번호가 틀렸다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description That turn has not been played yet — TURN_NOT_REACHED / 아직 진행하지 않은 턴이다 — TURN_NOT_REACHED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getSeasonProjection: {
        parameters: {
            query?: {
                phase?: components["schemas"]["ProjectionMoment"];
            };
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Projection / 예상 분포 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProjectionDto"];
                };
            };
            /** @description phase or the season number is wrong — INVALID_ARGUMENT / phase 또는 시즌 번호가 틀렸다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description That moment is not available — TURN_NOT_REACHED (START before turn 1), SEASON_NOT_FINISHED (END of a season still running), SEASON_NOT_ACTIVE (CURRENT of a finished season)
             *
             *     그 시점은 볼 수 없다 — TURN_NOT_REACHED(1턴 전의 START), SEASON_NOT_FINISHED(진행 중 시즌의 END), SEASON_NOT_ACTIVE(끝난 시즌의 CURRENT)
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The history cannot carry a projection this long — PATH_TOO_LONG / 이 길이의 예상 분포를 이력이 받쳐 주지 못한다 — PATH_TOO_LONG */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
            /** @description The engine is busy or not reachable; try again later — ENGINE_UNAVAILABLE / 엔진이 바쁘거나 연결되지 않는다. 잠시 뒤 다시 시도한다 — ENGINE_UNAVAILABLE */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    placeOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PlaceOrderRequest"];
            };
        };
        responses: {
            /** @description Fill result / 체결 결과 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderResultDto"];
                };
            };
            /** @description The input is invalid (quantity 0 or less, declared position on a sell, a malformed or BENCH source) — INVALID_ARGUMENT / 입력이 틀렸다(수량 0 이하, 매도에 선언 포지션, 모양이 틀렸거나 BENCH인 source) — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description An order cannot be placed now — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH, EVENT_TIME_CONFLICT
             *
             *     지금은 주문할 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH, EVENT_TIME_CONFLICT
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description The order content is not acceptable — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED
             *
             *     주문 내용을 받을 수 없다 — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED
             */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    quoteOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PlaceOrderRequest"];
            };
        };
        responses: {
            /** @description Quote / 견적 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderQuoteDto"];
                };
            };
            /** @description The input is invalid (quantity 0 or less, declared position on a sell, a malformed or BENCH source) — INVALID_ARGUMENT / 입력이 틀렸다(수량 0 이하, 매도에 선언 포지션, 모양이 틀렸거나 BENCH인 source) — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description An order cannot be placed now — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH
             *
             *     지금은 주문할 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description The order content is not acceptable — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED
             *
             *     주문 내용을 받을 수 없다 — SOURCE_NOT_VISIBLE, ASSET_NOT_IN_POOL, INSUFFICIENT_CASH, INSUFFICIENT_HOLDING, POSITION_CONFLICT, POSITION_REQUIRED
             */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    changeFormation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangeFormationRequest"];
            };
        };
        responses: {
            /** @description Change result (turn, before, after, reason) / 변경 결과(턴·이전·이후·사유) */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FormationChangeDto"];
                };
            };
            /**
             * @description The input is invalid (sum ≠ 100, goalkeeper 0, out of range, missing required value, reason over 200 characters, same formation as now) — INVALID_ARGUMENT
             *
             *     입력이 틀렸다(합 ≠ 100, 골키퍼 0, 범위 밖, 필수값 누락, 사유 200자 초과, 현재와 같은 포메이션) — INVALID_ARGUMENT
             */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description It cannot be changed now — SEASON_NOT_ACTIVE, TURN_MISMATCH (including turn 0), INVALID_FORMATION, EVENT_TIME_CONFLICT
             *
             *     지금은 바꿀 수 없다 — SEASON_NOT_ACTIVE, TURN_MISMATCH(0턴 포함), INVALID_FORMATION, EVENT_TIME_CONFLICT
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    changeLineup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangeLineupRequest"];
            };
        };
        responses: {
            /** @description The open span after the call and whether it changed / 호출 뒤의 열린 구간과 변경 여부 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LineupChangeDto"];
                };
            };
            /**
             * @description The input is invalid (missing required value, unknown position, asset number below 1) — INVALID_ARGUMENT
             *
             *     입력이 틀렸다(필수값 누락, 알 수 없는 포지션, 종목 번호 1 미만) — INVALID_ARGUMENT
             */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description It cannot be changed now — SEASON_NOT_ACTIVE (not in progress), TURN_MISMATCH (including turn 0), LINEUP_MISMATCH
             *
             *     지금은 바꿀 수 없다 — SEASON_NOT_ACTIVE(진행 중이 아님), TURN_MISMATCH(0턴 포함), LINEUP_MISMATCH
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description The asset is not held (never bought, sold out or not in the pool) — INSUFFICIENT_HOLDING
             *
             *     보유 중인 종목이 아니다(산 적 없음, 전량 매도, 풀에 없음) — INSUFFICIENT_HOLDING
             */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    advanceTurn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AdvanceTurnRequest"];
            };
        };
        responses: {
            /** @description Turn result / 턴 결과 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TurnResultDto"];
                };
            };
            /** @description The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The turn cannot be advanced now — SEASON_NOT_ACTIVE, SEASON_HALTED (stopped after repeated reconciliation failures; an administrator resumes it), TURN_MISMATCH, RECONCILIATION_FAILED / 지금은 넘길 수 없다 — SEASON_NOT_ACTIVE, SEASON_HALTED(대사 실패가 거듭되어 멈춤, 관리자가 재개), TURN_MISMATCH, RECONCILIATION_FAILED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getTurn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
                turnNo: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Turn result / 턴 결과 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TurnResultDto"];
                };
            };
            /** @description turnNo is below 1, or the season or turn number is not a number — INVALID_ARGUMENT / turnNo 가 1 미만이거나 시즌·턴 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description That turn has not been played yet — TURN_NOT_REACHED (every number above the current turn; a number beyond the path length gets this code even in a finished season, so clients first check the range against pathLength)
             *
             *     아직 진행하지 않은 턴이다 — TURN_NOT_REACHED(현재 턴보다 큰 번호 전부. 경로 길이를 넘는 번호는 끝난 시즌에서도 이 코드이므로 클라이언트는 pathLength 로 범위를 먼저 거른다)
             */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getTurnRanking: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
                turnNo: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Ranking board / 순위판 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RankingBoardDto"];
                };
            };
            /** @description turnNo is below 1, or the season or turn number is not a number — INVALID_ARGUMENT / turnNo 가 1 미만이거나 시즌·턴 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description That turn has not been played yet — TURN_NOT_REACHED / 아직 진행하지 않은 턴이다 — TURN_NOT_REACHED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getHoldings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current team / 현재 팀 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HoldingsDto"];
                };
            };
            /** @description The season number is not a number — INVALID_ARGUMENT / 시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getSeasonReport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Season report / 시즌 성적표 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeasonReportDto"];
                };
            };
            /** @description The season number is not a number — INVALID_ARGUMENT / 시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The season is not finished — SEASON_NOT_FINISHED / 끝나지 않은 시즌이다 — SEASON_NOT_FINISHED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getSeasonAnalysis: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Season analysis / 시즌 분석 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeasonAnalysisDto"];
                };
            };
            /** @description The season number is not a number — INVALID_ARGUMENT / 시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The season is not finished — SEASON_NOT_FINISHED / 끝나지 않은 시즌이다 — SEASON_NOT_FINISHED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getSeasonReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Season review / 시즌 복기 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeasonReviewDto"];
                };
            };
            /** @description The season number is not a number — INVALID_ARGUMENT / 시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The season is not finished — SEASON_NOT_FINISHED; or it is a league season and the league is still open — LEAGUE_OPEN / 끝나지 않은 시즌이다 — SEASON_NOT_FINISHED, 또는 리그 시즌인데 리그가 아직 열려 있다 — LEAGUE_OPEN */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getSeasonScore: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                seasonId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Season score / 시즌 점수 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeasonScoreDto"];
                };
            };
            /** @description The season number is not a number — INVALID_ARGUMENT / 시즌 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The season does not exist or is another member's — NOT_FOUND / 시즌이 없거나 다른 회원의 것이다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The season is not finished — SEASON_NOT_FINISHED / 끝나지 않은 시즌이다 — SEASON_NOT_FINISHED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
            /** @description The score cannot be computed now because the engine is busy or not reachable; nothing is stored, try again later — ENGINE_UNAVAILABLE / 엔진이 바쁘거나 연결되지 않아 지금은 점수를 계산할 수 없다. 아무것도 저장하지 않았으니 나중에 다시 시도한다 — ENGINE_UNAVAILABLE */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getMyRating: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Manager rating / 매니저 레이팅 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RatingDto"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            500: components["responses"]["InternalError"];
        };
    };
    getLeagueTable: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                leagueId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The league table / 리그 표 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LeagueTableDto"];
                };
            };
            /** @description The league number is not a number — INVALID_ARGUMENT / 리그 번호가 숫자가 아니다 — INVALID_ARGUMENT */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            /** @description The league does not exist or is not the caller's — NOT_FOUND / 리그가 없거나 호출자의 것이 아니다 — NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    listMyAgents: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The agents / 에이전트 목록 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AgentListDto"];
                };
            };
            401: components["responses"]["Unauthenticated"];
            500: components["responses"]["InternalError"];
        };
    };
}
