package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.auth.RefreshToken;
import antlab.auth.RevokeReason;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.member.MemberRole;
import antlab.support.JpaMappingTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mapping of RefreshToken and Member.role. Tests run in a transaction and roll back. */
@JpaMappingTest
class RefreshTokenMappingTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    EntityManager em;
    @Autowired
    EntityManagerFactory emf;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;

    private Member member() {
        return members.save(new Member("map-" + SEQ.incrementAndGet() + "@example.invalid", "시험", "x"));
    }

    private static String hash() {
        return String.format("%064x", System.nanoTime() + SEQ.incrementAndGet());
    }

    private static Instant later() {
        return Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void 저장하고_다시_읽으면_모든_필드가_같다() {
        Member m = member();
        UUID family = UUID.randomUUID();
        Instant expires = later();
        RefreshToken token = new RefreshToken(m.getId(), family, null, hash(), expires);
        em.persist(token);
        em.flush();
        em.clear();

        RefreshToken loaded = em.find(RefreshToken.class, token.getId());

        assertThat(loaded.getMemberId()).isEqualTo(m.getId());
        assertThat(loaded.getFamilyId()).isEqualTo(family);
        assertThat(loaded.getParentId()).isNull();
        assertThat(loaded.getTokenHash()).isEqualTo(token.getTokenHash());
        assertThat(loaded.getExpiresAt()).isEqualTo(expires);
        assertThat(loaded.getRevokedAt()).isNull();
        assertThat(loaded.getRevokedReason()).isNull();
        assertThat(loaded.getCreatedAt()).isEqualTo(loaded.getUpdatedAt());
    }

    @Test
    void 폐기하면_UPDATE_한_번이고_폐기_열과_수정일시만_바뀐다() {
        Member m = member();
        RefreshToken token = new RefreshToken(m.getId(), UUID.randomUUID(), null, hash(), later());
        em.persist(token);
        em.flush();
        em.clear();
        Instant createdBefore = jdbc.queryForObject(
                "SELECT created_at FROM refresh_token WHERE id = ?", java.sql.Timestamp.class, token.getId())
                .toInstant();

        RefreshToken loaded = em.find(RefreshToken.class, token.getId());
        Instant updatedBefore = loaded.getUpdatedAt();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        loaded.revoke(RevokeReason.LOGOUT, at);
        em.flush();
        assertThat(stats.getEntityUpdateCount()).isEqualTo(1);
        em.clear();

        RefreshToken after = em.find(RefreshToken.class, token.getId());
        assertThat(after.getRevokedAt()).isEqualTo(at);
        assertThat(after.getRevokedReason()).isEqualTo(RevokeReason.LOGOUT);
        assertThat(after.getCreatedAt()).isEqualTo(createdBefore);
        assertThat(after.getUpdatedAt()).isAfter(updatedBefore);
        assertThat(after.getMemberId()).isEqualTo(token.getMemberId());
        assertThat(after.getFamilyId()).isEqualTo(token.getFamilyId());
        assertThat(after.getParentId()).isNull();
        assertThat(after.getTokenHash()).isEqualTo(token.getTokenHash());
        assertThat(after.getExpiresAt()).isEqualTo(token.getExpiresAt());
        assertThatThrownBy(() -> after.revoke(RevokeReason.ROTATED, at)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 폐기에_null_인자를_주면_거부하고_토큰은_살아_있다() {
        Member m = member();
        RefreshToken token = new RefreshToken(m.getId(), UUID.randomUUID(), null, hash(), later());
        Instant at = Instant.now();

        assertThatThrownBy(() -> token.revoke(null, at)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> token.revoke(RevokeReason.LOGOUT, null)).isInstanceOf(NullPointerException.class);
        assertThat(token.getRevokedAt()).isNull();
        assertThat(token.getRevokedReason()).isNull();
    }

    /**
     * With IDENTITY keys persist() inserts at once while the revoke UPDATE waits for flush, so a child persisted right
     * after revoke() meets a still-live parent and uq_refresh_token_family_live rejects it.
     */
    @Test
    void 폐기_뒤에_flush_없이_자식을_넣으면_묶음당_하나_인덱스가_막는다() {
        Member m = member();
        UUID family = UUID.randomUUID();
        RefreshToken root = new RefreshToken(m.getId(), family, null, hash(), later());
        em.persist(root);
        em.flush();

        root.revoke(RevokeReason.ROTATED, Instant.now());

        assertThatThrownBy(() -> em.persist(new RefreshToken(m.getId(), family, root.getId(), hash(), later())))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().hasMessageContaining("uq_refresh_token_family_live");
    }

    @Test
    void 폐기_뒤에_flush를_끼우면_자식이_들어간다() {
        Member m = member();
        UUID family = UUID.randomUUID();
        RefreshToken root = new RefreshToken(m.getId(), family, null, hash(), later());
        em.persist(root);
        em.flush();

        root.revoke(RevokeReason.ROTATED, Instant.now());
        em.flush();
        RefreshToken child = new RefreshToken(m.getId(), family, root.getId(), hash(), later());
        em.persist(child);
        em.flush();

        assertThat(child.getId()).isGreaterThan(root.getId());
    }

    @Test
    void 새_회원의_역할은_USER이고_SQL로_올린_ADMIN도_읽힌다() {
        Member m = member();
        em.flush();
        em.clear();
        assertThat(em.find(Member.class, m.getId()).getRole()).isEqualTo(MemberRole.USER);

        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", m.getId());
        em.clear();

        assertThat(em.find(Member.class, m.getId()).getRole()).isEqualTo(MemberRole.ADMIN);
    }
}
