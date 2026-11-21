package antlab.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** The signed-in member's agents, oldest agent first. No paging. */
@Schema(description = "The agents of the signed-in member (oldest first). No paging / 로그인한 회원의 에이전트(오래된 순). 쪽 나누기 없음")
public record AgentListDto(
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<AgentSummaryDto> items) {
}
