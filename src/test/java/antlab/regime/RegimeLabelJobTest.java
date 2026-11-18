package antlab.regime;

import static antlab.regime.RegimeFixtures.PATH;
import static antlab.regime.RegimeFixtures.edited;
import static antlab.regime.RegimeFixtures.month;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.error.DomainException;
import antlab.engine.client.EngineUnavailableException;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The label job against a fake engine and the real schema: fills from NULL, is idempotent, refuses a stored value that differs
 * and any answer that is not the one asked for, and in every failure leaves the table exactly as it was. Rows are committed
 * (the job has its own transaction), so each test removes what it inserted.
 */
@IntegrationTest
class RegimeLabelJobTest {

    private static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> TOKEN);
        registry.add("antlab.engine.regime-timeout", () -> "1s");
    }

    @AfterAll
    static void stop() {
        ENGINE.close();
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RegimeLabelJob job;
    @Autowired
    MonthRegimeWriter writer;

    @BeforeEach
    void clean() {
        ENGINE.reset();
        jdbc.update("DELETE FROM month_block");
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("ALTER TABLE month_block DROP CONSTRAINT IF EXISTS zz_probe_no_p4");
        jdbc.update("DELETE FROM month_block");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void months(String... yearMonths) {
        for (String month : yearMonths) {
            jdbc.update("INSERT INTO month_block (month_start) VALUES (?::date)", month + "-01");
        }
    }

    private void serve(ObjectNode answer) {
        ENGINE.respond(PATH, 200, "application/json", answer.toString());
    }

    private void serveFixture() {
        ENGINE.respondFixture(PATH, 200, "regime-response.json");
    }

    /** Every row with its xmin, so even an UPDATE that rewrites the same values shows. */
    private List<String> snapshot() {
        return jdbc.queryForList("SELECT to_jsonb(t)::text || ' xmin=' || t.xmin::text FROM month_block t ORDER BY month_start", String.class);
    }

    private Map<String, Object> row(String yearMonth) {
        return jdbc.queryForMap("""
                SELECT rate_dir, growth_dir, fx_dir, sentiment_dir, rate_strength::text AS rate_strength,
                       growth_strength::text AS growth_strength, fx_strength::text AS fx_strength,
                       sentiment_strength::text AS sentiment_strength, prob_p1::text AS prob_p1, prob_p2::text AS prob_p2,
                       prob_p3::text AS prob_p3, prob_p4::text AS prob_p4, regime_label, indicator_z::text AS indicator_z,
                       indicator_z ->> '1' AS z1, indicator_z ->> '3' AS z3, indicator_z ->> '14US' AS z14us,
                       jsonb_typeof(indicator_z -> '2') AS z2_type, jsonb_typeof(indicator_z -> '1') AS z1_type, jsonb_typeof(indicator_z) AS z_type
                FROM month_block WHERE month_start = ?::date""", yearMonth + "-01");
    }

    @Test
    void nullRowsAreFilledWithTheDigitsOfTheAnswer() {
        months("1997-02", "1997-01");
        serveFixture();

        RegimeLabelJob.Outcome outcome = job.run();

        assertThat(outcome).isEqualTo(new RegimeLabelJob.Outcome(2, 1, 1, 1));
        Map<String, Object> evaluated = row("1997-01");
        assertThat(evaluated).containsEntry("rate_dir", "UP").containsEntry("rate_strength", "0.41250000")
                .containsEntry("growth_dir", "NEUTRAL").containsEntry("growth_strength", "0.10000000")
                .containsEntry("fx_dir", "DOWN").containsEntry("fx_strength", "0.30000000")
                .containsEntry("sentiment_dir", null).containsEntry("sentiment_strength", null)
                .containsEntry("prob_p1", "0.2731").containsEntry("prob_p2", "0.1284").containsEntry("prob_p3", "0.1907")
                .containsEntry("prob_p4", "0.4078").containsEntry("regime_label", "P4")
                .containsEntry("z_type", "object").containsEntry("z1", "0.52000000").containsEntry("z3", "-1.15069709")
                .containsEntry("z14us", "-0.30000000").containsEntry("z2_type", "null").containsEntry("z1_type", "number");
        Map<String, Object> noData = row("1997-02");
        assertThat(noData).containsEntry("rate_dir", null).containsEntry("growth_dir", null).containsEntry("fx_dir", null)
                .containsEntry("sentiment_dir", null).containsEntry("fx_strength", null).containsEntry("prob_p1", null)
                .containsEntry("regime_label", null).containsEntry("indicator_z", null).containsEntry("z_type", null);
    }

    @Test
    void theEngineIsAskedOnceWithTheTokenAndEveryMonthInCalendarOrder() {
        months("1997-02", "1997-01");
        serveFixture();

        job.run();

        assertThat(ENGINE.requests()).hasSize(1);
        FakeEngineServer.Recorded sent = ENGINE.requests().getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo(PATH);
        assertThat(sent.header("X-Antlab-Engine-Token")).containsExactly(TOKEN);
        assertThat(RegimeFixtures.MAPPER.readTree(sent.body())).isEqualTo(RegimeFixtures.MAPPER.readTree("{\"labelVersion\":\"m2-rl-v1\","
                + "\"months\":[\"1997-01\",\"1997-02\"]}"));
    }

    @Test
    void aSecondRunWithTheSameAnswerChangesNothingAndTouchesNoRow() {
        months("1997-01", "1997-02");
        serveFixture();
        job.run();
        List<String> afterFirst = snapshot();

        RegimeLabelJob.Outcome outcome = job.run();

        assertThat(outcome).isEqualTo(new RegimeLabelJob.Outcome(2, 1, 0, 2));
        assertThat(snapshot()).as("same values and same row versions").isEqualTo(afterFirst);
        assertThat(ENGINE.requests()).hasSize(2);
    }

    @Test
    void aStoredValueThatDiffersStopsTheRunAndNothingIsWritten() {
        months("1997-01", "1997-02");
        jdbc.update("UPDATE month_block SET rate_dir = 'DOWN', rate_strength = 0.5 WHERE month_start = '1997-01-01'");
        serveFixture();
        List<String> before = snapshot();

        assertThatThrownBy(job::run).isInstanceOf(MonthRegimeWriter.ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("rate_dir").doesNotContain("1997").doesNotContain("DOWN").doesNotContain("0.5"));

        assertThat(snapshot()).as("the second month was not filled either").isEqualTo(before);
    }

    @Test
    void aConflictOnTheLastRowStillLeavesTheEarlierRowsUntouched() {
        months("1997-01", "1997-02");
        jdbc.update("UPDATE month_block SET fx_dir = 'UP', fx_strength = 0.9 WHERE month_start = '1997-02-01'");
        serveFixture();
        List<String> before = snapshot();

        assertThatThrownBy(job::run).isInstanceOf(MonthRegimeWriter.ConflictException.class);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aStoredValueWhereTheAnswerHasNoneIsAlsoADifference() {
        months("1997-01", "1997-02");
        jdbc.update("UPDATE month_block SET rate_dir = 'UP', rate_strength = 0.1 WHERE month_start = '1997-02-01'");
        serveFixture();

        assertThatThrownBy(job::run).isInstanceOf(MonthRegimeWriter.ConflictException.class);
    }

    @Test
    void aStoredIndicatorThatDiffersIsADifference() {
        months("1997-01", "1997-02");
        serveFixture();
        job.run();
        jdbc.update("UPDATE month_block SET indicator_z = jsonb_set(indicator_z, '{1}', '0.52000001') WHERE month_start = '1997-01-01'");

        assertThatThrownBy(job::run).isInstanceOf(MonthRegimeWriter.ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("indicator_z"));
    }

    @Test
    void aRowThatAlreadyHoldsPartOfTheAnswerGetsTheRestAndKeepsWhatItHas() {
        months("1997-01", "1997-02");
        jdbc.update("UPDATE month_block SET rate_dir = 'UP', rate_strength = 0.4125 WHERE month_start = '1997-01-01'");
        serveFixture();

        RegimeLabelJob.Outcome outcome = job.run();

        assertThat(outcome).isEqualTo(new RegimeLabelJob.Outcome(2, 1, 1, 1));
        assertThat(row("1997-01")).containsEntry("rate_strength", "0.41250000").containsEntry("regime_label", "P4");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "NO_DATA", "CARRIED" })
    void aMonthWithoutAnEvaluationStaysEmptySoALaterFullerAnswerFillsIt(String status) {
        months("1997-01", "1997-02");
        serve(edited(n -> {
            month(n, 1).put("status", status);
            if (status.equals("CARRIED")) {
                ((ObjectNode) month(n, 1).get("axes")).set("sentiment", month(n, 0).get("axes").get("fx"));
                ((ObjectNode) n.get("coverage")).put("carried", 1).put("noData", 0);
            }
        }));

        assertThat(job.run()).isEqualTo(new RegimeLabelJob.Outcome(2, 1, 1, 1));
        assertThat(row("1997-02")).containsEntry("fx_dir", null).containsEntry("sentiment_dir", null)
                .containsEntry("regime_label", null).containsEntry("indicator_z", null);

        serve(edited(n -> {
            ObjectNode second = month(RegimeFixtures.tree(), 0).deepCopy();
            second.put("month", "1997-02");
            ((ArrayNode) n.get("months")).set(1, second);
            ((ObjectNode) n.get("coverage")).put("evaluated", 2).put("carried", 0).put("noData", 0);
        }));

        assertThat(job.run()).isEqualTo(new RegimeLabelJob.Outcome(2, 2, 1, 1));
        assertThat(row("1997-02")).containsEntry("regime_label", "P4").containsEntry("z1", "0.52000000").containsEntry("fx_dir", "DOWN");
    }

    @Test
    void aFailureInTheMiddleOfTheWriteRollsBackTheRowsWrittenBefore() {
        months("1996-12", "1997-01");
        ObjectNode answer = edited(n -> {
            ArrayNode list = (ArrayNode) n.get("months");
            ObjectNode first = month(n, 0).deepCopy();
            first.put("month", "1996-12").put("label", "P1");
            list.removeAll();
            list.add(first);
            list.add(month(RegimeFixtures.tree(), 0));
            ((ObjectNode) n.get("coverage")).put("evaluated", 2).put("noData", 0);
        });
        serve(answer);
        // the second row is the one with label P4, so the database refuses it after the first row was updated
        jdbc.execute("ALTER TABLE month_block ADD CONSTRAINT zz_probe_no_p4 CHECK (regime_label IS DISTINCT FROM 'P4') NOT VALID");
        List<String> before = snapshot();

        assertThatThrownBy(job::run).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(snapshot()).as("the first row went back too").isEqualTo(before);
        assertThat(row("1996-12")).containsEntry("regime_label", null);
    }

    private static Stream<Arguments> answersThatAreNotTheOneAsked() {
        return Stream.of(
                Arguments.of("other label version", (Consumer<ObjectNode>) n -> n.put("labelVersion", "m2-rl-v2")),
                Arguments.of("month missing", (Consumer<ObjectNode>) n -> ((ArrayNode) n.get("months")).remove(1)),
                Arguments.of("month added", (Consumer<ObjectNode>) n -> ((ArrayNode) n.get("months")).add(month(n, 1).deepCopy())),
                Arguments.of("months swapped", (Consumer<ObjectNode>) n -> {
                    ArrayNode list = (ArrayNode) n.get("months");
                    var first = list.get(0);
                    list.set(0, list.get(1));
                    list.set(1, first);
                }),
                Arguments.of("probability with five digits", (Consumer<ObjectNode>) n ->
                        ((ObjectNode) month(n, 0).get("probabilities")).put("p1", "0.27310")),
                Arguments.of("probability sum off by 0.001", (Consumer<ObjectNode>) n ->
                        ((ObjectNode) month(n, 0).get("probabilities")).put("p1", "0.2741")),
                Arguments.of("direction without strength", (Consumer<ObjectNode>) n ->
                        ((ObjectNode) month(n, 0).get("axes").get("rate")).remove("strength")),
                Arguments.of("label without probabilities", (Consumer<ObjectNode>) n -> month(n, 0).putNull("probabilities")),
                Arguments.of("unknown direction", (Consumer<ObjectNode>) n ->
                        ((ObjectNode) month(n, 0).get("axes").get("rate")).put("direction", "SIDEWAYS")),
                Arguments.of("unknown label", (Consumer<ObjectNode>) n -> month(n, 0).put("label", "P5")),
                Arguments.of("an indicator key too many", (Consumer<ObjectNode>) n -> ((ObjectNode) month(n, 0).get("indicators")).put("15", "0.00000000")),
                Arguments.of("an indicator key missing", (Consumer<ObjectNode>) n -> ((ObjectNode) month(n, 0).get("indicators")).remove("14KR")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answersThatAreNotTheOneAsked")
    void anAnswerThatIsNotTheOneAskedForIsRefusedBeforeAnyWrite(String name, Consumer<ObjectNode> change) {
        months("1997-01", "1997-02");
        serve(edited(change));
        List<String> before = snapshot();

        assertThatThrownBy(job::run).as(name).isInstanceOf(RuntimeException.class);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void theMonthsOfTheTableChangingBetweenTheCallAndTheWriteStopsTheFill() {
        months("1997-01", "1997-02");
        List<MonthRegime> answer = antlab.regime.RegimeAnswerCheck.check(
                new antlab.engine.client.EngineRegimeRequest("m2-rl-v1", List.of("1997-01", "1997-02")), RegimeFixtures.parsed());
        months("1997-03");
        List<String> before = snapshot();

        assertThatThrownBy(() -> writer.fill(answer)).isInstanceOf(MonthRegimeWriter.MonthsChangedException.class);
        assertThat(snapshot()).isEqualTo(before);

        jdbc.update("DELETE FROM month_block WHERE month_start = '1997-03-01'");
        jdbc.update("UPDATE month_block SET month_start = '1997-04-01' WHERE month_start = '1997-02-01'");
        before = snapshot();
        assertThatThrownBy(() -> writer.fill(answer)).isInstanceOf(MonthRegimeWriter.MonthsChangedException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void anEngineThatCannotAnswerLeavesTheTableAlone() {
        months("1997-01", "1997-02");
        List<String> before = snapshot();

        ENGINE.respondFixture(PATH, 503, "problem-history-unavailable.json");
        assertThatThrownBy(job::run).isInstanceOfSatisfying(EngineUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.HISTORY_UNAVAILABLE));

        ENGINE.respondFixture(PATH, 503, "problem-engine-busy.json");
        assertThatThrownBy(job::run).isInstanceOfSatisfying(EngineUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.BUSY));

        for (int status : new int[] { 400, 401, 422, 500 }) {
            String fixture = switch (status) {
                case 400 -> "problem-invalid-argument.json";
                case 401 -> "problem-unauthenticated.json";
                case 422 -> "problem-regime-data-invalid.json";
                default -> "problem-internal-error.json";
            };
            ENGINE.respondFixture(PATH, status, fixture);
            assertThatThrownBy(job::run).as("status " + status).isInstanceOf(DomainException.class);
        }

        ENGINE.reset().respondFixture(PATH, 200, "regime-response.json").delay(Duration.ofMillis(2500));
        assertThatThrownBy(job::run).isInstanceOfSatisfying(EngineUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.TIMEOUT));
        // the fake server has one thread: let the delayed answer finish so it cannot hold up the next test's request
        sleep(Duration.ofMillis(1800));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aTableWithoutMonthsNeedsNoEngineCall() {
        RegimeLabelJob.Outcome outcome = job.run();

        assertThat(outcome).isEqualTo(new RegimeLabelJob.Outcome(0, 0, 0, 0));
        assertThat(ENGINE.requests()).isEmpty();
    }

    @Test
    void aMonthThatTheEngineCannotTakeStopsTheRunBeforeTheCall() {
        months("1850-01", "1997-01");

        assertThatThrownBy(job::run).isInstanceOf(RegimeLabelJob.RequestRefusedException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("1850"));
        assertThat(ENGINE.requests()).isEmpty();
    }

    @Test
    void moreMonthsThanOneCallTakesStopTheRunBeforeTheCall() {
        jdbc.update("INSERT INTO month_block (month_start) SELECT (DATE '1950-01-01' + (g || ' months')::interval)::date "
                + "FROM generate_series(0, 480) g");

        assertThatThrownBy(job::run).isInstanceOf(RegimeLabelJob.RequestRefusedException.class);
        assertThat(ENGINE.requests()).isEmpty();
    }

    @Test
    void theMonthListIsInCalendarOrderWhateverTheInsertOrder() {
        months("1997-02", "1996-11", "1997-01");

        assertThat(writer.months()).containsExactly("1996-11", "1997-01", "1997-02");
    }
}
