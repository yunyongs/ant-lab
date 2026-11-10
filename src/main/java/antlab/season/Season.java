package antlab.season;

import antlab.common.persistence.AuditedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 시즌 — 가변 엔티티. 상태 변경은 이름 있는 메서드만 한다(setter 없음).
 * 계산·업무 거절은 서비스 몫이고, 여기서는 DB CHECK 와 같은 불변식만 두 번째 그물로 지킨다.
 * 연관은 만들지 않고 회원은 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Table(name = "season")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Season extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 11, nullable = false)
    private SeasonStatus status;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "path_length", nullable = false)
    private int pathLength;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "current_turn_no", nullable = false)
    private int currentTurnNo;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "abandoned_turn_no", nullable = true)
    private Integer abandonedTurnNo;

    @Column(name = "seed", nullable = false)
    private long seed;

    @Column(name = "engine_version", length = 20, nullable = false)
    private String engineVersion;

    @Column(name = "data_snapshot_date", nullable = false)
    private LocalDate dataSnapshotDate;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "initial_cash", precision = 19, scale = 4, nullable = false)
    private BigDecimal initialCash;

    @Column(name = "fee_rate_kr", precision = 12, scale = 8, nullable = false)
    private BigDecimal feeRateKr;

    @Column(name = "fee_rate_us", precision = 12, scale = 8, nullable = false)
    private BigDecimal feeRateUs;

    @Column(name = "fx_spread_rate", precision = 12, scale = 8, nullable = false)
    private BigDecimal fxSpreadRate;

    @Column(name = "risk_limit", precision = 12, scale = 8, nullable = false)
    private BigDecimal riskLimit;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "goalkeeper", column = @Column(name = "start_goalkeeper", nullable = false)),
            @AttributeOverride(name = "defender", column = @Column(name = "start_defender", nullable = false)),
            @AttributeOverride(name = "midfielder", column = @Column(name = "start_midfielder", nullable = false)),
            @AttributeOverride(name = "forward", column = @Column(name = "start_forward", nullable = false))})
    private Formation startFormation;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "goalkeeper", column = @Column(name = "current_goalkeeper", nullable = false)),
            @AttributeOverride(name = "defender", column = @Column(name = "current_defender", nullable = false)),
            @AttributeOverride(name = "midfielder", column = @Column(name = "current_midfielder", nullable = false)),
            @AttributeOverride(name = "forward", column = @Column(name = "current_forward", nullable = false))})
    private Formation currentFormation;

    /** 낙관적 락 버전. 래퍼 형이라 저장 전에는 null 이다(새 엔티티 판정에 쓰인다). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 새 시즌: 진행 중, 0턴, 현재 포메이션 = 시작 포메이션. */
    public Season(Long memberId, int pathLength, long seed, String engineVersion, LocalDate dataSnapshotDate,
            LocalDate baseDate, BigDecimal initialCash, BigDecimal feeRateKr, BigDecimal feeRateUs,
            BigDecimal fxSpreadRate, BigDecimal riskLimit, Formation startFormation) {
        if (pathLength <= 0) {
            throw new IllegalArgumentException("경로 길이는 1 이상이어야 한다: " + pathLength);
        }
        this.memberId = memberId;
        this.status = SeasonStatus.IN_PROGRESS;
        this.pathLength = pathLength;
        this.currentTurnNo = 0;
        this.seed = seed;
        this.engineVersion = engineVersion;
        this.dataSnapshotDate = dataSnapshotDate;
        this.baseDate = baseDate;
        this.initialCash = initialCash;
        this.feeRateKr = feeRateKr;
        this.feeRateUs = feeRateUs;
        this.fxSpreadRate = fxSpreadRate;
        this.riskLimit = riskLimit;
        this.startFormation = startFormation;
        this.currentFormation = startFormation;
    }

    /** 전술 변경: 현재 포메이션을 바꾼다. 진행 중에만, 이전과 달라야 한다(ck_formation_change_changed). */
    public void changeFormation(Formation after) {
        requireInProgress();
        if (after.equals(currentFormation)) {
            throw new IllegalStateException("현재 포메이션과 같은 값으로는 바꿀 수 없다");
        }
        this.currentFormation = after;
    }

    /** 턴을 하나 진행한다. 마지막 턴에 닿으면 끝난 시즌이 된다(ck_season_finished_turn). */
    public void advanceTurn() {
        requireInProgress();
        if (currentTurnNo >= pathLength) {
            throw new IllegalStateException("이미 마지막 턴이다: " + currentTurnNo);
        }
        this.currentTurnNo += 1;
        if (currentTurnNo == pathLength) {
            this.status = SeasonStatus.FINISHED;
        }
    }

    private void requireInProgress() {
        if (status != SeasonStatus.IN_PROGRESS) {
            throw new IllegalStateException("진행 중인 시즌이 아니다: " + status);
        }
    }
}
