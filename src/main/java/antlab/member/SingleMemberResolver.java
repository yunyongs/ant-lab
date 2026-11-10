package antlab.member;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 고정 회원 한 명을 이메일로 찾는다. 첫 시즌 시작 때 없으면 만든다(비밀번호 칸 '!' = 로그인 불가 표식).
 * 이미 있으면 그 행을 잠가, 같은 회원의 동시 시작이 순서대로 지나가게 한다(진행 중 시즌 상한 검사용).
 */
@Component
@ConditionalOnProperty(prefix = "antlab.single-member", name = "enabled", havingValue = "true")
public class SingleMemberResolver implements CurrentMember {

    static final String NO_LOGIN_PASSWORD = "!";

    private final MemberRepository members;
    private final SingleMemberProperties properties;

    public SingleMemberResolver(MemberRepository members, SingleMemberProperties properties) {
        this.members = members;
        this.properties = properties;
    }

    @Override
    public Optional<Long> existingId() {
        return members.findByEmail(properties.email()).map(Member::getId);
    }

    @Override
    public long ensureId() {
        return members.findByEmailForUpdate(properties.email())
                .orElseGet(() -> members.save(new Member(properties.email(), properties.name(), NO_LOGIN_PASSWORD)))
                .getId();
    }
}
