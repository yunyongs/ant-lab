package antlab.agent;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins a persona version to one file hash. The runner sends (key, version, sha256, display name) at every season
 * start; the first start with a version records it, and every later start must agree with the record.
 */
@Service
public class PersonaVersionService {

    private final PersonaVersionRepository versions;
    private final EntityManager entityManager;

    public PersonaVersionService(PersonaVersionRepository versions, EntityManager entityManager) {
        this.versions = versions;
        this.entityManager = entityManager;
    }

    /**
     * Records the version when it is new, and refuses a start that disagrees with what is stored: another hash for the
     * version (or this hash under another version) is {@code PERSONA_VERSION_CONFLICT}, a version below the newest
     * stored one is {@code PERSONA_VERSION_STALE}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pin(String personaKey, int version, String sha256, String displayName) {
        // Two agents on one persona may start at once; the lock is per persona and released with the transaction.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(?1, 0))")
                .setParameter(1, "persona_version:" + personaKey).getSingleResult();
        PersonaVersion same = versions.findById(new PersonaVersionId(personaKey, version)).orElse(null);
        if (same != null && !same.getSha256().equals(sha256)) {
            throw new DomainException(ErrorCode.PERSONA_VERSION_CONFLICT,
                    "같은 페르소나 버전에 다른 파일 해시가 이미 기록되어 있다");
        }
        PersonaVersion newest = versions.findFirstByPersonaKeyOrderByVersionDesc(personaKey).orElse(null);
        if (newest != null && version < newest.getVersion()) {
            throw new DomainException(ErrorCode.PERSONA_VERSION_STALE, "더 새로운 페르소나 버전이 이미 기록되어 있다");
        }
        if (same != null) {
            return;
        }
        // A new version with bytes of an older one means the file changed back or the version was bumped for nothing.
        if (versions.existsByPersonaKeyAndSha256(personaKey, sha256)) {
            throw new DomainException(ErrorCode.PERSONA_VERSION_CONFLICT,
                    "이 파일 해시는 다른 페르소나 버전으로 이미 기록되어 있다");
        }
        versions.save(new PersonaVersion(personaKey, version, sha256, displayName));
    }
}
