package antlab.support;

import antlab.scoring.CheckCode;
import antlab.scoring.ReconciliationClassification;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대사 정의(db/22_reconcile_season_def.sql)를 JDBC 연결 하나에 싣고 {@code pg_temp.recon_rules} 결과를 읽는 시험 도구.
 * 임시 객체(pg_temp)는 그 연결에만 있고 트랜잭션이 롤백되면 함께 사라진다 — 시험 트랜잭션 안에서 Java 평가와 같은 연결·같은 미커밋 상태를 본다.
 */
public final class ReconDefinitions {

    public static final Path DEFINITION_FILE = Path.of("db/22_reconcile_season_def.sql");
    /** 턴 전환 대사(db/23): 규칙 11·19 와 17행 묶음 {@code recon_rules_turn}. 22 를 먼저 실은 연결에 싣는다. */
    public static final Path TURN_DEFINITION_FILE = Path.of("db/23_turn_advance_def.sql");

    private static final String RULES_SQL = """
            SELECT rule_no, check_code, status, expected_value, actual_value, diff_value, mismatch_count,
                   classification, CAST(detail AS text)
            FROM pg_temp.recon_rules(?, CAST(? AS smallint)) ORDER BY rule_no""";

    private ReconDefinitions() {
    }

    /** 정의 파일을 지금 연결에 싣는다(여러 문장을 한 번에 실행). 같은 트랜잭션 안에서 불러야 한다. */
    public static void load(JdbcTemplate jdbc) {
        try {
            jdbc.execute(Files.readString(DEFINITION_FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("대사 정의 파일을 읽을 수 없다", e);
        }
    }

    /** 22 위에 23 정의 파일도 싣는다({@code recon_rules_turn} 용). 같은 트랜잭션 안에서 불러야 한다. */
    public static void loadTurn(JdbcTemplate jdbc) {
        load(jdbc);
        try {
            jdbc.execute(Files.readString(TURN_DEFINITION_FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("턴 전환 정의 파일을 읽을 수 없다", e);
        }
    }

    /** {@code pg_temp.recon_rules_turn(시즌, 턴)} 17행을 {@link RuleResult} 로 읽는다. {@link #loadTurn} 을 먼저 불러야 한다. */
    public static List<RuleResult> sqlTurnRules(JdbcTemplate jdbc, long seasonId, int turnNo) {
        return jdbc.query(RULES_SQL.replace("pg_temp.recon_rules(", "pg_temp.recon_rules_turn("),
                (rs, i) -> new RuleResult(rs.getInt(1), CheckCode.valueOf(rs.getString(2)),
                        ReconciliationStatus.valueOf(rs.getString(3)), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getInt(7),
                        rs.getString(8) == null ? null : ReconciliationClassification.valueOf(rs.getString(8)),
                        rs.getString(9)), seasonId, turnNo);
    }

    /** {@code pg_temp.recon_rules(시즌, 턴)} 15행을 {@link RuleResult} 로 읽는다. 정의를 먼저 실어 둔 연결이어야 한다. */
    public static List<RuleResult> sqlRules(JdbcTemplate jdbc, long seasonId, int turnNo) {
        return jdbc.query(RULES_SQL, (rs, i) -> new RuleResult(rs.getInt(1), CheckCode.valueOf(rs.getString(2)),
                ReconciliationStatus.valueOf(rs.getString(3)), rs.getBigDecimal(4), rs.getBigDecimal(5),
                rs.getBigDecimal(6), rs.getInt(7),
                rs.getString(8) == null ? null : ReconciliationClassification.valueOf(rs.getString(8)),
                rs.getString(9)), seasonId, turnNo);
    }

    /** 빈 값(null)을 보존하는 {@link BigDecimal} 읽기 도우미 — 시험에서 값 없음과 0을 구분한다. */
    static BigDecimal nullableDecimal(BigDecimal value) {
        return value;
    }
}
