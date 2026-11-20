package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Applying V15 to a V14 database backfills persona_version from the recorded agent seasons, ties every
 * season_agent_config row to it, and refuses to guess when the history has two hashes for one version (or one hash under
 * two versions).
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV15UpgradeTest {

    private static final String SEASON = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version, data_snapshot_date,
                base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            VALUES (?, 'IN_PROGRESS', 12, 0, ?, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20) RETURNING id
            """;

    private static final String CONFIG = """
            INSERT INTO season_agent_config (season_id, member_id, persona_key, persona_version, persona_sha256, model_route,
                model_requested, temperature, max_tokens, turn_timeout_ms, prompt_template_version, runner_version,
                budget_calls, budget_usd)
            VALUES (?, ?, ?, ?, ?, 'OPENROUTER', 'vendor/model-a', 0.2, 2000, 60000, 'p1', 'r1', 100, 1.5)
            """;

    @Autowired
    PostgreSQLContainer postgres;

    private static long agent(JdbcTemplate jdbc, long owner, String name, String persona) {
        long id = jdbc.queryForObject("INSERT INTO member (kind, name) VALUES ('AGENT', ?) RETURNING id", Long.class, name);
        jdbc.update("INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id) "
                + "VALUES (?, ?, ?, 'OPENROUTER', ?)", id, owner, persona, "vendor/" + name);
        return id;
    }

    private static void config(JdbcTemplate jdbc, long agent, long seed, String persona, int version, String sha) {
        long season = jdbc.queryForObject(SEASON, Long.class, agent, seed);
        jdbc.update(CONFIG, season, agent, persona, version, sha);
    }

    private static long humanAndAgents(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES ('h@example.invalid', 'h', '!') RETURNING id",
                Long.class);
    }

    @Test
    void upgradeFromV14BackfillsOneRowPerRecordedVersionAndTiesTheConfigs() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v15_upgrade")) {
            scratch.migrateTo("14");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = humanAndAgents(jdbc);
            long a1 = agent(jdbc, owner, "agent-one", "calm-value");
            long a2 = agent(jdbc, owner, "agent-two", "calm-value");
            long a3 = agent(jdbc, owner, "agent-three", "trend-rider");
            config(jdbc, a1, 1, "calm-value", 1, "a".repeat(64));
            config(jdbc, a2, 2, "calm-value", 1, "a".repeat(64));
            config(jdbc, a1, 3, "calm-value", 2, "b".repeat(64));
            config(jdbc, a3, 4, "trend-rider", 1, "c".repeat(64));
            List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM season_agent_config ORDER BY season_id");

            assertThat(scratch.migrateTo("15").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForList("SELECT persona_key, version, sha256 FROM persona_version ORDER BY 1, 2"))
                    .containsExactly(
                            Map.of("persona_key", "calm-value", "version", 1, "sha256", "a".repeat(64)),
                            Map.of("persona_key", "calm-value", "version", 2, "sha256", "b".repeat(64)),
                            Map.of("persona_key", "trend-rider", "version", 1, "sha256", "c".repeat(64)));
            assertThat(jdbc.queryForList("SELECT display_name FROM persona_version WHERE persona_key = 'trend-rider'",
                    String.class)).containsExactly("agent-three");
            assertThat(jdbc.queryForList("SELECT * FROM season_agent_config ORDER BY season_id")).isEqualTo(before);

            jdbc.update(SEASON.replace(" RETURNING id", ""), a1, 5L);
            long season = jdbc.queryForObject("SELECT max(id) FROM season", Long.class);
            assertThatThrownBy(() -> jdbc.update(CONFIG, season, a1, "calm-value", 3, "d".repeat(64)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_season_agent_config_persona");
            // a recorded version with another hash is refused by the database as well
            assertThatThrownBy(() -> jdbc.update(CONFIG, season, a1, "calm-value", 1, "e".repeat(64)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_season_agent_config_persona");
        }
    }

    @Test
    void twoHashesForOneVersionStopTheMigration() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v15_two_hashes")) {
            scratch.migrateTo("14");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = humanAndAgents(jdbc);
            long a1 = agent(jdbc, owner, "agent-one", "calm-value");
            config(jdbc, a1, 1, "calm-value", 1, "a".repeat(64));
            config(jdbc, a1, 2, "calm-value", 1, "b".repeat(64));

            assertThatThrownBy(() -> scratch.migrateTo("15")).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("two hashes for one persona version");
            assertThat(jdbc.queryForObject("SELECT to_regclass('persona_version')", String.class)).isNull();
        }
    }

    @Test
    void oneHashUnderTwoVersionsStopsTheMigration() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v15_two_versions")) {
            scratch.migrateTo("14");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = humanAndAgents(jdbc);
            long a1 = agent(jdbc, owner, "agent-one", "calm-value");
            config(jdbc, a1, 1, "calm-value", 1, "a".repeat(64));
            config(jdbc, a1, 2, "calm-value", 2, "a".repeat(64));

            assertThatThrownBy(() -> scratch.migrateTo("15")).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("one hash under two persona versions");
        }
    }

    @Test
    void theNewTableRulesAreEnforcedByName() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v15_rules")) {
            scratch.migrateTo("15");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            String insert = "INSERT INTO persona_version (persona_key, version, sha256, display_name) VALUES (?, ?, ?, ?)";
            assertThat(jdbc.update(insert, "calm-value", 1, "a".repeat(64), "Calm")).isOne();

            rejected(jdbc, insert, "pk_persona_version", "calm-value", 1, "b".repeat(64), "Calm");
            rejected(jdbc, insert, "uq_persona_version_sha", "calm-value", 2, "a".repeat(64), "Calm");
            rejected(jdbc, insert, "ck_persona_version_key", "Calm_Value", 1, "a".repeat(64), "Calm");
            rejected(jdbc, insert, "ck_persona_version_version", "other", 0, "a".repeat(64), "Calm");
            rejected(jdbc, insert, "ck_persona_version_sha256", "other", 1, "A".repeat(64), "Calm");
            rejected(jdbc, insert, "ck_persona_version_display_name", "other", 1, "a".repeat(64), " Calm");
            rejected(jdbc, insert, "ck_persona_version_display_name", "other", 1, "a".repeat(64), "");
        }
    }

    private static void rejected(JdbcTemplate jdbc, String sql, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }
}
