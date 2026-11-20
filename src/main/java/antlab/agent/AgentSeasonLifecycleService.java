package antlab.agent;

import antlab.account.CashAccountRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.member.MemberKind;
import antlab.season.Season;
import antlab.season.SeasonDto;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pause, resume and void of an agent season (AG-02 A5, PLAN §6.9). Each call locks the season row first, like orders
 * and turn advances, so it serializes with them.
 *
 * <p>An un-resumed pause ends as an abandon after {@link Season#PAUSE_LIMIT}. There is no scheduler: the stored row is
 * converted by the next pause, resume or abandon of that season, always from the clock, so the result does not depend on
 * when it was applied. Reads (season answers, the turn view, the observation views) already show an overdue pause as
 * ABANDONING. A new season is refused while any season of the agent is paused or abandoning, so a pause is never a way
 * to leave a season out of the score.
 */
@Service
public class AgentSeasonLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AgentSeasonLifecycleService.class);

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final CashAccountRepository accounts;
    private final Clock clock;

    public AgentSeasonLifecycleService(CurrentMember currentMember, SeasonRepository seasons,
            CashAccountRepository accounts, Clock clock) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Pause a season in progress. A repeat on a paused season answers it unchanged; an overdue pause becomes an abandon. */
    @Transactional
    public SeasonDto pause(long agentId, long seasonId) {
        Season season = lock(agentId, seasonId);
        Instant now = clock.instant();
        switch (season.getStatus()) {
            case IN_PROGRESS -> season.pause(now);
            case PAUSED -> expireIfDue(season, now);
            default -> throw notActive(season);
        }
        return dto(season);
    }

    /**
     * Resume a paused season at the same turn. A repeat on a season in progress answers it unchanged. A pause past its
     * window is not resumed: it becomes an abandon and the answer shows ABANDONING.
     */
    @Transactional
    public SeasonDto resume(long agentId, long seasonId) {
        Season season = lock(agentId, seasonId);
        switch (season.getStatus()) {
            case IN_PROGRESS -> {
                // a repeat of a resume whose answer was lost
            }
            case PAUSED -> {
                if (!expireIfDue(season, clock.instant())) {
                    season.continueAfterPause();
                }
            }
            default -> throw notActive(season);
        }
        return dto(season);
    }

    /** Void a season that is not finished or abandoned (a running, live-paused or halted one). A repeat with the same reason answers it unchanged; any other repeat is refused. */
    @Transactional
    public SeasonDto voidSeason(long agentId, long seasonId, VoidSeasonRequest request) {
        Season season = lock(agentId, seasonId);
        if (season.getStatus() == SeasonStatus.VOID && season.getVoidReason() == request.reason()) {
            return dto(season);
        }
        if (season.getStatus() == SeasonStatus.PAUSED) {
            // An overdue pause is already a counted abandon: it cannot be voided out of L. The refusal rolls the
            // conversion back; the next abandon, resume or pause converts it again from the same clock.
            expireIfDue(season, clock.instant());
        }
        if (season.getStatus() == SeasonStatus.FINISHED || season.getStatus() == SeasonStatus.VOID
                || season.getStatus() == SeasonStatus.ABANDONING) {
            throw notActive(season);
        }
        season.voidSeason(request.reason(), request.note().strip());
        log.warn("에이전트 시즌 무효: seasonId={} agentId={} reason={} turnNo={}", seasonId, agentId, request.reason(),
                season.getCurrentTurnNo());
        return dto(season);
    }

    /** @return true when the pause was over and the season became an abandon */
    private boolean expireIfDue(Season season, Instant now) {
        if (!season.pauseExpired(now)) {
            return false;
        }
        season.abandon();
        log.info("일시 정지 기한 만료로 포기 처리: seasonId={} abandonedTurnNo={}", season.getId(),
                season.getAbandonedTurnNo());
        return true;
    }

    /** The path's agent must be the acting one; any other id is the same NOT_FOUND as a missing agent. */
    public void requireActing(long agentId) {
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
    }

    private Season lock(long agentId, long seasonId) {
        requireActing(agentId);
        return seasons.findByIdAndMemberIdForUpdate(seasonId, agentId).orElseThrow(AgentSeasonLifecycleService::notFound);
    }

    private SeasonDto dto(Season season) {
        return SeasonDto.from(season, accounts.findById(season.getId())
                .orElseThrow(() -> new IllegalStateException("시즌에 현금 계좌가 없다")).getBalance(), MemberKind.AGENT);
    }

    private static DomainException notActive(Season season) {
        if (season.getStatus() == SeasonStatus.HALTED) {
            return new DomainException(ErrorCode.SEASON_HALTED, "대사가 거듭 맞지 않아 시즌을 멈췄다 — 관리자 확인이 필요하다");
        }
        return new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "이 상태의 시즌에는 할 수 없다");
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
