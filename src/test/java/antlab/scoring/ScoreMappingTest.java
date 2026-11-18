package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.member.RatingHistory;
import antlab.member.RatingHistoryRepository;
import antlab.member.RatingPillar;
import antlab.support.JpaMappingTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The two entities against the real tables: insert path, assigned key, JSON text, defaults and the read queries. */
@JpaMappingTest
class ScoreMappingTest {

    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SeasonScoreRepository scores;
    @Autowired
    RatingHistoryRepository ratings;

    private long member() {
        return jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES ('map-' || nextval('member_id_seq')"
                + " || '@example.invalid', 'm', 'x') RETURNING id", Long.class);
    }

    private long season(long member) {
        return jdbc.queryForObject("""
                INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version, data_snapshot_date,
                    base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                    start_goalkeeper, start_defender, start_midfielder, start_forward,
                    current_goalkeeper, current_defender, current_midfielder, current_forward)
                VALUES (?, 'FINISHED', 12, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                    10, 30, 40, 20, 10, 30, 40, 20) RETURNING id""", Long.class, member);
    }

    private static SeasonScoreResult result() {
        return SeasonScoreCalculator.calculate(SeasonScoreCalculatorTest.fixture().build());
    }

    @Test
    void aScoreRowRoundTripsWithItsAssignedKeyAndJsonInputs() {
        long m = member();
        long s = season(m);
        SeasonScore saved = scores.save(new SeasonScore(s, m, result(), null, "{\"engineN\": 2000}"));
        assertThat(saved.isNew()).as("new until the insert is flushed").isTrue();
        em.flush();
        assertThat(saved.isNew()).as("not new after the insert").isFalse();
        em.clear();

        SeasonScore loaded = scores.findById(s).orElseThrow();

        assertThat(loaded.getSeasonId()).isEqualTo(s);
        assertThat(loaded.getMemberId()).isEqualTo(m);
        assertThat(loaded.getScoreVersion()).isEqualTo("s63-v3");
        assertThat(loaded.getScore()).isEqualByComparingTo("65.89");
        assertThat(loaded.getResultScore()).isEqualByComparingTo("62.50");
        assertThat(loaded.getProcessScore()).isEqualByComparingTo("69.29");
        assertThat(loaded.getSharpeDiff()).isEqualByComparingTo("-0.06002159");
        assertThat(loaded.getSharpeDiffPercentile()).isEqualByComparingTo("0.625");
        assertThat(loaded.getLuckPercentile()).isEqualByComparingTo("0.45");
        assertThat(loaded.getConsistencyScore()).isNull();
        assertThat(loaded.getNoiseScore()).isNull();
        assertThat(loaded.getProcessItems()).isEqualTo(7);
        assertThat(loaded.getRatingExclusion()).isNull();
        assertThat(loaded.getInputs()).contains("engineN");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(scores.existsBySeasonId(s)).isTrue();
        assertThat(scores.findBySeasonIdAndMemberId(s, m)).isPresent();
        assertThat(scores.findBySeasonIdAndMemberId(s, m + 1000)).isEmpty();
    }

    @Test
    void anExcludedRowKeepsItsExclusionAndNullScore() {
        long m = member();
        long s = season(m);
        SeasonScoreCalculatorTest.Builder b = SeasonScoreCalculatorTest.fixture();
        b.diffCounts = null;
        b.endCounts = null;
        SeasonScoreResult unscored = SeasonScoreCalculator.calculate(b.build());
        scores.save(new SeasonScore(s, m, unscored, SeasonScoreCalculator.exclusion(false, unscored), "{}"));
        em.flush();
        em.clear();

        SeasonScore loaded = scores.findById(s).orElseThrow();

        assertThat(loaded.getScore()).isNull();
        assertThat(loaded.getResultScore()).isNull();
        assertThat(loaded.getRatingExclusion()).isEqualTo(RatingExclusion.NO_SCORE);
    }

    @Test
    void ratingRowsAreReadNewestFirstAndTheLatestIsFound() {
        long m = member();
        long s1 = season(m);
        long s2 = season(m);
        scores.save(new SeasonScore(s1, m, result(), null, "{}"));
        scores.save(new SeasonScore(s2, m, result(), null, "{}"));
        RatingHistory first = ratings.save(new RatingHistory(m, RatingPillar.MANAGER, 1, s1, new BigDecimal("65.89"), 1));
        ratings.save(new RatingHistory(m, RatingPillar.MANAGER, 2, s2, new BigDecimal("70.00"), 2));
        em.flush();
        em.clear();

        List<RatingHistory> list = ratings.findNewestFirst(m, RatingPillar.MANAGER);

        assertThat(first.getId()).isNotNull();
        assertThat(list).extracting(RatingHistory::getSeqNo).containsExactly(2, 1);
        assertThat(list.get(0).getRating()).isEqualByComparingTo("70.00");
        assertThat(list.get(0).getPillar()).isEqualTo(RatingPillar.MANAGER);
        assertThat(list.get(0).getSeasonsUsed()).isEqualTo(2);
        assertThat(list.get(0).getCreatedAt()).isNotNull();
        assertThat(ratings.findLatest(m, RatingPillar.MANAGER)).get().extracting(RatingHistory::getSeqNo).isEqualTo(2);
        assertThat(ratings.existsBySeasonId(s1)).isTrue();
        assertThat(scores.findByMemberId(m)).extracting(SeasonScore::getSeasonId).containsExactly(s1, s2);
    }

    @Test
    void aMemberWithoutRatingRowsHasNoLatest() {
        assertThat(ratings.findLatest(member(), RatingPillar.MANAGER)).isEmpty();
        assertThat(ratings.findNewestFirst(member(), RatingPillar.MANAGER)).isEmpty();
    }
}
