package antlab.agent.memory;

import antlab.account.OrderRepository;
import antlab.account.RecentTradeRow;
import antlab.agent.AgentCallRepository;
import antlab.agent.AgentFinalCallRow;
import antlab.agent.AgentTurnViewService;
import antlab.agent.DecisionOutcome;
import antlab.season.CitedFeedRow;
import antlab.season.TurnFeedRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-turn part of the reflection view, read in one snapshot: the agent's own stated reasons and rejects, its fills
 * (no prices) and the kind of the feed items its orders cited. The caller has already checked that the season is
 * finished, eligible and released.
 */
@Component
public class ReflectionTurnReader {

    private final AgentCallRepository calls;
    private final OrderRepository orders;
    private final TurnFeedRepository feeds;
    private final JsonMapper mapper;

    public ReflectionTurnReader(AgentCallRepository calls, OrderRepository orders, TurnFeedRepository feeds,
            JsonMapper mapper) {
        this.calls = calls;
        this.orders = orders;
        this.feeds = feeds;
        this.mapper = mapper;
    }

    /** Relative label of a decision turn: year of play and month of that year, counted from the start of the season. */
    static String label(int turnNo) {
        return (turnNo / 12 + 1) + "년차 " + (turnNo % 12 + 1) + "월";
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ReflectionViewDto.Turn> turns(long seasonId, int pathLength) {
        Map<Integer, AgentFinalCallRow> finals = new HashMap<>();
        calls.findFinalDecisions(seasonId).forEach(row -> finals.put(row.turnNo(), row));
        Map<Integer, List<ReflectionViewDto.Fill>> fills = new HashMap<>();
        for (RecentTradeRow t : orders.findTrades(seasonId, 0, pathLength)) {
            fills.computeIfAbsent(t.turnNo(), k -> new ArrayList<>())
                    .add(new ReflectionViewDto.Fill(t.market().name() + ":" + t.ticker(), t.side(), t.quantity()));
        }
        Map<Integer, List<ReflectionViewDto.Cited>> cited = new HashMap<>();
        Map<Integer, Set<Integer>> seen = new HashMap<>();
        for (CitedFeedRow c : feeds.findRevealedCitedItems(seasonId)) {
            if (seen.computeIfAbsent(c.turnNo(), k -> new HashSet<>()).add(c.feedTurnNo() * 1000 + c.seq())) {
                cited.computeIfAbsent(c.turnNo(), k -> new ArrayList<>())
                        .add(new ReflectionViewDto.Cited(c.feedTurnNo(), c.seq(), c.kind()));
            }
        }
        List<ReflectionViewDto.Turn> out = new ArrayList<>();
        for (int turn = 0; turn < pathLength; turn++) {
            AgentFinalCallRow fin = finals.get(turn);
            out.add(new ReflectionViewDto.Turn(turn, label(turn), reason(fin), fills.getOrDefault(turn, List.of()),
                    fin == null || fin.rejects() == null ? List.of()
                            : AgentTurnViewService.parseRejects(fin.rejects()).items(),
                    cited.getOrDefault(turn, List.of())));
        }
        return List.copyOf(out);
    }

    private String reason(AgentFinalCallRow fin) {
        if (fin == null || fin.outcome() != DecisionOutcome.DECISION || fin.parsed() == null) {
            return null;
        }
        JsonNode reason = mapper.readTree(fin.parsed()).get("reason");
        return reason != null && reason.isString() ? reason.asString() : null;
    }
}
