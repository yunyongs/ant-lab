package antlab.season;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Season review: the real month and events of each turn of a finished season (P0-39). Read-only, no locks; the path,
 * month blocks and events of a finished season never change, so the default isolation is enough.
 * Check order: member → season (another member's season is 404) → status (409) → the two reveal queries. No reveal
 * value is read before the status check. The statement count does not depend on the number of turns.
 */
@Service
public class SeasonReviewService {

    // "uuuu" (proleptic year), not "yyyy" (year of era)
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("uuuu-MM");

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final SeasonPathRepository paths;
    private final FormationChangeRepository changes;

    public SeasonReviewService(CurrentMember currentMember, SeasonRepository seasons, SeasonPathRepository paths,
            FormationChangeRepository changes) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.paths = paths;
        this.changes = changes;
    }

    @Transactional(readOnly = true)
    public SeasonReviewDto get(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        if (season.getStatus() != SeasonStatus.FINISHED) {
            throw new DomainException(ErrorCode.SEASON_NOT_FINISHED, "끝난 시즌만 복기를 볼 수 있다");
        }
        long id = season.getId();
        List<RevealedMonthRow> months = paths.findRevealedMonths(id);
        requireContiguous(months, season.getPathLength());
        Map<Integer, List<ReviewEventDto>> eventsByTurn = new TreeMap<>();
        for (RevealedEventRow e : paths.findRevealedEvents(id)) {
            eventsByTurn.computeIfAbsent(e.turnNo(), t -> new ArrayList<>()).add(ReviewEventDto.from(e));
        }
        List<ReviewTurnDto> turns = months.stream()
                .map(m -> new ReviewTurnDto(m.turnNo(), YEAR_MONTH.format(m.monthStart()), m.eventSummary(),
                        eventsByTurn.getOrDefault(m.turnNo(), List.of())))
                .toList();
        List<FormationChangeDto> formationChanges = changes.findAllBySeasonId(id).stream()
                .map(FormationChangeDto::from).toList();
        return new SeasonReviewDto(season.getStatus(), season.getPathLength(), season.getAbandonedTurnNo(),
                Long.toString(season.getSeed()), season.getEngineVersion(), season.getDataSnapshotDate().toString(),
                turns, formationChanges);
    }

    /** Turn numbers must run 1..pathLength without a gap; anything else is a contradictory source (common 500). */
    private static void requireContiguous(List<RevealedMonthRow> months, int pathLength) {
        boolean ok = months.size() == pathLength;
        for (int i = 0; ok && i < months.size(); i++) {
            ok = months.get(i).turnNo() == i + 1;
        }
        if (!ok) {
            throw new IllegalStateException("복기 원천이 모순이다: 경로가 1턴부터 끝 턴까지 이어지지 않는다");
        }
    }
}
