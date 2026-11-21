package antlab.api.agent;

import antlab.agent.AgentListDto;
import antlab.agent.AgentListQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "agent")
public class AgentController {

    private final AgentListQuery agents;

    public AgentController(AgentListQuery agents) {
        this.agents = agents;
    }

    @GetMapping(value = "/api/v1/agents", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMyAgents", summary = "My agents / 내 에이전트",
            description = "Returns the agents the signed-in member owns, oldest first, each with raw season counts: "
                    + "startedSeasons (all), countedSeasons (FINISHED), voidSeasons (VOID), unfinishedAbandonSeasons "
                    + "(ABANDONING and not yet FINISHED, including a pause older than 7 days), inProgressSeasons, "
                    + "pausedSeasons (paused less than 7 days), haltedSeasons (stopped by a reconciliation mismatch) and failedTurnCount (turns whose final model call ended "
                    + "NO_RESPONSE or INVALID). It has no score, rank or minimum-season rule.\n"
                    + "\n"
                    + "로그인한 회원이 소유한 에이전트를 오래된 순으로 준다. 각 에이전트에 시즌 집계 값이 붙는다: startedSeasons(전체), "
                    + "countedSeasons(FINISHED), voidSeasons(VOID), unfinishedAbandonSeasons(ABANDONING 이고 아직 FINISHED 가 아닌 "
                    + "시즌, 7일 넘은 정지 포함), inProgressSeasons, pausedSeasons(정지 7일 미만), haltedSeasons(대사 불일치로 멈춤), failedTurnCount(최종 모델 호출이 "
                    + "NO_RESPONSE 또는 INVALID 로 끝난 턴 수). 점수·순위·최소 시즌 규칙은 없다.")
    @ApiResponse(responseCode = "200", description = "The agents / 에이전트 목록")
    public AgentListDto list() {
        return agents.list();
    }
}
