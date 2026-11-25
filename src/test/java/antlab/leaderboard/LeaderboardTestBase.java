package antlab.leaderboard;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared setup of the leaderboard tests: members, agents, seasons, last turns, season scores, parts rows and leagues
 * written straight into the tables inside the test transaction (rolled back afterwards). Before each test the parts rows
 * of every other test are removed inside the same transaction, so the board holds exactly the rows the test wrote.
 * Finish order is set by the last turn's {@code created_at}: each new finished season finishes one minute after the
 * previous one.
 */
@IntegrationTest
@Transactional
abstract class LeaderboardTestBase {

    static final String VERSION = "ref69-v1";
    static final String PROCESS_VERSION = "s63-v3";
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final AtomicInteger PERSONAS = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;

    private int finishes;

    @BeforeEach
    void isolateBoard() {
        jdbc.update("DELETE FROM season_manager_parts");
        finishes = 0;
    }

    /** Parts of one counted season: return, risk-adjusted, risk (8 places) and process (2 places), plus the multiple. */
    record P(String ret, String riskAdjusted, String risk, String process, String multiple, String processVersion) {

        P(String ret, String riskAdjusted, String risk, String process) {
            this(ret, riskAdjusted, risk, process, "1.00000000", PROCESS_VERSION);
        }

        /** A season whose four parts all equal {@code part}: S = part under any formula. */
        static P flat(String part) {
            return new P(part, part, part, part);
        }
    }

    long human(String name) {
        return jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES (?, ?, '!') RETURNING id", Long.class,
                "lb-" + UUID.randomUUID() + "@example.test", name);
    }

    /** An agent owned by {@code owner}, with persona versions 1 and 2 of a fresh persona key. */
    long agent(long owner, String personaName, String modelId) {
        String key = "lbtest-" + PERSONAS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        for (int v = 1; v <= 2; v++) {
            jdbc.update("INSERT INTO persona_version (persona_key, version, sha256, display_name) VALUES (?, ?, ?, ?)", key, v,
                    sha(key, v), personaName + (v == 1 ? "" : " v2"));
        }
        long id = jdbc.queryForObject("INSERT INTO member (name, kind) VALUES (?, 'AGENT') RETURNING id", Long.class,
                "owner-typed-name-" + key);
        jdbc.update("""
                INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id)
                VALUES (?, ?, ?, 'OPENROUTER', ?)""", id, owner, key, modelId);
        return id;
    }

    /** A season in the given state; a finished one gets its last turn, the next finish time and a season score. */
    long season(long member, String status) {
        boolean finished = status.equals("FINISHED");
        long id = jdbc.queryForObject("""
                INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version,
                    data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                    start_goalkeeper, start_defender, start_midfielder, start_forward,
                    current_goalkeeper, current_defender, current_midfielder, current_forward, paused_at, void_reason, void_note)
                VALUES (?, ?, 12, ?, ?, 7, 'fixture-1', DATE '2026-01-02', DATE '2026-01-02', 100000000, 0.00015, 0.0025,
                    0.004, -0.20, 10, 30, 30, 30, 10, 30, 30, 30, ?, ?, ?)
                RETURNING id""", Long.class, member, status, finished ? 12 : 3, status.equals("ABANDONING") ? 2 : null,
                status.equals("PAUSED") ? Timestamp.from(START) : null, status.equals("VOID") ? "MODEL_GONE" : null,
                status.equals("VOID") ? "fixture" : null);
        if (finished) {
            Timestamp at = Timestamp.from(START.plusSeconds(60L * ++finishes));
            jdbc.update("""
                    INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value,
                        defender_value, midfielder_value, forward_value, drawdown, created_at)
                    VALUES (?, 12, 0, 100000000, 100000000, 100000000, 0, 0, 0, 0, ?)""", id, at);
            jdbc.update("""
                    INSERT INTO season_score (season_id, member_id, score_version, score, result_score, process_score, sharpe,
                        bench_sharpe, sharpe_diff, sharpe_diff_percentile, luck_percentile, risk_score, process_items, inputs)
                    VALUES (?, ?, 's63-v3', 50, 50, 50, 0, 0, 0, 0.5, 0.5, 50, 1, '{}'::jsonb)""", id, member);
        }
        return id;
    }

    /** Moves a finished season's finish time (its last turn's {@code created_at}) to {@code minute} minutes after the start. */
    void finishAt(long season, int minute) {
        jdbc.update("UPDATE turn SET created_at = ? WHERE season_id = ? AND turn_no = 12",
                Timestamp.from(START.plusSeconds(60L * minute)), season);
    }

    /** The agent config of an agent season, pinned to the persona version and memory version. */
    void agentConfig(long season, long agent, int personaVersion, Integer memoryVersion) {
        if (memoryVersion != null && jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE member_id = ? AND version = ?",
                Integer.class, agent, memoryVersion) == 0) {
            // the memory version is written from this season's accepted reflection (any source season would do)
            jdbc.update("""
                    INSERT INTO agent_reflection (season_id, member_id, outcome, body, filter_version)
                    VALUES (?, ?, 'ACCEPTED', '{}'::jsonb, 'f1')""", season, agent);
            jdbc.update("""
                    INSERT INTO agent_memory (member_id, version, source_season_id, playbook, playbook_carried, filter_version)
                    VALUES (?, ?, ?, '{}'::jsonb, false, 'f1')""", agent, memoryVersion, season);
        }
        String key = jdbc.queryForObject("SELECT persona_key FROM agent_profile WHERE member_id = ?", String.class, agent);
        jdbc.update("""
                INSERT INTO season_agent_config (season_id, member_id, persona_key, persona_version, persona_sha256,
                    model_route, model_requested, temperature, max_tokens, turn_timeout_ms, prompt_template_version,
                    runner_version, memory_version, budget_calls, budget_usd)
                VALUES (?, ?, ?, ?, ?, 'OPENROUTER', 'fixture-model', 0.2, 1000, 60000, 'p1', 'r1', ?, 100, 1)""",
                season, agent, key, personaVersion, sha(key, personaVersion), memoryVersion);
    }

    /** A finished season with counted parts. */
    long counted(long member, P p) {
        long season = season(member, "FINISHED");
        parts(season, member, p);
        return season;
    }

    /** A finished agent season with counted parts and its agent config. */
    long countedAgent(long agent, P p, int personaVersion, Integer memoryVersion) {
        long season = season(agent, "FINISHED");
        agentConfig(season, agent, personaVersion, memoryVersion);
        parts(season, agent, p);
        return season;
    }

    void parts(long season, long member, P p) {
        jdbc.update("""
                INSERT INTO season_manager_parts (season_id, member_id, reference_version, reference_key, reference_size,
                    bot_version, engine_version, excess_cagr, sharpe_diff, sortino_diff, calmar_diff, mdd, bench_mdd,
                    pct_excess_cagr, pct_sharpe_diff, pct_sortino_diff, pct_calmar_diff, pct_risk_item, pct_mdd,
                    part_return, part_risk_adjusted, part_risk, part_process, process_score_version, final_multiple)
                VALUES (?, ?, ?, ?, 200, 'random/1', 'fixture', 0, 0, 0, 0, -0.1, -0.1, 50, 50, 50, 50, 50, 50,
                    ?, ?, ?, ?, ?, ?)""", season, member, VERSION, "a".repeat(64), new BigDecimal(p.ret()),
                new BigDecimal(p.riskAdjusted()), new BigDecimal(p.risk()), new BigDecimal(p.process()), p.processVersion(),
                new BigDecimal(p.multiple()));
    }

    /** A finished season whose parts row is an exclusion. */
    long excluded(long member, String exclusion) {
        long season = season(member, "FINISHED");
        jdbc.update("""
                INSERT INTO season_manager_parts (season_id, member_id, reference_version, exclusion, part_process,
                    process_score_version, final_multiple)
                VALUES (?, ?, ?, ?, 50, ?, 1)""", season, member, VERSION, exclusion, PROCESS_VERSION);
        return season;
    }

    /** An open league owned by {@code owner} with the given seasons as seats. */
    long league(long owner, long... seasons) {
        long league = jdbc.queryForObject("""
                INSERT INTO league (owner_member_id, name, seed, path_length, base_date, engine_version, data_snapshot_date,
                    initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, max_entries)
                VALUES (?, 'fixture league', 7, 12, DATE '2026-01-02', 'fixture-1', DATE '2026-01-02', 100000000, 0.00015,
                    0.0025, 0.004, 10)
                RETURNING id""", Long.class, owner);
        for (long season : seasons) {
            long member = jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season);
            jdbc.update("INSERT INTO league_entry (league_id, season_id, member_id) VALUES (?, ?, ?)", league, season, member);
        }
        return league;
    }

    void closeLeague(long league) {
        jdbc.update("UPDATE league SET status = 'CLOSED', closed_at = now() WHERE id = ?", league);
    }

    /** A newer formula row: adopted after every existing one, with a version string that sorts before them. */
    void newerFormula(String version, String wReturn, String wRiskAdjusted, String wRisk, String wProcess) {
        jdbc.update("""
                INSERT INTO manager_score_formula (version, w_return, w_risk_adjusted, w_risk, w_process, status, basis,
                    adopted_at)
                VALUES (?, ?, ?, ?, ?, 'PROVISIONAL', 'test formula', (SELECT max(adopted_at) FROM manager_score_formula)
                    + interval '1 day')""", version, new BigDecimal(wReturn), new BigDecimal(wRiskAdjusted),
                new BigDecimal(wRisk), new BigDecimal(wProcess));
    }

    private static String sha(String key, int version) {
        String hex = Integer.toHexString((key + version).hashCode() & 0x7fffffff);
        return (hex + "0".repeat(64)).substring(0, 64);
    }
}
