package antlab.api.admin;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.detection.DetectionImportService;
import antlab.support.DetectionFixtures;
import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /api/v1/admin/detection-summaries: an ADMIN token stores a summary (201), the same summary again writes nothing
 * (200), another summary under the same source hash is DETECTION_CONFLICT, a broken one is 400 with pointers only, a USER
 * token is 403 and no token 401. Requests commit; the rows are deleted after each test.
 */
@IntegrationTest
class DetectionImportApiTest {

    private static final String PATH = "/api/v1/admin/detection-summaries";
    private static final String SHA = "c".repeat(64);

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestTokens tokens;
    @Autowired
    TestMembers members;
    @Autowired
    DetectionImportService imports;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM detection_metric");
        jdbc.update("DELETE FROM detection_test");
    }

    private static String summary(String sha) {
        return DetectionFixtures.summary(sha);
    }

    private MockHttpServletResponse post(String body, String token) {
        var request = mvc.post().uri(PATH).requestAttr(ANONYMOUS, true).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange().getResponse();
    }

    private MockHttpServletResponse asAdmin(String body) {
        return post(body, tokens.admin(members.defaultId()));
    }

    private static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    private long rows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void anAdministratorStoresOneTestRowPerWindowWithFiveMetrics() throws Exception {
        MockHttpServletResponse r = asAdmin(summary(SHA));

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        assertThat(json(r).get("created").asBoolean()).isTrue();
        assertThat(json(r).get("windows").asInt()).isEqualTo(2);
        assertThat(rows("detection_test")).isEqualTo(2);
        assertThat(rows("detection_metric")).isEqualTo(10);
        assertThat(jdbc.queryForList("""
                SELECT t.model_id, t.test_version, t.sample_layer, t.window_months, t.units, t.source_sha256,
                       to_char(t.run_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') AS run_at
                FROM detection_test t ORDER BY t.window_months""")).extracting(m -> m.get("window_months").toString(),
                m -> m.get("units").toString(), m -> m.get("run_at")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("1", "98", "2026-01-02T03:04:05.678901"),
                org.assertj.core.groups.Tuple.tuple("3", "94", "2026-01-02T03:04:05.678901"));
        assertThat(jdbc.queryForObject("""
                SELECT m.hits || '/' || m.units || '@' || m.base_rate FROM detection_metric m
                JOIN detection_test t ON t.id = m.test_id WHERE t.window_months = 3 AND m.metric = 'US_NEXT'""",
                String.class)).isEqualTo("54/94@0.58000000");
    }

    @Test
    void theSameSummaryAgainIsAnswered200AndWritesNothing() throws Exception {
        assertThat(asAdmin(summary(SHA)).getStatus()).isEqualTo(201);
        // the same values in another member order and spacing are the same summary
        ObjectNode reordered = (ObjectNode) JsonMapper.shared().readTree(summary(SHA));
        JsonNode model = reordered.remove("modelId");
        reordered.set("modelId", model);

        MockHttpServletResponse again = asAdmin(reordered.toString());

        assertThat(again.getStatus()).as(again.getContentAsString()).isEqualTo(200);
        assertThat(json(again).get("created").asBoolean()).isFalse();
        assertThat(json(again).get("windows").asInt()).isEqualTo(2);
        assertThat(rows("detection_test")).isEqualTo(2);
        assertThat(rows("detection_metric")).isEqualTo(10);
    }

    @Test
    void anotherSummaryUnderTheSameSourceHashIsAConflictAndWritesNothing() throws Exception {
        assertThat(asAdmin(summary(SHA)).getStatus()).isEqualTo(201);
        List<String> changed = List.of(summary(SHA).replace("\"hits\":55", "\"hits\":56"),
                summary(SHA).replace("05.678901Z", "05.678900Z"),
                summary(SHA).replace("\"baseRate\":\"0.58000000\"}}},", "\"baseRate\":\"0.58000001\"}}},"),
                summary(SHA).replace("fake/model-1", "fake/model-2"));
        for (String body : changed) {
            assertThat(body).isNotEqualTo(summary(SHA));
            MockHttpServletResponse r = asAdmin(body);
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
            assertThat(json(r).get("code").asString()).isEqualTo("DETECTION_CONFLICT");
        }
        ObjectNode oneWindow = (ObjectNode) JsonMapper.shared().readTree(summary(SHA));
        ((ObjectNode) oneWindow.get("windows")).remove("3");
        assertThat(asAdmin(oneWindow.toString()).getStatus()).isEqualTo(409);
        assertThat(rows("detection_test")).isEqualTo(2);
        assertThat(rows("detection_metric")).isEqualTo(10);
        // a new source hash is a new run of the same model
        assertThat(asAdmin(summary("d".repeat(64))).getStatus()).isEqualTo(201);
        assertThat(rows("detection_test")).isEqualTo(4);
    }

    @Test
    void aBrokenSummaryIs400WithPointersAndKeywordsOnly() throws Exception {
        String body = summary(SHA).replace("\"sampleLayer\":\"P0-08\"", "\"sampleLayer\":\"secret-layer\"")
                .replace("\"hits\":55", "\"hits\":-55");

        MockHttpServletResponse r = asAdmin(body);

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(400);
        JsonNode problem = json(r);
        assertThat(problem.get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        List<String> errors = new ArrayList<>();
        problem.get("errors").forEach(e -> errors.add(e.get("field").asString() + " " + e.get("reason").asString()));
        assertThat(errors).containsExactly("/sampleLayer pattern", "/windows/1/metrics/US_NEXT/hits minimum");
        assertThat(r.getContentAsString()).doesNotContain("secret").doesNotContain("-55");
        assertThat(rows("detection_test")).isZero();
    }

    @Test
    void theRulesAfterTheSchemaAre400AndWriteNothing() throws Exception {
        MockHttpServletResponse hits = asAdmin(summary(SHA).replace("\"hits\":55", "\"hits\":99"));
        MockHttpServletResponse future = asAdmin(summary(SHA).replace("2026-01-02T", "2999-01-02T"));
        MockHttpServletResponse notJson = asAdmin("[1]");

        assertThat(hits.getStatus()).isEqualTo(400);
        assertThat(json(hits).get("errors").get(0).get("field").asString()).isEqualTo("/windows/1/metrics/US_NEXT/hits");
        assertThat(json(hits).get("errors").get(0).get("reason").asString()).isEqualTo("atMostUnits");
        assertThat(future.getStatus()).isEqualTo(400);
        assertThat(json(future).get("errors").get(0).get("reason").asString()).isEqualTo("notFuture");
        assertThat(notJson.getStatus()).isEqualTo(400);
        assertThat(json(notJson).get("errors").get(0).get("reason").asString()).isEqualTo("type");
        assertThat(rows("detection_test")).isZero();
    }

    @Test
    void aUserTokenIs403AndNoTokenIs401() throws Exception {
        MockHttpServletResponse user = post(summary(SHA), tokens.user(members.defaultId()));
        MockHttpServletResponse anonymous = post(summary(SHA), null);

        assertThat(user.getStatus()).isEqualTo(403);
        assertThat(json(user).get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(anonymous.getStatus()).isEqualTo(401);
        assertThat(json(anonymous).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(rows("detection_test")).isZero();
    }

    @Test
    void twoImportsOfOneSourceAtOnceStoreItOnce() throws Exception {
        JsonNode document = JsonMapper.shared().readTree(summary(SHA));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<DetectionImportService.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return imports.importSummary(document);
                }));
            }
            start.countDown();
            List<Boolean> created = new ArrayList<>();
            for (Future<DetectionImportService.Outcome> f : results) {
                created.add(f.get().created());
            }
            assertThat(created).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows("detection_test")).isEqualTo(2);
    }
}
