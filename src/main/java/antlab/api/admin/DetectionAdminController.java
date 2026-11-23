package antlab.api.admin;

import antlab.agent.detection.DetectionImportDto;
import antlab.agent.detection.DetectionImportService;
import antlab.api.error.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Administrator imports. The URL rule of {@code /api/v1/admin/**} requires the role ADMIN (SecurityConfig). */
@RestController
@Tag(name = "admin")
public class DetectionAdminController {

    private static final String PROBLEM = "application/problem+json";

    private final DetectionImportService imports;

    public DetectionAdminController(DetectionImportService imports) {
        this.imports = imports;
    }

    @PostMapping(value = "/api/v1/admin/detection-summaries", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "importDetectionSummary", summary = "Import a detection summary / 탐지 요약 들여오기",
            description = "Stores one detection-summary/1 document (docs/api/detection-summary-1.schema.json): one model's "
                    + "detection test results per window length, written as one detection_test row per window with its five "
                    + "detection_metric rows. Administrators only. The document is checked against the schema and then the "
                    + "rules the schema cannot say (runAt a real instant and not in the future, hits at most the metric's "
                    + "units, a metric's units at most the window's units); a refusal lists JSON pointers and keywords in "
                    + "errors and never echoes a value. A summary equal to the one already stored under the same "
                    + "sourceSha256 answers 200 and writes nothing; a different one answers 409. Detection is report only "
                    + "and changes no score, rank or season.\n"
                    + "\n"
                    + "detection-summary/1 문서 하나(docs/api/detection-summary-1.schema.json)를 저장한다: 모델 하나의 창 길이별 탐지 "
                    + "시험 결과를 창마다 detection_test 행 하나와 그 아래 detection_metric 행 다섯으로 쓴다. 관리자만 쓴다. 문서는 스키마로 "
                    + "검사한 뒤 스키마가 말하지 못하는 규칙(runAt은 실제 시각이고 미래가 아님, hits는 지표의 단위 수 이하, 지표의 단위 수는 "
                    + "창의 단위 수 이하)으로 검사하며, 거절하면 errors에 JSON 포인터와 키워드만 싣고 값은 되풀이하지 않는다. 같은 "
                    + "sourceSha256으로 이미 저장된 요약과 같으면 200으로 답하고 아무것도 쓰지 않으며, 다르면 409다. 탐지는 보고 전용이라 "
                    + "점수·순위·시즌을 바꾸지 않는다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "A detection-summary/1 document / "
            + "detection-summary/1 문서",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")))
    @ApiResponse(responseCode = "200", description = "The same summary was already stored; nothing was written / 같은 "
            + "요약이 이미 저장되어 있어 아무것도 쓰지 않았다")
    @ApiResponse(responseCode = "201", description = "The summary was stored / 요약을 저장했다")
    @ApiResponse(responseCode = "400", description = "The document breaks the schema or a rule after it (errors lists "
            + "pointers and keywords) — INVALID_ARGUMENT / 문서가 스키마나 그 뒤의 규칙을 어겼다(errors에 포인터와 키워드) — "
            + "INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not an administrator — FORBIDDEN / 호출자가 관리자가 "
            + "아니다 — FORBIDDEN",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "Another summary is already stored under the same sourceSha256 — "
            + "DETECTION_CONFLICT / 같은 sourceSha256으로 다른 요약이 이미 저장되어 있다 — DETECTION_CONFLICT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<DetectionImportDto> importSummary(@RequestBody JsonNode document) {
        DetectionImportService.Outcome outcome = imports.importSummary(document);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new DetectionImportDto(outcome.created(), outcome.windows()));
    }
}
