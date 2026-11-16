package antlab.agent;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every constraint and index V5 adds, by name, plus the exact V4 to V5 difference. The erd tables for the agent
 * schema are written in the docs step of the AG-02 stack, so the expected list lives here until then.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentSchemaCatalogTest {

    private static final Set<String> EXPECTED = Set.of(
            "member.ck_member_kind", "member.ck_member_kind_credentials", "member.ck_member_kind_role",
            "member.uq_member_id_kind", "season.uq_season_id_member",
            "agent_profile.pk_agent_profile", "agent_profile.uq_agent_profile_identity",
            "agent_profile.fk_agent_profile_member", "agent_profile.fk_agent_profile_owner",
            "agent_profile.ck_agent_profile_member_agent_only", "agent_profile.ck_agent_profile_owner_human_only",
            "agent_profile.ck_agent_profile_persona_key", "agent_profile.ck_agent_profile_model_route",
            "agent_profile.ck_agent_profile_model_id", "agent_profile.ck_agent_profile_status",
            "season_agent_config.pk_season_agent_config", "season_agent_config.fk_season_agent_config_season",
            "season_agent_config.fk_season_agent_config_agent", "season_agent_config.ck_season_agent_config_persona_key",
            "season_agent_config.ck_season_agent_config_persona_version",
            "season_agent_config.ck_season_agent_config_persona_sha256",
            "season_agent_config.ck_season_agent_config_model_route",
            "season_agent_config.ck_season_agent_config_model_requested",
            "season_agent_config.ck_season_agent_config_temperature",
            "season_agent_config.ck_season_agent_config_max_tokens",
            "season_agent_config.ck_season_agent_config_turn_timeout",
            "season_agent_config.ck_season_agent_config_prompt_version",
            "season_agent_config.ck_season_agent_config_runner_version",
            "season_agent_config.ck_season_agent_config_memory_version",
            "season_agent_config.ck_season_agent_config_budget_calls",
            "season_agent_config.ck_season_agent_config_budget_usd",
            "agent_call.pk_agent_call", "agent_call.uq_agent_call_attempt", "agent_call.uq_agent_call_ledger",
            "agent_call.fk_agent_call_config", "agent_call.uq_agent_call_final_per_turn",
            "agent_call.ck_agent_call_turn_no", "agent_call.ck_agent_call_attempt_no",
            "agent_call.ck_agent_call_call_kind", "agent_call.ck_agent_call_status",
            "agent_call.ck_agent_call_http_status", "agent_call.ck_agent_call_text_size",
            "agent_call.ck_agent_call_tokens_in", "agent_call.ck_agent_call_tokens_out",
            "agent_call.ck_agent_call_cost_usd", "agent_call.ck_agent_call_latency_ms",
            "agent_call.ck_agent_call_outcome", "agent_call.ck_agent_call_request_sha256",
            "agent_call.ck_agent_call_final_outcome", "agent_call.ck_agent_call_final_hash",
            "agent_call.ck_agent_call_final_rejects");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PostgreSQLContainer postgres;

    private static Set<String> objects(JdbcTemplate j) {
        Set<String> out = new TreeSet<>();
        j.query("""
                SELECT conrelid::regclass::text || '.' || conname FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND conrelid::regclass::text <> 'flyway_schema_history'
                UNION
                SELECT tablename || '.' || indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, rs -> {
            out.add(rs.getString(1));
        });
        return out;
    }

    @Test
    void v5AddsExactlyTheNamedConstraintsAndIndexes() {
        Set<String> v4;
        Set<String> v5;
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v5_catalog")) {
            scratch.migrateTo("4");
            v4 = objects(new JdbcTemplate(scratch.dataSource()));
            scratch.migrateTo("5");
            v5 = objects(new JdbcTemplate(scratch.dataSource()));
        }
        Set<String> added = new TreeSet<>(v5);
        added.removeAll(v4);

        assertThat(v4).isSubsetOf(v5);
        assertThat(added).containsExactlyInAnyOrderElementsOf(EXPECTED);
        assertThat(objects(jdbc)).containsAll(EXPECTED);
    }

    @Test
    void everyNewNameFitsIn63Bytes() {
        assertThat(EXPECTED).allSatisfy(n -> assertThat(n.substring(n.indexOf('.') + 1).length()).isLessThanOrEqualTo(63));
    }

    @Test
    void newIndexesAreValid() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index
                WHERE indrelid IN ('agent_profile'::regclass, 'season_agent_config'::regclass, 'agent_call'::regclass)
                  AND NOT (indisvalid AND indisready)
                """, Integer.class)).isZero();
    }

    @Test
    void partialFinalIndexIsUniqueAndPartial() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_agent_call_final_per_turn'", String.class);
        assertThat(def).contains("UNIQUE").contains("(season_id, turn_no, call_kind)").contains("WHERE is_final");
    }
}
