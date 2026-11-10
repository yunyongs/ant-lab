package antlab.member;

import java.util.Optional;

/**
 * 지금 요청을 보낸 회원. 인증(SEC)이 들어오기 전에는 고정 회원 한 명이다.
 * 조회는 {@link #existingId()}(회원을 만들지 않는다), 쓰기는 {@link #ensureId()}(없으면 만들고 행을 잠근다)를 쓴다.
 */
public interface CurrentMember {

    /** 읽기 전용 트랜잭션에서도 부를 수 있다. 회원이 아직 없으면 빈 값. */
    Optional<Long> existingId();

    /** 쓰기 트랜잭션 안에서만 부른다. 없으면 만들고, 있으면 그 행을 비관적 락으로 잠근다. */
    long ensureId();
}
