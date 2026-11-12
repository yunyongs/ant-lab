package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.member.MemberRepository;
import antlab.account.CashAccountRepository;
import antlab.scoring.CheckCode;
import antlab.scoring.ReconciliationClassification;
import antlab.scoring.ReconciliationLog;
import antlab.scoring.ReconciliationStatus;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.JpaMappingTest;
import antlab.support.SeasonAccountFixture;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A constraint violation (CHECK 23514, NOT NULL 23502) must not leak row values into the exception message or the log:
 * the values are price-derived amounts. Relies on logServerErrorDetail=false reaching the pool through application.yml.
 * The values are synthetic marker numbers that appear nowhere else.
 */
@JpaMappingTest
@ExtendWith(OutputCaptureExtension.class)
class ServerErrorDetailLogTest {

    private static final String MARKER_EXPECTED = "7770001.1111";
    private static final String MARKER_ACTUAL = "7770002.2222";
    private static final String MARKER_DIFF = "7770009.9999";
    private static final String[] MARKERS = {MARKER_EXPECTED, MARKER_ACTUAL, MARKER_DIFF, "7770001", "7770002", "7770009"};

    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    SeasonAccountFixture fixture;

    @BeforeEach
    void 준비() {
        fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
    }

    private static SQLException sqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s) {
                return s;
            }
        }
        throw new AssertionError("SQLException 원인이 없다", t);
    }

    private static void 행_값이_없다(String text) {
        assertThat(text).doesNotContain("Failing row contains");
        for (String marker : MARKERS) {
            assertThat(text).doesNotContain(marker);
        }
    }

    @Test
    void 풀이_드라이버에_서버_오류_상세_끄기_속성을_넘긴다() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        assertThat(pool.getDataSourceProperties().getProperty("logServerErrorDetail")).isEqualTo("false");
    }

    @Test
    void CHECK_위반_23514의_행_값이_예외_메시지와_서버_로그에_없다(CapturedOutput output) {
        Season season = fixture.newSeason(fixture.newMember());
        // diff != actual - expected violates ck_reconciliation_log_values
        ReconciliationLog bad = new ReconciliationLog(season.getId(), 1, 1, CheckCode.CASH_SUM,
                ReconciliationStatus.MISMATCH, new BigDecimal(MARKER_EXPECTED), new BigDecimal(MARKER_ACTUAL),
                new BigDecimal(MARKER_DIFF), 1, ReconciliationClassification.INVESTIGATE, null);

        assertThatThrownBy(() -> {
            em.persist(bad);
            em.flush();
        }).satisfies(e -> {
            SQLException sql = sqlCause(e);
            assertThat(sql.getSQLState()).isEqualTo("23514");
            assertThat(sql.getMessage()).contains("ck_reconciliation_log_values");
            행_값이_없다(sql.getMessage());
        });
        assertThat(output.getAll()).contains("ck_reconciliation_log_values");
        행_값이_없다(output.getAll());
    }

    @Test
    void NOT_NULL_위반_23502의_행_값이_예외_메시지와_서버_로그에_없다(CapturedOutput output) {
        Season season = fixture.newSeason(fixture.newMember());

        // Markers are bound, not inlined: the SQL text itself is logged on error.
        assertThatThrownBy(() -> {
            em.createNativeQuery("INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status,"
                    + " expected_value, actual_value, diff_value, mismatch_count, classification)"
                    + " VALUES (:s, 1, 1, 'CASH_SUM', 'MISMATCH', :e, :a, :d, NULL, 'INVESTIGATE')")
                    .setParameter("s", season.getId())
                    .setParameter("e", new BigDecimal(MARKER_EXPECTED))
                    .setParameter("a", new BigDecimal(MARKER_ACTUAL))
                    .setParameter("d", new BigDecimal(MARKER_DIFF))
                    .executeUpdate();
        }).satisfies(e -> {
            SQLException sql = sqlCause(e);
            assertThat(sql.getSQLState()).isEqualTo("23502");
            행_값이_없다(sql.getMessage());
        });
        행_값이_없다(output.getAll());
    }
}
