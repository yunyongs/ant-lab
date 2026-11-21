package antlab.agent;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** PersonaVersion append-only repository: insert and the lookups of the pin check. */
public interface PersonaVersionRepository extends AppendOnlyRepository<PersonaVersion, PersonaVersionId> {

    Optional<PersonaVersion> findFirstByPersonaKeyOrderByVersionDesc(String personaKey);

    boolean existsByPersonaKeyAndSha256(String personaKey, String sha256);

    /** The newest recorded version of each of the given persona keys, in one query. */
    @Query("""
            select new antlab.agent.AgentLatestPersonaRow(v.personaKey, max(v.version))
            from PersonaVersion v where v.personaKey in :keys group by v.personaKey
            """)
    List<AgentLatestPersonaRow> findLatestVersions(@Param("keys") Collection<String> keys);
}
