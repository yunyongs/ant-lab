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
import java.time.Duration;
import java.time.Instant;
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

    /** A pause not resumed within this window becomes an abandon (PLAN §4.1-7, §6.9). A rule, not a setting. */
    public static final Duration PAUSE_LIMIT = Duration.ofDays(7);

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

    /** Start of the current pause; set exactly while the status is PAUSED (ck_season_paused_at). */
    @Column(name = "paused_at", nullable = true)
    private Instant pausedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "void_reason", length = 20, nullable = true)
    private VoidReason voidReason;

    @Column(name = "void_note", length = 500, nullable = true)
    private String voidNote;

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

    /**
     * Marks the season as abandoned at the current turn: the remaining turns run without trades and the season ends
     * through the normal turn path. Only a season in progress can be abandoned.
     */
    public void abandon() {
        if (status != SeasonStatus.PAUSED) {
            requireInProgress();
        }
        this.abandonedTurnNo = currentTurnNo;
        this.pausedAt = null;
        this.status = SeasonStatus.ABANDONING;
    }

    /** Stops a season in progress at its current turn; the pause window starts at {@code now}. */
    public void pause(Instant now) {
        requireInProgress();
        this.pausedAt = now;
        this.status = SeasonStatus.PAUSED;
    }

    /** True when the season is paused and the pause window is over: exactly 7 days after {@code pausedAt} counts as over. */
    public boolean pauseExpired(Instant now) {
        return status == SeasonStatus.PAUSED && !now.isBefore(pausedAt.plus(PAUSE_LIMIT));
    }

    /**
     * What a read shows: a pause past its window is an abandon even when nothing has converted it yet (O-2). The
     * stored row is converted by the agent's next action; this only keeps a dormant agent's season from staying PAUSED.
     */
    public SeasonStatus effectiveStatus(Instant now) {
        return pauseExpired(now) ? SeasonStatus.ABANDONING : status;
    }

    /** The abandon turn a read shows: the paused turn for an overdue pause, as the conversion would store it. */
    public Integer effectiveAbandonedTurnNo(Instant now) {
        return pauseExpired(now) ? Integer.valueOf(currentTurnNo) : abandonedTurnNo;
    }

    /** Back to the same turn. Only a paused season resumes here; the caller checks {@link #pauseExpired} first. */
    public void continueAfterPause() {
        if (status != SeasonStatus.PAUSED) {
            throw new IllegalStateException("일시 정지된 시즌이 아니다: " + status);
        }
        this.pausedAt = null;
        this.status = SeasonStatus.IN_PROGRESS;
    }

    /**
     * Voids the season: terminal, the turn stays where it is. Any unfinished status can be voided (a finished season is
     * already scored); a repeat on a void season is the caller's to answer, not allowed here.
     */
    public void voidSeason(VoidReason reason, String note) {
        if (status == SeasonStatus.FINISHED || status == SeasonStatus.VOID) {
            throw new IllegalStateException("무효로 만들 수 없는 상태다: " + status);
        }
        this.voidReason = reason;
        this.voidNote = note;
        this.pausedAt = null;
        this.status = SeasonStatus.VOID;
    }

    /**
     * 턴을 하나 진행한다. 마지막 턴에 닿으면 끝난 시즌이 된다(ck_season_finished_turn).
     * An abandoning season advances too (auto turns); its abandon turn is kept.
     */
    public void advanceTurn() {
        if (status != SeasonStatus.IN_PROGRESS && status != SeasonStatus.ABANDONING) {
            throw new IllegalStateException("진행 중인 시즌이 아니다: " + status);
        }
        if (currentTurnNo >= pathLength) {
            throw new IllegalStateException("이미 마지막 턴이다: " + currentTurnNo);
        }
        this.currentTurnNo += 1;
        if (currentTurnNo == pathLength) {
            this.status = SeasonStatus.FINISHED;
        }
    }

    /** Stops the season after repeated reconciliation failures. Only a running season (in progress or abandoning) can halt. */
    public void halt() {
        if (status != SeasonStatus.IN_PROGRESS && status != SeasonStatus.ABANDONING) {
            throw new IllegalStateException("멈출 수 없는 상태다: " + status);
        }
        this.status = SeasonStatus.HALTED;
    }

    /**
     * Resumes a halted season: back to ABANDONING when an abandon turn is set, else IN_PROGRESS.
     *
     * @return true when the season was halted and is running again; false (nothing changed) for any other status
     */
    public boolean resume() {
        if (status != SeasonStatus.HALTED) {
            return false;
        }
        this.status = abandonedTurnNo == null ? SeasonStatus.IN_PROGRESS : SeasonStatus.ABANDONING;
        return true;
    }

    private void requireInProgress() {
        if (status != SeasonStatus.IN_PROGRESS) {
            throw new IllegalStateException("진행 중인 시즌이 아니다: " + status);
        }
    }
}
