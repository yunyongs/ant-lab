package antlab.scoring;

import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * GET /api/v1/members/me/rating with a fake engine: shown from the third counted season, weights 5 4 3 2 1 from the newest,
 * seasons left out are counted, and an engine outage never fails the answer. Expected ratings are computed here from the
 * stored season scores.
 */
class RatingApiTest extends ScoreApiTestBase {

    private static final List<String> KEYS = List.of("pillar", "rating", "countedSeasons", "seasonsUntilPublic",
            "notStartedCount", "noScoreCount", "pendingSeasons", "history");
    private static final List<String> ENTRY_KEYS = List.of("seqNo", "seasonId", "score", "rating");

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    /** The first two seasons answer 10 and 50 points of result; every later one 90. */
    private void spreadResults() {
        ENGINE.queue(PERCENTILES, 200, APPLICATION_JSON, EnginePercentileAnswers.body(PATHS, TURNS, 400, 0, 199, 2));
        ENGINE.queue(PERCENTILES, 200, APPLICATION_JSON, EnginePercentileAnswers.body(PATHS, TURNS, 400, 0, 999, 2));
        engineAnswers(EnginePercentileAnswers.body(PATHS, TURNS, 400, 0, 1799, 2));
    }

    /** Weighted mean of the scores (oldest first) with weights 5, 4, ... for the newest, divided once, 2 decimals. */
    private static BigDecimal weighted(List<BigDecimal> oldestFirst) {
        int used = Math.min(5, oldestFirst.size());
        int first = oldestFirst.size() - used;
        BigDecimal sum = BigDecimal.ZERO;
        int weights = 0;
        for (int i = 0; i < used; i++) {
            int weight = 5 - used + 1 + i;
            sum = sum.add(oldestFirst.get(first + i).multiply(BigDecimal.valueOf(weight)));
            weights += weight;
        }
        return sum.divide(BigDecimal.valueOf(weights), 30, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    void 시즌이_없는_회원도_200이고_모든_값이_비어_있다() throws Exception {
        MockHttpServletResponse r = getRating();

        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(names(body)).containsExactlyInAnyOrderElementsOf(KEYS);
        assertThat(body.get("pillar").asString()).isEqualTo("MANAGER");
        assertThat(body.get("rating").isNull()).isTrue();
        assertThat(body.get("countedSeasons").asInt()).isZero();
        assertThat(body.get("seasonsUntilPublic").asInt()).isEqualTo(3);
        assertThat(body.get("notStartedCount").asInt()).isZero();
        assertThat(body.get("noScoreCount").asInt()).isZero();
        assertThat(body.get("pendingSeasons").asInt()).isZero();
        assertThat(body.get("history")).isEmpty();
        assertThat(percentileCalls()).isZero();
    }

    @Test
    void 레이팅은_세_번째_집계_시즌부터_보이고_가중치는_최근부터_5_4_3이다() throws Exception {
        spreadResults();
        long s1 = finishedSeason();
        JsonNode one = json(getRating());
        assertThat(one.get("rating").isNull()).as("one season").isTrue();
        assertThat(one.get("countedSeasons").asInt()).isEqualTo(1);
        assertThat(one.get("seasonsUntilPublic").asInt()).isEqualTo(2);
        assertThat(one.at("/history/0/rating").isNull()).isTrue();

        long s2 = finishedSeason();
        JsonNode two = json(getRating());
        assertThat(two.get("rating").isNull()).as("two seasons").isTrue();
        assertThat(two.get("seasonsUntilPublic").asInt()).isEqualTo(1);

        long s3 = finishedSeason();
        MockHttpServletResponse r = getRating();

        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode three = json(r);
        List<BigDecimal> scores = List.of(score(s1), score(s2), score(s3));
        assertThat(scores).as("the three seasons have different scores").doesNotHaveDuplicates();
        assertThat(three.get("countedSeasons").asInt()).isEqualTo(3);
        assertThat(three.get("seasonsUntilPublic").asInt()).isZero();
        assertThat(three.get("rating").asString()).isEqualTo(weighted(scores).toPlainString());
        JsonNode history = three.get("history");
        assertThat(history).hasSize(3);
        assertThat(names(history.get(0))).containsExactlyInAnyOrderElementsOf(ENTRY_KEYS);
        assertThat(history.get(0).get("seqNo").asInt()).isEqualTo(3);
        assertThat(history.get(0).get("seasonId").asLong()).isEqualTo(s3);
        assertThat(history.get(0).get("score").asString()).isEqualTo(scores.get(2).toPlainString());
        assertThat(history.get(0).get("rating").asString()).isEqualTo(weighted(scores).toPlainString());
        assertThat(history.get(1).get("seqNo").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("rating").isNull()).as("not shown before the third season").isTrue();
        assertThat(history.get(2).get("seqNo").asInt()).isEqualTo(1);
        assertThat(history.get(2).get("seasonId").asLong()).isEqualTo(s1);
        assertThat(history.get(2).get("rating").isNull()).isTrue();
        // the stored rating row exists from season 1 even though it is hidden
        assertThat(jdbc.queryForObject("SELECT rating FROM rating_history WHERE season_id = ?", BigDecimal.class, s1))
                .isEqualByComparingTo(score(s1));
    }

    @Test
    void 빠진_시즌은_횟수만_세고_순번을_쓰지_않는다() throws Exception {
        long refused = finishedSeason();
        long early = startedSeason();
        play(early, 0, 1);
        MockHttpServletResponse abandoned = mvc.post().uri("/api/v1/seasons/" + early + "/abandon")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTurnNo\": 1}").exchange().getResponse();
        assertThat(abandoned.getStatus()).isEqualTo(200);
        long counted = finishedSeason();
        ENGINE.queue(PERCENTILES, 422, "application/problem+json", FakeEngineServer.fixture("problem-path-too-long.json"));

        JsonNode body = json(getRating());

        assertThat(body.get("noScoreCount").asInt()).isEqualTo(1);
        assertThat(body.get("notStartedCount").asInt()).isEqualTo(1);
        assertThat(body.get("countedSeasons").asInt()).isEqualTo(1);
        assertThat(body.get("pendingSeasons").asInt()).isZero();
        assertThat(body.get("history")).hasSize(1);
        assertThat(body.at("/history/0/seasonId").asLong()).isEqualTo(counted);
        assertThat(body.at("/history/0/seqNo").asInt()).isEqualTo(1);
        assertThat(scoreRow(refused).get("rating_exclusion")).isEqualTo("NO_SCORE");
        assertThat(scoreRow(early).get("rating_exclusion")).isEqualTo("NOT_STARTED");
    }

    @Test
    void 엔진이_멈춰도_레이팅은_200이고_대기_시즌_수만_올라가며_회복하면_순서대로_채워진다() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        engineBusy();

        MockHttpServletResponse down = getRating();

        assertThat(down.getStatus()).as("the rating never answers 503").isEqualTo(200);
        JsonNode pending = json(down);
        assertThat(pending.get("pendingSeasons").asInt()).isEqualTo(2);
        assertThat(pending.get("countedSeasons").asInt()).isZero();
        assertThat(pending.get("history")).isEmpty();
        assertThat(scoreRows()).isZero();

        engineAnswersMiddle();
        JsonNode up = json(getRating());

        assertThat(up.get("pendingSeasons").asInt()).isZero();
        assertThat(up.get("countedSeasons").asInt()).isEqualTo(2);
        assertThat(ratedSeasons()).containsExactly(first, second);
    }
}
