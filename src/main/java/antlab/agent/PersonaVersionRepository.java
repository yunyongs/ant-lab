package antlab.agent;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Optional;

/** PersonaVersion append-only repository: insert and the lookups of the pin check. */
public interface PersonaVersionRepository extends AppendOnlyRepository<PersonaVersion, PersonaVersionId> {

    Optional<PersonaVersion> findFirstByPersonaKeyOrderByVersionDesc(String personaKey);

    boolean existsByPersonaKeyAndSha256(String personaKey, String sha256);
}
