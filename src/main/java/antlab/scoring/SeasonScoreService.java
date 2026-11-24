package antlab.scoring;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.persistence.UniqueViolations;
import antlab.engine.client.EngineClient;
import antlab.engine.client.EnginePercentileRequest;
import antlab.engine.client.EnginePercentileResult;
import antlab.engine.client.EngineProperties;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Season scores, made lazily after a season has finished. <b>No transaction here</b>: the inputs are read in one
 * read-only snapshot, the engine is called with no connection held, and the result is written by one short transaction
 * ({@link ScoreWriter}). Scoring never runs inside a turn advance, so an engine outage cannot block a turn.
 *
 * <p>{@link #ensureMember} scores a member's finished seasons that have no score yet, in finish order, one at a time. A
 * deterministic engine refusal (the history cannot carry this season length) stores a row with no score that stays out of the
 * rating; a transient failure (engine unreachable, busy, slow, or a bad answer) stores nothing and stops the loop, so a later
 * season is never rated before an earlier one. The rating is therefore a function of the finished seasons in finish order only:
 * not looking at a bad season cannot keep it out of the rating. Every call is idempotent.
 */
@Service
public class SeasonScoreService {

    private static final Logger LOG = LoggerFactory.getLogger(SeasonScoreService.class);

    static final String SCORE_CONSTRAINT = "pk_season_score";

    /** @param pendingSeasons finished seasons of the member that still have no score row after the attempt */
    public record Ensured(int pendingSeasons) {
    }

    /**
     * What {@link #ensureSeason} found: the season has a score row; an earlier season of the member has none, so this one
     * waits; the engine was unavailable; or the attempt failed otherwise (a contract fault or a bad answer).
     */
    public enum Step { SCORED, BLOCKED, UNAVAILABLE, FAILED }

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final SeasonScoreRepository scores;
    private final ScoreInputQuery query;
    private final ScoreInputReader reader;
    private final MarketBaselineSeries baseline;
    private final EngineClient engine;
    private final ScoreWriter writer;
    private final int pathCount;

    public SeasonScoreService(CurrentMember currentMember, SeasonRepository seasons, SeasonScoreRepository scores,
            ScoreInputQuery query, ScoreInputReader reader, MarketBaselineSeries baseline, EngineClient engine,
            ScoreWriter writer, EngineProperties properties) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.scores = scores;
        this.query = query;
        this.reader = reader;
        this.baseline = baseline;
        this.engine = engine;
        this.writer = writer;
        this.pathCount = properties.projectionPaths();
    }

    /** The score of one finished season of the current member; 503 ENGINE_UNAVAILABLE while it cannot be computed yet. */
    public SeasonScoreDto get(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        if (season.getStatus() != SeasonStatus.FINISHED) {
            throw new DomainException(ErrorCode.SEASON_NOT_FINISHED, "끝난 시즌만 점수를 볼 수 있다");
        }
        ensureMember(memberId);
        SeasonScore row = scores.findBySeasonIdAndMemberId(seasonId, memberId).orElseThrow(
                () -> new DomainException(ErrorCode.ENGINE_UNAVAILABLE, "점수를 아직 계산하지 못했다. 잠시 뒤 다시 시도한다"));
        return SeasonScoreDto.of(row);
    }

    /** Scores every finished season of the member that has no score yet. Never throws for an engine failure. */
    public Ensured ensureMember(long memberId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("점수 서비스는 트랜잭션 밖에서만 부른다 — 엔진 호출 중에 연결을 잡고 있으면 안 된다");
        }
        List<Long> unscored = query.unscoredFinished(memberId);
        if (unscored.isEmpty()) {
            return new Ensured(0);
        }
        for (Long seasonId : unscored) {
            if (scoreOne(seasonId) != Step.SCORED) {
                break;
            }
        }
        return new Ensured(query.unscoredFinished(memberId).size());
    }

    /**
     * Makes sure one finished season has a score row, with at most one engine call: the season is scored only when it is the
     * member's earliest finished season without a score, so the finish order holds and an earlier season that failed is
     * not tried again on this season's behalf. Never throws for an engine failure.
     */
    public Step ensureSeason(long memberId, long seasonId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("점수 서비스는 트랜잭션 밖에서만 부른다 — 엔진 호출 중에 연결을 잡고 있으면 안 된다");
        }
        List<Long> unscored = query.unscoredFinished(memberId);
        if (!unscored.contains(seasonId)) {
            return Step.SCORED;
        }
        if (unscored.get(0) != seasonId) {
            return Step.BLOCKED;
        }
        return scoreOne(seasonId);
    }

    /** {@link Step#SCORED} when a row now exists; anything else stores nothing and stops a member's loop. */
    private Step scoreOne(long seasonId) {
        ScoreBasis basis = reader.read(seasonId);
        SeasonReportDto report = SeasonReportCalculator.calculate(basis.sources());
        ScoreAssembler.Observation observation = ScoreAssembler.observe(basis, baseline.returns(basis.sources()));

        EnginePercentileRequest request = null;
        EnginePercentileResult result = null;
        String noDistribution = null;
        if (!EnginePercentileRequest.supportsHorizon(basis.pathLength())) {
            noDistribution = ScoreInputsJson.HORIZON_UNSUPPORTED;
        } else {
            request = ScoreAssembler.request(basis, observation, pathCount);
            try {
                result = engine.createPercentile(request);
            } catch (DomainException refused) {
                if (refused.getCode() == ErrorCode.PATH_TOO_LONG) {
                    noDistribution = ScoreInputsJson.PATH_TOO_LONG;
                } else if (refused.getCode() == ErrorCode.ENGINE_UNAVAILABLE || refused.getCode() == ErrorCode.INTERNAL_ERROR) {
                    // transient or a contract fault: nothing is stored, so an engine fix scores the season later
                    LOG.warn("score.pending seasonId={} code={}", seasonId, refused.getCode());
                    return refused.getCode() == ErrorCode.ENGINE_UNAVAILABLE ? Step.UNAVAILABLE : Step.FAILED;
                } else {
                    throw refused;
                }
            }
            if (result != null && (result.n() != request.n() || result.horizonTurns() != request.horizonTurns())) {
                LOG.error("engine.bad_response seasonId={} rule=percentile_shape", seasonId);
                return Step.FAILED;
            }
        }

        RankCounts diff = result == null ? null
                : new RankCounts(result.sharpeDiff().below(), result.sharpeDiff().equal(), result.n());
        RankCounts end = result == null ? null
                : new RankCounts(result.endMultiple().below(), result.endMultiple().equal(), result.n());
        SeasonScoreResult score = SeasonScoreCalculator.calculate(ScoreAssembler.inputs(report, observation, basis, diff, end));
        RatingExclusion exclusion = SeasonScoreCalculator.exclusion(basis.notStarted(), score);
        String inputs = ScoreInputsJson.of(report, observation, basis, request, result, noDistribution);
        try {
            boolean written = writer.write(seasonId, basis.memberId(), score, exclusion, inputs);
            if (written) {
                LOG.info("score.written seasonId={} counted={} exclusion={}", seasonId, exclusion == null, exclusion);
            }
        } catch (RuntimeException duplicate) {
            // another request scored the season between the member lock and its own insert; the stored row stands
            if (!UniqueViolations.isViolationOf(duplicate, SCORE_CONSTRAINT)) {
                throw duplicate;
            }
        }
        return Step.SCORED;
    }
}
