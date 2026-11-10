package antlab.member;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 회원 저장소 — 넣기, 번호·이메일로 읽기. 이메일 비교는 uq_member_email 인덱스(lower)와 같은 뜻이다. */
public interface MemberRepository extends Repository<Member, Long> {

    Member save(Member member);

    Optional<Member> findById(Long id);

    @Query("select m from Member m where lower(m.email) = lower(:email)")
    Optional<Member> findByEmail(@Param("email") String email);

    /** 같은 조건으로 행을 잠근다(PostgreSQL 은 {@code FOR NO KEY UPDATE}). 락 순서는 member → season → cash_account. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where lower(m.email) = lower(:email)")
    Optional<Member> findByEmailForUpdate(@Param("email") String email);
}
