package antlab.auth;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Refresh token store. Rows are never deleted (erd §9); PR B adds the rotation queries. */
public interface RefreshTokenRepository extends Repository<RefreshToken, Long> {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findById(Long id);
}
