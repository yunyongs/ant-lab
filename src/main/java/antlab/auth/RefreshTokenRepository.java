package antlab.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Refresh token store. The app never deletes rows; OPS cleanup does (erd §9). */
public interface RefreshTokenRepository extends Repository<RefreshToken, Long> {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findById(Long id);

    /** A scalar read on purpose: it must not put the presented row into the persistence context before the lock. */
    @Query("select t.familyId from RefreshToken t where t.tokenHash = :hash")
    Optional<UUID> findFamilyIdByTokenHash(@Param("hash") String hash);

    /** One root per family (uq_refresh_token_family_root); every write to a family locks it first (erd §9). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.familyId = :family and t.parentId is null")
    Optional<RefreshToken> findFamilyRootForUpdate(@Param("family") UUID family);

    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHash(@Param("hash") String hash);

    /** At most one live token per family (uq_refresh_token_family_live). */
    @Query("select t from RefreshToken t where t.familyId = :family and t.revokedAt is null")
    Optional<RefreshToken> findLiveInFamily(@Param("family") UUID family);
}
