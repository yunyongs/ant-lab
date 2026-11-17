package antlab.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.JpaMappingTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bad row per rule of the agent schema, checked by SQLSTATE and constraint name (never message text). Every case
 * runs in its own rolled-back transaction, and each breaks exactly one rule because PostgreSQL names only the first
 * violated constraint.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentConstraintTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String SHA = "a".repeat(64);
    private static final String CHECK = "23514";
    private static final String FK = "23503";
    private static final String UNIQUE = "23505";

    @Autowired
    DataSource dataSource;

    final class Tx implements AutoCloseable {
        final Connection c;
        final long human;
        final long otherHuman;
        final long agent;

        Tx() throws SQLException {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
            human = human();
            otherHuman = human();
            agent = agent(human, "calm-value", "OPENROUTER", "vendor/model-a");
        }

        long human() throws SQLException {
            return queryLong("INSERT INTO member (email, name, password) VALUES ('ag-" + SEQ.incrementAndGet()
                    + "@example.invalid', 'h', 'x') RETURNING id");
        }

        long agent(long owner, String persona, String route, String model) throws SQLException {
            long id = queryLong("INSERT INTO member (kind, name) VALUES ('AGENT', 'a') RETURNING id");
            exec("INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id) VALUES ("
                    + id + ", " + owner + ", '" + persona + "', '" + route + "', '" + model + "')");
            return id;
        }

        long season(long member) throws SQLException {
            return queryLong("""
                    INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date,
                        initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                        start_goalkeeper, start_defender, start_midfielder, start_forward,
                        current_goalkeeper, current_defender, current_midfielder, current_forward)
                    VALUES (%d, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                        10, 30, 40, 20, 10, 30, 40, 20) RETURNING id""".formatted(member));
        }

        String configSql(long season, long member, String overrides) {
            String sql = "INSERT INTO season_agent_config (season_id, member_id, persona_key, persona_version, "
                    + "persona_sha256, model_route, model_requested, temperature, max_tokens, turn_timeout_ms, "
                    + "prompt_template_version, runner_version, budget_calls, budget_usd) VALUES (" + season + ", "
                    + member + ", 'calm-value', 1, '" + SHA + "', 'OPENROUTER', 'vendor/model-a', 0.2, 2000, 60000, "
                    + "'p1', 'r1', 100, 1.5)";
            return overrides.isEmpty() ? sql : sql.replace(overrides.split("=>")[0], overrides.split("=>")[1]);
        }

        long agentSeasonWithConfig() throws SQLException {
            long s = season(agent);
            exec(configSql(s, agent, ""));
            return s;
        }

        String callSql(long season, int turn, int attempt, String extraColumns, String extraValues) {
            return "INSERT INTO agent_call (season_id, turn_no, attempt_no, call_kind, status, model_requested, "
                    + "request_text, latency_ms" + extraColumns + ") VALUES (" + season + ", " + turn + ", " + attempt
                    + ", 'TURN_DECISION', 'OK', 'vendor/model-a', 'req', 5" + extraValues + ")";
        }

        String finalCall(long season, int turn, int attempt) {
            return callSql(season, turn, attempt, ", is_final, outcome, request_sha256",
                    ", TRUE, 'DECISION', '" + SHA + "'");
        }

        void exec(String sql) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }

        long queryLong(String sql) throws SQLException {
            try (PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }

        @Override
        public void close() throws SQLException {
            c.rollback();
            c.close();
        }
    }

    interface Body {
        String sql(Tx tx) throws Exception;
    }

    /** Runs the setup of {@code body}'s statement in a fresh transaction and expects the final statement to fail. */
    private void rejects(String state, String constraint, Body body) throws Exception {
        try (Tx tx = new Tx()) {
            String sql = body.sql(tx);
            assertThatThrownBy(() -> tx.exec(sql)).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo(state);
                if (!constraint.isEmpty()) {
                    assertThat(constraintName(e)).isEqualTo(constraint);
                }
            });
        }
    }

    private static String constraintName(SQLException e) {
        String first = e.getMessage().lines().findFirst().orElse("");
        Matcher m = Pattern.compile("constraint \"([^\"]+)\"").matcher(first);
        return m.find() ? m.group(1) : first;
    }

    // ---- member kind

    @Test
    void agentWithEmailIsRejected() throws Exception {
        rejects(CHECK, "ck_member_kind_credentials",
                t -> "INSERT INTO member (kind, name, email) VALUES ('AGENT', 'a', 'x@example.invalid')");
    }

    @Test
    void agentWithPasswordIsRejected() throws Exception {
        rejects(CHECK, "ck_member_kind_credentials", t -> "INSERT INTO member (kind, name, password) VALUES ('AGENT', 'a', 'x')");
    }

    @Test
    void humanWithoutEmailIsRejected() throws Exception {
        rejects(CHECK, "ck_member_kind_credentials", t -> "INSERT INTO member (name, password) VALUES ('h', 'x')");
    }

    @Test
    void humanWithoutPasswordIsRejected() throws Exception {
        rejects(CHECK, "ck_member_kind_credentials", t -> "INSERT INTO member (name, email) VALUES ('h', 'y@example.invalid')");
    }

    @Test
    void agentWithAdminRoleIsRejected() throws Exception {
        rejects(CHECK, "ck_member_kind_role", t -> "INSERT INTO member (kind, name, role) VALUES ('AGENT', 'a', 'ADMIN')");
    }

    @Test
    void unknownKindIsRejected() throws Exception {
        rejects(CHECK, "ck_member_kind", t -> "INSERT INTO member (kind, name) VALUES ('ROBOT', 'a')");
    }

    @Test
    void promotingAnAgentToAdminIsRejected() throws Exception {
        rejects(CHECK, "ck_member_kind_role", t -> "UPDATE member SET role = 'ADMIN' WHERE id = " + t.agent);
    }

    @Test
    void changingAnAgentMemberIntoAHumanIsRejectedWhileItHasAProfile() throws Exception {
        rejects(FK, "fk_agent_profile_member",
                t -> "UPDATE member SET kind = 'HUMAN', email = 'z@example.invalid', password = 'x' WHERE id = " + t.agent);
    }

    // ---- agent_profile

    @Test
    void profileForAHumanMemberIsRejected() throws Exception {
        rejects(FK, "fk_agent_profile_member", t -> "INSERT INTO agent_profile (member_id, owner_member_id, persona_key, "
                + "model_route, model_id) VALUES (" + t.otherHuman + ", " + t.human + ", 'p', 'OLLAMA', 'm')");
    }

    @Test
    void profileOwnedByAnAgentIsRejected() throws Exception {
        rejects(FK, "fk_agent_profile_owner", t -> {
            long second = t.queryLong("INSERT INTO member (kind, name) VALUES ('AGENT', 'a2') RETURNING id");
            return "INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id) VALUES ("
                    + second + ", " + t.agent + ", 'p', 'OLLAMA', 'm')";
        });
    }

    @Test
    void profileCannotClaimMemberKindHuman() throws Exception {
        rejects(CHECK, "ck_agent_profile_member_agent_only", t -> "INSERT INTO agent_profile (member_id, member_kind, "
                + "owner_member_id, persona_key, model_route, model_id) VALUES (" + t.otherHuman + ", 'HUMAN', "
                + t.human + ", 'p', 'OLLAMA', 'm')");
    }

    @Test
    void profileCannotClaimOwnerKindAgent() throws Exception {
        rejects(CHECK, "ck_agent_profile_owner_human_only", t -> {
            long second = t.queryLong("INSERT INTO member (kind, name) VALUES ('AGENT', 'a2') RETURNING id");
            return "INSERT INTO agent_profile (member_id, owner_member_id, owner_kind, persona_key, model_route, "
                    + "model_id) VALUES (" + second + ", " + t.agent + ", 'AGENT', 'p', 'OLLAMA', 'm')";
        });
    }

    @Test
    void profileValueRulesAreEnforced() throws Exception {
        for (String[] bad : new String[][] {
                {"ck_agent_profile_persona_key", "'Bad_Key', 'OLLAMA', 'm', 'ACTIVE'"},
                {"ck_agent_profile_model_route", "'k', 'MAGIC', 'm', 'ACTIVE'"},
                {"ck_agent_profile_model_id", "'k', 'OLLAMA', ' m', 'ACTIVE'"},
                {"ck_agent_profile_model_id", "'k', 'OLLAMA', '', 'ACTIVE'"},
                {"ck_agent_profile_status", "'k', 'OLLAMA', 'm', 'GONE'"}}) {
            rejects(CHECK, bad[0], t -> {
                long second = t.queryLong("INSERT INTO member (kind, name) VALUES ('AGENT', 'a2') RETURNING id");
                return "INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id, "
                        + "status) SELECT " + second + ", " + t.human + ", * FROM (VALUES (" + bad[1] + ")) v";
            });
        }
    }

    @Test
    void sameOwnerPersonaAndModelCannotRegisterTwice() throws Exception {
        rejects(UNIQUE, "uq_agent_profile_identity", t -> {
            long second = t.queryLong("INSERT INTO member (kind, name) VALUES ('AGENT', 'a2') RETURNING id");
            return "INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id) VALUES ("
                    + second + ", " + t.human + ", 'calm-value', 'OPENROUTER', 'vendor/model-a')";
        });
    }

    @Test
    void sameOwnerAndPersonaWithAnotherModelIsAnotherAgent() throws Exception {
        try (Tx tx = new Tx()) {
            long other = tx.agent(tx.human, "calm-value", "OPENROUTER", "vendor/model-b");

            assertThat(other).isNotEqualTo(tx.agent);
        }
    }

    // ---- season_agent_config

    @Test
    void configForAHumansSeasonIsRejected() throws Exception {
        rejects(FK, "fk_season_agent_config_agent", t -> t.configSql(t.season(t.human), t.human, ""));
    }

    @Test
    void configWithAnotherMembersIdIsRejected() throws Exception {
        rejects(FK, "fk_season_agent_config_season", t -> {
            long second = t.agent(t.human, "other-persona", "OLLAMA", "m");
            return t.configSql(t.season(t.agent), second, "");
        });
    }

    @Test
    void secondConfigForTheSameSeasonIsRejected() throws Exception {
        rejects(UNIQUE, "pk_season_agent_config", t -> {
            long s = t.agentSeasonWithConfig();
            return t.configSql(s, t.agent, "");
        });
    }

    @Test
    void configValueRulesAreEnforced() throws Exception {
        String[][] bad = {
                {"ck_season_agent_config_persona_sha256", "'" + SHA + "'=>'" + "G".repeat(64) + "'"},
                {"ck_season_agent_config_model_route", "'OPENROUTER', 'vendor=>'MAGIC', 'vendor"},
                {"ck_season_agent_config_temperature", "0.2, 2000=>2.5, 2000"},
                {"ck_season_agent_config_max_tokens", "2000, 60000=>0, 60000"},
                {"ck_season_agent_config_budget_usd", "100, 1.5=>100, -1"},
                {"ck_season_agent_config_persona_version", "'calm-value', 1,=>'calm-value', 0,"}};
        for (String[] b : bad) {
            rejects(CHECK, b[0], t -> t.configSql(t.season(t.agent), t.agent, b[1]));
        }
    }

    // ---- agent_call

    @Test
    void callForASeasonWithoutConfigIsRejected() throws Exception {
        rejects(FK, "fk_agent_call_config", t -> t.callSql(t.season(t.agent), 0, 1, "", ""));
    }

    @Test
    void duplicateAttemptIsRejected() throws Exception {
        rejects(UNIQUE, "uq_agent_call_attempt", t -> {
            long s = t.agentSeasonWithConfig();
            t.exec(t.callSql(s, 0, 1, "", ""));
            return t.callSql(s, 0, 1, "", "");
        });
    }

    @Test
    void secondFinalRowForOneTurnIsRejected() throws Exception {
        rejects(UNIQUE, "uq_agent_call_final_per_turn", t -> {
            long s = t.agentSeasonWithConfig();
            t.exec(t.finalCall(s, 0, 1));
            return t.finalCall(s, 0, 2);
        });
    }

    @Test
    void finalRowsOfDifferentTurnsAreAllowed() throws Exception {
        try (Tx tx = new Tx()) {
            long s = tx.agentSeasonWithConfig();
            tx.exec(tx.finalCall(s, 0, 1));
            tx.exec(tx.finalCall(s, 1, 1));
            tx.exec(tx.callSql(s, 1, 2, "", ""));
        }
    }

    @Test
    void duplicateLedgerCallIdIsRejected() throws Exception {
        rejects(UNIQUE, "uq_agent_call_ledger", t -> {
            long s = t.agentSeasonWithConfig();
            String id = "'11111111-1111-1111-1111-111111111111'";
            t.exec(t.callSql(s, 0, 1, ", ledger_call_id", ", " + id));
            return t.callSql(s, 0, 2, ", ledger_call_id", ", " + id);
        });
    }

    @Test
    void finalFlagNeedsOutcomeAndHash() throws Exception {
        rejects(CHECK, "ck_agent_call_final_outcome", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", is_final, request_sha256", ", TRUE, '" + SHA + "'"));
        rejects(CHECK, "ck_agent_call_final_hash", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", is_final, outcome", ", TRUE, 'DECISION'"));
        rejects(CHECK, "ck_agent_call_final_outcome", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", outcome", ", 'DECISION'"));
        rejects(CHECK, "ck_agent_call_final_hash", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", request_sha256", ", '" + SHA + "'"));
        rejects(CHECK, "ck_agent_call_final_rejects", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", rejects", ", '[]'::jsonb"));
    }

    @Test
    void callValueRulesAreEnforced() throws Exception {
        // 262145 ASCII characters exceed the column length itself (string data right truncation)
        rejects("22001", "", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", response_text", ", repeat('x', 262145)"));
        rejects(CHECK, "ck_agent_call_text_size", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", response_text", ", repeat('한', 90000)"));
        rejects(CHECK, "ck_agent_call_cost_usd", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1, ", cost_usd", ", -0.1"));
        rejects(CHECK, "ck_agent_call_status", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1, "", "")
                .replace("'OK'", "'WEIRD'"));
        rejects(CHECK, "ck_agent_call_outcome", t -> t.finalCall(t.agentSeasonWithConfig(), 0, 1)
                .replace("'DECISION'", "'MAYBE'"));
        rejects(CHECK, "ck_agent_call_http_status", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1,
                ", http_status", ", 99"));
        rejects(CHECK, "ck_agent_call_request_sha256", t -> t.finalCall(t.agentSeasonWithConfig(), 0, 1)
                .replace("'" + SHA + "'", "'zz'"));
        rejects(CHECK, "ck_agent_call_attempt_no", t -> t.callSql(t.agentSeasonWithConfig(), 0, 0, "", ""));
        rejects(CHECK, "ck_agent_call_turn_no", t -> t.callSql(t.agentSeasonWithConfig(), -1, 1, "", ""));
        rejects(CHECK, "ck_agent_call_latency_ms", t -> t.callSql(t.agentSeasonWithConfig(), 0, 1, "", "")
                .replace(", 5)", ", -5)"));
    }

    @Test
    void multiByteTextAtTheByteLimitIsAccepted() throws Exception {
        try (Tx tx = new Tx()) {
            long s = tx.agentSeasonWithConfig();
            // 87381 three-byte characters = 262143 bytes
            tx.exec(tx.callSql(s, 0, 1, ", response_text", ", repeat('한', 87381)"));
        }
    }
}
