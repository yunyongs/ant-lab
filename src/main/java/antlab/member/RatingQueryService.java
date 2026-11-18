package antlab.member;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.scoring.RatingExclusion;
import antlab.scoring.SeasonScore;
import antlab.scoring.SeasonScoreRepository;
import antlab.scoring.SeasonScoreService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The current member's manager rating. <b>No transaction here</b>: it first lets the score service score the member's
 * finished seasons (which calls the engine outside any transaction), then reads the stored rows. It never fails on an
 * engine problem; seasons that could not be scored only raise {@code pendingSeasons}.
 */
@Service
public class RatingQueryService {

    /** At most this many history entries are returned. */
    static final int HISTORY_LIMIT = 20;

    private final CurrentMember currentMember;
    private final SeasonScoreService scoring;
    private final RatingHistoryRepository ratings;
    private final SeasonScoreRepository scores;

    public RatingQueryService(CurrentMember currentMember, SeasonScoreService scoring, RatingHistoryRepository ratings,
            SeasonScoreRepository scores) {
        this.currentMember = currentMember;
        this.scoring = scoring;
        this.ratings = ratings;
        this.scores = scores;
    }

    public RatingDto getMine() {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "인증이 필요하다"));
        int pending = scoring.ensureMember(memberId).pendingSeasons();
        List<RatingHistory> newestFirst = ratings.findNewestFirst(memberId, RatingPillar.MANAGER);
        int counted = newestFirst.isEmpty() ? 0 : newestFirst.get(0).getSeqNo();
        List<RatingHistory> shown = newestFirst.stream().limit(HISTORY_LIMIT).toList();
        Map<Long, SeasonScore> byseason = shown.isEmpty() ? Map.of()
                : scores.findBySeasonIds(shown.stream().map(RatingHistory::getSeasonId).toList()).stream()
                        .collect(Collectors.toMap(SeasonScore::getSeasonId, Function.identity()));
        List<RatingDto.Entry> history = shown.stream().map(row -> {
            SeasonScore score = byseason.get(row.getSeasonId());
            if (score == null) {
                throw new IllegalStateException("rating.row_without_score");
            }
            return new RatingDto.Entry(row.getSeqNo(), row.getSeasonId(), score.getScore(),
                    RatingCalculator.isShown(row.getSeqNo()) ? row.getRating() : null);
        }).toList();
        boolean shownNow = RatingCalculator.isShown(counted);
        return new RatingDto(RatingPillar.MANAGER, shownNow ? newestFirst.get(0).getRating() : null, counted,
                Math.max(0, RatingCalculator.SHOWN_FROM - counted), (int) scores.countExcluded(memberId, RatingExclusion.NOT_STARTED),
                (int) scores.countExcluded(memberId, RatingExclusion.NO_SCORE), pending, history);
    }
}
