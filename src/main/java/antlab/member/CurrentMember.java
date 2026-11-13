package antlab.member;

import java.util.Optional;

/**
 * The member who sent the request: the token's member, or the fixed member until SEC-05.
 * Reads use {@link #existingId()} (never creates a member); writes use {@link #ensureId()} (locks the member row;
 * the fixed member is created if missing).
 */
public interface CurrentMember {

    /** 읽기 전용 트랜잭션에서도 부를 수 있다. 회원이 아직 없으면 빈 값. */
    Optional<Long> existingId();

    /** 쓰기 트랜잭션 안에서만 부른다. 없으면 만들고, 있으면 그 행을 비관적 락으로 잠근다. */
    long ensureId();
}
