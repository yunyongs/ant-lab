package antlab.api.agentrunner;

import antlab.agent.AgentDto;
import antlab.agent.AgentRegistration;
import antlab.agent.AgentRegistrationService;
import antlab.agent.AgentSeasonService;
import antlab.agent.AgentTurnViewDto;
import antlab.agent.AgentTurnViewService;
import antlab.agent.RegisterAgentRequest;
import antlab.agent.StartAgentSeasonRequest;
import antlab.agent.decision.AgentDecisionService;
import antlab.agent.decision.AgentTurnAdvanceService;
import antlab.agent.decision.CallsRequest;
import antlab.agent.decision.DecisionRequest;
import antlab.agent.decision.DecisionResultDto;
import antlab.season.AdvanceTurnRequest;
import antlab.season.TurnResultDto;
import jakarta.validation.constraints.Min;
import antlab.season.SeasonDto;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations of the agent runner. The contract is {@code docs/api/agent-runner.yaml}; the controller is hidden from the
 * public API description. Who may call, and which agent a path acts for, is decided by the runner security chain.
 */
@Hidden
@RestController
public class AgentRunnerController {

    static final String BASE = "/internal/agent-runner/v1";

    private final AgentRegistrationService registrations;
    private final AgentSeasonService seasons;
    private final AgentTurnViewService turnViews;
    private final AgentDecisionService decisions;
    private final AgentTurnAdvanceService advances;

    public AgentRunnerController(AgentRegistrationService registrations, AgentSeasonService seasons,
            AgentTurnViewService turnViews, AgentDecisionService decisions, AgentTurnAdvanceService advances) {
        this.registrations = registrations;
        this.seasons = seasons;
        this.turnViews = turnViews;
        this.decisions = decisions;
        this.advances = advances;
    }

    @PostMapping(value = BASE + "/agents", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentDto> register(@Valid @RequestBody RegisterAgentRequest request) {
        AgentRegistration result = registrations.register(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.agent());
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SeasonDto> startSeason(@PathVariable long agentId,
            @Valid @RequestBody StartAgentSeasonRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(seasons.start(agentId, request));
    }

    @GetMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/turn-view",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentTurnViewDto turnView(@PathVariable long agentId, @PathVariable long seasonId) {
        return turnViews.get(agentId, seasonId);
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/turns/{turnNo}/calls",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> flushCalls(@PathVariable long agentId, @PathVariable long seasonId,
            @PathVariable @Min(0) int turnNo, @Valid @RequestBody CallsRequest request) {
        decisions.flush(agentId, seasonId, turnNo, request.calls());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/turns/{turnNo}/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public DecisionResultDto submitDecision(@PathVariable long agentId, @PathVariable long seasonId,
            @PathVariable @Min(0) int turnNo, @Valid @RequestBody DecisionRequest request) {
        return decisions.submit(agentId, seasonId, turnNo, request);
    }

    @GetMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/turns/{turnNo}/decision",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DecisionResultDto readDecision(@PathVariable long agentId, @PathVariable long seasonId,
            @PathVariable @Min(0) int turnNo) {
        return decisions.read(agentId, seasonId, turnNo);
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/turns",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TurnResultDto> advance(@PathVariable long agentId, @PathVariable long seasonId,
            @Valid @RequestBody AdvanceTurnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(advances.advance(agentId, seasonId, request.expectedTurnNo()));
    }
}
