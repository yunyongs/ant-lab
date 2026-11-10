package antlab.member;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 고정 회원이 꺼진 설정(운영 문서 값)의 대체 구현 — 인증(SEC)이 들어오기 전에는 회원을 알 수 없어 503 이다. */
@Component
@ConditionalOnProperty(prefix = "antlab.single-member", name = "enabled", havingValue = "false")
public class UnavailableCurrentMember implements CurrentMember {

    @Override
    public Optional<Long> existingId() {
        throw unavailable();
    }

    @Override
    public long ensureId() {
        throw unavailable();
    }

    private static DomainException unavailable() {
        return new DomainException(ErrorCode.MEMBER_UNAVAILABLE, "현재 회원을 알 수 없다(인증 전)");
    }
}
