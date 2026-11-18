package antlab.api.report;

import static antlab.api.turn.RankingSupport.playedSeason;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.scoring.NoiseReactionSource;
import antlab.scoring.SeasonNoiseSource;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** The port's real bean is the same calculation as the analysis response, and only a finished season that traded has a value. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonNoiseSourceTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NoiseReactionSource source;

    @Test
    void 실제_빈이_기본_빈을_대신한다() {
        assertThat(source).isInstanceOf(SeasonNoiseSource.class);
    }

    @Test
    void 끝난_시즌의_값은_분석_응답의_소음_비율과_같다() throws Exception {
        long season = playedSeason(mvc, jdbc, 12);
        ReportApiSupport.fill(jdbc, season, assetId(jdbc, "BRK.B"), 4, "BUY", 2);
        ReportApiSupport.fill(jdbc, season, assetId(jdbc, "SPY"), 7, "SELL", 1);
        JsonNode noise = json(SeasonAnalysisApiSupport.getAnalysis(mvc, season)).get("noise");
        assertThat(noise.get("noiseShare").isNull()).isFalse();
        Optional<BigDecimal> value = source.noiseShare(season);
        assertThat(value).isPresent();
        assertThat(value.get().toPlainString()).isEqualTo(noise.get("noiseShare").asString());
    }

    @Test
    void 모순이_있는_끝난_시즌은_빈_값이_아니라_예외다() throws Exception {
        long season = playedSeason(mvc, jdbc, 12);
        long brk = assetId(jdbc, "BRK.B");
        ReportApiSupport.fill(jdbc, season, brk, 4, "BUY", 2);
        jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND asset_id = ? AND turn_no = 4", season, brk);
        assertThatThrownBy(() -> source.noiseShare(season)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 거래가_없거나_끝나지_않았거나_없는_시즌은_비어_있다() throws Exception {
        assertThat(source.noiseShare(ReportApiSupport.finishedSeason(mvc, jdbc))).isEmpty();
        long inProgress = ReportApiSupport.season(mvc, jdbc, 3);
        ReportApiSupport.fill(jdbc, inProgress, assetId(jdbc, "BRK.B"), 1, "BUY", 1);
        assertThat(source.noiseShare(inProgress)).as("a season in progress has no value yet").isEmpty();
        assertThat(source.noiseShare(999_999_999L)).isEmpty();
    }
}
