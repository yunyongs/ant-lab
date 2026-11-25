package antlab.leaderboard;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The current score formula: the row adopted last ({@code adopted_at}), never the highest version string. Read only. */
@Component
public class ManagerScoreFormulaReader {

    private static final String CURRENT = """
            SELECT version, status, w_return, w_risk_adjusted, w_risk, w_process
            FROM manager_score_formula
            ORDER BY adopted_at DESC
            LIMIT 1""";

    private final JdbcClient jdbc;

    public ManagerScoreFormulaReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ManagerScoreFormula> current() {
        return jdbc.sql(CURRENT).query((rs, n) -> new ManagerScoreFormula(rs.getString("version"),
                ManagerScoreFormulaStatus.valueOf(rs.getString("status")), rs.getBigDecimal("w_return"),
                rs.getBigDecimal("w_risk_adjusted"), rs.getBigDecimal("w_risk"), rs.getBigDecimal("w_process")))
                .optional();
    }
}
