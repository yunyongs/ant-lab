package antlab.member;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 회원 저장소 — 넣기와 번호로 읽기. 이메일 조회는 인증 기능이 더한다. */
public interface MemberRepository extends Repository<Member, Long> {

    Member save(Member member);

    Optional<Member> findById(Long id);
}
