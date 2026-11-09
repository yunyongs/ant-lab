package antlab.api.pool;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET /api/v1/pool — 합성 고정본(실제 표본 아님)으로 값·정렬·기준일·오류·쿼리 수를 확인한다. */
@IntegrationTest
@Transactional
class PoolApiTest {

    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory emf;

    /** 판정까지 채운 US 종목 하나를 기준일 풀에 더한다(정렬 시험용). */
    private void addJudgedUsAsset(String ticker) {
        jdbc.update("INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)"
                + " SELECT 'US', ?, '가짜 정렬용', 'STOCK', 'USD', id, DATE '2010-01-04', 'TOTAL'"
                + " FROM asset_group WHERE code = 'FAKE_SEMI'", ticker);
        jdbc.update("INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate,"
                + " window_start, window_end, months_used, up_participation, down_participation, volatility,"
                + " max_drawdown, system_position)"
                + " SELECT id, DATE '2026-01-02', 10.0000, 1350.0000, DATE '2020-01-01', DATE '2025-12-01', 72,"
                + " 1.00000000, 1.00000000, 0.05000000, -0.20000000, 'MIDFIELDER' FROM asset WHERE ticker = ?", ticker);
    }

    private MockHttpServletResponse get() {
        return mvc.get().uri("/api/v1/pool").exchange().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JsonMapper.shared().readTree(response.getContentAsString());
    }

    @Test
    @Sql("/fixtures/history_pool.sql")
    void 기준일_풀을_시장_종목코드_순서로_준다() throws Exception {
        // BRKA 는 'BRK.B' 와 순서가 콜레이션에 따라 갈린다: 바이트 순서는 '.'(0x2E) < 'A'(0x41)이라 BRK.B 가 먼저,
        // en_US 콜레이션은 구두점을 1차 비교에서 무시해 BRKA 가 먼저다. 서비스 정렬이 DB 정렬로 바뀌면 이 시험이 깨진다.
        addJudgedUsAsset("BRKA");
        MockHttpServletResponse response = get();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");

        JsonNode assets = json(response).get("assets");
        List<String> order = new ArrayList<>();
        assets.forEach(a -> order.add(a.get("market").asString() + ":" + a.get("ticker").asString()));
        // KR 숫자 코드 → US 코드 단위(바이트) 순서. 다른 기준일 행은 섞이지 않는다(고정본 4건 + BRKA).
        assertThat(order).containsExactly("KR:000660", "KR:069500", "US:BRK.B", "US:BRKA", "US:SPY");

        JsonNode first = assets.get(0);
        assertThat(first.get("assetId").asLong()).isEqualTo(
                jdbc.queryForObject("SELECT id FROM asset WHERE market = 'KR' AND ticker = '000660'", Long.class));
        assertThat(first.get("name").asString()).isEqualTo("가짜 반도체");
        assertThat(first.get("assetType").asString()).isEqualTo("STOCK");
        assertThat(first.get("returnBasis").asString()).isEqualTo("PRICE");
        assertThat(first.has("proxyIndexName")).isTrue();
        assertThat(first.get("proxyIndexName").isNull()).isTrue();
        assertThat(first.get("groupName").asString()).isEqualTo("가짜 반도체");
        assertThat(first.get("groupKind").asString()).isEqualTo("SECTOR");
        assertThat(first.get("groupId").asLong()).isEqualTo(
                jdbc.queryForObject("SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'", Long.class));
        assertThat(first.get("systemPosition").asString()).isEqualTo("FORWARD_BREAKOUT");
        // 비율은 소수 8자리 문자열(음수는 ASCII 빼기 기호)
        assertThat(first.get("upParticipation").asString()).isEqualTo("1.52000000");
        assertThat(first.get("downParticipation").asString()).isEqualTo("1.31000000");
        assertThat(first.get("volatility").asString()).isEqualTo("0.12000000");
        assertThat(first.get("maxDrawdown").asString()).isEqualTo("-0.48000000");

        JsonNode spy = assets.get(4);
        assertThat(spy.get("proxyIndexName").asString()).isEqualTo("가짜 추종 지수");
        assertThat(spy.get("returnBasis").asString()).isEqualTo("TOTAL");
        assertThat(spy.get("groupKind").asString()).isEqualTo("ASSET_CLASS");
    }

    @Test
    @Sql("/fixtures/history_pool.sql")
    void 조회_SQL은_풀_크기와_무관하게_하나다() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        assertThat(get().getStatus()).isEqualTo(200);
        assertThat(stats.getPrepareStatementCount()).as("준비된 SQL 수").isEqualTo(1);
    }

    @Test
    void 풀이_비어_있으면_409_POOL_NOT_LOADED() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_position WHERE base_date = DATE '2026-01-02'",
                Long.class)).as("전제: 공유 DB 에 기준일 풀 행이 없어야 한다(다른 시험이 커밋했는지 확인)").isZero();
        MockHttpServletResponse response = get();
        assertProblem(response, "POOL_NOT_LOADED", "기준일 풀이 비어 있다. API DB 표본 적재 절차를 먼저 실행한다");
    }

    @Test
    @Sql("/fixtures/history_pool.sql")
    void 판정이_빈_종목이_섞이면_409_POOL_NOT_JUDGED() throws Exception {
        jdbc.update("INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)"
                + " SELECT 'US', 'ZZZ', '가짜 미판정', 'STOCK', 'USD', id, DATE '2010-01-04', 'TOTAL'"
                + " FROM asset_group WHERE code = 'FAKE_SEMI'");
        jdbc.update("INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)"
                + " SELECT id, DATE '2026-01-02', 10.0000, 1350.0000 FROM asset WHERE ticker = 'ZZZ'");
        assertProblem(get(), "POOL_NOT_JUDGED",
                "기준일 풀에 판정이 비어 있는 종목이 있다. 포지션 계산을 먼저 실행한다");
    }

    @Test
    void POST는_405다() {
        MockHttpServletResponse response = mvc.post().uri("/api/v1/pool").exchange().getResponse();
        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    private static void assertProblem(MockHttpServletResponse response, String code, String detail) throws Exception {
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(PROBLEM)).isTrue();
        JsonNode body = json(response);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(409);
        assertThat(body.get("title").asString()).isEqualTo("Conflict");
        assertThat(body.get("detail").asString()).isEqualTo(detail);
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/pool");
        // 본문에 날짜·기준일 값이 나가지 않는다.
        assertThat(response.getContentAsString()).doesNotContain("2026").doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }
}
