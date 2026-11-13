package antlab.api.season;

import antlab.api.error.ApiProblem;
import antlab.common.error.DomainException;
import antlab.observability.GameMetrics;
import antlab.season.SeasonDto;
import antlab.season.SeasonListDto;
import antlab.season.SeasonQueryService;
import antlab.season.SeasonStartService;
import antlab.season.StartSeasonRequest;
import antlab.season.TurnPricesDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = "season")
public class SeasonController {

    private static final String PROBLEM = "application/problem+json";

    private final SeasonStartService starts;
    private final SeasonQueryService queries;
    private final GameMetrics metrics;

    public SeasonController(SeasonStartService starts, SeasonQueryService queries, GameMetrics metrics) {
        this.starts = starts;
        this.queries = queries;
        this.metrics = metrics;
    }

    @PostMapping(value = "/api/v1/seasons", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "startSeason", summary = "Start a season / 시즌 시작",
            description = "Starts a new season from a risk limit, a formation and a path length. Initial capital "
                    + "and rates come from the server configuration. The season, path, cash account, deposit and "
                    + "turn-0 prices are created in one transaction.\n"
                    + "\n"
                    + "위험 선언·포메이션·경로 길이로 새 시즌을 시작한다. 초기 자금·요율은 서버 설정이 정한다. 시즌·경로·현금 계좌·입금·0턴 가격이 한 트랜잭션으로 만들어진다.")
    @ApiResponse(responseCode = "201", description = "The season that was started / 시작한 시즌",
            headers = @Header(name = "Location", description = "Address of the new season / 새 시즌의 주소",
                    schema = @Schema(type = "string", format = "uri")))
    @ApiResponse(responseCode = "400", description = "The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — "
            + "INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "A season cannot be started now — POOL_NOT_LOADED, POOL_NOT_JUDGED, SEASON_LIMIT (3 in "
                    + "progress)\n"
                    + "\n"
                    + "지금은 시작할 수 없다 — POOL_NOT_LOADED, POOL_NOT_JUDGED, SEASON_LIMIT(진행 중 3개)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422", description = "A path of the requested length cannot be built — "
            + "PATH_TOO_LONG / 요청한 길이의 경로를 만들 수 없다 — PATH_TOO_LONG",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The current member is unknown — MEMBER_UNAVAILABLE "
            + "(production, before sign-in) / 현재 회원을 알 수 없다 — MEMBER_UNAVAILABLE(운영, 인증 전)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<SeasonDto> start(@Valid @RequestBody StartSeasonRequest request) {
        SeasonDto season;
        try {
            season = starts.start(request);
        } catch (DomainException rejected) {
            metrics.seasonRejected(rejected.getCode());
            throw rejected;
        }
        metrics.seasonStarted();
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(season.id()).toUri();
        return ResponseEntity.created(location).body(season);
    }

    @GetMapping(value = "/api/v1/seasons", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSeasons", summary = "My seasons / 내 시즌 목록",
            description = "Returns all seasons of the current member, newest season number first. / 현재 회원의 시즌 "
                    + "전부를 시즌 번호 내림차순으로 준다.")
    @ApiResponse(responseCode = "200", description = "Season list / 시즌 목록")
    @ApiResponse(responseCode = "503", description = "The current member is unknown — MEMBER_UNAVAILABLE / 현재 "
            + "회원을 알 수 없다 — MEMBER_UNAVAILABLE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public SeasonListDto list() {
        return queries.list();
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSeason", summary = "One season / 시즌 한 개",
            description = "One season of the current member. 404 if it does not exist or belongs to another "
                    + "member. / 현재 회원의 시즌 한 개. 없거나 다른 회원의 시즌이면 404.")
    @ApiResponse(responseCode = "200", description = "Season / 시즌")
    @ApiResponse(responseCode = "400", description = "The season number is not a number — INVALID_ARGUMENT / 시즌 "
            + "번호가 숫자가 아니다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The current member is unknown — MEMBER_UNAVAILABLE / 현재 "
            + "회원을 알 수 없다 — MEMBER_UNAVAILABLE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public SeasonDto get(@PathVariable Long seasonId) {
        return queries.get(seasonId);
    }

    @GetMapping(value = "/api/v1/seasons/{seasonId}/prices", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getTurnPrices", summary = "Turn prices / 턴 가격",
            description = "Returns the prices of the whole pool for one turn, ordered by asset number. If "
                    + "turnNo is omitted, returns prices for the current turn.\n"
                    + "\n"
                    + "한 턴의 풀 전체 가격을 종목 번호 순으로 준다. turnNo 를 생략하면 현재 턴.")
    @ApiResponse(responseCode = "200", description = "Prices of that turn / 그 턴의 가격")
    @ApiResponse(responseCode = "400", description = "turnNo or the season number is wrong — INVALID_ARGUMENT / "
            + "turnNo 또는 시즌 번호가 틀렸다 — INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "That turn has not been played yet — TURN_NOT_REACHED / 아직 "
            + "진행하지 않은 턴이다 — TURN_NOT_REACHED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The current member is unknown — MEMBER_UNAVAILABLE / 현재 "
            + "회원을 알 수 없다 — MEMBER_UNAVAILABLE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public TurnPricesDto prices(@PathVariable Long seasonId,
            @RequestParam(required = false) @Min(0) Integer turnNo) {
        return queries.prices(seasonId, turnNo);
    }
}
