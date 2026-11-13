package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class RefreshTokenLockTest {

    @Autowired
    RefreshTokenRepository tokens;
    @Autowired
    MemberRepository members;
    @Autowired
    EntityManager entityManager;

    @Test
    void 묶음_뿌리_잠금은_for_no_key_update이고_뿌리_행만_읽는다() {
        Member member = members.save(new Member("lock@example.test", "locker", "{bcrypt}x"));
        UUID family = UUID.randomUUID();
        RefreshToken root = tokens.save(new RefreshToken(member.getId(), family, null, RefreshTokens.hash("a"),
                Instant.now().plusSeconds(3600)));
        root.revoke(RevokeReason.ROTATED, Instant.now().plusSeconds(1));
        entityManager.flush();
        tokens.save(new RefreshToken(member.getId(), family, root.getId(), RefreshTokens.hash("b"),
                Instant.now().plusSeconds(3600)));

        SqlRecorder.start();
        RefreshToken locked = tokens.findFamilyRootForUpdate(family).orElseThrow();
        List<String> sqls = SqlRecorder.stop();

        assertThat(locked.getId()).isEqualTo(root.getId());
        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0)).matches("(?s).* for no key update of \\w+");
    }
}
