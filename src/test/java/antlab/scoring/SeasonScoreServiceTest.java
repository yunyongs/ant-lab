package antlab.scoring;

import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lazy, ordered, idempotent scoring of finished seasons (MVP-18 PR B) with a fake engine. Seasons are the synthetic
 * 12-turn seasons of the turn tests; the expected numbers come from the canned engine counts and from table rows.
 */
class SeasonScoreServiceTest extends ScoreApiTestBase {

    private static JsonNode tree(String text) {
        return JsonMapper.shared().readTree(text);
    }

    // ---- A1: one season, one score row, one rating row, idempotent ---------------------------------------------

    @Test
    void 끝난_시즌_하나는_점수_행_하나와_레이팅_행_하나가_되고_두_번째_호출은_아무것도_쓰지_않는다() throws Exception {
        long season = finishedSeason();

        SeasonScoreService.Ensured first = scoring.ensureMember(memberId());

        assertThat(first.pendingSeasons()).isZero();
        assertThat(scoreRows()).isEqualTo(1);
        assertThat(ratingRows()).isEqualTo(1);
        assertThat(percentileCalls()).isEqualTo(1);
        Map<String, Object> row = scoreRow(season);
        assertThat(row.get("score_version")).isEqualTo("s63-v2");
        // 999 below + 2 equal of 2000: mid-rank (999 + 1) / 2000 = 0.5 exactly; the luck count is 500 below, none equal
        assertThat((BigDecimal) row.get("sharpe_diff_percentile")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) row.get("luck_percentile")).isEqualByComparingTo("0.25");
        assertThat((BigDecimal) row.get("result_score")).isEqualByComparingTo("50.00");
        assertThat(row.get("rating_exclusion")).isNull();
        assertThat(row.get("consistency_score")).isNull();
        assertThat(row.get("noise_score")).isNull();
        BigDecimal process = (BigDecimal) row.get("process_score");
        // score = 0.5 x 50 + 0.5 x process, rounded once (the stored process is already rounded: a tolerance of 0.01)
        assertThat(((BigDecimal) row.get("score")).subtract(BigDecimal.valueOf(25).add(process.divide(BigDecimal.TWO))).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.01"));
        long nonNullItems = List.of("risk_score", "discipline_score", "diversification_score", "turnover_score",
                "consistency_score", "formation_score", "role_score", "noise_score", "nudge_score").stream()
                .filter(c -> row.get(c) != null).count();
        assertThat(((Number) row.get("process_items")).longValue()).isEqualTo(nonNullItems);

        Map<String, Object> rating = jdbc.queryForMap("SELECT * FROM rating_history WHERE member_id = ?", memberId());
        assertThat(rating.get("seq_no")).isEqualTo(1);
        assertThat(((Number) rating.get("seasons_used")).intValue()).isEqualTo(1);
        assertThat((BigDecimal) rating.get("rating")).isEqualByComparingTo((BigDecimal) row.get("score"));
        assertThat(rating.get("pillar")).isEqualTo("MANAGER");

        SeasonScoreService.Ensured second = scoring.ensureMember(memberId());

        assertThat(second.pendingSeasons()).isZero();
        assertThat(scoreRows()).isEqualTo(1);
        assertThat(ratingRows()).isEqualTo(1);
        assertThat(percentileCalls()).as("a scored season never asks the engine again").isEqualTo(1);
    }

    @Test
    void 샤프_원값과_엔진_개수가_inputs_에_남고_시드와_달은_남지_않는다() throws Exception {
        long season = finishedSeason();
        scoring.ensureMember(memberId());

        String inputs = jdbc.queryForObject("SELECT inputs::text FROM season_score WHERE season_id = ?", String.class, season);
        JsonNode node = tree(inputs);

        assertThat(node.get("scoreVersion").asString()).isEqualTo("s63-v2");
        assertThat(node.at("/engine/n").asInt()).isEqualTo(PATHS);
        assertThat(node.at("/engine/endMultiple/below").asInt()).isEqualTo(500);
        assertThat(node.at("/engine/sharpeDiff/below").asInt()).isEqualTo(999);
        assertThat(node.at("/engine/sharpeDiff/equal").asInt()).isEqualTo(2);
        assertThat(node.at("/engine/projectionVersion").asString()).isEqualTo("m2-pj-v1");
        assertThat(node.at("/engine/historyDigest").asString()).isEqualTo(EnginePercentileAnswers.DIGEST);
        assertThat(node.get("noDistribution").isNull()).isTrue();
        // the raw (unrounded) Sharpe values, at least 20 decimals, so a capped column can still be audited
        for (String key : List.of("sharpe", "benchSharpe", "sharpeDiff")) {
            assertThat(node.at("/sharpeRaw/" + key).asString()).as(key).matches("-?\\d+(\\.\\d{20,})?");
        }
        assertThat(new BigDecimal(node.at("/sharpeRaw/sharpe").asString()).subtract(sharpeOfTeam(season)).abs().doubleValue())
                .as("team Sharpe against an independent computation").isLessThan(1e-9);
        assertThat(inputs).doesNotContain(seedOf(season)).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}")
                .doesNotContainPattern("\"\\d{4}-\\d{2}\"");
        assertThat(node.get("report").propertyNames()).contains("riskBreachTurns", "maxSingleWeight", "turnoverAnnual");
    }

    /** Sample Sharpe (mean / deviation with n - 1) of the monthly team returns, from the stored turn values. */
    private BigDecimal sharpeOfTeam(long season) {
        BigDecimal previous = jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season);
        List<BigDecimal> totals = jdbc.queryForList("SELECT total_value FROM turn WHERE season_id = ? ORDER BY turn_no",
                BigDecimal.class, season);
        List<Double> returns = new ArrayList<>();
        for (BigDecimal total : totals) {
            returns.add(total.subtract(previous).divide(previous, 30, RoundingMode.HALF_UP).doubleValue());
            previous = total;
        }
        double mean = returns.stream().mapToDouble(x -> x).average().orElseThrow();
        double variance = returns.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (returns.size() - 1);
        return variance == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(mean / Math.sqrt(variance));
    }

    // ---- A12: what the engine is asked ------------------------------------------------------------------------

    @Test
    void 엔진_요청은_시작_팀_비중과_십진_문자열_관측값을_싣고_다른_것은_싣지_않는다() throws Exception {
        long season = finishedSeason();
        // the start team from table rows: holdings at turn 0 prices (no trade after turn 0, so holdings are unchanged)
        List<Map<String, Object>> held = jdbc.queryForList("""
                SELECT a.market || ':' || a.ticker AS key, h.quantity AS qty, p0.price AS p0, pt.price AS pt
                FROM holding h JOIN asset a ON a.id = h.asset_id
                JOIN turn_price p0 ON p0.season_id = h.season_id AND p0.asset_id = h.asset_id AND p0.turn_no = 0
                JOIN turn_price pt ON pt.season_id = h.season_id AND pt.asset_id = h.asset_id AND pt.turn_no = 12
                WHERE h.season_id = ? AND h.quantity > 0 ORDER BY a.id""", season);
        BigDecimal cash = jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class, season);
        BigDecimal total0 = cash;
        BigDecimal totalT = cash;
        for (Map<String, Object> h : held) {
            BigDecimal qty = BigDecimal.valueOf(((Number) h.get("qty")).longValue());
            total0 = total0.add(qty.multiply((BigDecimal) h.get("p0")));
            totalT = totalT.add(qty.multiply((BigDecimal) h.get("pt")));
        }

        scoring.ensureMember(memberId());

        JsonNode request = lastRequest();
        assertThat(request.propertyNames()).containsExactlyInAnyOrder("seasonSeed", "baseDate", "horizonTurns", "n", "team",
                "benchmark", "observed");
        assertThat(request.get("seasonSeed").asString()).isEqualTo(seedOf(season));
        assertThat(request.get("baseDate").asString())
                .isEqualTo(jdbc.queryForObject("SELECT base_date::text FROM season WHERE id = ?", String.class, season));
        assertThat(request.get("horizonTurns").asInt()).isEqualTo(12);
        assertThat(request.get("n").asInt()).isEqualTo(PATHS);
        JsonNode team = request.get("team");
        assertThat(team.get("weights")).hasSize(held.size());
        BigDecimal sum = new BigDecimal(team.get("cash").asString());
        for (int i = 0; i < held.size(); i++) {
            BigDecimal value = BigDecimal.valueOf(((Number) held.get(i).get("qty")).longValue())
                    .multiply((BigDecimal) held.get(i).get("p0"));
            BigDecimal expected = value.divide(total0, 12, RoundingMode.DOWN);
            assertThat(team.get("weights").get(i).get("asset").asString()).isEqualTo(held.get(i).get("key"));
            assertThat(team.get("weights").get(i).get("weight").asString()).isEqualTo(expected.toPlainString());
            sum = sum.add(new BigDecimal(team.get("weights").get(i).get("weight").asString()));
        }
        assertThat(sum).as("cash plus weights is exactly 1").isEqualByComparingTo(BigDecimal.ONE);
        assertThat(request.at("/benchmark/cash").asString()).isEqualTo("0");
        assertThat(request.at("/benchmark/weights/0/asset").asString()).isEqualTo("KR:069500");
        assertThat(request.at("/benchmark/weights/0/weight").asString()).isEqualTo("0.5");
        assertThat(request.at("/benchmark/weights/1/asset").asString()).isEqualTo("US:SPY");
        assertThat(request.at("/benchmark/weights/1/weight").asString()).isEqualTo("0.5");
        JsonNode observed = request.get("observed");
        assertThat(observed.get("endMultiple").isString()).as("a decimal string, never a JSON number").isTrue();
        assertThat(observed.get("sharpeDiff").isString()).isTrue();
        assertThat(observed.get("endMultiple").asString())
                .isEqualTo(totalT.divide(total0, 12, RoundingMode.HALF_UP).toPlainString());
        JsonNode inputs = tree(jdbc.queryForObject("SELECT inputs::text FROM season_score WHERE season_id = ?", String.class, season));
        assertThat(new BigDecimal(observed.get("sharpeDiff").asString()))
                .isEqualByComparingTo(new BigDecimal(inputs.at("/sharpeRaw/sharpeDiff").asString()).setScale(12, RoundingMode.HALF_UP));
        assertThat(request.toString()).doesNotContain("score");
        assertThat(ENGINE.requests().get(0).header("X-Antlab-Engine-Token")).containsExactly(TOKEN);
    }

    // ---- A2: finish order ------------------------------------------------------------------------------------

    @Test
    void 순번은_시즌_번호가_아니라_끝난_순서를_따르고_엔진도_그_순서로_묻는다() throws Exception {
        long a = startedSeason();
        long b = startedSeason();
        long c = startedSeason();
        play(b, 0, TURNS);
        play(a, 0, TURNS);
        play(c, 0, TURNS);

        // the newest season is asked for first; the earlier ones are still scored before it
        MockHttpServletResponse r = getScore(c);

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(ratedSeasons()).containsExactly(b, a, c);
        List<String> asked = ENGINE.requests().stream().map(x -> tree(x.body()).get("seasonSeed").asString()).toList();
        assertThat(asked).containsExactly(seedOf(b), seedOf(a), seedOf(c));
        assertThat(jdbc.queryForList("SELECT seq_no FROM rating_history WHERE member_id = ? ORDER BY seq_no", Integer.class,
                memberId())).containsExactly(1, 2, 3);
    }

    // ---- A3: a transient failure stores nothing and stops the loop --------------------------------------------

    @Test
    void 엔진이_바쁘면_아무것도_저장하지_않고_첫_시즌에서_멈추며_나중에_순서대로_저장한다() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        engineBusy();

        SeasonScoreService.Ensured ensured = scoring.ensureMember(memberId());

        assertThat(ensured.pendingSeasons()).isEqualTo(2);
        assertThat(scoreRows()).isZero();
        assertThat(ratingRows()).isZero();
        assertThat(percentileCalls()).as("the loop stops at the first transient failure").isEqualTo(1);
        MockHttpServletResponse notReady = getScore(first);
        assertThat(notReady.getStatus()).isEqualTo(503);
        assertThat(code(notReady)).isEqualTo("ENGINE_UNAVAILABLE");
        MockHttpServletResponse later = getScore(second);
        assertThat(later.getStatus()).as("a later season is not scored before an earlier one").isEqualTo(503);
        assertThat(scoreRows()).isZero();

        engineAnswersMiddle();
        MockHttpServletResponse ok = getScore(second);

        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(scoreRows()).isEqualTo(2);
        assertThat(ratedSeasons()).containsExactly(first, second);
    }

    @Test
    void 엔진_바깥_실패와_나쁜_응답은_저장하지_않는다_400도_NO_SCORE로_저장하지_않는다() throws Exception {
        long season = finishedSeason();

        engineProblem(400, "problem-invalid-argument.json");
        assertThat(scoring.ensureMember(memberId()).pendingSeasons()).as("400 is a contract fault, not a refusal").isEqualTo(1);
        engineProblem(404, "problem-pool-not-found.json");
        assertThat(scoring.ensureMember(memberId()).pendingSeasons()).isEqualTo(1);
        engineAnswers(EnginePercentileAnswers.body(PATHS, TURNS, 1500, 600, 10, 0));
        assertThat(scoring.ensureMember(memberId()).pendingSeasons()).as("below + equal above n").isEqualTo(1);
        engineAnswers(EnginePercentileAnswers.middle(100, TURNS));
        assertThat(scoring.ensureMember(memberId()).pendingSeasons()).as("n differs from the request").isEqualTo(1);
        engineAnswers(EnginePercentileAnswers.middle(PATHS, 24));
        assertThat(scoring.ensureMember(memberId()).pendingSeasons()).as("horizon differs from the request").isEqualTo(1);
        engineAnswers("{\"projectionVersion\":\"m2-pj-v1\"}");
        assertThat(scoring.ensureMember(memberId()).pendingSeasons()).as("a body without the counts").isEqualTo(1);

        assertThat(scoreRows()).isZero();
        assertThat(ratingRows()).isZero();
        assertThat(getScore(season).getStatus()).isEqualTo(503);
    }

    // ---- A4: a deterministic refusal --------------------------------------------------------------------------

    @Test
    void 이력이_모자라_422이면_NO_SCORE_행을_남기고_다음_시즌은_계속_채점한다() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        ENGINE.queue(PERCENTILES, 422, "application/problem+json", FakeEngineServer.fixture("problem-path-too-long.json"));

        SeasonScoreService.Ensured ensured = scoring.ensureMember(memberId());

        assertThat(ensured.pendingSeasons()).isZero();
        Map<String, Object> refused = scoreRow(first);
        assertThat(refused.get("rating_exclusion")).isEqualTo("NO_SCORE");
        assertThat(refused.get("score")).isNull();
        assertThat(refused.get("result_score")).isNull();
        assertThat(refused.get("sharpe_diff_percentile")).isNull();
        assertThat(refused.get("luck_percentile")).isNull();
        assertThat((BigDecimal) refused.get("process_score")).isNotNull();
        JsonNode inputs = tree(jdbc.queryForObject("SELECT inputs::text FROM season_score WHERE season_id = ?", String.class, first));
        assertThat(inputs.get("noDistribution").asString()).isEqualTo("PATH_TOO_LONG");
        assertThat(inputs.at("/engine/n").isMissingNode()).isTrue();
        assertThat(scoreRow(second).get("rating_exclusion")).isNull();
        assertThat(ratedSeasons()).as("the refused season takes no place in the rating").containsExactly(second);
        assertThat(jdbc.queryForObject("SELECT seq_no FROM rating_history WHERE season_id = ?", Integer.class, second))
                .isEqualTo(1);
        MockHttpServletResponse r = getScore(first);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode dto = json(r);
        assertThat(dto.get("score").isNull()).isTrue();
        assertThat(dto.get("resultScore").isNull()).isTrue();
        assertThat(dto.get("countedInRating").asBoolean()).isFalse();
        assertThat(dto.get("ratingExclusion").asString()).isEqualTo("NO_SCORE");
    }

    // ---- A5: abandoned seasons ----------------------------------------------------------------------------------

    private void abandonAndFinish(long season, int atTurn) {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/abandon").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTurnNo\": " + atTurn + "}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
    }

    @Test
    void 사람이_3턴_전에_포기한_시즌은_점수는_받지만_레이팅에서_빠지고_3턴에_포기하면_집계된다() throws Exception {
        long early = startedSeason();
        play(early, 0, 2);
        abandonAndFinish(early, 2);
        long late = startedSeason();
        play(late, 0, 3);
        abandonAndFinish(late, 3);

        scoring.ensureMember(memberId());

        Map<String, Object> notStarted = scoreRow(early);
        assertThat(notStarted.get("rating_exclusion")).isEqualTo("NOT_STARTED");
        assertThat((BigDecimal) notStarted.get("score")).as("scored, only left out of the rating").isNotNull();
        assertThat(scoreRow(late).get("rating_exclusion")).isNull();
        assertThat(ratedSeasons()).containsExactly(late);
        JsonNode dto = json(getScore(early));
        assertThat(dto.get("countedInRating").asBoolean()).isFalse();
        assertThat(dto.get("ratingExclusion").asString()).isEqualTo("NOT_STARTED");
    }

    @Test
    void 한_시즌에서_포기한_뒤_자동으로_끝까지_간_시즌도_끝까지_보유한_것으로_채점한다() throws Exception {
        long season = startedSeason();
        abandonAndFinish(season, 0);

        scoring.ensureMember(memberId());

        Map<String, Object> row = scoreRow(season);
        assertThat(row.get("rating_exclusion")).isEqualTo("NOT_STARTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn WHERE season_id = ?", Integer.class, season)).isEqualTo(TURNS);
        assertThat(ratingRows()).isZero();
    }

    // ---- A6: not finished ---------------------------------------------------------------------------------------

    @Test
    void 끝나지_않은_시즌은_409이고_행도_엔진_호출도_없다() throws Exception {
        long running = startedSeason();
        play(running, 0, 2);
        long abandoning = startedSeason();
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = current_turn_no WHERE id = ?", abandoning);
        long halted = startedSeason();
        jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", halted);

        for (long season : new long[] {running, abandoning, halted}) {
            MockHttpServletResponse r = getScore(season);
            assertThat(r.getStatus()).as("season " + season).isEqualTo(409);
            assertThat(code(r)).isEqualTo("SEASON_NOT_FINISHED");
        }
        assertThat(scoreRows()).isZero();
        assertThat(percentileCalls()).isZero();
    }

    // ---- A8: two callers at once --------------------------------------------------------------------------------

    @Test
    void 두_호출이_동시에_와도_행은_시즌마다_하나이고_순번은_이어진다() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        ENGINE.delay(Duration.ofMillis(250));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<SeasonScoreService.Ensured> call = () -> scoring.ensureMember(memberId());
            List<Future<SeasonScoreService.Ensured>> results = pool.invokeAll(List.of(call, call));
            for (Future<SeasonScoreService.Ensured> f : results) {
                assertThat(f.get().pendingSeasons()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(scoreRows()).isEqualTo(2);
        assertThat(ratingRows()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT seq_no FROM rating_history WHERE member_id = ? ORDER BY seq_no", Integer.class,
                memberId())).containsExactly(1, 2);
        assertThat(ratedSeasons()).containsExactly(first, second);
    }

    // ---- A11: the turn path never touches the engine ------------------------------------------------------------

    @Test
    void 엔진이_멈춰_있어도_마지막_턴은_커밋되고_턴_경로는_엔진을_부르지_않는다() throws Exception {
        engineBusy();
        long season = startedSeason();

        play(season, 0, TURNS);

        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
        assertThat(ENGINE.requests()).as("no call at all while the turns ran").isEmpty();
        assertThat(scoreRows()).isZero();
        assertThat(getScore(season).getStatus()).isEqualTo(503);
        engineAnswersMiddle();
        assertThat(getScore(season).getStatus()).isEqualTo(200);
    }

    // ---- the member lock ----------------------------------------------------------------------------------------

    @Test
    void 점수_쓰기는_회원_행_잠금이_풀릴_때까지_기다린다() throws Exception {
        finishedSeason();
        var tx = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = tx.execute(status -> {
                jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR NO KEY UPDATE", Long.class, memberId());
                Future<?> f = pool.submit(() -> scoring.ensureMember(memberId()));
                long deadline = System.currentTimeMillis() + 10_000;
                while (percentileCalls() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.onSpinWait();
                }
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                assertThat(scoreRows()).as("the write waits for the member lock").isZero();
                return f;
            });
            writer.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(scoreRows()).isEqualTo(1);
    }

    // ---- guards -------------------------------------------------------------------------------------------------

    @Test
    void 점수_서비스는_트랜잭션_안에서는_거절한다() {
        var tx = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> scoring.ensureMember(memberId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(percentileCalls()).isZero();
    }

    @Test
    void 사용_중인_훈련_원천은_계산된_매매_규율이다() {
        Object target = AopTestUtils.getUltimateTargetObject(disciplineSource);
        assertThat(target).isInstanceOf(CalculatedDisciplineSource.class);
    }

    @Autowired
    DisciplineSource disciplineSource;
}
