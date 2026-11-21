package antlab.api.agentrunner;

import antlab.agent.AgentDto;
import antlab.agent.AgentRegistration;
import antlab.agent.AgentRegistrationService;
import antlab.agent.AgentSeasonConfigDto;
import antlab.agent.AgentSeasonConfigQueryService;
import antlab.agent.AgentSeasonLifecycleService;
import antlab.agent.AgentSeasonService;
import antlab.agent.AgentTurnViewDto;
import antlab.agent.AgentTurnViewService;
import antlab.agent.RegisterAgentRequest;
import antlab.agent.StartAgentSeasonRequest;
import antlab.agent.VoidSeasonRequest;
import antlab.agent.decision.AgentDecisionService;
import antlab.agent.decision.AgentTurnAdvanceService;
import antlab.agent.decision.CallsRequest;
import antlab.agent.decision.DecisionRequest;
import antlab.agent.decision.DecisionResultDto;
import antlab.agent.memory.AgentMemoryStateDto;
import antlab.agent.memory.AgentMemoryVersionDto;
import antlab.agent.memory.MemoryReadService;
import antlab.agent.memory.MemorySubmitService;
import antlab.agent.memory.ReflectionViewDto;
import antlab.agent.memory.ReflectionViewService;
import antlab.agent.memory.SubmitSeasonMemoryRequest;
import antlab.agent.memory.SubmitSeasonMemoryResultDto;
import antlab.league.CreateLeagueRequest;
import antlab.league.LeagueDto;
import antlab.league.LeagueService;
import antlab.season.AbandonResultDto;
import antlab.season.AbandonSeasonRequest;
import antlab.season.AdvanceTurnRequest;
import antlab.season.SeasonAbandonService;
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
    private final AgentSeasonLifecycleService lifecycle;
    private final SeasonAbandonService abandons;
    private final AgentTurnViewService turnViews;
    private final AgentDecisionService decisions;
    private final AgentTurnAdvanceService advances;
    private final AgentSeasonConfigQueryService configQueries;
    private final LeagueService leagues;
    private final MemoryReadService memories;
    private final ReflectionViewService reflectionViews;
    private final MemorySubmitService memorySubmits;

    public AgentRunnerController(AgentRegistrationService registrations, AgentSeasonService seasons,
            AgentSeasonLifecycleService lifecycle, SeasonAbandonService abandons, AgentTurnViewService turnViews,
            AgentDecisionService decisions, AgentTurnAdvanceService advances,
            AgentSeasonConfigQueryService configQueries, LeagueService leagues, MemoryReadService memories,
            ReflectionViewService reflectionViews, MemorySubmitService memorySubmits) {
        this.registrations = registrations;
        this.seasons = seasons;
        this.lifecycle = lifecycle;
        this.abandons = abandons;
        this.turnViews = turnViews;
        this.decisions = decisions;
        this.advances = advances;
        this.configQueries = configQueries;
        this.leagues = leagues;
        this.memories = memories;
        this.reflectionViews = reflectionViews;
        this.memorySubmits = memorySubmits;
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

    @GetMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/config",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentSeasonConfigDto getConfig(@PathVariable long agentId, @PathVariable long seasonId) {
        return configQueries.get(agentId, seasonId);
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

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    public SeasonDto pause(@PathVariable long agentId, @PathVariable long seasonId) {
        return lifecycle.pause(agentId, seasonId);
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    public SeasonDto resume(@PathVariable long agentId, @PathVariable long seasonId) {
        return lifecycle.resume(agentId, seasonId);
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/void", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SeasonDto voidSeason(@PathVariable long agentId, @PathVariable long seasonId,
            @Valid @RequestBody VoidSeasonRequest request) {
        return lifecycle.voidSeason(agentId, seasonId, request);
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/abandon",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AbandonResultDto abandon(@PathVariable long agentId, @PathVariable long seasonId,
            @Valid @RequestBody AbandonSeasonRequest request) {
        lifecycle.requireActing(agentId);
        return abandons.abandon(seasonId, request.expectedTurnNo());
    }

    @GetMapping(value = BASE + "/agents/{agentId}/memory", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentMemoryStateDto getMemory(@PathVariable long agentId) {
        return memories.state(agentId);
    }

    @GetMapping(value = BASE + "/agents/{agentId}/memory/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentMemoryVersionDto getMemoryVersion(@PathVariable long agentId, @PathVariable @Min(1) int version) {
        return memories.version(agentId, version);
    }

    @GetMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/reflection-view",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ReflectionViewDto getReflectionView(@PathVariable long agentId, @PathVariable long seasonId) {
        return reflectionViews.get(agentId, seasonId);
    }

    @PostMapping(value = BASE + "/agents/{agentId}/seasons/{seasonId}/memory", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SubmitSeasonMemoryResultDto> submitSeasonMemory(@PathVariable long agentId,
            @PathVariable long seasonId, @Valid @RequestBody SubmitSeasonMemoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memorySubmits.submit(agentId, seasonId, request));
    }

    @PostMapping(value = BASE + "/leagues", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LeagueDto> createLeague(@Valid @RequestBody CreateLeagueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leagues.create(request));
    }

    @GetMapping(value = BASE + "/leagues/{leagueId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LeagueDto getLeague(@PathVariable long leagueId) {
        return leagues.get(leagueId);
    }

    @PostMapping(value = BASE + "/leagues/{leagueId}/close", produces = MediaType.APPLICATION_JSON_VALUE)
    public LeagueDto closeLeague(@PathVariable long leagueId) {
        return leagues.close(leagueId);
    }
}
