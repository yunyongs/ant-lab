package antlab.asset;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기준일 종목 풀 조회. 판정은 다시 계산하지 않고 적재된 열을 그대로 읽는다. */
@Service
public class PoolService {

    /** 시장·종목코드 문자열 코드 단위 오름차순(ASCII 라 바이트 순서와 같다). */
    private static final Comparator<PoolAssetDto> BY_MARKET_TICKER =
            Comparator.comparing((PoolAssetDto a) -> a.market().name()).thenComparing(PoolAssetDto::ticker);

    private final AssetPositionRepository positions;
    private final PoolProperties properties;

    public PoolService(AssetPositionRepository positions, PoolProperties properties) {
        this.positions = positions;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PoolDto pool() {
        List<PoolAssetDto> rows = positions.findPoolByBaseDate(properties.baseDate());
        if (rows.isEmpty()) {
            throw new DomainException(ErrorCode.POOL_NOT_LOADED,
                    "기준일 풀이 비어 있다. API DB 표본 적재 절차를 먼저 실행한다");
        }
        if (rows.stream().anyMatch(PoolAssetDto::unjudged)) {
            throw new DomainException(ErrorCode.POOL_NOT_JUDGED,
                    "기준일 풀에 판정이 비어 있는 종목이 있다. 포지션 계산을 먼저 실행한다");
        }
        return new PoolDto(rows.stream().sorted(BY_MARKET_TICKER).toList());
    }
}
