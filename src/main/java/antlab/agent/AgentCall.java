package antlab.agent;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only: one model call (an attempt) of an agent season, with the request and reply text as sent and received.
 * The final row of a turn carries the outcome, the reject list and the request hash; at most one such row exists
 * per turn (uq_agent_call_final_per_turn). {@code parsed} and {@code rejects} hold JSON text.
 */
@Entity
@Immutable
@Table(name = "agent_call")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentCall extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_kind", length = 13, nullable = false)
    private AgentCallKind callKind;

    @Column(name = "ledger_call_id", nullable = true)
    private UUID ledgerCallId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 14, nullable = false)
    private AgentCallStatus status;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "http_status", nullable = true)
    private Integer httpStatus;

    @Column(name = "model_requested", length = 200, nullable = false)
    private String modelRequested;

    @Column(name = "model_actual", length = 200, nullable = true)
    private String modelActual;

    @Column(name = "provider", length = 100, nullable = true)
    private String provider;

    @Column(name = "request_text", length = 262144, nullable = false)
    private String requestText;

    @Column(name = "response_text", length = 262144, nullable = true)
    private String responseText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed", nullable = true)
    private String parsed;

    @Column(name = "tokens_in", nullable = true)
    private Integer tokensIn;

    @Column(name = "tokens_out", nullable = true)
    private Integer tokensOut;

    @Column(name = "cost_usd", precision = 19, scale = 8, nullable = true)
    private BigDecimal costUsd;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "is_final", nullable = false)
    private boolean finalCall;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 11, nullable = true)
    private DecisionOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rejects", nullable = true)
    private String rejects;

    @Column(name = "request_sha256", length = 64, nullable = true)
    private String requestSha256;

    /** A non-final attempt: no outcome, no reject list, no request hash. */
    @SuppressWarnings("java:S107")
    public AgentCall(long seasonId, int turnNo, int attemptNo, AgentCallKind callKind, UUID ledgerCallId,
            AgentCallStatus status, Integer httpStatus, String modelRequested, String modelActual, String provider,
            String requestText, String responseText, String parsed, Integer tokensIn, Integer tokensOut,
            BigDecimal costUsd, int latencyMs, DecisionOutcome outcome, String rejects, String requestSha256) {
        this.seasonId = seasonId;
        this.turnNo = turnNo;
        this.attemptNo = attemptNo;
        this.callKind = callKind;
        this.ledgerCallId = ledgerCallId;
        this.status = status;
        this.httpStatus = httpStatus;
        this.modelRequested = modelRequested;
        this.modelActual = modelActual;
        this.provider = provider;
        this.requestText = requestText;
        this.responseText = responseText;
        this.parsed = parsed;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.costUsd = costUsd;
        this.latencyMs = latencyMs;
        this.outcome = outcome;
        this.finalCall = outcome != null;
        this.rejects = rejects;
        this.requestSha256 = requestSha256;
    }
}
