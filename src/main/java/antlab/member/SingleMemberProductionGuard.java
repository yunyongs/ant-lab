package antlab.member;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 운영(prod)에서 고정 회원이 켜져 있으면 기동을 거부한다 — 인증 없이 한 사람의 시즌이 열리는 것을 막는다. */
@Component
@Profile("prod")
public class SingleMemberProductionGuard {

    public SingleMemberProductionGuard(SingleMemberProperties properties) {
        if (properties.enabled()) {
            throw new IllegalStateException("운영(prod)에서는 antlab.single-member.enabled 를 켤 수 없다");
        }
    }
}
