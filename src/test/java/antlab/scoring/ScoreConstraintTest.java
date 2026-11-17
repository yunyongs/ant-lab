package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.JpaMappingTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bad row per rule of season_score and rating_history, checked by SQLSTATE and constraint name (never message
 * text). Each case runs in its own rolled-back transaction and breaks exactly one rule, because PostgreSQL names only
 * the first violated constraint.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScoreConstraintTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String CHECK = "23514";
    private static final String FK = "23503";
    private static final String UNIQUE = "23505";

    @Autowired
    DataSource dataSource;

    final class Tx implements AutoCloseable {
        final Connection c;
        final long member;
        final long otherMember;
        final long season;

        Tx() throws SQLException {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
            member = member();
            otherMember = member();
            season = season(member);
        }

        long member() throws SQLException {
            return queryLong("INSERT INTO member (email, name, password) VALUES ('sc-" + SEQ.incrementAndGet()
                    + "@example.invalid', 'm', 'x') RETURNING id");
        }

        long season(long owner) throws SQLException {
            return queryLong("""
                    INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date,
                        initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                        start_goalkeeper, start_defender, start_midfielder, start_forward,
                        current_goalkeeper, current_defender, current_midfielder, current_forward)
                    VALUES (%d, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                        10, 30, 40, 20, 10, 30, 40, 20) RETURNING id""".formatted(owner));
        }

        /** A valid scored row for (season, member); {@code overrides} replace single columns (SQL literal text). */
        String scoreSql(long seasonId, long memberId, Map<String, String> overrides) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("season_id", String.valueOf(seasonId));
            v.put("member_id", String.valueOf(memberId));
            v.put("score_version", "'s63-v1'");
            v.put("score", "65.89");
            v.put("result_score", "62.50");
            v.put("process_score", "69.29");
            v.put("sharpe", "0.34412360");
            v.put("bench_sharpe", "0.40414519");
            v.put("sharpe_diff", "-0.06002159");
            v.put("sharpe_diff_percentile", "0.62500000");
            v.put("luck_percentile", "0.45000000");
            v.put("process_items", "7");
            v.put("inputs", "'{}'::jsonb");
            v.putAll(overrides);
            return "INSERT INTO season_score (" + String.join(", ", v.keySet()) + ") VALUES ("
                    + String.join(", ", v.values()) + ")";
        }

        void score(long seasonId, long memberId) throws SQLException {
            exec(scoreSql(seasonId, memberId, Map.of()));
        }

        String ratingSql(long memberId, String pillar, int seq, long seasonId, String rating, int used) {
            return "INSERT INTO rating_history (member_id, pillar, seq_no, season_id, rating, seasons_used) VALUES ("
                    + memberId + ", '" + pillar + "', " + seq + ", " + seasonId + ", " + rating + ", " + used + ")";
        }

        void exec(String sql) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }

        long queryLong(String sql) throws SQLException {
            try (PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }

        @Override
        public void close() throws SQLException {
            c.rollback();
            c.close();
        }
    }

    interface Body {
        String sql(Tx tx) throws Exception;
    }

    private void rejects(String state, String constraint, Body body) throws Exception {
        try (Tx tx = new Tx()) {
            String sql = body.sql(tx);
            assertThatThrownBy(() -> tx.exec(sql)).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo(state);
                assertThat(constraintName(e)).isEqualTo(constraint);
            });
        }
    }

    private static String constraintName(SQLException e) {
        String first = e.getMessage().lines().findFirst().orElse("");
        Matcher m = Pattern.compile("constraint \"([^\"]+)\"").matcher(first);
        return m.find() ? m.group(1) : first;
    }

    // ---- accepted rows

    @Test
    void aFullScoreRowAndItsRatingRowAreAccepted() throws Exception {
        try (Tx tx = new Tx()) {
            tx.score(tx.season, tx.member);
            tx.exec(tx.ratingSql(tx.member, "MANAGER", 1, tx.season, "65.89", 1));

            assertThat(tx.queryLong("SELECT count(*) FROM rating_history WHERE member_id = " + tx.member)).isEqualTo(1);
        }
    }

    @Test
    void aNotStartedRowWithAScoreAndANoScoreRowWithoutOneAreAccepted() throws Exception {
        try (Tx tx = new Tx()) {
            tx.exec(tx.scoreSql(tx.season, tx.member, Map.of("rating_exclusion", "'NOT_STARTED'")));
            long second = tx.season(tx.member);
            tx.exec(tx.scoreSql(second, tx.member, Map.of("score", "NULL", "result_score", "NULL",
                    "sharpe_diff_percentile", "NULL", "luck_percentile", "NULL", "rating_exclusion", "'NO_SCORE'")));

            assertThat(tx.queryLong("SELECT count(*) FROM season_score WHERE member_id = " + tx.member)).isEqualTo(2);
        }
    }

    // ---- season_score rules

    @Test
    void scoreWithoutResultScoreIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_score_pair", tx -> tx.scoreSql(tx.season, tx.member,
                Map.of("result_score", "NULL", "sharpe_diff_percentile", "NULL", "luck_percentile", "NULL")));
    }

    @Test
    void resultScoreWithoutScoreIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_score_pair", tx -> tx.scoreSql(tx.season, tx.member,
                Map.of("score", "NULL", "rating_exclusion", "'NO_SCORE'")));
    }

    @Test
    void splitPercentilePairIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_percentile_pair",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("luck_percentile", "NULL")));
    }

    @Test
    void percentilesWithoutResultScoreAreRefused() throws Exception {
        rejects(CHECK, "ck_season_score_percentile_pair", tx -> tx.scoreSql(tx.season, tx.member,
                Map.of("score", "NULL", "result_score", "NULL", "rating_exclusion", "'NO_SCORE'")));
    }

    @Test
    void scoreAboveOneHundredIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_score", tx -> tx.scoreSql(tx.season, tx.member, Map.of("score", "100.01")));
    }

    @Test
    void negativeScoreIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_score", tx -> tx.scoreSql(tx.season, tx.member, Map.of("score", "-0.01")));
    }

    @Test
    void nanScoreIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_score",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("score", "'NaN'::numeric")));
    }

    @Test
    void resultAndProcessScoreOutsideZeroToOneHundredAreRefused() throws Exception {
        rejects(CHECK, "ck_season_score_result", tx -> tx.scoreSql(tx.season, tx.member, Map.of("result_score", "100.5")));
        rejects(CHECK, "ck_season_score_process", tx -> tx.scoreSql(tx.season, tx.member, Map.of("process_score", "100.5")));
        rejects(CHECK, "ck_season_score_process", tx -> tx.scoreSql(tx.season, tx.member, Map.of("process_score", "-1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"risk", "discipline", "diversification", "turnover", "consistency", "formation", "role",
            "noise", "nudge"})
    void everyItemScoreMustBeWithinZeroToOneHundred(String item) throws Exception {
        rejects(CHECK, "ck_season_score_" + item,
                tx -> tx.scoreSql(tx.season, tx.member, Map.of(item + "_score", "100.01")));
        rejects(CHECK, "ck_season_score_" + item,
                tx -> tx.scoreSql(tx.season, tx.member, Map.of(item + "_score", "-0.01")));
    }

    @Test
    void sharpeValuesMustNotBeNan() throws Exception {
        rejects(CHECK, "ck_season_score_sharpe",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("sharpe", "'NaN'::numeric")));
    }

    @Test
    void percentilesOutsideZeroToOneAreRefused() throws Exception {
        rejects(CHECK, "ck_season_score_diff_percentile",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("sharpe_diff_percentile", "1.00000001")));
        rejects(CHECK, "ck_season_score_luck_percentile",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("luck_percentile", "-0.00000001")));
    }

    @Test
    void processItemsOfZeroAndTenAreRefused() throws Exception {
        rejects(CHECK, "ck_season_score_process_items",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("process_items", "0")));
        rejects(CHECK, "ck_season_score_process_items",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("process_items", "10")));
    }

    @Test
    void unknownExclusionIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_rating_exclusion",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("rating_exclusion", "'INVALID'")));
    }

    @Test
    void noScoreAndNoExclusionIsRefused() throws Exception {
        rejects(CHECK, "ck_season_score_no_score", tx -> tx.scoreSql(tx.season, tx.member,
                Map.of("score", "NULL", "result_score", "NULL", "sharpe_diff_percentile", "NULL",
                        "luck_percentile", "NULL")));
    }

    @Test
    void emptyVersionAndNonObjectInputsAreRefused() throws Exception {
        rejects(CHECK, "ck_season_score_version", tx -> tx.scoreSql(tx.season, tx.member, Map.of("score_version", "''")));
        rejects(CHECK, "ck_season_score_inputs",
                tx -> tx.scoreSql(tx.season, tx.member, Map.of("inputs", "'[]'::jsonb")));
    }

    @Test
    void aSecondScoreForTheSameSeasonIsRefused() throws Exception {
        rejects(UNIQUE, "pk_season_score", tx -> {
            tx.score(tx.season, tx.member);
            return tx.scoreSql(tx.season, tx.member, Map.of());
        });
    }

    @Test
    void aScoreRowWhoseMemberIsNotTheSeasonsMemberIsRefused() throws Exception {
        rejects(FK, "fk_season_score_season", tx -> tx.scoreSql(tx.season, tx.otherMember, Map.of()));
    }

    // ---- rating_history rules

    @Test
    void unknownPillarIsRefused() throws Exception {
        rejects(CHECK, "ck_rating_history_pillar", tx -> {
            tx.score(tx.season, tx.member);
            return tx.ratingSql(tx.member, "SCREENING", 1, tx.season, "50", 1);
        });
    }

    @Test
    void sequenceZeroIsRefused() throws Exception {
        rejects(CHECK, "ck_rating_history_seq_no", tx -> {
            tx.score(tx.season, tx.member);
            return tx.ratingSql(tx.member, "MANAGER", 0, tx.season, "50", 1);
        });
    }

    @Test
    void seasonsUsedAboveFiveOrAboveTheSequenceIsRefused() throws Exception {
        rejects(CHECK, "ck_rating_history_used", tx -> {
            tx.score(tx.season, tx.member);
            return tx.ratingSql(tx.member, "MANAGER", 6, tx.season, "50", 6);
        });
        rejects(CHECK, "ck_rating_history_used", tx -> {
            tx.score(tx.season, tx.member);
            return tx.ratingSql(tx.member, "MANAGER", 2, tx.season, "50", 3);
        });
    }

    @Test
    void ratingOutsideZeroToOneHundredOrNanIsRefused() throws Exception {
        rejects(CHECK, "ck_rating_history_rating", tx -> {
            tx.score(tx.season, tx.member);
            return tx.ratingSql(tx.member, "MANAGER", 1, tx.season, "100.01", 1);
        });
        rejects(CHECK, "ck_rating_history_rating", tx -> {
            tx.score(tx.season, tx.member);
            return tx.ratingSql(tx.member, "MANAGER", 1, tx.season, "'NaN'::numeric", 1);
        });
    }

    @Test
    void aDuplicateMemberPillarSequenceIsRefused() throws Exception {
        rejects(UNIQUE, "uq_rating_history_member_pillar_seq", tx -> {
            tx.score(tx.season, tx.member);
            long second = tx.season(tx.member);
            tx.score(second, tx.member);
            tx.exec(tx.ratingSql(tx.member, "MANAGER", 1, tx.season, "50", 1));
            return tx.ratingSql(tx.member, "MANAGER", 1, second, "50", 1);
        });
    }

    @Test
    void theSameSeasonCannotBeRatedTwice() throws Exception {
        rejects(UNIQUE, "uq_rating_history_season", tx -> {
            tx.score(tx.season, tx.member);
            tx.exec(tx.ratingSql(tx.member, "MANAGER", 1, tx.season, "50", 1));
            return tx.ratingSql(tx.member, "MANAGER", 2, tx.season, "50", 2);
        });
    }

    @Test
    void aRatingRowWithoutAScoreRowIsRefused() throws Exception {
        rejects(FK, "fk_rating_history_score", tx -> tx.ratingSql(tx.member, "MANAGER", 1, tx.season, "50", 1));
    }

    @Test
    void aRatingRowForAnUnknownMemberIsRefused() throws Exception {
        rejects(FK, "fk_rating_history_member", tx -> {
            tx.score(tx.season, tx.member);
            return tx.ratingSql(-1, "MANAGER", 1, tx.season, "50", 1);
        });
    }

    @Test
    void aSeasonThatHasAScoreCannotBeDeleted() throws Exception {
        rejects(FK, "fk_season_score_season", tx -> {
            tx.score(tx.season, tx.member);
            return "DELETE FROM season WHERE id = " + tx.season;
        });
    }
}
