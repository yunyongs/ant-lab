package antlab.collect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Collector run log — read-only mapping (job_run). The collector writes and finishes rows with JdbcTemplate
 * (SPRING_JPA §7); JPA only reads them. No setters, no public constructor, and {@code @Immutable} keeps
 * Hibernate from sending changes. Other tables are referenced by {@code Long} id only (SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "job_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobRun {

    @Id
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_name", length = 20, nullable = false)
    private JobName jobName;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_trigger", length = 9, nullable = false)
    private RunTrigger runTrigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 9, nullable = false)
    private JobStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "request_count")
    private Integer requestCount;

    @Column(name = "inserted_count")
    private Integer insertedCount;

    @Column(name = "revised_count")
    private Integer revisedCount;

    @Column(name = "unchanged_count")
    private Integer unchangedCount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "failed_target_count")
    private Integer failedTargetCount;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
