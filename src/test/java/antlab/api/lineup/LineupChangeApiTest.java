package antlab.api.lineup;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.ManualReconciliationDto;
import antlab.scoring.ManualReconciliationService;
import antlab.season.LineupChangeService;
import antlab.season.TurnService;
import antlab.support.LockWaitSupport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Public position-only change route (MVP-14 PR 2). The team is the usual four-asset team (069500 is the midfielder, 100
 * shares) taken to turn 1 through real turns.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class LineupChangeApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ManualReconciliationService manual;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    TurnService turnService;
    @Autowired
    LineupChangeService lineupChangeService;

    private long seasonAtTurnOne() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        return season;
    }

    private MockHttpServletResponse post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
    }

    private MockHttpServletResponse change(long season, int turn, long asset, String position) {
        return post("/api/v1/seasons/" + season + "/lineup-changes", "{\"expectedTurnNo\": " + turn + ", \"assetId\": "
                + asset + ", \"declaredPosition\": " + (position == null ? "null" : "\"" + position + "\"") + "}");
    }

    private String lineupRows(long season, String ticker) {
        return jdbc.queryForObject("""
                SELECT coalesce(string_agg(declared_position || ':' || start_turn || ':' || coalesce(end_turn::text, '-'), ',' ORDER BY id), '')
                FROM lineup WHERE season_id = ? AND asset_id = ?""", String.class, season, assetId(jdbc, ticker));
    }

    private String allLineupRows(long season) {
        return jdbc.queryForObject("""
                SELECT coalesce(string_agg(asset_id || ':' || declared_position || ':' || start_turn || ':' || coalesce(end_turn::text, '-'),
                                           ',' ORDER BY id), '') FROM lineup WHERE season_id = ?""", String.class, season);
    }

    /** Everything a rejected or no-op change must leave alone. */
    private Map<String, Object> state(long season) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lineup", allLineupRows(season));
        out.put("orders", jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season));
        out.put("ledger", jdbc.queryForObject("SELECT count(*) FROM cash_ledger WHERE season_id = ?", Long.class, season));
        out.put("changes", jdbc.queryForObject("SELECT count(*) FROM formation_change WHERE season_id = ?", Long.class, season));
        out.put("holding", jdbc.queryForObject(
                "SELECT coalesce(string_agg(asset_id || ':' || quantity || ':' || avg_price::text, ',' ORDER BY asset_id), '') FROM holding WHERE season_id = ?",
                String.class, season));
        out.put("account", jdbc.queryForObject("SELECT balance::text || '/' || version FROM cash_account WHERE season_id = ?",
                String.class, season));
        out.put("season", jdbc.queryForObject("SELECT status || ':' || current_turn_no || ':' || version FROM season WHERE id = ?",
                String.class, season));
        out.put("lineupIdentity", jdbc.queryForObject(
                "SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('lineup', 'id')), 0)", Long.class));
        return out;
    }

    private BigDecimal turnValue(long season, int turn, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM turn WHERE season_id = ? AND turn_no = ?", BigDecimal.class,
                season, turn);
    }

    private long matchRows(long season, int turn) {
        return jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = ? AND status = 'MATCH'",
                Long.class, season, turn);
    }

    @Test
    void changeClosesTheOpenSpanAndTheNextTurnValuesTheAssetUnderTheNewPosition() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        BigDecimal midBefore = turnValue(season, 1, "midfielder_value");
        BigDecimal defBefore = turnValue(season, 1, "defender_value");
        assertThat(midBefore.signum()).isPositive();
        Map<String, Object> untouched = state(season);

        MockHttpServletResponse r = change(season, 1, asset, "DEFENDER");

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(json(r).get("assetId").asLong()).isEqualTo(asset);
        assertThat(json(r).get("declaredPosition").asString()).isEqualTo("DEFENDER");
        assertThat(json(r).get("startTurn").asInt()).isEqualTo(1);
        assertThat(json(r).get("changed").asBoolean()).isTrue();
        assertThat(lineupRows(season, "069500")).isEqualTo("MIDFIELDER:0:1,DEFENDER:1:-");
        Map<String, Object> after = state(season);
        assertThat(after.get("orders")).isEqualTo(untouched.get("orders"));
        assertThat(after.get("ledger")).isEqualTo(untouched.get("ledger"));
        assertThat(after.get("changes")).isEqualTo(untouched.get("changes"));
        assertThat(after.get("holding")).as("holdings unchanged").isEqualTo(untouched.get("holding"));
        assertThat(after.get("account")).as("cash and account version unchanged").isEqualTo(untouched.get("account"));

        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        assertThat(turnValue(season, 1, "midfielder_value")).as("the turn already taken is unchanged").isEqualByComparingTo(midBefore);
        assertThat(turnValue(season, 1, "defender_value")).isEqualByComparingTo(defBefore);
        assertThat(turnValue(season, 2, "midfielder_value").signum()).as("the asset left the midfield").isZero();
        BigDecimal expectedDefender = jdbc.queryForObject("""
                SELECT sum(h.quantity * p.price) FROM holding h JOIN turn_price p ON p.season_id = h.season_id AND p.asset_id = h.asset_id
                WHERE h.season_id = ? AND p.turn_no = 2 AND h.asset_id IN (?, ?)""", BigDecimal.class, season, asset,
                assetId(jdbc, "SPY"));
        assertThat(turnValue(season, 2, "defender_value")).isEqualByComparingTo(expectedDefender);
    }

    @Test
    void theSamePositionAnswers200WithChangedFalseAndWritesNothing() throws Exception {
        long season = seasonAtTurnOne();
        Map<String, Object> before = state(season);

        MockHttpServletResponse r = change(season, 1, assetId(jdbc, "069500"), "MIDFIELDER");

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(json(r).get("changed").asBoolean()).isFalse();
        assertThat(json(r).get("startTurn").asInt()).as("the existing span").isZero();
        assertThat(state(season)).isEqualTo(before);
    }

    @Test
    void afterAChangeTheManualReconciliationMatchesAndSoDoesTheNextTurn() throws Exception {
        long season = seasonAtTurnOne();
        assertThat(change(season, 1, assetId(jdbc, "069500"), "FORWARD_TARGET").getStatus()).isEqualTo(200);

        ManualReconciliationDto result = manual.run(season);
        assertThat(result.result()).isEqualTo("MATCH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status = 'MISMATCH'",
                Long.class, season)).isZero();

        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        assertThat(matchRows(season, 2)).isEqualTo(17L);
    }

    @Test
    void twoChangesInOneTurnKeepAZeroLengthSpanAndReconcile() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        assertThat(change(season, 1, asset, "DEFENDER").getStatus()).isEqualTo(200);
        assertThat(change(season, 1, asset, "FORWARD_DEFENSIVE").getStatus()).isEqualTo(200);

        assertThat(lineupRows(season, "069500")).isEqualTo("MIDFIELDER:0:1,DEFENDER:1:1,FORWARD_DEFENSIVE:1:-");
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        assertThat(matchRows(season, 2)).isEqualTo(17L);
    }

    @Test
    void aLaterBuyMustUseTheNewPosition() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        assertThat(change(season, 1, asset, "DEFENDER").getStatus()).isEqualTo(200);
        String oldPosition = "{\"expectedTurnNo\": 1, \"side\": \"BUY\", \"assetId\": " + asset
                + ", \"quantity\": 1, \"declaredPosition\": \"MIDFIELDER\"}";
        String newPosition = oldPosition.replace("MIDFIELDER", "DEFENDER");

        MockHttpServletResponse conflict = post("/api/v1/seasons/" + season + "/orders", oldPosition);
        assertThat(conflict.getStatus()).isEqualTo(422);
        assertThat(code(conflict)).isEqualTo("POSITION_CONFLICT");
        assertThat(post("/api/v1/seasons/" + season + "/orders", newPosition).getStatus()).isEqualTo(201);
    }

    @Test
    void turnZeroAndAStaleTurnAre409TurnMismatchWithNoWrite() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        long asset = assetId(jdbc, "069500");
        Map<String, Object> atZero = state(season);
        MockHttpServletResponse zero = change(season, 0, asset, "DEFENDER");
        assertThat(zero.getStatus()).isEqualTo(409);
        assertThat(code(zero)).isEqualTo("TURN_MISMATCH");
        assertThat(state(season)).isEqualTo(atZero);

        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        Map<String, Object> atOne = state(season);
        for (int stale : new int[] {0, 2}) {
            MockHttpServletResponse r = change(season, stale, asset, "DEFENDER");
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("TURN_MISMATCH");
        }
        assertThat(state(season)).isEqualTo(atOne);
    }

    @Test
    void anAssetThatIsNotHeldAnUnknownAssetAndASoldOutAssetAre422InsufficientHolding() throws Exception {
        long season = seasonAtTurnOne();
        String sell = "{\"expectedTurnNo\": 1, \"side\": \"SELL\", \"assetId\": " + assetId(jdbc, "069500") + ", \"quantity\": 100}";
        assertThat(post("/api/v1/seasons/" + season + "/orders", sell).getStatus()).isEqualTo(201);
        long notHeld = assetId(jdbc, "000660");
        jdbc.update("DELETE FROM holding WHERE season_id = ? AND asset_id = ?", season, notHeld);
        Map<String, Object> before = state(season);
        for (long asset : new long[] {assetId(jdbc, "069500"), notHeld, 999_999_999L}) {
            MockHttpServletResponse r = change(season, 1, asset, "DEFENDER");
            assertThat(r.getStatus()).as("asset " + asset).isEqualTo(422);
            assertThat(code(r)).isEqualTo("INSUFFICIENT_HOLDING");
        }
        assertThat(state(season)).isEqualTo(before);
    }

    @Test
    void invalidBodiesAre400InvalidArgumentNeverA500() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        Map<String, Object> before = state(season);
        String uri = "/api/v1/seasons/" + season + "/lineup-changes";
        String[] bodies = {
                "{\"expectedTurnNo\": 1, \"assetId\": " + asset + "}",
                "{\"expectedTurnNo\": 1, \"assetId\": " + asset + ", \"declaredPosition\": null}",
                "{\"expectedTurnNo\": 1, \"assetId\": " + asset + ", \"declaredPosition\": \"STRIKER\"}",
                "{\"expectedTurnNo\": 1, \"assetId\": " + asset + ", \"declaredPosition\": \"defender\"}",
                "{\"expectedTurnNo\": 1, \"assetId\": 0, \"declaredPosition\": \"DEFENDER\"}",
                "{\"assetId\": " + asset + ", \"declaredPosition\": \"DEFENDER\"}",
                "{\"expectedTurnNo\": -1, \"assetId\": " + asset + ", \"declaredPosition\": \"DEFENDER\"}",
                "{\"expectedTurnNo\": 1, \"declaredPosition\": \"DEFENDER\"}",
                "{}", "", "[]"};
        for (String body : bodies) {
            MockHttpServletResponse r = post(uri, body);
            assertThat(r.getStatus()).as(body).isEqualTo(400);
            assertThat(code(r)).as(body).isEqualTo("INVALID_ARGUMENT");
        }
        assertThat(change(season, 1, asset, null).getStatus()).isEqualTo(400);
        assertThat(state(season)).isEqualTo(before);
    }

    @Test
    void aSeasonThatIsNotInProgressIs409SeasonNotActiveAndAnUnknownSeasonIs404() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        for (String status : new String[] {"ABANDONING", "HALTED", "FINISHED"}) {
            jdbc.update("UPDATE season SET status = ?, abandoned_turn_no = ?, current_turn_no = CASE WHEN ? = 'FINISHED'"
                    + " THEN path_length ELSE current_turn_no END WHERE id = ?", status,
                    status.equals("ABANDONING") ? 1 : null, status, season);
            Map<String, Object> before = state(season);
            MockHttpServletResponse r = change(season, 1, asset, "DEFENDER");
            assertThat(r.getStatus()).as(status).isEqualTo(409);
            assertThat(code(r)).as(status).isEqualTo("SEASON_NOT_ACTIVE");
            assertThat(state(season)).as(status).isEqualTo(before);
        }
        MockHttpServletResponse missing = change(999_999_999L, 1, asset, "DEFENDER");
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("NOT_FOUND");
    }

    @Test
    void anotherMembersSeasonIsTheSame404AsAMissingOne() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        long mine = jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season);
        jdbc.update("INSERT INTO member (email, name, password) VALUES ('other@example.test', 'other', 'x')");
        long stranger = jdbc.queryForObject("SELECT id FROM member WHERE email = 'other@example.test'", Long.class);
        jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", stranger, season);
        try {
            MockHttpServletResponse r = change(season, 1, asset, "DEFENDER");
            assertThat(r.getStatus()).isEqualTo(404);
            assertThat(code(r)).isEqualTo("NOT_FOUND");
        } finally {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", mine, season);
            jdbc.update("DELETE FROM member WHERE email = 'other@example.test'");
        }
        assertThat(lineupRows(season, "069500")).isEqualTo("MIDFIELDER:0:-");
    }

    @Test
    void numericAndOtherNonStringPositionsAre400() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        Map<String, Object> before = state(season);
        for (String value : new String[] {"1", "0", "1.0", "true", "[]", "{}"}) {
            MockHttpServletResponse r = post("/api/v1/seasons/" + season + "/lineup-changes",
                    "{\"expectedTurnNo\": 1, \"assetId\": " + asset + ", \"declaredPosition\": " + value + "}");
            assertThat(r.getStatus()).as(value).isEqualTo(400);
            assertThat(code(r)).as(value).isEqualTo("INVALID_ARGUMENT");
            assertThat(r.getContentType()).startsWith("application/problem+json");
        }
        assertThat(state(season)).isEqualTo(before);
    }

    @Test
    void aHeldAssetWithoutAnOpenSpanIs409LineupMismatchWithNoWrite() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "000660");
        jdbc.update("UPDATE lineup SET end_turn = 0 WHERE season_id = ? AND asset_id = ?", season, asset);
        Map<String, Object> before = state(season);
        MockHttpServletResponse r = change(season, 1, asset, "DEFENDER");
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("LINEUP_MISMATCH");
        assertThat(state(season)).isEqualTo(before);
    }

    @Test
    void aChangeThatWaitsBehindATurnAdvanceIsRefusedAsTurnMismatchAndWritesNothing() throws Exception {
        long season = seasonAtTurnOne();
        long asset = assetId(jdbc, "069500");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<MockHttpServletResponse>> response = new AtomicReference<>();
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                turnService.advance(season, 1);
                long base = LockWaitSupport.waiters(jdbc);
                response.set(pool.submit(() -> change(season, 1, asset, "DEFENDER")));
                try {
                    LockWaitSupport.awaitWaitersAbove(jdbc, base, 1);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                assertThat(response.get().isDone()).as("no answer before the lock is released").isFalse();
            });
            MockHttpServletResponse late = response.get().get(30, TimeUnit.SECONDS);
            assertThat(late.getStatus()).isEqualTo(409);
            assertThat(code(late)).isEqualTo("TURN_MISMATCH");
        } finally {
            pool.shutdownNow();
        }
        assertThat(lineupRows(season, "069500")).isEqualTo("MIDFIELDER:0:-");
        assertThat(matchRows(season, 2)).isEqualTo(17L);
    }
}
