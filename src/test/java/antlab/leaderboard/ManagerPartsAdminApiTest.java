package antlab.leaderboard;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.TestTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/v1/admin/manager-parts/run: an ADMIN token runs the queue and gets counts only, a USER token is 403, no token
 * 401, and a limit outside 1..20 (or none) is 400 before anything runs.
 */
class ManagerPartsAdminApiTest extends ManagerPartsTestBase {

    private static final String PATH = "/api/v1/admin/manager-parts/run";

    @Autowired
    TestTokens tokens;

    private MockHttpServletResponse post(String query, String token) {
        var request = mvc.post().uri(PATH + query).requestAttr(ANONYMOUS, true);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    @Test
    void anAdministratorRunsTheQueueAndGetsCountsOnly() throws Exception {
        long counted = finishedSeason();
        notStartedSeason();
        scoreAll();
        partsAnswer(200, goodAnswer(counted));

        MockHttpServletResponse r = post("?limit=20", tokens.admin(memberId()));

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode body = json(r);
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        assertThat(names).containsExactlyInAnyOrder("status", "ranked", "excluded", "alreadyStored", "failed", "pending");
        assertThat(body.get("status").asString()).isEqualTo("DONE");
        assertThat(body.get("ranked").asInt()).isOne();
        assertThat(body.get("excluded").asInt()).isOne();
        assertThat(body.get("alreadyStored").asInt()).isZero();
        assertThat(body.get("failed").asInt()).isZero();
        assertThat(body.get("pending").asInt()).isZero();
        assertThat(partsRows()).isEqualTo(2);
    }

    @Test
    void aUserTokenIsForbiddenAndNoTokenIsUnauthenticated() throws Exception {
        finishedSeason();
        MockHttpServletResponse user = post("?limit=1", tokens.user(memberId()));
        assertThat(user.getStatus()).isEqualTo(403);
        assertThat(json(user).get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(post("?limit=1", null).getStatus()).isEqualTo(401);
        assertThat(partsCalls()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_score", Long.class)).as("nothing ran").isZero();
    }

    @Test
    void aLimitOutsideOneToTwentyIsRefusedBeforeAnythingRuns() throws Exception {
        String admin = tokens.admin(memberId());
        Map<String, Integer> probes = new LinkedHashMap<>();
        probes.put("?limit=0", 400);
        probes.put("?limit=21", 400);
        probes.put("", 400);
        probes.put("?limit=x", 400);
        probes.put("?limit=1", 200);
        for (Map.Entry<String, Integer> probe : probes.entrySet()) {
            MockHttpServletResponse r = post(probe.getKey(), admin);
            assertThat(r.getStatus()).as(probe.getKey() + " " + r.getContentAsString()).isEqualTo(probe.getValue());
            if (probe.getValue() == 400) {
                assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
            }
        }
    }
}
