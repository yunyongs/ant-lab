package antlab.api.agentrunner;

import antlab.agent.AgentDto;
import antlab.agent.AgentRegistration;
import antlab.agent.AgentRegistrationService;
import antlab.agent.AgentSeasonService;
import antlab.agent.RegisterAgentRequest;
import antlab.agent.StartAgentSeasonRequest;
import antlab.season.SeasonDto;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    public AgentRunnerController(AgentRegistrationService registrations, AgentSeasonService seasons) {
        this.registrations = registrations;
        this.seasons = seasons;
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
}
