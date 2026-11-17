package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.PlaceOrderRequest;
import antlab.asset.Position;
import antlab.auth.AgentActingAuthentication;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * Check 5 of the decision: with {@code antlab.game.single-asset-limit} set, one asset may not exceed that share of the
 * total value after a buy — the same switch for an agent unit (reject code) and a human order (400). Unset, nobody is limited
 * (every other test of the runner API runs unset).
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.game.single-asset-limit=0.10")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentSingleAssetLimitTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderService orders;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
    }

    private long[] agentSeason() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, registerBody("limit-test", "OPENROUTER", "fake/model-1", "한도 시험"));
        MockHttpServletResponse started = post(mvc, AGENTS + "/" + agent + "/seasons", START_BODY);
        assertThat(started.getStatus()).isEqualTo(201);
        return new long[] {agent, json(started).get("id").asLong()};
    }

    private static String buy(int quantity) {
        return """
                {"outcome": "DECISION", "decision": {"schema": "turn-decision/1", "formation_change": null,
                  "orders": [{"asset": "KR:069500", "side": "BUY", "quantity": %d, "position": "MIDFIELDER",
                              "source": {"kind": "DIRECT"}}], "theses": [], "reason": "x"},
                 "calls": [{"attemptNo": 1, "kind": "TURN_DECISION", "ledgerCallId": "%s", "status": "OK",
                            "modelRequested": "fake/model-1", "requestText": "r", "responseText": "p", "latencyMs": 1}]}
                """.formatted(quantity, java.util.UUID.randomUUID());
    }

    private int sharesFor(long season, String portion) {
        BigDecimal price = jdbc.queryForObject("SELECT price FROM turn_price tp JOIN asset s ON s.id = tp.asset_id "
                + "WHERE tp.season_id = ? AND tp.turn_no = 0 AND s.ticker = '069500'", BigDecimal.class, season);
        BigDecimal cash = jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class, season);
        return cash.multiply(new BigDecimal(portion)).divide(price, 0, java.math.RoundingMode.DOWN).intValueExact();
    }

    @Test
    void a_buy_that_takes_more_than_the_limit_is_rejected_and_one_under_it_fills() throws Exception {
        long[] a = agentSeason();
        String uri = AGENTS + "/" + a[0] + "/seasons/" + a[1] + "/turns/0/decision";
        JsonNode over = json(post(mvc, uri, buy(sharesFor(a[1], "0.12"))));
        assertThat(over.get("orders").get(0).get("code").asString()).isEqualTo("SINGLE_ASSET_LIMIT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, a[1])).isZero();
    }

    @Test
    void a_buy_under_the_limit_fills() throws Exception {
        long[] a = agentSeason();
        JsonNode under = json(post(mvc, AGENTS + "/" + a[0] + "/seasons/" + a[1] + "/turns/0/decision",
                buy(sharesFor(a[1], "0.08"))));
        assertThat(under.get("orders").get(0).get("status").asString()).isEqualTo("APPLIED");
    }

    @Test
    void the_same_limit_stops_a_human_order_with_a_400() throws Exception {
        long[] a = agentSeason();
        long asset = jdbc.queryForObject("SELECT id FROM asset WHERE ticker = '069500'", Long.class);
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(a[0]));
        assertThatThrownBy(() -> orders.place(a[1], new PlaceOrderRequest(0, OrderSide.BUY, asset, sharesFor(a[1], "0.12"),
                Position.MIDFIELDER))).isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void a_limit_outside_zero_to_one_is_refused_at_start() {
        for (BigDecimal bad : List.of(BigDecimal.ZERO, new BigDecimal("-0.1"), new BigDecimal("1.01"))) {
            assertThatThrownBy(() -> new OrderService(null, null, null, null, null, null, null, null, null, null, null,
                    null, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
