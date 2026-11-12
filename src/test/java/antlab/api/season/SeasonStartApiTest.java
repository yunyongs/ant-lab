package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.PROBLEM;
import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.body;
import static antlab.api.season.SeasonApiSupport.counts;
import static antlab.api.season.SeasonApiSupport.formation;
import static antlab.api.season.SeasonApiSupport.get;
import static antlab.api.season.SeasonApiSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.PathRules;
import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /api/v1/seasons — 합성 고정본(실제 표본 아님)으로 정상·음성·DB 행을 확인한다. 시험 트랜잭션이 롤백한다. */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class SeasonStartApiTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    // ---- 정상 ----

    @Test
    void 정상_시작은_201_Location_본문_형식을_지킨다() throws Exception {
        MockHttpServletResponse r = post(mvc, VALID_BODY);
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode s = json(r);
        assertThat(r.getHeader("Location")).endsWith("/api/v1/seasons/" + s.get("id").asLong());
        assertThat(s.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(s.get("currentTurnNo").asInt()).isZero();
        assertThat(s.get("pathLength").asInt()).as("생략하면 12").isEqualTo(12);
        // 금액은 원 단위 정수 문자열, 비율은 소수 8자리 문자열
        for (String money : List.of("initialCash", "cashBalance")) {
            assertThat(s.get(money).isString()).isTrue();
            assertThat(s.get(money).asString()).matches("^\\d+$");
        }
        for (String rate : List.of("riskLimit", "feeRateKr", "feeRateUs", "fxSpreadRate")) {
            assertThat(s.get(rate).asString()).matches("^-?\\d+\\.\\d{8}$");
        }
        assertThat(s.get("riskLimit").asString()).isEqualTo("-0.20000000");
        assertThat(s.get("feeRateKr").asString()).isEqualTo("0.00015000");
        assertThat(s.get("cashBalance").asString()).isEqualTo(s.get("initialCash").asString());
        assertThat(s.get("startFormation")).isEqualTo(s.get("currentFormation"));
        assertThat(s.get("startFormation").get("goalkeeper").asInt()).isEqualTo(10);
    }

    @Test
    void Location은_요청의_쿼리_문자열을_복사하지_않는다() throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons?x=1").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(VALID_BODY).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(r.getHeader("Location")).endsWith("/api/v1/seasons/" + json(r).get("id").asLong())
                .doesNotContain("?");
    }

    @Test
    void 같은_Location의_GET은_POST_본문과_같다() throws Exception {
        MockHttpServletResponse created = post(mvc, VALID_BODY);
        String path = java.net.URI.create(created.getHeader("Location")).getPath();
        MockHttpServletResponse fetched = get(mvc, path);
        assertThat(fetched.getStatus()).isEqualTo(200);
        assertThat(json(fetched)).isEqualTo(json(created));
    }

    @Test
    void DB_행은_설정과_고정_회원으로_만들어진다() throws Exception {
        Map<String, Long> before = counts(jdbc);
        JsonNode s = json(post(mvc, VALID_BODY));
        long id = s.get("id").asLong();
        Map<String, Long> after = counts(jdbc);
        assertThat(after.get("member") - before.get("member")).isEqualTo(1);
        assertThat(after.get("season") - before.get("season")).isEqualTo(1);
        assertThat(after.get("season_path") - before.get("season_path")).isEqualTo(12);
        assertThat(after.get("cash_account") - before.get("cash_account")).isEqualTo(1);
        assertThat(after.get("cash_ledger") - before.get("cash_ledger")).isEqualTo(1);
        // 풀 4종목(고정본) 전부의 0턴 가격. 다른 기준일 종목 행은 풀에 없다.
        assertThat(after.get("turn_price") - before.get("turn_price")).isEqualTo(4);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT se.*, m.email, m.password FROM season se JOIN member m ON m.id = se.member_id WHERE se.id = ?""", id);
        assertThat(row.get("email")).isEqualTo("player@example.invalid");
        assertThat(row.get("password")).as("로그인 불가 표식").isEqualTo("!");
        assertThat(row.get("engine_version")).isEqualTo("p0-boot6-v1");
        assertThat(row.get("base_date")).hasToString("2026-01-02");
        assertThat(row.get("data_snapshot_date")).hasToString("2026-11-07");
        assertThat((BigDecimal) row.get("initial_cash")).isEqualByComparingTo("30000000");
        assertThat((BigDecimal) row.get("fee_rate_kr")).isEqualByComparingTo("0.00015");
        assertThat((BigDecimal) row.get("fee_rate_us")).isEqualByComparingTo("0.0025");
        assertThat((BigDecimal) row.get("fx_spread_rate")).isEqualByComparingTo("0.004");
        assertThat((BigDecimal) row.get("risk_limit")).isEqualByComparingTo("-0.2");
        // 시작 = 현재 포메이션
        assertThat(row.get("current_goalkeeper")).isEqualTo(row.get("start_goalkeeper"));
        assertThat(row.get("current_forward")).isEqualTo(row.get("start_forward"));
    }

    @Test
    void 개설_입금과_계좌는_원장_합계와_맞고_예약금은_0이다() throws Exception {
        long id = json(post(mvc, VALID_BODY)).get("id").asLong();
        Map<String, Object> ledger = jdbc.queryForMap("SELECT * FROM cash_ledger WHERE season_id = ?", id);
        assertThat(ledger.get("type")).isEqualTo("DEPOSIT");
        assertThat(ledger.get("ref_order_id")).isNull();
        assertThat((BigDecimal) ledger.get("amount")).isEqualByComparingTo("30000000");
        assertThat((BigDecimal) ledger.get("balance_after")).isEqualByComparingTo("30000000");
        Map<String, Object> account = jdbc.queryForMap("SELECT * FROM cash_account WHERE season_id = ?", id);
        assertThat((BigDecimal) account.get("balance")).isEqualByComparingTo("30000000");
        assertThat((BigDecimal) account.get("reserved")).isEqualByComparingTo("0");
    }

    @Test
    void 가짜_풀의_0턴_가격은_기준가_곱하기_환율이다_1턴_이상은_없다() throws Exception {
        long id = json(post(mvc, VALID_BODY)).get("id").asLong();
        // 고정본(가짜 값): KR 기준가 × 1, US 기준가 × 1350 (손으로 계산한 기대값)
        Map<String, BigDecimal> expected = Map.of("000660", new BigDecimal("150000"), "069500", new BigDecimal("35000"),
                "SPY", new BigDecimal("648000"), "BRK.B", new BigDecimal("540000"));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.ticker, tp.price, tp.turn_no FROM turn_price tp JOIN asset a ON a.id = tp.asset_id
                WHERE tp.season_id = ?""", id);
        assertThat(rows).hasSize(4);
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("turn_no")).intValue()).isZero();
            assertThat((BigDecimal) row.get("price")).isEqualByComparingTo(expected.get((String) row.get("ticker")));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 24})
    void 저장된_경로는_길이_블록_달력_규칙을_지킨다(int length) throws Exception {
        long id = json(post(mvc, body("\"-0.20\"", formation("10", "30", "30", "30"), String.valueOf(length))))
                .get("id").asLong();
        List<LocalDate> months = jdbc.queryForList("""
                SELECT mb.month_start FROM season_path sp JOIN month_block mb ON mb.id = sp.month_block_id
                WHERE sp.season_id = ? ORDER BY sp.turn_no""", LocalDate.class, id);
        assertThat(months).hasSize(length);
        assertThat(PathRules.violations(months, BASE, length, 6)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"-0.1\"", "\"-0.10\"", "-0.20", "\"-0.2\"", "\"-0.30000000\"", "\"-0.4\""})
    void 위험_선언_허용_네_값은_표기가_달라도_같은_값으로_저장된다(String risk) throws Exception {
        MockHttpServletResponse r = post(mvc, body(risk, formation("10", "30", "30", "30"), null));
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(json(r).get("riskLimit").asString()).matches("^-0\\.[1-4]0000000$");
    }

    @Test
    void 회원은_한_명을_재사용한다() throws Exception {
        long members = counts(jdbc).get("member");
        post(mvc, VALID_BODY);
        post(mvc, VALID_BODY);
        assertThat(counts(jdbc).get("member") - members).isEqualTo(1);
    }

    @Test
    void 요청에_설정값을_넣어도_서버_설정이_이긴다() throws Exception {
        String hostile = """
                {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30},
                 "seed": 7, "initialCash": 1, "feeRateKr": 0.5, "engineVersion": "x", "baseDate": "1999-01-01"}""";
        MockHttpServletResponse r = post(mvc, hostile);
        assertThat(r.getStatus()).isEqualTo(201);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM season WHERE id = ?", json(r).get("id").asLong());
        assertThat((BigDecimal) row.get("initial_cash")).isEqualByComparingTo("30000000");
        assertThat((BigDecimal) row.get("fee_rate_kr")).isEqualByComparingTo("0.00015");
        assertThat(row.get("seed")).isNotEqualTo(7L);
        assertThat(row.get("engine_version")).isEqualTo("p0-boot6-v1");
        assertThat(row.get("base_date")).hasToString("2026-01-02");
    }

    @Test
    void 시작은_계좌를_한_번만_INSERT_하고_UPDATE_하지_않으며_계좌가_원장보다_먼저다() {
        SqlRecorder.start();
        MockHttpServletResponse r = post(mvc, VALID_BODY);
        List<String> sql = SqlRecorder.stop().stream().map(String::toLowerCase).toList();
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(sql).noneMatch(q -> q.startsWith("update cash_account"));
        assertThat(sql.stream().filter(q -> q.startsWith("insert into cash_account")).count()).isEqualTo(1);
        int account = indexOf(sql, "insert into cash_account");
        int ledger = indexOf(sql, "insert into cash_ledger");
        assertThat(account).as("계좌 INSERT 가 원장 INSERT 보다 앞").isLessThan(ledger).isNotNegative();
        // member 조회는 락을 건다
        assertThat(sql).anyMatch(q -> q.contains("from member") && q.contains("for no key update"));
        // 순서: season → season_path → cash_account → cash_ledger → turn_price
        assertThat(indexOf(sql, "insert into season ")).isLessThan(indexOf(sql, "insert into season_path"));
        assertThat(indexOf(sql, "insert into season_path")).isLessThan(account);
        assertThat(ledger).isLessThan(indexOf(sql, "insert into turn_price"));
    }

    private static int indexOf(List<String> sql, String prefix) {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // ---- 음성: 요청 검증(@Valid)이 서비스 앞에서 막는다 — 서비스 단계의 거부와 행 불변은 SeasonRollbackTest ----

    private void assertRejected(String requestBody, int status, String code) throws Exception {
        Map<String, Long> before = counts(jdbc);
        MockHttpServletResponse r = post(mvc, requestBody);
        assertThat(r.getStatus()).isEqualTo(status);
        assertThat(r.getContentType()).startsWith(PROBLEM.toString());
        assertThat(json(r).get("code").asString()).isEqualTo(code);
        assertThat(counts(jdbc)).as("거부된 요청은 행을 남기지 않는다").isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 합 99 · 골키퍼 0 · 비중 범위 밖
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":29}}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":0,\"defender\":40,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":101,\"defender\":0,\"midfielder\":0,\"forward\":-1}}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":-10,\"defender\":60,\"midfielder\":30,\"forward\":20}}",
            // 필드 누락
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":60}}",
            "{\"riskLimit\":\"-0.20\"}",
            "{\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            "{}",
            // 위험 선언 목록 밖 · 자릿수 · 형식
            "{\"riskLimit\":\"-0.25\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"-0.5\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"-0.200000001\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"abc\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            // 정수 칸에 소수·지수 표기 (P0-22 ⑥·C3)
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10.5,\"defender\":29.5,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10.0,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":1e1,\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30},\"pathLength\":12.5}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30},\"pathLength\":12.0}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30},\"pathLength\":1e1}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30},\"pathLength\":\"12\"}",
            "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":\"10\",\"defender\":30,\"midfielder\":30,\"forward\":30}}",
            // 깨진 JSON
            "{\"riskLimit\":"})
    void 입력이_틀리면_400_INVALID_ARGUMENT이고_행이_늘지_않는다(String requestBody) throws Exception {
        assertRejected(requestBody, 400, "INVALID_ARGUMENT");
    }

    @Test
    void 포메이션_합_위반의_오류_목록은_필드와_제약_이름만_담는다() throws Exception {
        MockHttpServletResponse r = post(mvc, body("\"-0.20\"", formation("10", "30", "30", "29"), null));
        assertThat(r.getStatus()).isEqualTo(400);
        JsonNode errors = json(r).get("errors");
        assertThat(errors).isNotNull();
        assertThat(errors.toString()).contains("\"field\":\"formation\"").contains("FormationSum")
                .doesNotContain("99").doesNotContain("29");
        MockHttpServletResponse gk = post(mvc, body("\"-0.20\"", formation("0", "40", "30", "30"), null));
        assertThat(json(gk).get("errors").toString()).contains("formation.goalkeeper").contains("Min");
    }

    @Test
    void 요청_본문이_JSON이_아니면_415() {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType("text/plain").content("x")
                .exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(415);
    }
}
