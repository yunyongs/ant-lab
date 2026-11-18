package antlab.scoring;

import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.member.MemberDto;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.EnginePercentileAnswers;
import antlab.support.TestTokens;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * GET /api/v1/seasons/{id}/score through the real chain with a fake engine: the answer's shape and formats, what it must
 * not carry, who may ask, and that a refused request changes nothing.
 */
class SeasonScoreApiTest extends ScoreApiTestBase {

    private static final List<String> KEYS = List.of("scoreVersion", "score", "resultScore", "processScore", "luckPercentile",
            "sharpeDiffPercentile", "items", "processItems", "countedInRating", "ratingExclusion");
    private static final List<String> ITEM_KEYS = List.of("risk", "discipline", "diversification", "turnover", "consistency",
            "formation", "role", "noise", "nudge");

    @Autowired
    SignupService signup;
    @Autowired
    TestTokens tokens;

    private final List<Long> strangers = new ArrayList<>();

    @AfterEach
    void removeStrangers() {
        for (Long id : strangers) {
            jdbc.update("DELETE FROM member WHERE id = ?", id);
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    // ---- A10: shape and exposure -------------------------------------------------------------------------------

    @Test
    void 점수_응답은_정해진_키와_자릿수만_싣고_키를_생략하지_않는다() throws Exception {
        long season = finishedSeason();

        MockHttpServletResponse r = getScore(season);

        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(names(body)).containsExactlyInAnyOrderElementsOf(KEYS);
        assertThat(names(body.get("items"))).containsExactlyInAnyOrderElementsOf(ITEM_KEYS);
        assertThat(body.get("scoreVersion").asString()).isEqualTo("s63-v3");
        Map<String, Object> row = scoreRow(season);
        assertThat(body.get("score").asString()).isEqualTo(((BigDecimal) row.get("score")).toPlainString()).matches("\\d{1,3}\\.\\d{2}");
        assertThat(body.get("resultScore").asString()).isEqualTo("50.00");
        assertThat(body.get("processScore").asString()).isEqualTo(((BigDecimal) row.get("process_score")).toPlainString());
        assertThat(body.get("luckPercentile").asString()).isEqualTo("0.25000000");
        assertThat(body.get("sharpeDiffPercentile").asString()).isEqualTo("0.50000000");
        assertThat(body.get("processItems").asInt()).isEqualTo(((Number) row.get("process_items")).intValue());
        assertThat(body.get("countedInRating").asBoolean()).isTrue();
        assertThat(body.get("ratingExclusion").isNull()).isTrue();
        assertThat(body.at("/items/consistency").isNull()).isTrue();
        assertThat(body.at("/items/noise").isNull()).isTrue();
        assertThat(body.at("/items/diversification").asString()).matches("\\d{1,3}\\.\\d{2}");
    }

    @Test
    void 점수_응답에는_시드_달_다이제스트_버전_입력_기록이_없다() throws Exception {
        long season = finishedSeason();

        String text = getScore(season).getContentAsString();

        assertThat(text).doesNotContain(seedOf(season)).doesNotContain(EnginePercentileAnswers.DIGEST)
                .doesNotContain("m2-pj-v1").doesNotContain("m2-sb6-v1").doesNotContain("inputs").doesNotContain("seed")
                .doesNotContain("historyDigest").doesNotContain("engineVersion").doesNotContain("projectionVersion")
                .doesNotContainPattern("\\d{4}-\\d{2}");
        assertThat(text).as("only the formula version is a version").containsOnlyOnce("s63-v3");
    }

    @Test
    void 같은_시즌을_다시_물으면_보관한_행을_주고_엔진을_다시_부르지_않는다() throws Exception {
        long season = finishedSeason();
        String first = getScore(season).getContentAsString();
        int calls = percentileCalls();
        engineBusy();

        String second = getScore(season).getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(percentileCalls()).isEqualTo(calls);
    }

    // ---- A7: who may ask --------------------------------------------------------------------------------------

    @Test
    void 남의_시즌과_없는_시즌은_404이고_채점_부작용이_없다() throws Exception {
        long season = finishedSeason();
        MemberDto stranger = signup.signup(new SignupRequest("stranger-" + System.nanoTime() + "@example.test", "가짜 회원",
                "correct-horse-battery"));
        strangers.add(stranger.id());

        MockHttpServletResponse other = mvc.get().uri("/api/v1/seasons/" + season + "/score")
                .header("Authorization", "Bearer " + tokens.user(stranger.id())).exchange().getResponse();
        MockHttpServletResponse missing = getScore(987_654_321L);

        assertThat(other.getStatus()).isEqualTo(404);
        assertThat(code(other)).isEqualTo("NOT_FOUND");
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("NOT_FOUND");
        assertThat(scoreRows()).as("the owner's season was not scored by a stranger's request").isZero();
        assertThat(percentileCalls()).isZero();
    }

    @Test
    void 시즌_번호가_숫자가_아니면_400이다() throws Exception {
        MockHttpServletResponse r = mvc.get().uri("/api/v1/seasons/abc/score").exchange().getResponse();

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
    }
}
