package antlab.agent;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * Append-only: one released version of a persona and the hash of its file, written the first time a season starts with
 * it. The key is assigned, so the entity implements {@link Persistable}.
 */
@Entity
@Immutable
@IdClass(PersonaVersionId.class)
@Table(name = "persona_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonaVersion extends CreatedEntity implements Persistable<PersonaVersionId> {

    @Id
    @Column(name = "persona_key", length = 64, nullable = false)
    private String personaKey;

    @Id
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "display_name", length = 50, nullable = false)
    private String displayName;

    @Transient
    private boolean fresh = true;

    public PersonaVersion(String personaKey, int version, String sha256, String displayName) {
        this.personaKey = personaKey;
        this.version = version;
        this.sha256 = sha256;
        this.displayName = displayName;
    }

    @Override
    public PersonaVersionId getId() {
        return new PersonaVersionId(personaKey, version);
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.fresh = false;
    }
}
