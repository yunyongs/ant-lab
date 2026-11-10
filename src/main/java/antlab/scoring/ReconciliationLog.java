package antlab.scoring;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 대사 기록 — 추가 전용. 시즌은 {@code Long} 번호로만 가리킨다(패키지가 다르다). 검사 규칙은 P0-32 가 붙인다. */
@Entity
@Immutable
@Table(name = "reconciliation_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationLog extends CreatedEntity {

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
    @Column(name = "check_code", length = 20, nullable = false)
    private CheckCode checkCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 8, nullable = false)
    private ReconciliationStatus status;

    @Column(name = "expected_value", precision = 19, scale = 4, nullable = true)
    private BigDecimal expectedValue;

    @Column(name = "actual_value", precision = 19, scale = 4, nullable = true)
    private BigDecimal actualValue;

    @Column(name = "diff_value", precision = 19, scale = 4, nullable = true)
    private BigDecimal diffValue;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", length = 11, nullable = true)
    private ReconciliationClassification classification;

    /** JSON 문자열 그대로(jsonb 열). 없으면 null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = true)
    private String detail;

    public ReconciliationLog(Long seasonId, int turnNo, int attemptNo, CheckCode checkCode,
            ReconciliationStatus status, BigDecimal expectedValue, BigDecimal actualValue, BigDecimal diffValue,
            int mismatchCount, ReconciliationClassification classification, String detail) {
        this.seasonId = seasonId;
        this.turnNo = turnNo;
        this.attemptNo = attemptNo;
        this.checkCode = checkCode;
        this.status = status;
        this.expectedValue = expectedValue;
        this.actualValue = actualValue;
        this.diffValue = diffValue;
        this.mismatchCount = mismatchCount;
        this.classification = classification;
        this.detail = detail;
    }
}
