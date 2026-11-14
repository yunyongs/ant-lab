package antlab.member;

import java.util.Optional;

/**
 * The member who sent the request: the token's member. Reads use {@link #existingId()}; writes use
 * {@link #ensureId()}, which locks the member row. Neither creates a member; a missing member is an
 * {@code UNAUTHENTICATED} error.
 */
public interface CurrentMember {

    /** Callable in a read-only transaction. A missing member row is UNAUTHENTICATED. */
    Optional<Long> existingId();

    /** Call only inside a write transaction. Locks the row pessimistically; a missing row is UNAUTHENTICATED. */
    long ensureId();
}
