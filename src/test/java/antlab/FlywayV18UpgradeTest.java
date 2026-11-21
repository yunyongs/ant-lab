package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V18 adds agent_reflection and agent_memory, the memory pin on season_agent_config, two agent_call kinds and
 * league.seed_league_id to a V17 database without touching its rows, and every rule is enforced by constraint name.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV18UpgradeTest {

    private static final String SHA = "a".repeat(64);
    private static final String FILTER = "mf1-0123456789ab";

    private static final String SEASON = """
            INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version, data_snapshot_date,
                base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            VALUES (?, 'FINISHED', 12, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20) RETURNING id
            """;

    private static final String CONFIG = """
            INSERT INTO season_agent_config (season_id, member_id, persona_key, persona_version, persona_sha256,
                model_route, model_requested, temperature, max_tokens, turn_timeout_ms, prompt_template_version,
                runner_version, memory_version, budget_calls, budget_usd)
            VALUES (?, ?, 'calm-value', 1, '%s', 'OPENROUTER', 'vendor/model-a', 0.2, 2000, 60000, 'p1', 'r1', ?, 100, 1.5)
            """.formatted(SHA);

    private static final String LEAGUE = """
            INSERT INTO league (owner_member_id, name, seed, path_length, base_date, engine_version, data_snapshot_date,
                initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, max_entries, seed_league_id)
            VALUES (?, 'x', 7, 12, '2020-01-31', 'test', '2020-01-31', 10000000, 0.001, 0.001, 0.004, 8, ?) RETURNING id
            """;

    @Autowired
    PostgreSQLContainer postgres;

    private static long human(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES (?, 'h', '!') RETURNING id",
                Long.class, email);
    }

    private static long agent(JdbcTemplate jdbc, long owner, String model) {
        long id = jdbc.queryForObject("INSERT INTO member (kind, name) VALUES ('AGENT', 'a') RETURNING id", Long.class);
        jdbc.update("INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id) "
                + "VALUES (?, ?, 'calm-value', 'OPENROUTER', ?)", id, owner, model);
        return id;
    }

    private static void rejected(JdbcTemplate jdbc, String sql, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    private static void accepted(JdbcTemplate jdbc, long season, long member) {
        jdbc.update("INSERT INTO agent_reflection (season_id, member_id, outcome, body, filter_version) "
                + "VALUES (?, ?, 'ACCEPTED', '{\"schema\":\"season-reflection/1\"}'::jsonb, ?)", season, member, FILTER);
    }

    private static void memory(JdbcTemplate jdbc, long member, int version, long source) {
        jdbc.update("INSERT INTO agent_memory (member_id, version, source_season_id, playbook, playbook_carried, "
                + "filter_version) VALUES (?, ?, ?, '{\"schema\":\"operating-note/1\"}'::jsonb, false, ?)",
                member, version, source, FILTER);
    }

    @Test
    void upgradeFromV17KeepsTheExistingRowsAndAddsEmptyTables() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v18_upgrade")) {
            scratch.migrateTo("17");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long agent = agent(jdbc, owner, "vendor/model-a");
            jdbc.update("INSERT INTO persona_version (persona_key, version, sha256, display_name) VALUES "
                    + "('calm-value', 1, ?, 'Calm')", SHA);
            long season = jdbc.queryForObject(SEASON, Long.class, agent);
            jdbc.update(CONFIG, season, agent, null);
            long league = jdbc.queryForObject(LEAGUE.replace(", seed_league_id)", ")").replace(", ?) RETURNING", ") RETURNING"),
                    Long.class, owner);
            jdbc.update("INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, "
                    + "request_text, latency_ms) VALUES (?, 0, 1, 'TURN_DECISION', 'OK', 'vendor/model-a', 'req', 5)", season);
            var configs = jdbc.queryForList("SELECT * FROM season_agent_config ORDER BY season_id");
            var calls = jdbc.queryForList("SELECT * FROM agent_call ORDER BY id");

            assertThat(scratch.migrateTo("18").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForList("SELECT * FROM season_agent_config ORDER BY season_id")).isEqualTo(configs);
            assertThat(jdbc.queryForList("SELECT * FROM agent_call ORDER BY id")).isEqualTo(calls);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_reflection", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT seed_league_id FROM league WHERE id = ?", Long.class, league)).isNull();
        }
    }

    @Test
    void migratingV18AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v18_again")) {
            scratch.migrateTo("18");

            assertThat(scratch.migrateTo("18").migrationsExecuted).isZero();
        }
    }

    @Test
    void aReflectionHasOneOutcomePerSeasonAndTheRightColumnsForIt() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v18_reflection")) {
            scratch.migrateTo("18");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long agent = agent(jdbc, owner, "vendor/model-a");
            long other = agent(jdbc, owner, "vendor/model-b");
            long s1 = jdbc.queryForObject(SEASON, Long.class, agent);
            long s2 = jdbc.queryForObject(SEASON, Long.class, agent);
            String insert = "INSERT INTO agent_reflection (season_id, member_id, outcome, body, discard_codes, "
                    + "filter_version) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)";
            String body = "{\"schema\":\"season-reflection/1\"}";

            assertThat(jdbc.update(insert, s1, agent, "ACCEPTED", body, null, FILTER)).isOne();
            rejected(jdbc, insert, "pk_agent_reflection", s1, agent, "DISCARDED", null, "[\"YEAR\"]", FILTER);
            rejected(jdbc, insert, "ck_agent_reflection_outcome", s2, agent, "KEPT", null, null, FILTER);
            // an accepted row needs a body and no codes; a discarded row the opposite
            rejected(jdbc, insert, "ck_agent_reflection_body", s2, agent, "ACCEPTED", null, null, FILTER);
            rejected(jdbc, insert, "ck_agent_reflection_discard_codes", s2, agent, "ACCEPTED", body, "[\"YEAR\"]", FILTER);
            rejected(jdbc, insert, "ck_agent_reflection_body", s2, agent, "DISCARDED", body, "[\"YEAR\"]", FILTER);
            rejected(jdbc, insert, "ck_agent_reflection_discard_codes", s2, agent, "DISCARDED", null, null, FILTER);
            rejected(jdbc, insert, "ck_agent_reflection_body_object", s2, agent, "ACCEPTED", "[1]", null, FILTER);
            rejected(jdbc, insert, "ck_agent_reflection_discard_codes_array", s2, agent, "DISCARDED", null, "{}", FILTER);
            rejected(jdbc, insert, "ck_agent_reflection_filter_version", s2, agent, "ACCEPTED", body, null, "");
            // the member is the season's own member
            rejected(jdbc, insert, "fk_agent_reflection_season", s2, other, "ACCEPTED", body, null, FILTER);
            rejected(jdbc, insert, "fk_agent_reflection_season", 987654321L, agent, "ACCEPTED", body, null, FILTER);
            assertThat(jdbc.update(insert, s2, agent, "DISCARDED", null, "[\"YEAR\",\"PRICE\"]", FILTER)).isOne();
        }
    }

    @Test
    void onlyAnAcceptedReflectionOfTheSameAgentCanMakeAVersion() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v18_memory")) {
            scratch.migrateTo("18");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long agent = agent(jdbc, owner, "vendor/model-a");
            long other = agent(jdbc, owner, "vendor/model-b");
            long s1 = jdbc.queryForObject(SEASON, Long.class, agent);
            long s2 = jdbc.queryForObject(SEASON, Long.class, agent);
            long s3 = jdbc.queryForObject(SEASON, Long.class, agent);
            long s4 = jdbc.queryForObject(SEASON, Long.class, agent);
            accepted(jdbc, s1, agent);
            accepted(jdbc, s4, agent);
            jdbc.update("INSERT INTO agent_reflection (season_id, member_id, outcome, discard_codes, filter_version) "
                    + "VALUES (?, ?, 'DISCARDED', '[\"YEAR\"]'::jsonb, ?)", s2, agent, FILTER);
            String insert = "INSERT INTO agent_memory (member_id, version, source_season_id, playbook, playbook_carried, "
                    + "filter_version) VALUES (?, ?, ?, ?::jsonb, false, ?)";
            String note = "{\"schema\":\"operating-note/1\"}";

            assertThat(jdbc.update(insert, agent, 1, s1, note, FILTER)).isOne();
            // a discarded reflection has no version, a missing one neither, and another agent's reflection is not usable
            rejected(jdbc, insert, "fk_agent_memory_source", agent, 2, s2, note, FILTER);
            rejected(jdbc, insert, "fk_agent_memory_source", agent, 2, 987654321L, note, FILTER);
            rejected(jdbc, insert, "fk_agent_memory_source", other, 1, s4, note, FILTER);
            // one version per number, one source season per version
            rejected(jdbc, insert, "pk_agent_memory", agent, 1, s1, note, FILTER);
            accepted(jdbc, s3, agent);
            rejected(jdbc, insert, "ck_agent_memory_version", agent, 0, s3, note, FILTER);
            rejected(jdbc, insert, "ck_agent_memory_playbook_object", agent, 2, s3, "[]", FILTER);
            rejected(jdbc, insert, "ck_agent_memory_filter_version", agent, 2, s3, note, "");
            assertThat(jdbc.update(insert, agent, 2, s3, note, FILTER)).isOne();
            rejected(jdbc, "UPDATE agent_memory SET source_outcome = 'DISCARDED'", "ck_agent_memory_source_outcome");
            // the same reflection cannot make a second version
            rejected(jdbc, insert, "uq_agent_memory_source", agent, 3, s3, note, FILTER);
        }
    }

    @Test
    void aSeasonCanPinOnlyAVersionTheAgentHasAndNullIsFree() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v18_pin")) {
            scratch.migrateTo("18");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long agent = agent(jdbc, owner, "vendor/model-a");
            long other = agent(jdbc, owner, "vendor/model-b");
            jdbc.update("INSERT INTO persona_version (persona_key, version, sha256, display_name) VALUES "
                    + "('calm-value', 1, ?, 'Calm')", SHA);
            long s1 = jdbc.queryForObject(SEASON, Long.class, agent);
            long s2 = jdbc.queryForObject(SEASON, Long.class, agent);
            long s3 = jdbc.queryForObject(SEASON, Long.class, other);
            accepted(jdbc, s1, agent);
            memory(jdbc, agent, 1, s1);

            assertThat(jdbc.update(CONFIG, s2, agent, null)).isOne();
            rejected(jdbc, CONFIG, "fk_season_agent_config_memory", s3, other, 1);
            rejected(jdbc, CONFIG, "fk_season_agent_config_memory", s3, other, 2);
            long s4 = jdbc.queryForObject(SEASON, Long.class, agent);
            assertThat(jdbc.update(CONFIG, s4, agent, 1)).isOne();
            rejected(jdbc, "DELETE FROM agent_memory", "fk_season_agent_config_memory");
        }
    }

    @Test
    void memoryCallKindsAreAllowedAndNothingElseIs() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v18_kind")) {
            scratch.migrateTo("18");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long agent = agent(jdbc, owner, "vendor/model-a");
            jdbc.update("INSERT INTO persona_version (persona_key, version, sha256, display_name) VALUES "
                    + "('calm-value', 1, ?, 'Calm')", SHA);
            long season = jdbc.queryForObject(SEASON, Long.class, agent);
            jdbc.update(CONFIG, season, agent, null);
            String call = "INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, "
                    + "request_text, latency_ms) VALUES (?, 12, ?, ?, 'OK', 'vendor/model-a', 'req', 5)";

            assertThat(jdbc.update(call, season, 1, "REFLECTION")).isOne();
            assertThat(jdbc.update(call, season, 1, "PLAYBOOK")).isOne();
            assertThat(jdbc.update(call, season, 1, "TURN_DECISION")).isOne();
            rejected(jdbc, call, "ck_agent_call_call_kind", season, 2, "SUMMARY");
        }
    }

    @Test
    void aSiblingLeaguePointsAtAnotherLeagueNeverAtItself() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v18_league")) {
            scratch.migrateTo("18");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            long owner = human(jdbc, "a@example.invalid");
            long root = jdbc.queryForObject(LEAGUE, Long.class, owner, null);

            long sibling = jdbc.queryForObject(LEAGUE, Long.class, owner, root);

            assertThat(jdbc.queryForObject("SELECT seed_league_id FROM league WHERE id = ?", Long.class, sibling))
                    .isEqualTo(root);
            rejected(jdbc, LEAGUE, "fk_league_seed_league", owner, 987654321L);
            rejected(jdbc, "UPDATE league SET seed_league_id = id", "ck_league_seed_league_not_self");
            rejected(jdbc, "DELETE FROM league WHERE id = " + root, "fk_league_seed_league");
        }
    }
}
