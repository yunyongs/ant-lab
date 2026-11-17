package antlab.agent;

import antlab.account.HoldingsQueryService;
import antlab.account.OrderRepository;
import antlab.account.RecentTradeRow;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.asset.PoolService;
import antlab.member.CurrentMember;
import antlab.season.SeasonDto;
import antlab.season.SeasonQueryService;
import antlab.season.TurnQueryService;
import antlab.season.TurnResultDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the turn screen of an agent. Every embedded part comes from the service that answers the human turn endpoints
 * (the same code runs for the acting agent member), so the agent cannot be shown more than a human is. The agent part
 * adds only the agent's own past: its fills in the last {@code k} turns before the current one, and the rejects of the
 * previous turn's final call. Nothing of the current turn or later is read. One snapshot serves all parts.
 */
@Service
public class AgentTurnViewService {

    private final CurrentMember currentMember;
    private final SeasonQueryService seasonQueries;
    private final TurnQueryService turnQueries;
    private final HoldingsQueryService holdingsQueries;
    private final PoolService pools;
    private final OrderRepository orders;
    private final AgentCallRepository calls;
    private final int recentTurns;

    public AgentTurnViewService(CurrentMember currentMember, SeasonQueryService seasonQueries,
            TurnQueryService turnQueries, HoldingsQueryService holdingsQueries, PoolService pools,
            OrderRepository orders, AgentCallRepository calls,
            @Value("${antlab.agent-runner.recent-turns:3}") int recentTurns) {
        if (recentTurns < 1) {
            throw new IllegalArgumentException("antlab.agent-runner.recent-turns must be at least 1");
        }
        this.currentMember = currentMember;
        this.seasonQueries = seasonQueries;
        this.turnQueries = turnQueries;
        this.holdingsQueries = holdingsQueries;
        this.pools = pools;
        this.orders = orders;
        this.calls = calls;
        this.recentTurns = recentTurns;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentTurnViewDto get(long agentId, long seasonId) {
        // The filter already resolved the path's agent; a mismatch is the same "not found" as a season of someone else.
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
        SeasonDto season = seasonQueries.get(seasonId);
        int turn = season.currentTurnNo();
        TurnResultDto lastTurn = turn >= 1 ? turnQueries.get(seasonId, turn) : null;
        return new AgentTurnViewDto(season, lastTurn, holdingsQueries.get(seasonId), seasonQueries.prices(seasonId, null),
                pools.pool().assets(), new AgentPartDto(AgentPartDto.SCHEMA, recentTrades(seasonId, turn),
                        lastRejects(seasonId, turn), null));
    }

    /** Fills of the turns {@code turn - k .. turn - 1}; the current turn and later ones are never read. */
    private List<RecentTradeDto> recentTrades(long seasonId, int turn) {
        if (turn < 1) {
            return List.of();
        }
        List<RecentTradeRow> rows = orders.findTrades(seasonId, Math.max(0, turn - recentTurns), turn - 1);
        return rows.stream().map(r -> new RecentTradeDto(r.turnNo(), r.side(), r.market().name() + ":" + r.ticker(),
                r.quantity(), MoneyMath.exactWon(r.fillPrice()), MoneyMath.exactWon(r.fee()), r.origin())).toList();
    }

    /** Rejects the engine recorded on the previous turn's final call; none if that turn has no recorded rejects. */
    private List<RejectDto> lastRejects(long seasonId, int turn) {
        if (turn < 1) {
            return List.of();
        }
        return calls.findFinalBySeasonIdAndTurnNo(seasonId, turn - 1).map(AgentCall::getRejects)
                .map(AgentTurnViewService::parseRejects).orElse(List.of());
    }

    /** The stored list is written by the engine only; a row of another shape is damaged data, not an input error. */
    static List<RejectDto> parseRejects(String json) {
        JsonNode root = JsonMapper.shared().readTree(json);
        if (!root.isArray()) {
            throw new IllegalStateException("agent_call.rejects is not an array");
        }
        List<RejectDto> out = new ArrayList<>();
        for (JsonNode item : root) {
            JsonNode unit = item.get("unit");
            JsonNode code = item.get("code");
            JsonNode index = item.get("index");
            if (unit == null || !unit.isString() || code == null || !code.isString()
                    || (index != null && !index.isNull() && !index.isIntegralNumber())) {
                throw new IllegalStateException("agent_call.rejects has an item of an unknown shape");
            }
            out.add(new RejectDto(unit.asString(), index == null || index.isNull() ? null : index.asInt(),
                    code.asString()));
        }
        return List.copyOf(out);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
