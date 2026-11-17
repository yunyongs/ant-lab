package antlab.agent.decision;

import antlab.season.TurnFacade;
import antlab.season.TurnResultDto;
import org.springframework.stereotype.Service;

/**
 * Advances an agent season through the human turn path ({@link TurnFacade}), but only when the current turn has a final
 * decision. Not transactional itself: the facade must run outside a transaction. The guard only reads; a season that is
 * not in progress or a turn number that is stale is left to the facade, which answers with its usual codes.
 */
@Service
public class AgentTurnAdvanceService {

    private final AgentDecisionGuard guard;
    private final TurnFacade turns;

    public AgentTurnAdvanceService(AgentDecisionGuard guard, TurnFacade turns) {
        this.guard = guard;
        this.turns = turns;
    }

    public TurnResultDto advance(long agentId, long seasonId, int expectedTurnNo) {
        guard.requireDecision(agentId, seasonId, expectedTurnNo);
        return turns.advance(seasonId, expectedTurnNo);
    }
}
