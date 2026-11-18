package antlab.scoring;

import antlab.member.MemberRepository;
import antlab.member.RatingCalculator;
import antlab.member.RatingHistory;
import antlab.member.RatingHistoryRepository;
import antlab.member.RatingPillar;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one short write transaction of the score service. It locks the member row (so a member's rating sequence has one
 * writer at a time), re-checks that the season has no score row, appends the score and, when the season counts, the next
 * rating row. It takes no season or cash-account lock, so it cannot deadlock with the turn flow (season, then account).
 * Nothing is updated or deleted: rows are only appended.
 */
@Component
public class ScoreWriter {

    private final MemberRepository members;
    private final SeasonScoreRepository scores;
    private final RatingHistoryRepository ratings;
    private final EntityManager entityManager;

    public ScoreWriter(MemberRepository members, SeasonScoreRepository scores, RatingHistoryRepository ratings,
            EntityManager entityManager) {
        this.members = members;
        this.scores = scores;
        this.ratings = ratings;
        this.entityManager = entityManager;
    }

    /**
     * @param exclusion why the season stays out of the rating, null when it counts
     * @return true when rows were appended, false when another request had already scored the season
     */
    @Transactional
    public boolean write(long seasonId, long memberId, SeasonScoreResult result, RatingExclusion exclusion, String inputs) {
        members.findByIdForUpdate(memberId).orElseThrow(() -> new IllegalStateException("score.member_missing"));
        if (scores.existsBySeasonId(seasonId)) {
            return false;
        }
        if (exclusion == null && result.score() == null) {
            throw new IllegalStateException("score.counted_season_without_score");
        }
        scores.save(new SeasonScore(seasonId, memberId, result, exclusion, inputs));
        // The rating row references the score row; an identity insert would otherwise run before the score insert.
        entityManager.flush();
        if (exclusion == null) {
            appendRating(seasonId, memberId, result.score());
        }
        return true;
    }

    private void appendRating(long seasonId, long memberId, BigDecimal newScore) {
        List<RatingHistory> newestFirst = ratings.findNewestFirst(memberId, RatingPillar.MANAGER);
        int seqNo = newestFirst.isEmpty() ? 1 : newestFirst.get(0).getSeqNo() + 1;
        List<RatingHistory> previous = newestFirst.stream().limit(RatingCalculator.WINDOW - 1L).toList();
        List<BigDecimal> oldestFirst = new ArrayList<>(scoresOf(previous, memberId));
        oldestFirst.add(newScore);
        RatingCalculator.Rating rating = RatingCalculator.rate(oldestFirst);
        ratings.save(new RatingHistory(memberId, RatingPillar.MANAGER, seqNo, seasonId, rating.rating(), rating.seasonsUsed()));
    }

    /** The scores behind the given rating rows, oldest first; a row whose score belongs to another member is a bug. */
    private List<BigDecimal> scoresOf(List<RatingHistory> newestFirst, long memberId) {
        if (newestFirst.isEmpty()) {
            return List.of();
        }
        Map<Long, SeasonScore> bySeason = scores.findBySeasonIds(newestFirst.stream().map(RatingHistory::getSeasonId).toList())
                .stream().collect(Collectors.toMap(SeasonScore::getSeasonId, Function.identity()));
        return newestFirst.stream().sorted(Comparator.comparingInt(RatingHistory::getSeqNo)).map(row -> {
            SeasonScore score = bySeason.get(row.getSeasonId());
            if (score == null || score.getScore() == null || score.getMemberId() != memberId) {
                throw new IllegalStateException("score.rating_row_without_own_score");
            }
            return score.getScore();
        }).toList();
    }
}
