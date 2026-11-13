package antlab.api.pool;

import antlab.api.error.ApiProblem;
import antlab.asset.PoolDto;
import antlab.asset.PoolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "pool")
public class PoolController {

    private final PoolService pools;

    public PoolController(PoolService pools) {
        this.pools = pools;
    }

    @GetMapping(value = "/api/v1/pool", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPool", summary = "Asset pool on the reference date / 기준일 종목 풀",
            description = "Returns the whole pool of the server-configured reference date, ordered by market and "
                    + "asset code. Each asset carries its system-judged position and the judgment basis "
                    + "(participation rates, volatility, maximum drawdown; KRW, monthly, 8 decimal places).\n"
                    + "\n"
                    + "서버 설정의 기준일 풀 전체를 시장·종목코드 순서로 준다. 시스템 판정 포지션과 판정 근거(참여율·변동성·최대 낙폭, 원화·월 단위·소수 8자리)를 함께 준다.")
    @ApiResponse(responseCode = "200", description = "Pool on the reference date / 기준일 풀")
    @ApiResponse(responseCode = "409",
            description = "The pool is not ready — POOL_NOT_LOADED (no rows), POOL_NOT_JUDGED (some assets have "
                    + "no judgment)\n"
                    + "\n"
                    + "풀이 준비되지 않았다 — POOL_NOT_LOADED(행 없음), POOL_NOT_JUDGED(판정 빈 종목 있음)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    public PoolDto pool() {
        return pools.pool();
    }
}
