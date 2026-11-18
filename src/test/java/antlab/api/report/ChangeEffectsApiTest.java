package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.RepresentativeTable;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
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
 * The per-change effect through the whole stack with all four baselines buildable: a fifth fixture asset (the growth leg) is added to
 * the pool of a finished season, two formation changes are written straight into the table, and the effects are checked against the
 * split of the same response. The telescoping sum of the effects equals the tactic of the split only when every change's window
 * reaches the season end (turn + 6 >= T); with an earlier window it does not, so the changes here are at turns 6 and 8 of 12.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ChangeEffectsApiTest {

    private static final int TURNS = 12;

    @TestConfiguration(proxyBeanMethods = false)
    static class FullTable {
        @Bean
        @Primary
        RepresentativeTable fullTable() {
            return new RepresentativeTable("rep-v1", new AssetKey("KR", "069500"), new AssetKey("US", "SPY"),
                    List.of(new AssetKey("KR", "000660")), new AssetKey("US", "BRK.B"), new AssetKey("KR", "ZZ9999"));
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private void setPrice(long season, String ticker, int turn, long price) {
        assertThat(jdbc.update("UPDATE turn_price SET price = ? WHERE season_id = ? AND turn_no = ? AND asset_id = "
                + "(SELECT id FROM asset WHERE ticker = ?)", price, season, turn, ticker)).isEqualTo(1);
    }

    private long seasonWithFifthAsset() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis) "
                + "VALUES ('KR', 'ZZ9999', '가짜 성장', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'), "
                + "DATE '2000-01-04', 'PRICE')");
        jdbc.update("INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used, "
                + "up_participation, down_participation, volatility, max_drawdown, system_position) "
                + "SELECT a.id, s.base_date, 10000, 1.0000, DATE '2020-01-01', DATE '2025-12-01', 72, 1.0, 1.0, 0.05, -0.2, 'MIDFIELDER' "
                + "FROM asset a, season s WHERE a.ticker = 'ZZ9999' AND s.id = ?", season);
        jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) SELECT ?, t, a.id, 10000 + 1000 * t "
                + "FROM asset a CROSS JOIN generate_series(0, ?) t WHERE a.ticker = 'ZZ9999'", season, TURNS);
        for (int t = 0; t <= TURNS; t++) {
            setPrice(season, "000660", t, 50_000 + 500L * t);
            setPrice(season, "BRK.B", t, 100_000);
            setPrice(season, "069500", t, 10_000);
            setPrice(season, "SPY", t, 20_000);
        }
        return season;
    }

    private void change(long season, int turn, int[] before, int[] after) {
        jdbc.update("INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, "
                + "before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                season, turn, before[0], before[1], before[2], before[3], after[0], after[1], after[2], after[3]);
    }

    @Test
    void 변경_둘의_효과는_행마다_값이_있고_창이_끝까지_닿으면_합이_전술_항과_같다() throws Exception {
        long season = seasonWithFifthAsset();
        int[] start = {10, 30, 30, 30};
        int[] first = {10, 10, 30, 50};
        int[] second = {10, 50, 30, 10};
        change(season, 6, start, first);
        change(season, 8, first, second);
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode a = json(r);
        AnalysisAssertions.assertConsistent(a, json(ReportApiSupport.getReport(mvc, season)), 4);
        JsonNode effects = a.get("changeEffects");
        assertThat(effects.size()).isEqualTo(2);
        assertThat(effects.get(0).get("turnNo").asInt()).isEqualTo(6);
        assertThat(effects.get(0).get("windowTurns").asInt()).as("min(6, 12 - 6)").isEqualTo(6);
        assertThat(effects.get(1).get("turnNo").asInt()).isEqualTo(8);
        assertThat(effects.get(1).get("windowTurns").asInt()).as("min(6, 12 - 8)").isEqualTo(4);
        BigDecimal e1 = AnalysisAssertions.won(effects.get(0).get("effectWon"));
        BigDecimal e2 = AnalysisAssertions.won(effects.get(1).get("effectWon"));
        assertThat(e1.signum()).as("the first change moves value, so the telescoping sum is not trivial").isNotZero();
        assertThat(e2.signum()).isNotZero();
        assertThat(e1.add(e2)).as("every window reaches the end (turn + 6 >= T): sum of effects = tactic")
                .isEqualByComparingTo(AnalysisAssertions.won(a.get("decomposition").get("tacticWon")));
    }
}
