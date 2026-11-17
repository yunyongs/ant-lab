package antlab.agent;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * Append-only: the settings an agent season was started with, written once. The key is the season id (assigned),
 * so the entity implements {@link Persistable}. The composite foreign key (season_id, member_id) makes the config
 * belong to a season of the same member, and the key to agent_profile makes that member an agent.
 */
@Entity
@Immutable
@Table(name = "season_agent_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonAgentConfig extends CreatedEntity implements Persistable<Long> {

    @Id
    @Column(name = "season_id")
    private Long seasonId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "persona_key", length = 64, nullable = false)
    private String personaKey;

    @Column(name = "persona_version", nullable = false)
    private int personaVersion;

    @Column(name = "persona_sha256", length = 64, nullable = false)
    private String personaSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_route", length = 10, nullable = false)
    private ModelRoute modelRoute;

    @Column(name = "model_requested", length = 200, nullable = false)
    private String modelRequested;

    @Column(name = "temperature", precision = 4, scale = 3, nullable = false)
    private BigDecimal temperature;

    @Column(name = "model_seed", nullable = true)
    private Long modelSeed;

    @Column(name = "max_tokens", nullable = false)
    private int maxTokens;

    @Column(name = "turn_timeout_ms", nullable = false)
    private int turnTimeoutMs;

    @Column(name = "prompt_template_version", length = 32, nullable = false)
    private String promptTemplateVersion;

    @Column(name = "runner_version", length = 32, nullable = false)
    private String runnerVersion;

    @Column(name = "memory_version", nullable = true)
    private Integer memoryVersion;

    @Column(name = "budget_calls", nullable = false)
    private int budgetCalls;

    @Column(name = "budget_usd", precision = 19, scale = 8, nullable = false)
    private BigDecimal budgetUsd;

    @Transient
    private boolean fresh = true;

    @SuppressWarnings("java:S107")
    public SeasonAgentConfig(long seasonId, long memberId, String personaKey, int personaVersion,
            String personaSha256, ModelRoute modelRoute, String modelRequested, BigDecimal temperature,
            Long modelSeed, int maxTokens, int turnTimeoutMs, String promptTemplateVersion, String runnerVersion,
            Integer memoryVersion, int budgetCalls, BigDecimal budgetUsd) {
        this.seasonId = seasonId;
        this.memberId = memberId;
        this.personaKey = personaKey;
        this.personaVersion = personaVersion;
        this.personaSha256 = personaSha256;
        this.modelRoute = modelRoute;
        this.modelRequested = modelRequested;
        this.temperature = temperature;
        this.modelSeed = modelSeed;
        this.maxTokens = maxTokens;
        this.turnTimeoutMs = turnTimeoutMs;
        this.promptTemplateVersion = promptTemplateVersion;
        this.runnerVersion = runnerVersion;
        this.memoryVersion = memoryVersion;
        this.budgetCalls = budgetCalls;
        this.budgetUsd = budgetUsd;
    }

    @Override
    public Long getId() {
        return seasonId;
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
