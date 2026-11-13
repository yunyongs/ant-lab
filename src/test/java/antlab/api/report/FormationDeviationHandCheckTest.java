package antlab.api.report;

import static antlab.api.formation.FormationApiSupportAccess.C;
import static antlab.api.formation.FormationApiSupportAccess.B;
import static antlab.api.formation.FormationApiSupportAccess.change;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.SeedGenerator;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.IntFunction;
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
 * The report's {@code formationDeviation} after two formation changes in the same turn, recomputed by hand.
 * The hand calculation reads only the raw {@code turn} snapshot rows and applies targets written out as literals from the
 * scenario (start 10/30/30/30; turn 3 changes to C, then to B). It does not call the report calculator or the SQL 26
 * functions. The target in effect for turn {@code t} is the last change made in a turn before {@code t}, so the second change
 * of turn 3 governs turns 4..12 and the first one never governs any turn.
 */
@IntegrationTest
@Import(FormationDeviationHandCheckTest.FixedSeed.class)
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationDeviationHandCheckTest {

    private static final int[] START = {10, 30, 30, 30};
    private static final int CHANGE_TURN = 3;

    /** A fixed seed keeps the scenario (and so the distinguishing power of the three hand values) the same on every run. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedSeed {
        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> 20261114L;
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private record Snapshot(int turnNo, BigInteger[] position, BigInteger total) {
    }

    private List<Snapshot> snapshots(long season) {
        return jdbc.query("""
                SELECT turn_no, goalkeeper_value, defender_value, midfielder_value, forward_value, total_value
                FROM turn WHERE season_id = ? AND turn_no >= 1 ORDER BY turn_no
                """, (rs, n) -> new Snapshot(rs.getInt(1), new BigInteger[] {rs.getBigDecimal(2).toBigIntegerExact(),
                rs.getBigDecimal(3).toBigIntegerExact(), rs.getBigDecimal(4).toBigIntegerExact(),
                rs.getBigDecimal(5).toBigIntegerExact()}, rs.getBigDecimal(6).toBigIntegerExact()), season);
    }

    /** (1/n) Σ a_t / b_t with a_t = Σ |100·v − f·V| and b_t = 100·V, as one exact fraction rounded half up once. */
    private static BigDecimal hand(List<Snapshot> rows, IntFunction<int[]> target) {
        BigInteger num = BigInteger.ZERO;
        BigInteger den = BigInteger.ONE;
        for (Snapshot s : rows) {
            int[] f = target.apply(s.turnNo());
            BigInteger a = BigInteger.ZERO;
            for (int p = 0; p < 4; p++) {
                a = a.add(s.position()[p].multiply(BigInteger.valueOf(100))
                        .subtract(s.total().multiply(BigInteger.valueOf(f[p]))).abs());
            }
            BigInteger b = s.total().multiply(BigInteger.valueOf(100));
            num = num.multiply(b).add(a.multiply(den));
            den = den.multiply(b);
        }
        return new BigDecimal(num).divide(new BigDecimal(den.multiply(BigInteger.valueOf(rows.size()))), 8, RoundingMode.HALF_UP);
    }

    @Test
    void 같은_턴_두_변경_뒤_목표는_둘째_변경이고_성적표_값과_손_계산이_같다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < CHANGE_TURN; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        assertThat(change(mvc, season, CHANGE_TURN, C).getStatus()).isEqualTo(201);
        assertThat(change(mvc, season, CHANGE_TURN, B).getStatus()).isEqualTo(201);
        for (int t = CHANGE_TURN; t < 12; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM formation_change WHERE season_id = ? AND turn_no = ?",
                Integer.class, season, CHANGE_TURN)).as("same-turn changes").isEqualTo(2);

        JsonNode report = JsonMapper.shared().readTree(ReportApiSupport.getReport(mvc, season).getContentAsString());
        BigDecimal reported = new BigDecimal(report.get("formationDeviation").asString());
        List<Snapshot> rows = snapshots(season);
        assertThat(rows).as("report turns").hasSize(report.get("reportTurns").asInt()).hasSize(12);

        BigDecimal second = hand(rows, t -> t <= CHANGE_TURN ? START : B);
        BigDecimal first = hand(rows, t -> t <= CHANGE_TURN ? START : C);
        BigDecimal never = hand(rows, t -> START);
        System.out.println("[F1] formationDeviation reported=" + reported + " hand(second)=" + second + " hand(first)=" + first
                + " hand(none)=" + never);
        assertThat(reported).as("second change governs turns after the change").isEqualByComparingTo(second);
        assertThat(second).as("the scenario tells the second change apart from the first").isNotEqualByComparingTo(first);
        assertThat(second).as("and from no change").isNotEqualByComparingTo(never);
        assertThat(first).isNotEqualByComparingTo(never);
    }
}
