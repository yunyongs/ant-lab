package antlab.season;

import antlab.account.Holding;
import antlab.account.HoldingRepository;
import antlab.asset.Position;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선언 포지션 단독 변경(내부 서비스) — SQL {@code lineup_change} 와 같은 동작이다. 공개 HTTP 경로는 만들지 않는다(소유자 결정: 내부만).
 *
 * <p>순서: 시즌 잠금(회원 조건 포함, 첫 시즌 조회) → 진행 중·현재 턴·0턴 거절 → 보유 양수 → 열린 구간 → 같은 포지션이면 기존 구간을
 * 그대로 돌려주고 새 행 없음 → 다르면 기존 구간을 현재 턴에 닫고 새 구간을 연다. 같은 턴에 열린 뒤 닫힌 길이 없는 구간도 그대로 둔다.
 * 전술 변경 이력·주문·비용은 만들지 않고 계좌도 잠그지 않는다 — 락은 season 하나(보유·구간은 시즌 락 아래에서만 바뀐다).
 * 0턴(팀 구성 중)에는 바꿀 수 없다(SQL 의 입구 검사와 같다).
 */
@Service
public class LineupChangeService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final HoldingRepository holdings;
    private final LineupRepository lineups;
    private final EntityManager entityManager;

    public LineupChangeService(CurrentMember currentMember, SeasonRepository seasons, HoldingRepository holdings,
            LineupRepository lineups, EntityManager entityManager) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.holdings = holdings;
        this.lineups = lineups;
        this.entityManager = entityManager;
    }

    /**
     * <p>새 포지션이 null 이면 NullPointerException 이다 — 공개 경로를 만들 때 400(INVALID_ARGUMENT)으로 바꾼다.
     * <p>시즌·보유를 먼저 읽은 트랜잭션 안에서 부르지 않는다 — 잠금 조회가 영속성 컨텍스트의 옛 엔티티를 돌려줄 수 있다(SPRING_JPA §5.2 ①).
     * @return 바꾼 뒤 열린 구간(같은 포지션이면 기존 구간)
     */
    @Transactional
    public Lineup change(long seasonId, int expectedTurnNo, long assetId, Position newPosition) {
        Objects.requireNonNull(newPosition, "newPosition");
        long memberId = currentMember.existingId().orElseThrow(LineupChangeService::seasonNotFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId)
                .orElseThrow(LineupChangeService::seasonNotFound);
        if (season.getStatus() != SeasonStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "진행 중인 시즌이 아니다");
        }
        int turnNo = season.getCurrentTurnNo();
        if (turnNo != expectedTurnNo) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "시즌의 현재 턴과 변경이 본 턴이 다르다");
        }
        if (turnNo == 0) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "팀 구성 중인 0턴에는 포지션을 바꿀 수 없다");
        }
        Holding holding = holdings.findBySeasonIdAndAssetId(seasonId, assetId).filter(h -> h.getQuantity() > 0)
                .orElseThrow(() -> new DomainException(ErrorCode.INSUFFICIENT_HOLDING, "보유 중인 종목이 아니다"));
        Lineup open = lineups.findOpenBySeasonIdAndAssetId(seasonId, assetId)
                .orElseThrow(() -> new DomainException(ErrorCode.LINEUP_MISMATCH, "보유와 라인업 구간이 맞지 않는다"));
        if (open.getDeclaredPosition() == newPosition) {
            return open;
        }
        open.close(turnNo);
        // 열린 구간은 종목당 하나만 둘 수 있다(부분 유일 인덱스) — 닫는 UPDATE 를 새 구간 INSERT 보다 먼저 내보낸다.
        entityManager.flush();
        Lineup opened = lineups.save(new Lineup(season, holding.getAssetId(), newPosition, turnNo));
        return opened;
    }

    private static DomainException seasonNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }
}
