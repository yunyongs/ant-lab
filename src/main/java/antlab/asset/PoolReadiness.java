package antlab.asset;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.util.Collection;
import java.util.function.Predicate;

/**
 * 기준일 풀이 쓸 수 있는 상태인지 보는 한 곳. 풀 조회와 시즌 시작이 같은 문장으로 거절하도록 함께 쓴다.
 * 본다: 행이 있는가, 판정이 빈 종목이 없는가. (환율 전제·판정 창은 적재 단계의 검증이 이미 본다.)
 */
public final class PoolReadiness {

    private PoolReadiness() {
    }

    public static <T> void require(Collection<T> rows, Predicate<T> unjudged) {
        if (rows.isEmpty()) {
            throw new DomainException(ErrorCode.POOL_NOT_LOADED,
                    "기준일 풀이 비어 있다. API DB 표본 적재 절차를 먼저 실행한다");
        }
        if (rows.stream().anyMatch(unjudged)) {
            throw new DomainException(ErrorCode.POOL_NOT_JUDGED,
                    "기준일 풀에 판정이 비어 있는 종목이 있다. 포지션 계산을 먼저 실행한다");
        }
    }
}
