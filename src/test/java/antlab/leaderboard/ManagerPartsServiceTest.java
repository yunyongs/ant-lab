package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.money.MoneyMath;
import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineManagerPartsRequest;
import antlab.engine.client.EngineProperties;
import antlab.leaderboard.ManagerPartsService.Outcome;
import antlab.leaderboard.ManagerPartsService.Run;
import antlab.support.FakeEngineServer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * One finished season per pass, against a fake engine: a ranked row stored as sent from the request built of stored rows,
 * each exclusion kind, the three failure classes (also while scoring), answer checks, the queue order and the member's
 * finish order, the transaction guard, the advisory lock shared with the job (also after a failed unlock), and the
 * single-asset-limit refusal.
 */
@ExtendWith(OutputCaptureExtension.class)
class ManagerPartsServiceTest extends ManagerPartsTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ManagerPartsQuery queue;
    @Autowired
    ManagerPartsReader reader;
    @Autowired
    ManagerPartsWriter writer;
    @Autowired
    EngineClient engine;
    @Autowired
    EngineProperties engineProperties;
    @Autowired
    JdbcClient jdbcClient;

    private Run runOne() {
        return parts.run(1, List.of());
    }

    @Test
    void aFinishedSeasonIsSentAsStoredAndItsAnswerIsStoredAsSent() throws Exception {
        long season = finishedSeason();
        scoreAll();
        partsAnswer(200, goodAnswer(season));

        Run run = runOne();

        assertThat(run.status()).isEqualTo(ManagerPartsRunStatus.DONE);
        assertThat(run.attempts()).containsExactly(new ManagerPartsService.Attempt(season, Outcome.RANKED));
        assertThat(run.pending()).isZero();
        assertThat(partsCalls()).isEqualTo(1);

        JsonNode sent = lastPartsRequest();
        assertThat(sent.get("referenceVersion").asString()).isEqualTo("ref69-v1");
        List<String> months = jdbc.queryForList("""
                SELECT mb.month_start::text FROM season_path sp JOIN month_block mb ON mb.id = sp.month_block_id
                WHERE sp.season_id = ? ORDER BY sp.turn_no""", String.class, season);
        assertThat(strings(sent.get("months"))).isEqualTo(months);
        List<BigDecimal> totals = jdbc.queryForList("SELECT total_value FROM turn WHERE season_id = ? AND turn_no >= 1 ORDER BY turn_no",
                BigDecimal.class, season);
        assertThat(strings(sent.get("team").get("totals"))).isEqualTo(totals.stream().map(BigDecimal::toPlainString).toList());
        Map<String, Object> facts = jdbc.queryForMap("SELECT initial_cash, path_length FROM season WHERE id = ?", season);
        BigDecimal initial = (BigDecimal) facts.get("initial_cash");
        assertThat(sent.get("settings").get("initialCash").asString()).isEqualTo(initial.toPlainString());
        assertThat(sent.get("settings").get("memberKind").asString()).isEqualTo("HUMAN");
        // the first return is against the initial cash, unrounded at the score scale (30 places)
        assertThat(sent.get("team").get("returns").get(0).asString())
                .isEqualTo(MoneyMath.scoreDivide(totals.get(0).subtract(initial), initial).toPlainString());
        Map<String, Object> score = jdbc.queryForMap("""
                SELECT sharpe::text AS sharpe, bench_sharpe::text AS bench, risk_score::text AS risk, process_score::text AS process,
                       score_version FROM season_score WHERE season_id = ?""", season);
        assertThat(sent.get("team").get("sharpe").asString()).isEqualTo(score.get("sharpe"));
        assertThat(sent.get("team").get("benchSharpe").asString()).isEqualTo(score.get("bench"));
        JsonNode risk = sent.get("team").get("riskScore");
        assertThat(risk.isNull() ? null : risk.asString()).isEqualTo(score.get("risk"));

        Map<String, Object> row = partsRow(season);
        assertThat(row.get("exclusion")).isNull();
        assertThat(row.get("reference_key")).isEqualTo("3f".repeat(32));
        assertThat(((Number) row.get("reference_size")).intValue()).isEqualTo(200);
        assertThat(row.get("bot_version")).isEqualTo("random/1");
        assertThat(row.get("engine_version")).isEqualTo("0.1.0");
        assertThat(row.get("pct_return")).isEqualTo("71.25000000");
        assertThat(row.get("part_return")).isEqualTo("71.25000000");
        assertThat(row.get("part_risk_adjusted")).isEqualTo("41.00000000");
        assertThat(row.get("calmar_diff")).isEqualTo("-999998.476190466190");
        assertThat(row.get("pct_risk_item")).isEqualTo(hasRiskItem(season) ? "50.00000000" : null);
        assertThat(row.get("part_process")).isEqualTo(score.get("process"));
        assertThat(row.get("process_score_version")).isEqualTo(score.get("score_version"));
        assertThat(row.get("final_multiple")).isEqualTo(MoneyMath.ratio(totals.get(totals.size() - 1), initial).toPlainString());

        Run again = runOne();
        assertThat(again.attempts()).isEmpty();
        assertThat(partsCalls()).as("a stored season is never sent again").isEqualTo(1);
    }

    @Test
    void anUnscoredSeasonIsScoredFirstAndOnlyThatSeason() throws Exception {
        long season = finishedSeason();
        long later = finishedSeason();
        assertThat(scoreRows(season)).isZero();
        partsAnswer(200, FakeEngineServer.fixture("manager-parts-response.json"));
        // the answer may not fit the risk item; either way the score row is written first
        runOne();
        assertThat(scoreRows(season)).isOne();
        assertThat(scoreRows(later)).as("a run of one scores one season, not the member's whole backlog").isZero();
        assertThat(scoreCalls()).isOne();
    }

    @Test
    void anOutageWhileScoringStopsTheRunAsAnOutage() throws Exception {
        finishedSeason();
        finishedSeason();
        ENGINE.respond(PERCENTILES, 503, "application/problem+json", FakeEngineServer.fixture("problem-engine-busy.json"));

        Run run = parts.run(2, List.of());

        assertThat(run.status()).isEqualTo(ManagerPartsRunStatus.ENGINE_UNAVAILABLE);
        assertThat(run.attempts()).as("an outage is no failure of the season").isEmpty();
        assertThat(run.pending()).isEqualTo(2);
        assertThat(scoreCalls()).as("the second season is not tried after the outage").isOne();
        assertThat(partsCalls()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_score", Long.class)).isZero();
    }

    @Test
    void anEarlierSeasonThatFailedToScoreIsNotTriedAgainForALaterOne() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        scoreAnswerOnce(400, "problem-invalid-argument.json");
        partsAnswer(200, FakeEngineServer.fixture("manager-parts-response.json"));

        assertThat(runOne().attempts()).containsExactly(new ManagerPartsService.Attempt(first, Outcome.FAILED));
        assertThat(scoreCalls()).isOne();

        // the job's wait for the first season: the second one waits with it, with no engine call
        Run waiting = parts.run(2, List.of(first));
        assertThat(waiting.status()).isEqualTo(ManagerPartsRunStatus.DONE);
        assertThat(waiting.attempts()).isEmpty();
        assertThat(waiting.pending()).isEqualTo(2);
        assertThat(scoreCalls()).isOne();
        assertThat(partsCalls()).isZero();
        assertThat(scoreRows(second)).isZero();

        runOne();
        assertThat(scoreRows(first)).as("after its wait the first season is scored first").isOne();
        assertThat(scoreRows(second)).isZero();
        assertThat(partsCalls()).isOne();
    }

    @Test
    void aHumanSeasonThatDidNotStartIsExcludedWithoutAnEngineCall() throws Exception {
        long season = notStartedSeason();

        Run run = runOne();

        assertThat(run.attempts()).containsExactly(new ManagerPartsService.Attempt(season, Outcome.EXCLUDED));
        assertThat(partsCalls()).isZero();
        Map<String, Object> row = partsRow(season);
        assertThat(row.get("exclusion")).isEqualTo("NOT_STARTED");
        assertThat(row.get("reference_key")).isNull();
        assertThat(row.get("part_return")).isNull();
        assertThat(row.get("final_multiple")).isNotNull();
    }

    @Test
    void eachEngineRefusalIsStoredAsItsExclusion() throws Exception {
        long mismatch = finishedSeason();
        long feed = finishedSeason();
        long noReference = finishedSeason();
        scoreAll();
        partsAnswerOnce(409, FakeEngineServer.fixture("problem-reference-mismatch.json"));
        partsAnswerOnce(422, FakeEngineServer.fixture("problem-feed-input.json"));
        partsAnswerOnce(422, FakeEngineServer.fixture("problem-no-reference.json"));

        Run run = parts.run(3, List.of());

        assertThat(run.status()).isEqualTo(ManagerPartsRunStatus.DONE);
        assertThat(run.count(Outcome.EXCLUDED)).isEqualTo(3);
        assertThat(partsRow(mismatch).get("exclusion")).isEqualTo("REFERENCE_MISMATCH");
        assertThat(partsRow(feed).get("exclusion")).isEqualTo("NO_REFERENCE");
        assertThat(partsRow(noReference).get("exclusion")).isEqualTo("NO_REFERENCE");
    }

    @Test
    void anOutageStoresNothingAndStopsTheRun() throws Exception {
        finishedSeason();
        finishedSeason();
        scoreAll();
        partsAnswer(503, FakeEngineServer.fixture("problem-engine-busy.json"));

        Run run = parts.run(2, List.of());

        assertThat(run.status()).isEqualTo(ManagerPartsRunStatus.ENGINE_UNAVAILABLE);
        assertThat(run.attempts()).isEmpty();
        assertThat(run.pending()).isEqualTo(2);
        assertThat(partsCalls()).as("the second season is not tried after the outage").isEqualTo(1);
        assertThat(partsRows()).isZero();
    }

    @Test
    void aJavaFaultOrABadAnswerFailsThatSeasonOnlyAndTheRunGoesOn() throws Exception {
        long badRequest = finishedSeason();
        long wrongRiskItem = finishedSeason();
        long breaksCheck = finishedSeason();
        long fine = finishedSeason();
        scoreAll();
        String good = goodAnswer(wrongRiskItem);
        String wrong = good.contains("\"riskItem\":null")
                ? FakeEngineServer.fixture("manager-parts-response.json")
                : FakeEngineServer.fixture("manager-parts-response-no-risk-item.json");
        partsAnswerOnce(400, FakeEngineServer.fixture("problem-invalid-argument.json"));
        partsAnswerOnce(200, wrong);
        // passes the wire pattern but not ck_season_manager_parts (a drawdown is never positive)
        partsAnswerOnce(200, goodAnswer(breaksCheck).replace("\"mdd\":\"-0.052500000000\"", "\"mdd\":\"0.052500000000\""));
        partsAnswer(200, goodAnswer(fine));

        Run run = parts.run(4, List.of());

        assertThat(run.attempts()).containsExactly(new ManagerPartsService.Attempt(badRequest, Outcome.FAILED),
                new ManagerPartsService.Attempt(wrongRiskItem, Outcome.FAILED),
                new ManagerPartsService.Attempt(breaksCheck, Outcome.FAILED),
                new ManagerPartsService.Attempt(fine, Outcome.RANKED));
        assertThat(run.pending()).isEqualTo(3);
        assertThat(partsRows()).isOne();
    }

    @Test
    void theEarliestFinishGoesFirstAndSkippedSeasonsWait() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        scoreAll();
        partsAnswer(200, goodAnswer(second));

        assertThat(parts.run(1, List.of(first)).attempts()).extracting(ManagerPartsService.Attempt::seasonId)
                .containsExactly(second);
        partsAnswer(200, goodAnswer(first));
        assertThat(parts.run(1, List.of()).attempts()).extracting(ManagerPartsService.Attempt::seasonId).containsExactly(first);
    }

    @Test
    void aRunInsideATransactionIsRefused() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> runOne())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parts.run(0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parts.run(21, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSecondRunSkipsWhileTheLockIsHeld() throws Exception {
        long season = finishedSeason();
        scoreAll();
        partsAnswer(200, goodAnswer(season));
        try (Connection other = dataSource.getConnection(); Statement st = other.createStatement()) {
            other.setAutoCommit(true);
            try (ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(hashtextextended('" + ManagerPartsService.LOCK_KEY
                    + "', 0))")) {
                assertThat(rs.next() && rs.getBoolean(1)).isTrue();
            }

            assertThat(lockHolders()).as("the witness query sees the held lock").isOne();
            Run skipped = runOne();

            assertThat(skipped.status()).isEqualTo(ManagerPartsRunStatus.ALREADY_RUNNING);
            assertThat(skipped.pending()).isOne();
            assertThat(partsCalls()).isZero();
            st.execute("SELECT pg_advisory_unlock(hashtextextended('" + ManagerPartsService.LOCK_KEY + "', 0))");
        }
        assertThat(runOne().attempts()).extracting(ManagerPartsService.Attempt::outcome).containsExactly(Outcome.RANKED);
    }

    @Test
    void laterSeasonsOfAWaitingSeasonAreLeftOutByTheQueueItself() throws Exception {
        long first = finishedSeason();
        finishedSeason();
        finishedSeason();
        scoreAnswerOnce(400, "problem-invalid-argument.json");
        assertThat(runOne().attempts()).containsExactly(new ManagerPartsService.Attempt(first, Outcome.FAILED));
        int calls = scoreCalls();

        Run waiting = parts.run(1, List.of(first));

        assertThat(waiting.status()).isEqualTo(ManagerPartsRunStatus.DONE);
        assertThat(waiting.attempts()).isEmpty();
        assertThat(waiting.pending()).isEqualTo(3);
        assertThat(queue.next(EngineManagerPartsRequest.REFERENCE_VERSION, List.of(first), List.of()))
                .as("the later seasons wait in SQL, not one lookup each").isEmpty();
        assertThat(scoreCalls()).isEqualTo(calls);
        assertThat(partsCalls()).isZero();
    }

    @Test
    void aRunPassesOverAtMostTwentyWaitingSeasons(CapturedOutput output) throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        // a queue that keeps offering a season that must wait, as if many members each had an earlier unscored season
        List<Long> offered = new ArrayList<>();
        ManagerPartsQuery waitingEverywhere = new ManagerPartsQuery(jdbcClient) {
            @Override
            Optional<Pending> next(String referenceVersion, Collection<Long> skip, Collection<Long> skipMembers) {
                offered.add(second);
                return Optional.of(new Pending(second, memberId()));
            }
        };
        ManagerPartsService service = new ManagerPartsService(dataSource, waitingEverywhere, reader, writer, scoring, engine,
                engineProperties, null);

        Run run = service.run(1, List.of(first));

        assertThat(run.status()).isEqualTo(ManagerPartsRunStatus.DONE);
        assertThat(run.attempts()).isEmpty();
        assertThat(offered).hasSize(ManagerPartsService.MAX_WAITING_SKIPS);
        assertThat(scoreCalls()).isZero();
        assertThat(partsCalls()).isZero();
        assertThat(output.getAll()).contains("manager_parts.scan_budget_spent skipped=20");
    }

    @Test
    void aFailedUnlockDiscardsTheConnectionSoAnotherConnectionGetsTheLock() throws Exception {
        long season = finishedSeason();
        scoreAll();
        partsAnswer(200, goodAnswer(season));
        ManagerPartsService cancelledUnlock = new ManagerPartsService(dataSource, queue, reader, writer, scoring, engine,
                engineProperties, null) {
            @Override
            boolean unlock(Connection lock) throws SQLException {
                throw new SQLException("canceling statement due to user request", "57014");
            }
        };

        assertThat(cancelledUnlock.run(1, List.of()).count(Outcome.RANKED)).isOne();

        assertThat(lockFreeWithin(Duration.ofSeconds(5))).as("the session that held the lock is gone, not pooled").isTrue();
        long next = finishedSeason();
        scoreAll();
        partsAnswer(200, goodAnswer(next));
        Run after = runOne();
        assertThat(after.status()).isEqualTo(ManagerPartsRunStatus.DONE);
        assertThat(after.attempts()).containsExactly(new ManagerPartsService.Attempt(next, Outcome.RANKED));
    }

    /**
     * Sessions that hold the run lock, the asking one included. A try-lock from a pooled connection is no witness: the lock
     * is re-entrant, so the session that kept it would get it again.
     */
    private long lockHolders() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks l, LATERAL (SELECT hashtextextended(?, 0) AS h) k
                WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1
                  AND l.classid = ((k.h >> 32) & 4294967295)::oid AND l.objid = (k.h & 4294967295)::oid""", Long.class,
                ManagerPartsService.LOCK_KEY);
    }

    /** Waits for the pool to close an evicted connection, which it does shortly after the eviction. */
    private boolean lockFreeWithin(Duration wait) throws InterruptedException {
        long end = System.nanoTime() + wait.toNanos();
        while (lockHolders() > 0) {
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }

    @Test
    void aSingleAssetLimitRefusesTheRunAndLogsOncePerRun(CapturedOutput output) throws Exception {
        finishedSeason();
        scoreAll();
        partsAnswer(200, FakeEngineServer.fixture("manager-parts-response.json"));
        ManagerPartsService limited = new ManagerPartsService(dataSource, queue, reader, writer, scoring, engine,
                engineProperties, new BigDecimal("0.40"));
        int before = count(output.getAll(), "manager_parts.refused reason=single_asset_limit");

        Run run = limited.run(5, List.of());

        assertThat(run.status()).isEqualTo(ManagerPartsRunStatus.REFUSED);
        assertThat(run.attempts()).isEmpty();
        assertThat(run.pending()).isOne();
        assertThat(partsCalls()).isZero();
        assertThat(partsRows()).isZero();
        assertThat(count(output.getAll(), "manager_parts.refused reason=single_asset_limit") - before).isOne();
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
