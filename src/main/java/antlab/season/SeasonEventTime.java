package antlab.season;

import antlab.account.OrderRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 한 시즌·턴의 이벤트(주문·전술 변경) 시각 순서 가드 — 시즌 잠금을 얻은 <b>뒤</b>에 부른다(erd 7.5 규칙 16).
 * 흐르는 {@link Clock} 에서 새 시각을 읽어 PostgreSQL 정밀도(마이크로초)로 자르고, 같은 시즌·턴의 앞선 주문·변경 중
 * 가장 늦은 시각보다 <b>엄격히</b> 클 때만 돌려준다. 같거나 역행하면 {@link ErrorCode#EVENT_TIME_CONFLICT} 다.
 * 시각을 늘리거나 번호로 동률을 가르거나 기다렸다 다시 시도하지 않는다.
 * 돌려준 시각은 호출한 쪽이 INSERT 에 <b>그대로</b> 저장해야 한다 — 검사한 값과 저장한 값이 같다는 것이 이 가드의 전부다.
 * 비교 범위는 같은 턴뿐이라 이전 턴의 늦은 시각이 다음 턴을 막지 않는다.
 */
@Component
public class SeasonEventTime {

    private final Clock clock;
    private final OrderRepository orders;
    private final FormationChangeRepository formationChanges;

    public SeasonEventTime(Clock clock, OrderRepository orders, FormationChangeRepository formationChanges) {
        this.clock = clock;
        this.orders = orders;
        this.formationChanges = formationChanges;
    }

    public Instant next(long seasonId, int turnNo) {
        Instant at = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Instant latest = latest(orders.findLatestCreatedAt(seasonId, turnNo).orElse(null),
                formationChanges.findLatestCreatedAt(seasonId, turnNo).orElse(null));
        if (latest != null && !at.isAfter(latest)) {
            throw new DomainException(ErrorCode.EVENT_TIME_CONFLICT, "같은 턴의 앞선 주문·전술 변경과 시각이 겹치거나 앞선다");
        }
        return at;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
