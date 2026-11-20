package antlab.api.season;

import antlab.api.error.ApiProblem;
import antlab.league.SeasonEntryService;
import antlab.common.error.DomainException;
import antlab.observability.GameMetrics;
import antlab.season.AbandonResultDto;
import antlab.season.AbandonSeasonRequest;
import antlab.season.SeasonAbandonService;
import antlab.season.SeasonDto;
import antlab.season.SeasonListDto;
import antlab.season.SeasonQueryService;
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

    private final SeasonEntryService starts;
    private final SeasonQueryService queries;
    private final SeasonAbandonService abandons;
    private final GameMetrics metrics;

    public SeasonController(SeasonEntryService starts, SeasonQueryService queries, SeasonAbandonService abandons,
            GameMetrics metrics) {
        this.starts = starts;
        this.queries = queries;
        this.abandons = abandons;
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
    @ApiResponse(responseCode = "404", description = "The league does not exist or is not the caller's — NOT_FOUND / "
            + "리그가 없거나 호출자의 것이 아니다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "A season cannot be started now — POOL_NOT_LOADED, POOL_NOT_JUDGED, SEASON_LIMIT (3 in "
                    + "progress); with a leagueId: LEAGUE_CLOSED, LEAGUE_SEAT_TAKEN (one seat per member), LEAGUE_FULL, "
                    + "LEAGUE_STALE (the settings or the path no longer match the league's)\n"
                    + "\n"
                    + "지금은 시작할 수 없다 — POOL_NOT_LOADED, POOL_NOT_JUDGED, SEASON_LIMIT(진행 중 3개). leagueId 가 있으면 "
                    + "LEAGUE_CLOSED, LEAGUE_SEAT_TAKEN(회원당 한 자리), LEAGUE_FULL, LEAGUE_STALE(설정이나 경로가 리그와 달라졌다)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422", description = "A path of the requested length cannot be built — "
            + "PATH_TOO_LONG / 요청한 길이의 경로를 만들 수 없다 — PATH_TOO_LONG",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "503", description = "The path service is busy or not reachable; try again later — "
            + "ENGINE_UNAVAILABLE / 경로 서비스가 바쁘거나 연결되지 않는다. 잠시 뒤 다시 시도한다 — ENGINE_UNAVAILABLE",
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

    @PostMapping(value = "/api/v1/seasons/{seasonId}/abandon", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "abandonSeason", summary = "Abandon a season / 시즌 포기",
            description = "Marks the season as abandoned at the current turn, then runs the remaining turns with no "
                    + "trades through the normal turn path, one transaction per turn, until the season is finished "
                    + "or the per-request turn cap is reached. The same request continues an abandon that stopped "
                    + "midway: repeat it while the status is ABANDONING. A repeat on a finished abandoned season "
                    + "answers 200 and changes nothing. notStarted is true for a human member who abandoned before "
                    + "turn 3.\n"
                    + "\n"
                    + "시즌을 현재 턴에서 포기로 표시하고, 남은 턴을 거래 없이 보통의 턴 전환 경로로 턴마다 한 트랜잭션씩 시즌이 끝나거나 요청당 턴 상한에 닿을 때까지 "
                    + "진행한다. 중간에 멈춘 포기는 같은 요청으로 이어 간다 — 상태가 ABANDONING 이면 반복한다. 이미 끝난 포기 시즌에 반복하면 200 이고 아무것도 "
                    + "바꾸지 않는다. 사람 회원이 3턴 전에 포기하면 notStarted 가 true 다.")
    @ApiResponse(responseCode = "200", description = "State of the season after this request / 이번 요청 뒤 시즌의 상태")
    @ApiResponse(responseCode = "400", description = "The input is invalid — INVALID_ARGUMENT / 입력이 틀렸다 — "
            + "INVALID_ARGUMENT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "The season does not exist — NOT_FOUND / 시즌이 없다 — NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409",
            description = "The season cannot be abandoned now — TURN_MISMATCH (the season is in progress at another "
                    + "turn), SEASON_NOT_ACTIVE (finished without an abandon), SEASON_HALTED (an administrator "
                    + "resumes it first), RECONCILIATION_FAILED (an auto turn mismatched; the mark and the earlier "
                    + "auto turns stay, repeat to continue)\n"
                    + "\n"
                    + "지금은 포기할 수 없다 — TURN_MISMATCH(진행 중인 시즌의 현재 턴이 다르다), SEASON_NOT_ACTIVE(포기 없이 끝난 시즌), "
                    + "SEASON_HALTED(관리자가 먼저 재개), RECONCILIATION_FAILED(자동 턴의 대사가 어긋났다. 표시와 앞선 자동 턴은 남고 반복하면 이어 간다)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
    public AbandonResultDto abandon(@PathVariable Long seasonId, @Valid @RequestBody AbandonSeasonRequest request) {
        return abandons.abandon(seasonId, request.expectedTurnNo());
    }

    @GetMapping(value = "/api/v1/seasons", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSeasons", summary = "My seasons / 내 시즌 목록",
            description = "Returns all seasons of the current member, newest season number first. / 현재 회원의 시즌 "
                    + "전부를 시즌 번호 내림차순으로 준다.")
    @ApiResponse(responseCode = "200", description = "Season list / 시즌 목록")
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
    public TurnPricesDto prices(@PathVariable Long seasonId,
            @RequestParam(required = false) @Min(0) Integer turnNo) {
        return queries.prices(seasonId, turnNo);
    }
}
