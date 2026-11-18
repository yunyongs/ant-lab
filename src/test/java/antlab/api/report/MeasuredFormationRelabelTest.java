package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.asset.Position;
import antlab.season.LineupChangeService;
import antlab.season.SeedGenerator;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relabelling declared positions is free (a lineup change) and must not move the measured formation deviation.
 * Two seasons with the same seed and the same orders differ only by lineup relabels in the second one: the declared-basis
 * {@code formationDeviation} changes (the stored snapshots group by the declared position), the measured one does not.
 */
@IntegrationTest
@Import(MeasuredFormationRelabelTest.FixedSeed.class)
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class MeasuredFormationRelabelTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedSeed {
        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> 20261118L;
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LineupChangeService lineupChanges;

    private JsonNode finishSeason(boolean relabel) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 12; t++) {
            if (relabel && t == 2) {
                lineupChanges.change(season, 2, assetId(jdbc, "069500"), Position.GOALKEEPER);
                lineupChanges.change(season, 2, assetId(jdbc, "000660"), Position.DEFENDER);
            }
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        return JsonMapper.shared().readTree(ReportApiSupport.getReport(mvc, season).getContentAsString());
    }

    @Test
    void 선언만_바꾼_두_시즌은_실측_이탈이_같고_선언_기준_이탈은_다르다() throws Exception {
        JsonNode plain = finishSeason(false);
        jdbc.update("DELETE FROM season_score WHERE season_id IN (SELECT id FROM season)");
        jdbc.update("UPDATE season SET status = 'ABANDONED' WHERE status <> 'FINISHED'");
        JsonNode relabelled = finishSeason(true);
        BigDecimal plainMeasured = new BigDecimal(plain.get("measuredFormationDeviation").asString());
        BigDecimal relabelledMeasured = new BigDecimal(relabelled.get("measuredFormationDeviation").asString());
        assertThat(relabelledMeasured).isEqualByComparingTo(plainMeasured);
        assertThat(new BigDecimal(relabelled.get("formationDeviation").asString()))
                .isNotEqualByComparingTo(new BigDecimal(plain.get("formationDeviation").asString()));
    }

    @Test
    void 둘째_기준일_행이_있어도_시즌_기준일의_판정만_쓴다() throws Exception {
        JsonNode before = finishSeason(false);
        try {
            jdbc.update("""
                    INSERT INTO asset_position (id, asset_id, base_date, base_price, base_fx_rate, market_cap_rank, window_start,
                                                window_end, months_used, up_participation, down_participation, volatility,
                                                max_drawdown, system_position)
                    OVERRIDING SYSTEM VALUE
                    SELECT 2000000000 + ap.id, ap.asset_id, ap.base_date + 1, ap.base_price, ap.base_fx_rate, ap.market_cap_rank,
                           ap.window_start, ap.window_end, ap.months_used, ap.up_participation, ap.down_participation,
                           ap.volatility, ap.max_drawdown,
                           CASE WHEN ap.system_position = 'DEFENDER' THEN 'MIDFIELDER' ELSE 'DEFENDER' END
                    FROM asset_position ap JOIN season s ON s.base_date = ap.base_date
                    WHERE ap.system_position IS NOT NULL""");
            long season = jdbc.queryForObject("SELECT max(id) FROM season", Long.class);
            JsonNode after = JsonMapper.shared().readTree(ReportApiSupport.getReport(mvc, season).getContentAsString());
            assertThat(after.get("measuredFormationDeviation").asString())
                    .isEqualTo(before.get("measuredFormationDeviation").asString());
        } finally {
            jdbc.update("DELETE FROM asset_position WHERE id >= 2000000000");
        }
    }
}
