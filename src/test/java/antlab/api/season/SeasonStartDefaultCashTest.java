package antlab.api.season;

import static antlab.api.agentrunner.AgentRunnerSupport.BASE;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shipped default of 100,000,000 won (the test profile pins the Phase 0 value 30,000,000, so this class sets the shipped
 * value back): a new season and a new league start with it, and the opening DEPOSIT row equals it.
 */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@TestPropertySource(properties = "antlab.season.initial-cash=100000000")
class SeasonStartDefaultCashTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 새_시즌은_1억으로_시작하고_입금_원장이_같다() throws Exception {
        MockHttpServletResponse r = post(mvc, VALID_BODY);
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode s = JsonMapper.shared().readTree(r.getContentAsString());
        long id = s.get("id").asLong();
        assertThat(s.get("initialCash").asString()).isEqualTo("100000000");
        assertThat(s.get("cashBalance").asString()).isEqualTo("100000000");
        assertThat((BigDecimal) jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("100000000");
        Map<String, Object> ledger = jdbc.queryForMap("SELECT * FROM cash_ledger WHERE season_id = ?", id);
        assertThat(ledger.get("type")).isEqualTo("DEPOSIT");
        assertThat((BigDecimal) ledger.get("amount")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) ledger.get("balance_after")).isEqualByComparingTo("100000000");
        assertThat((BigDecimal) jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("100000000");
    }

    @Test
    void 새_리그도_같은_1억을_쓴다() throws Exception {
        ensureOwner(jdbc);
        MockHttpServletResponse r = antlab.api.agentrunner.AgentRunnerSupport.post(mvc, BASE + "/leagues", "{\"name\": \"Cash\"}");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        long id = antlab.api.agentrunner.AgentRunnerSupport.json(r).get("id").asLong();
        assertThat((BigDecimal) jdbc.queryForObject("SELECT initial_cash FROM league WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("100000000");
    }
}
