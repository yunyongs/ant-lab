package antlab.regime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.dbrole.RoleTestServer;
import antlab.engine.client.EngineRegimeRequest;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The writer under the production database rights: logged in as {@code antlab_app} on a database built by the role script, the
 * Flyway migrations and the afterMigrate callback. It must lock, read and fill the regime columns with nothing more than the
 * column-level UPDATE the callback grants.
 */
class RegimeWriterAsAppRoleTest {

    private static final RoleTestServer SERVER = RoleTestServer.scripts();
    private static final EngineRegimeRequest REQUEST = new EngineRegimeRequest(EngineRegimeRequest.LABEL_VERSION, List.of("1997-01", "1997-02"));

    private static MonthRegimeWriter writerOf(JdbcTemplate app) {
        DataSource dataSource = app.getDataSource();
        return new MonthRegimeWriter(JdbcClient.create(dataSource), new DataSourceTransactionManager(dataSource));
    }

    @Test
    void theAppRoleLocksReadsAndFillsTheRegimeColumnsAndKeepsEverythingElse() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "rw")) {
            db.createRoles();
            db.superuserExecute("INSERT INTO month_block (month_start, event_summary) VALUES ('1997-01-01', 'kept'), ('1997-02-01', NULL)");
            MonthRegimeWriter writer = writerOf(db.as("antlab_app"));
            List<MonthRegime> answer = RegimeAnswerCheck.check(REQUEST, RegimeFixtures.parsed());

            assertThat(writer.months()).containsExactly("1997-01", "1997-02");
            assertThat(writer.fill(answer)).isEqualTo(new MonthRegimeWriter.Result(2, 0));
            assertThat(writer.fill(answer)).isEqualTo(new MonthRegimeWriter.Result(0, 2));

            JdbcTemplate superuser = db.superuser();
            assertThat(superuser.queryForObject("SELECT regime_label || ' ' || prob_p4::text || ' ' || (indicator_z ->> '3') "
                    + "FROM month_block WHERE month_start = '1997-01-01'", String.class)).isEqualTo("P4 0.4078 -1.15069709");
            assertThat(superuser.queryForObject("SELECT event_summary FROM month_block WHERE month_start = '1997-01-01'", String.class))
                    .as("a column outside the grant is untouched").isEqualTo("kept");
        }
    }

    @Test
    void aConflictUnderTheAppRoleStillChangesNothing() throws SQLException {
        try (RoleTestServer.Db db = new RoleTestServer.Db(SERVER, "rwc")) {
            db.createRoles();
            db.superuserExecute("INSERT INTO month_block (month_start, rate_dir, rate_strength) VALUES ('1997-01-01', 'DOWN', 0.5), ('1997-02-01', NULL, NULL)");
            MonthRegimeWriter writer = writerOf(db.as("antlab_app"));
            String before = db.superuser().queryForList("SELECT to_jsonb(t)::text FROM month_block t ORDER BY month_start", String.class).toString();

            assertThatThrownBy(() -> writer.fill(RegimeAnswerCheck.check(REQUEST, RegimeFixtures.parsed())))
                    .isInstanceOf(MonthRegimeWriter.ConflictException.class);

            assertThat(db.superuser().queryForList("SELECT to_jsonb(t)::text FROM month_block t ORDER BY month_start", String.class).toString())
                    .isEqualTo(before);
        }
    }
}
