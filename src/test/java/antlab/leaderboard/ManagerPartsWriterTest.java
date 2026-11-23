package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.leaderboard.SeasonManagerParts.Ranked;
import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The parts writer appends one row per season and reference version, stores the engine's values as sent and never
 * replaces a stored row. Rolled back after each test.
 */
@IntegrationTest
@Transactional
class ManagerPartsWriterTest {

    private static final String SEASON = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version, data_snapshot_date,
                base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            VALUES (?, 'FINISHED', 12, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20) RETURNING id
            """;

    private static final String SCORE = """
            INSERT INTO season_score (season_id, member_id, score_version, score, result_score, process_score, sharpe,
                bench_sharpe, sharpe_diff, sharpe_diff_percentile, luck_percentile, risk_score, process_items, inputs)
            VALUES (?, ?, 's63-v3', 70, 70, 60, 1, 0.5, 0.5, 0.5, 0.5, 60, 1, '{}')
            """;

    @Autowired
    ManagerPartsWriter writer;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestMembers members;

    private long scoredSeason() {
        long member = members.defaultId();
        long season = jdbc.queryForObject(SEASON, Long.class, member);
        jdbc.update(SCORE, season, member);
        return season;
    }

    private static Ranked ranked(String riskItem) {
        return new Ranked("3f".repeat(32), 200, "random/1", "0.1.0", new BigDecimal("0.060000000000"),
                new BigDecimal("0.062500000000"), new BigDecimal("0.112345678901"), new BigDecimal("-999998.476190466190"),
                new BigDecimal("-0.052500000000"), new BigDecimal("0.000000000000"), new BigDecimal("71.25000000"),
                new BigDecimal("64.00000000"), new BigDecimal("58.75000000"), new BigDecimal("0.25000000"),
                riskItem == null ? null : new BigDecimal(riskItem), new BigDecimal("100.00000000"), new BigDecimal("71.25000000"),
                new BigDecimal("41.00000000"), new BigDecimal("75.00000000"));
    }

    private SeasonManagerParts counted(long season, String riskItem) {
        return new SeasonManagerParts(season, members.defaultId(), "ref69-v1", null, ranked(riskItem), new BigDecimal("60.00"),
                "s63-v3", new BigDecimal("1.08000000"));
    }

    @Test
    void aCountedRowIsStoredAsSent() {
        long season = scoredSeason();

        assertThat(writer.exists(season, "ref69-v1")).isFalse();
        assertThat(writer.insert(counted(season, "50.00000000"))).isTrue();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT exclusion, reference_key, reference_size, bot_version, calmar_diff::text AS calmar, bench_mdd::text AS bench,
                       pct_risk_item::text AS risk_item, part_return::text AS part_return, part_process::text AS process,
                       process_score_version, final_multiple::text AS multiple
                FROM season_manager_parts WHERE season_id = ?""", season);
        assertThat(row.get("exclusion")).isNull();
        assertThat(row.get("reference_key")).isEqualTo("3f".repeat(32));
        assertThat(((Number) row.get("reference_size")).intValue()).isEqualTo(200);
        assertThat(row.get("bot_version")).isEqualTo("random/1");
        assertThat(row.get("calmar")).isEqualTo("-999998.476190466190");
        assertThat(row.get("bench")).isEqualTo("0.000000000000");
        assertThat(row.get("risk_item")).isEqualTo("50.00000000");
        assertThat(row.get("part_return")).isEqualTo("71.25000000");
        assertThat(row.get("process")).isEqualTo("60.00");
        assertThat(row.get("process_score_version")).isEqualTo("s63-v3");
        assertThat(row.get("multiple")).isEqualTo("1.08000000");
        assertThat(writer.exists(season, "ref69-v1")).isTrue();
        assertThat(writer.exists(season, "ref70-v1")).isFalse();
    }

    @Test
    void anExcludedRowHasNoEngineValueAndACountedOneMayLackTheRiskItem() {
        long excluded = scoredSeason();
        long noRiskItem = scoredSeason();

        assertThat(writer.insert(new SeasonManagerParts(excluded, members.defaultId(), "ref69-v1",
                ManagerPartsExclusion.REFERENCE_MISMATCH, null, new BigDecimal("60.00"), "s63-v3", new BigDecimal("0.97000000"))))
                .isTrue();
        assertThat(writer.insert(counted(noRiskItem, null))).isTrue();

        assertThat(jdbc.queryForObject("SELECT exclusion || ':' || num_nonnulls(reference_key, part_return, pct_mdd) "
                + "FROM season_manager_parts WHERE season_id = ?", String.class, excluded)).isEqualTo("REFERENCE_MISMATCH:0");
        assertThat(jdbc.queryForObject("SELECT pct_risk_item IS NULL FROM season_manager_parts WHERE season_id = ?",
                Boolean.class, noRiskItem)).isTrue();
    }

    @Test
    void aStoredRowStandsAndASecondWriterInsertsNothing() {
        long season = scoredSeason();
        assertThat(writer.insert(counted(season, "50.00000000"))).isTrue();

        SeasonManagerParts other = new SeasonManagerParts(season, members.defaultId(), "ref69-v1", ManagerPartsExclusion.NO_REFERENCE,
                null, new BigDecimal("60.00"), "s63-v3", new BigDecimal("1.08000000"));
        assertThat(writer.insert(other)).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_manager_parts WHERE season_id = ?", Long.class, season))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT exclusion IS NULL FROM season_manager_parts WHERE season_id = ?", Boolean.class,
                season)).isTrue();
    }

    @Test
    void aRowIsEitherRankedOrExcluded() {
        assertThatThrownBy(() -> new SeasonManagerParts(1, 1, "ref69-v1", ManagerPartsExclusion.NO_REFERENCE, ranked(null),
                BigDecimal.ONE, "s63-v3", BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeasonManagerParts(1, 1, "ref69-v1", null, null, BigDecimal.ONE, "s63-v3", BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWriterHasNoUpdateOrDeleteStatement() throws IOException {
        String source = Files.readString(Path.of("src/main/java/antlab/leaderboard/ManagerPartsWriter.java")).toUpperCase(Locale.ROOT);

        assertThat(source).doesNotContain("UPDATE ").doesNotContain("DELETE ").doesNotContain("DO UPDATE").contains("DO NOTHING");
    }
}
