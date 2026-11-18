package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.RepresentativeTable;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * A representative table that makes the 60/40 baseline buildable from the fixture pool (its bond leg is the fixture's domestic
 * stock 000660) while the formation baselines stay unavailable (no growth asset). The bond price is tripled from turn 3, which
 * breaks the 5 %p band, so the baseline rebalances by band and nothing is forced: this is the case where the rebalance count
 * and the forced count differ.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAnalysisBondApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class DomesticBondTable {
        @Bean
        @Primary
        RepresentativeTable domesticBondTable() {
            return new RepresentativeTable("rep-v1", new AssetKey("KR", "069500"), new AssetKey("US", "SPY"),
                    List.of(new AssetKey("KR", "000660")), new AssetKey("US", "BRK.B"), new AssetKey("KR", "ZZ9999"));
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 채권_가격이_뛰면_60_40이_밴드로_되돌리고_강제_횟수는_0이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("UPDATE turn_price SET price = price * 3 WHERE season_id = ? AND asset_id = ? AND turn_no >= 3", season,
                assetId(jdbc, "000660"));
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode a = json(r);
        AnalysisAssertions.assertConsistent(a, json(ReportApiSupport.getReport(mvc, season)), 2);
        JsonNode sixtyForty = a.get("baselines").get(1);
        assertThat(sixtyForty.get("available").asBoolean()).isTrue();
        assertThat(sixtyForty.get("legs").size()).isEqualTo(3);
        assertThat(sixtyForty.get("legs").get(2).get("asset").asString()).isEqualTo("KR:000660");
        assertThat(sixtyForty.get("legs").get(2).get("slot").asString()).isEqualTo("BOND");
        assertThat(sixtyForty.get("bondLegSubstituted").asBoolean()).as("a domestic bond is no substitute").isFalse();
        assertThat(sixtyForty.get("rebalanceCount").asInt()).as("the tripled bond breaks the band").isPositive();
        assertThat(sixtyForty.get("forcedChangeCount").asInt()).isZero();
        assertThat(sixtyForty.get("forcedChangeFee").asString()).isEqualTo("0");
        assertThat(a.get("baselines").get(2).get("reason").asString()).isEqualTo("REPRESENTATIVE_MISSING");
        assertThat(a.get("baselines").get(3).get("reason").asString()).isEqualTo("REPRESENTATIVE_MISSING");
    }
}
