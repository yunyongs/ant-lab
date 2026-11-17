package antlab.season;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator resume of a halted season (local dev endpoint). No member filter: an administrator acts on any season.
 * Idempotent: resuming a season that is not halted changes nothing and answers {@code resumed = false}.
 * The count of failed attempts is derived from the log, so after a resume the next failure of the same pending turn
 * halts again at once ("one more try").
 */
@Service
public class SeasonResumeService {

    private static final Logger log = LoggerFactory.getLogger(SeasonResumeService.class);

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;

    public SeasonResumeService(CurrentMember currentMember, SeasonRepository seasons) {
        this.currentMember = currentMember;
        this.seasons = seasons;
    }

    @Transactional
    public DevResumeDto resume(long seasonId) {
        Season season = seasons.findByIdForUpdate(seasonId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        boolean resumed = season.resume();
        if (resumed) {
            log.warn("시즌 재개: seasonId={} status={} byMemberId={}", seasonId, season.getStatus(),
                    currentMember.existingId().orElse(null));
        }
        return new DevResumeDto(seasonId, season.getStatus(), resumed);
    }
}
