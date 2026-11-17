package antlab.projection;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.common.persistence.UniqueViolations;
import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineProjectionRequest;
import antlab.engine.client.EngineProjectionResult;
import antlab.engine.client.EngineProjectionResult.PortfolioResult;
import antlab.engine.client.EngineProperties;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Projections of a season's team. <b>No transaction here</b>: the inputs are read in one read-only snapshot, the engine is
 * called with no connection held, and a START or END result is stored by one short INSERT. A concurrent second writer
 * hits the unique key, and both callers answer from the stored row, so the two answers are identical. CURRENT is
 * computed on every request and never stored.
 *
 * <p>The request carries the season seed and base date and the team's shares only. Nothing here reads the season's own
 * path, so a projection cannot see the months the season will play (the engine derives its own paths from the seed).
 * START and END are rebuilt from immutable rows, so the lazy first read gives what an eager one would.
 */
@Service
public class ProjectionService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionService.class);

    static final String TEAM = "TEAM";
    static final int LONG_HORIZON = 120;
    static final int SHORT_HORIZON = 60;
    static final String UNIQUE_CONSTRAINT = "uq_projection_season_phase";

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final ProjectionInputQuery inputs;
    private final ProjectionStore store;
    private final EngineClient engine;
    private final int pathCount;

    public ProjectionService(CurrentMember currentMember, SeasonRepository seasons, ProjectionInputQuery inputs,
            ProjectionStore store, EngineClient engine, EngineProperties properties) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.inputs = inputs;
        this.store = store;
        this.engine = engine;
        this.pathCount = properties.projectionPaths();
    }

    public ProjectionDto get(Long seasonId, ProjectionMoment moment) {
        Season season = find(seasonId);
        requireReachable(season, moment);
        ProjectionPhase phase = moment.stored();
        if (phase != null) {
            var stored = store.find(season.getId(), phase);
            if (stored.isPresent()) {
                return fromRow(stored.get(), season);
            }
        }
        ProjectionBasis basis = switch (moment) {
            case CURRENT -> inputs.current(season.getId(), season.getCurrentTurnNo());
            case START -> inputs.start(season.getId());
            case END -> inputs.current(season.getId(), season.getPathLength());
        };
        ProjectionInput input = ProjectionInput.of(basis.cash(), basis.holdings());
        Answer answer = ask(season, input);
        if (phase == null) {
            return ProjectionDto.of(moment, basis.turnNo(), input.totalValue(), answer.request().horizonTurns(),
                    answer.request().n(), season.getRiskLimit(), answer.portfolio());
        }
        return fromRow(keep(season, phase, basis.turnNo(), input, answer), season);
    }

    private Season find(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        return seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
    }

    private static void requireReachable(Season season, ProjectionMoment moment) {
        switch (moment) {
            case CURRENT -> {
                if (season.getStatus() == SeasonStatus.FINISHED) {
                    throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "끝난 시즌이라 현재 팀의 예상 분포는 볼 수 없다");
                }
            }
            case START -> {
                if (season.getCurrentTurnNo() < 1) {
                    throw new DomainException(ErrorCode.TURN_NOT_REACHED, "아직 진행하지 않은 턴이다");
                }
            }
            case END -> {
                if (season.getStatus() != SeasonStatus.FINISHED) {
                    throw new DomainException(ErrorCode.SEASON_NOT_FINISHED, "끝나지 않은 시즌이다");
                }
            }
        }
    }

    private record Answer(EngineProjectionRequest request, EngineProjectionResult result, PortfolioResult portfolio) {
    }

    /** One call with the long horizon; a 422 (history too short for it) is retried once with the short one. */
    private Answer ask(Season season, ProjectionInput input) {
        try {
            return call(season, input, LONG_HORIZON);
        } catch (DomainException tooLong) {
            if (tooLong.getCode() != ErrorCode.PATH_TOO_LONG) {
                throw tooLong;
            }
            return call(season, input, SHORT_HORIZON);
        }
    }

    private Answer call(Season season, ProjectionInput input, int horizon) {
        List<EngineProjectionRequest.Holding> weights = input.shares().stream()
                .map(s -> new EngineProjectionRequest.Holding(s.asset(), s.weight().toPlainString())).toList();
        var team = new EngineProjectionRequest.Portfolio(TEAM, input.cashShare().toPlainString(), weights);
        TreeSet<Integer> turns = new TreeSet<>(List.of(season.getPathLength(), SHORT_HORIZON));
        if (horizon == LONG_HORIZON) {
            turns.add(LONG_HORIZON);
        }
        var request = new EngineProjectionRequest(Long.toString(season.getSeed()), season.getBaseDate(), horizon, pathCount,
                MoneyMath.exactRate(season.getRiskLimit()).toPlainString(), List.copyOf(turns), List.of(team));
        EngineProjectionResult result = engine.createProjection(request);
        return new Answer(request, result, checked(request, result));
    }

    /** The answer must be the one asked for; anything else is a contract break, answered as 500 with the rule name only. */
    private static PortfolioResult checked(EngineProjectionRequest request, EngineProjectionResult result) {
        if (result.n() != request.n() || result.horizonTurns() != request.horizonTurns()) {
            throw badAnswer("shape");
        }
        if (result.portfolios().size() != 1 || !TEAM.equals(result.portfolios().get(0).key())) {
            throw badAnswer("portfolios");
        }
        PortfolioResult team = result.portfolios().get(0);
        if (team.bands().size() != request.horizonTurns()) {
            throw badAnswer("band_count");
        }
        for (int i = 0; i < team.bands().size(); i++) {
            if (team.bands().get(i).turn() != i + 1) {
                throw badAnswer("band_turns");
            }
        }
        TreeSet<Integer> expected = new TreeSet<>(request.summaryTurns().stream()
                .filter(turn -> turn <= request.horizonTurns()).toList());
        expected.add(request.horizonTurns());
        List<Integer> actual = new ArrayList<>();
        team.summaries().forEach(s -> actual.add(s.turn()));
        if (!List.copyOf(expected).equals(actual)) {
            throw badAnswer("summary_turns");
        }
        return team;
    }

    private static IllegalStateException badAnswer(String rule) {
        LOG.error("engine.bad_response rule={}", rule);
        return new IllegalStateException("projection.bad_response");
    }

    /** Stores the result; if another request stored this phase first, returns that row instead. */
    private Projection keep(Season season, ProjectionPhase phase, int turnNo, ProjectionInput input, Answer answer) {
        Projection row = new Projection(season.getId(), phase, turnNo, input.totalValue(), answer.request().horizonTurns(),
                answer.request().n(), ProjectionJson.request(answer.request()),
                ProjectionJson.result(answer.result().portfolios()), answer.result().projectionVersion(),
                answer.result().engineVersion(), answer.result().historyDigest());
        try {
            return store.insert(row);
        } catch (DataIntegrityViolationException duplicate) {
            if (!UniqueViolations.isViolationOf(duplicate, UNIQUE_CONSTRAINT)) {
                throw duplicate;
            }
            return store.find(season.getId(), phase).orElseThrow(() -> duplicate);
        }
    }

    private static ProjectionDto fromRow(Projection row, Season season) {
        List<PortfolioResult> portfolios = ProjectionJson.readResult(row.getResult());
        PortfolioResult team = portfolios.stream().filter(p -> TEAM.equals(p.key())).findFirst()
                .orElseThrow(() -> new IllegalStateException("projection.stored_row_has_no_team"));
        ProjectionMoment moment = row.getPhase() == ProjectionPhase.START ? ProjectionMoment.START : ProjectionMoment.END;
        return ProjectionDto.of(moment, row.getTurnNo(), row.getTotalValue(), row.getHorizonTurns(), row.getPathCount(),
                season.getRiskLimit(), team);
    }
}
