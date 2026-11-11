package antlab.season;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전술 변경 — 한 요청 = 한 트랜잭션(SPRING_JPA §5.4). 변경 이력(formation_change)과 시즌의 현재 포메이션을 함께 바꾼다.
 *
 * <p>순서: 잠금 전 입력 검사(사유) → 시즌 잠금(회원 조건 포함, 첫 시즌 조회) → 진행 중·현재 턴·0턴 → 현재와 같은 값 거절 →
 * 사슬 확인(현재 = 마지막 변경의 이후 값, 없으면 시작 값) → 이벤트 시각 확정 → 이력 INSERT, 시즌 현재 포메이션 갱신.
 * 거절은 모두 쓰기 앞에 있고 예외는 unchecked 라 어느 단계에서든 전부 되돌아간다.
 *
 * <p>계좌·보유·원장은 건드리지 않으므로 계좌를 잠그지 않는다. 락은 season 하나라 주문·턴 전환과 같은 순서(season → cash_account)의
 * 앞부분이다. 요청마다 대사를 돌리거나 기록하지 않는다 — 대사는 턴 전환의 몫이다.
 * 같은 턴 안의 주문 연결은 주문이 INSERT 때 이 서비스가 남긴 마지막 변경 번호를 읽어 한다(주문을 사후에 고치지 않는다).
 *
 * <p>0턴(팀 구성 중)의 전술 변경은 허용하지 않는다(소유자 결정 2026-11-11; SQL formation_change 의 tr_open 과 같다).
 * 0턴 매수·매도는 별개로 허용된다.
 */
@Service
public class FormationChangeService {

    /** 사유 길이 상한 — PostgreSQL {@code VARCHAR(200)} 은 글자(유니코드 코드 포인트) 수로 센다. */
    private static final int REASON_MAX_CODE_POINTS = 200;

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final FormationChangeRepository formationChanges;
    private final SeasonEventTime eventTime;

    public FormationChangeService(CurrentMember currentMember, SeasonRepository seasons,
            FormationChangeRepository formationChanges, SeasonEventTime eventTime) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.formationChanges = formationChanges;
        this.eventTime = eventTime;
    }

    @Transactional
    public FormationChangeDto change(long seasonId, ChangeFormationRequest request) {
        // 0. 잠금 전 입력 검사 — 사유 원문은 바꾸지 않는다(trim 없음). 거부한 값은 메시지에 싣지 않는다.
        String reason = request.reason();
        if (reason != null && (reason.codePointCount(0, reason.length()) > REASON_MAX_CODE_POINTS
                || reason.indexOf('\u0000') >= 0)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "사유가 너무 길거나 쓸 수 없는 문자가 있다");
        }
        Formation after = request.formation().toFormation();

        // 1. 시즌 — 소유 확인과 잠금을 한 조회로(첫 시즌 조회가 곧 잠금 조회). 회원은 만들지도 잠그지도 않는다.
        long memberId = currentMember.existingId().orElseThrow(FormationChangeService::seasonNotFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId)
                .orElseThrow(FormationChangeService::seasonNotFound);
        if (season.getStatus() != SeasonStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "진행 중인 시즌이 아니다");
        }
        int turnNo = season.getCurrentTurnNo();
        if (turnNo != request.expectedTurnNo()) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "시즌의 현재 턴과 변경이 본 턴이 다르다");
        }
        if (turnNo == 0) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "팀 구성 중인 0턴에는 전술을 바꿀 수 없다");
        }

        // 2. 현재와 같은 값은 변경이 아니다(ck_formation_change_changed 와 같다).
        Formation before = season.getCurrentFormation();
        if (before.equals(after)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "현재 포메이션과 같아 바꿀 수 없다");
        }

        // 3. 사슬 — 저장된 현재 포메이션이 마지막 변경의 이후 값(없으면 시작 값)과 같아야 한다. 과거 전체 사슬은 대사 규칙 15 가 본다.
        Formation expected = formationChanges.findLastBySeasonId(seasonId).map(FormationChange::getAfter)
                .orElse(season.getStartFormation());
        if (!expected.equals(before)) {
            throw new DomainException(ErrorCode.INVALID_FORMATION, "현재 포메이션이 변경 이력과 맞지 않는다");
        }

        // 4~5. 시각 확정(잠금 뒤) → 쓰기. 확정한 시각을 그대로 저장한다.
        Instant at = eventTime.next(seasonId, turnNo);
        FormationChange saved = formationChanges.save(new FormationChange(season, turnNo, before, after, reason, at));
        season.changeFormation(after);
        // 시즌 UPDATE 는 커밋 때 flush 된다. 거기서 실패하면 위 INSERT 도 함께 되돌아간다.
        return FormationChangeDto.from(saved);
    }

    private static DomainException seasonNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }
}
