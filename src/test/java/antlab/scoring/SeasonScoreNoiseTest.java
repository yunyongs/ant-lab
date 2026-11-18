package antlab.scoring;

import static antlab.api.turn.TurnApiSupport.assetId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import antlab.api.report.ReportApiSupport;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * The noise item of the season score (MVP-19 PR D, formula {@code s63-v3}). The source is a spy over the real bean, so a
 * test either pins the share by hand or lets the real calculation run on rows written straight into the tables.
 */
class SeasonScoreNoiseTest extends ScoreApiTestBase {

    @MockitoSpyBean
    NoiseReactionSource noise;

    @Test
    void 소음_비중_0_25는_소음_점수_75와_s63_v3_행이_된다() throws Exception {
        long season = finishedSeason();
        doReturn(Optional.of(new BigDecimal("0.25000000"))).when(noise).noiseShare(season);

        scoring.ensureMember(memberId());

        Map<String, Object> row = scoreRow(season);
        assertThat(row.get("score_version")).isEqualTo("s63-v3");
        assertThat((BigDecimal) row.get("noise_score")).isEqualByComparingTo("75.00");
        long items = ((Number) row.get("process_items")).longValue();
        long nonNull = java.util.List.of("risk_score", "discipline_score", "diversification_score", "turnover_score",
                "consistency_score", "formation_score", "role_score", "noise_score", "nudge_score").stream()
                .filter(c -> row.get(c) != null).count();
        assertThat(items).isEqualTo(nonNull);
        String inputs = jdbc.queryForObject("SELECT inputs::text FROM season_score WHERE season_id = ?", String.class, season);
        assertThat(JsonMapper.shared().readTree(inputs).get("noiseShare").asString()).isEqualTo("0.25000000");
    }

    @Test
    void 소음_항목이_있으면_과정_항목_수가_하나_늘고_없으면_늘지_않는다() throws Exception {
        long withNoise = finishedSeason();
        doReturn(Optional.of(new BigDecimal("0.50000000"))).when(noise).noiseShare(withNoise);
        scoring.ensureMember(memberId());
        Map<String, Object> rowWith = scoreRow(withNoise);
        assertThat(rowWith.get("noise_score")).isNotNull();

        long without = finishedSeason();
        doReturn(Optional.empty()).when(noise).noiseShare(without);
        scoring.ensureMember(memberId());
        Map<String, Object> row = scoreRow(without);

        assertThat(row.get("noise_score")).isNull();
        // role fit is empty in some random seasons, so the two seasons are compared on the items they share
        assertThat(((Number) row.get("process_items")).intValue()).isEqualTo(filled(row));
        assertThat(((Number) rowWith.get("process_items")).intValue()).isEqualTo(filled(rowWith));
        assertThat(filled(rowWith) - (rowWith.get("role_score") != null ? 1 : 0))
                .isEqualTo(filled(row) - (row.get("role_score") != null ? 1 : 0) + 1);
        assertThat(row.get("score_version")).isEqualTo("s63-v3");
        String inputs = jdbc.queryForObject("SELECT inputs::text FROM season_score WHERE season_id = ?", String.class, without);
        assertThat(JsonMapper.shared().readTree(inputs).get("noiseShare").isNull()).isTrue();
    }

    private static int filled(Map<String, Object> row) {
        return (int) java.util.List.of("risk_score", "discipline_score", "diversification_score", "turnover_score",
                "consistency_score", "formation_score", "role_score", "noise_score", "nudge_score").stream()
                .filter(c -> row.get(c) != null).count();
    }

    @Test
    void 거래한_시즌의_실제_값이_점수와_inputs에_남는다() throws Exception {
        long season = finishedSeason();
        ReportApiSupport.fill(jdbc, season, assetId(jdbc, "BRK.B"), 4, "BUY", 2);
        ReportApiSupport.fill(jdbc, season, assetId(jdbc, "SPY"), 7, "SELL", 1);

        scoring.ensureMember(memberId());

        Map<String, Object> row = scoreRow(season);
        BigDecimal share = noise.noiseShare(season).orElseThrow();
        assertThat(row.get("score_version")).isEqualTo("s63-v3");
        assertThat((BigDecimal) row.get("noise_score"))
                .isEqualByComparingTo(BigDecimal.valueOf(100).multiply(BigDecimal.ONE.subtract(share)).setScale(2,
                        java.math.RoundingMode.HALF_UP));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s63-v1", "s63-v2"})
    void 이미_저장된_이전_버전_시즌은_다시_채점하지_않고_행도_그대로다(String version) throws Exception {
        long season = finishedSeason();
        jdbc.update("""
                INSERT INTO season_score (season_id, member_id, score_version, score, result_score, process_score, sharpe,
                    bench_sharpe, sharpe_diff, sharpe_diff_percentile, luck_percentile, risk_score, process_items, inputs)
                VALUES (?, ?, ?, 60.00, 50.00, 70.00, 0.1, 0.1, 0, 0.5, 0.5, 70.00, 1, '{}'::jsonb)""", season,
                memberId(), version);
        Map<String, Object> before = scoreRow(season);
        doReturn(Optional.of(new BigDecimal("0.25000000"))).when(noise).noiseShare(season);

        SeasonScoreService.Ensured ensured = scoring.ensureMember(memberId());

        assertThat(ensured.pendingSeasons()).isZero();
        assertThat(scoreRows()).isEqualTo(1);
        assertThat(scoreRow(season)).isEqualTo(before);
        assertThat(scoreRow(season).get("score_version")).isEqualTo(version);
        assertThat(percentileCalls()).isZero();
    }

    /**
     * An inconsistent finished season must not block the other eight items for ever, and the exception the source throws
     * must not leave the read snapshot or the later write unusable: the score row is written and the rating row follows.
     */
    @Test
    void 소음_원천이_예외를_던져도_점수는_소음_항목_없이_쓰이고_트랜잭션은_멀쩡하다() throws Exception {
        long season = finishedSeason();
        doThrow(new IllegalStateException("noise.price_missing")).when(noise).noiseShare(anyLong());

        SeasonScoreService.Ensured ensured = scoring.ensureMember(memberId());

        assertThat(ensured.pendingSeasons()).isZero();
        Map<String, Object> row = scoreRow(season);
        assertThat(row.get("score_version")).isEqualTo("s63-v3");
        assertThat(row.get("noise_score")).isNull();
        assertThat(row.get("score")).isNotNull();
        assertThat(ratingRows()).isEqualTo(1);
    }

    @Test
    void 일시적_DB_실패는_삼키지_않고_아무것도_저장하지_않는다() throws Exception {
        long season = finishedSeason();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down")).when(noise).noiseShare(anyLong());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scoring.ensureMember(memberId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(scoreRows()).isZero();
        assertThat(ratingRows()).isZero();
        org.mockito.Mockito.reset(noise);
        scoring.ensureMember(memberId());
        assertThat(scoreRow(season).get("score_version")).isEqualTo("s63-v3");
    }

    @Test
    void 거래가_없던_끝난_시즌은_소음_항목_없이_남은_항목의_평균으로_채점된다() throws Exception {
        long season = finishedSeason();

        scoring.ensureMember(memberId());

        Map<String, Object> row = scoreRow(season);
        assertThat(row.get("noise_score")).isNull();
        java.util.List<BigDecimal> present = java.util.List.of("risk_score", "discipline_score", "diversification_score",
                "turnover_score", "consistency_score", "formation_score", "role_score", "nudge_score").stream()
                .map(c -> (BigDecimal) row.get(c)).filter(java.util.Objects::nonNull).toList();
        assertThat(((Number) row.get("process_items")).intValue()).isEqualTo(present.size());
        BigDecimal mean = present.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 6, java.math.RoundingMode.HALF_UP);
        assertThat(((BigDecimal) row.get("process_score")).subtract(mean).abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    /** Executions are whole won (ck_execution_fill_price_won) and quantities are integers, so a notional is whole won. */
    @Test
    void 체결_대금은_정수_원이다() throws Exception {
        long season = finishedSeason();
        ReportApiSupport.fill(jdbc, season, assetId(jdbc, "BRK.B"), 4, "BUY", 2, 3);
        Long fractional = jdbc.queryForObject("SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id "
                + "WHERE o.season_id = ? AND (e.fill_price * e.quantity) <> ROUND(e.fill_price * e.quantity, 0)", Long.class,
                season);
        assertThat(fractional).isZero();
        Long order = jdbc.queryForObject("SELECT max(id) FROM orders WHERE season_id = ?", Long.class, season);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO execution (order_id, fill_price, quantity) VALUES (?, 1000.5, 1)", order))
                .hasMessageContaining("ck_execution_fill_price_won");
    }
}
