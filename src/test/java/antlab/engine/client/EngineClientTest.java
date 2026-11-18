package antlab.engine.client;

import static antlab.engine.client.EngineHarness.BASE;
import static antlab.engine.client.EngineHarness.FAKE_TOKEN;
import static antlab.engine.client.EngineHarness.MARKER;
import static antlab.support.FakeEngineServer.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(OutputCaptureExtension.class)
class EngineClientTest {

    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");

    private final EngineHarness h = new EngineHarness();

    private static JsonNode tree(String json) {
        return EngineWire.MAPPER.readTree(json);
    }

    private AtomicReference<String> expectPost(MockRestServiceServer server, String path) {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(once(), requestTo(BASE + path)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(header(EngineClient.TOKEN_HEADER, FAKE_TOKEN))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(fixture("paths-response-m2.json"), MediaType.APPLICATION_JSON));
        return body;
    }

    @Test
    void 경로_요청은_토큰_헤더_하나와_JSON_본문으로_나가고_응답을_읽는다() {
        AtomicReference<String> body = expectPost(h.paths, "/v1/paths");

        EnginePathResult result = h.client.createPath(EngineHarness.pathRequest());

        h.paths.verify();
        assertThat(tree(body.get())).isEqualTo(tree("{\"engineVersion\":\"m2-sb6-v1\",\"seed\":\"14\",\"length\":12,"
                + "\"baseDate\":\"2026-01-02\",\"conditional\":false}"));
        assertThat(result.engineVersion()).isEqualTo("m2-sb6-v1");
        assertThat(result.months()).hasSize(12).allMatch(m -> m.matches("\\d{4}-\\d{2}"));
        assertThat(result.blocks()).isNotEmpty();
        assertThat(result.historyDigest()).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(longs = { Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE })
    void 시드는_부호_있는_십진_문자열로_나간다(long seed) {
        AtomicReference<String> body = expectPost(h.paths, "/v1/paths");

        h.client.createPath(EnginePathRequest.of("m2-sb6-v1", seed, 12, EngineHarness.BASE_DATE));

        JsonNode sent = tree(body.get());
        assertThat(sent.get("seed").isString()).isTrue();
        assertThat(sent.get("seed").asString()).isEqualTo(Long.toString(seed));
    }

    @Test
    void 예상_분포_요청과_응답은_비율을_자리수_8의_BigDecimal로_읽는다() {
        AtomicReference<String> body = new AtomicReference<>();
        h.projections.expect(once(), requestTo(BASE + "/v1/projections")).andExpect(method(HttpMethod.POST))
                .andExpect(header(EngineClient.TOKEN_HEADER, FAKE_TOKEN))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(fixture("projection-response.json"), MediaType.APPLICATION_JSON));

        EngineProjectionResult result = h.client.createProjection(EngineHarness.projectionRequest());

        h.projections.verify();
        assertThat(tree(body.get())).isEqualTo(tree(fixture("projection-request.json")));
        EngineProjectionResult.PortfolioResult team = result.portfolios().getFirst();
        assertThat(team.key()).isEqualTo("TEAM");
        assertThat(team.bands()).hasSize(60);
        assertThat(team.bands().getFirst().p50().scale()).isEqualTo(8);
        assertThat(team.summaries()).extracting(EngineProjectionResult.Summary::turn).containsExactly(12, 60);
        BigDecimal probability = team.summaries().getFirst().breachProbability();
        assertThat(probability.scale()).isEqualTo(8);
        assertThat(probability).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    void 정보_조회는_GET_이고_토큰을_붙인다() {
        h.paths.expect(once(), requestTo(BASE + "/v1/info")).andExpect(method(HttpMethod.GET))
                .andExpect(header(EngineClient.TOKEN_HEADER, FAKE_TOKEN))
                .andRespond(withSuccess(fixture("info-response.json"), MediaType.APPLICATION_JSON));

        EngineInfo info = h.client.info();

        h.paths.verify();
        assertThat(info.service()).isEqualTo("antlab-engine");
        assertThat(info.apiVersion()).isEqualTo("v1");
    }

    private enum Op {
        PATH("/v1/paths"), PROJECTION("/v1/projections"), REGIME("/v1/regime/evaluate");

        final String uri;

        Op(String uri) {
            this.uri = uri;
        }
    }

    private Supplier<Object> call(Op op) {
        return switch (op) {
            case PATH -> () -> h.client.createPath(EngineHarness.pathRequest());
            case PROJECTION -> () -> h.client.createProjection(EngineHarness.projectionRequest());
            case REGIME -> () -> h.client.evaluateRegime(EngineHarness.regimeRequest());
        };
    }

    private MockRestServiceServer serverFor(Op op) {
        return switch (op) {
            case PATH -> h.paths;
            case PROJECTION -> h.projections;
            case REGIME -> h.regime;
        };
    }

    private static Stream<Arguments> problemRows() {
        return Stream.of(
                Arguments.of(Op.PATH, 400, "problem-invalid-argument.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.PROJECTION, 400, "problem-invalid-argument.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.REGIME, 400, "problem-invalid-argument.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.PATH, 401, "problem-unauthenticated.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.REGIME, 401, "problem-unauthenticated.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.PATH, 404, "problem-pool-not-found.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.PATH, 404, "problem-not-found.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.PATH, 405, "problem-method-not-allowed.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.PATH, 422, "problem-path-too-long.json", ErrorCode.PATH_TOO_LONG),
                Arguments.of(Op.PROJECTION, 422, "problem-path-too-long.json", ErrorCode.PATH_TOO_LONG),
                Arguments.of(Op.REGIME, 422, "problem-regime-data-invalid.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.PATH, 500, "problem-internal-error.json", ErrorCode.INTERNAL_ERROR),
                Arguments.of(Op.REGIME, 500, "problem-internal-error.json", ErrorCode.INTERNAL_ERROR));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("problemRows")
    void 엔진_오류는_정해진_자바_코드로_바뀌고_엔진_문장은_어디에도_없다(Op op, int status, String fixtureName, ErrorCode expected,
            CapturedOutput output) {
        serverFor(op).expect(once(), requestTo(BASE + op.uri))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)).contentType(PROBLEM)
                        .body(EngineHarness.problemWithMarker(fixtureName)));

        assertThatThrownBy(() -> call(op).get()).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.getCode()).isEqualTo(expected);
            assertThat(e.getMessage()).doesNotContain(MARKER);
            assertThat(e).hasNoCause();
        });
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(FAKE_TOKEN);
        serverFor(op).verify();
    }

    private static Stream<Arguments> unavailableRows() {
        return Stream.of(
                Arguments.of(Op.PATH, "problem-history-unavailable.json", EngineUnavailableException.Reason.HISTORY_UNAVAILABLE),
                Arguments.of(Op.PROJECTION, "problem-history-unavailable.json", EngineUnavailableException.Reason.HISTORY_UNAVAILABLE),
                Arguments.of(Op.PROJECTION, "problem-engine-busy.json", EngineUnavailableException.Reason.BUSY),
                Arguments.of(Op.REGIME, "problem-history-unavailable.json", EngineUnavailableException.Reason.HISTORY_UNAVAILABLE),
                Arguments.of(Op.REGIME, "problem-engine-busy.json", EngineUnavailableException.Reason.BUSY));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unavailableRows")
    void 일시적_엔진_실패는_다시_시도할_수_있는_불가용으로_바뀌고_한_번만_부른다(Op op, String fixtureName,
            EngineUnavailableException.Reason reason, CapturedOutput output) {
        serverFor(op).expect(once(), requestTo(BASE + op.uri))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(503)).contentType(PROBLEM)
                        .body(EngineHarness.problemWithMarker(fixtureName)));

        assertThatThrownBy(() -> call(op).get()).isInstanceOfSatisfying(EngineUnavailableException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.getMessage()).isEqualTo(EngineUnavailableException.MESSAGE);
            assertThat(e).hasNoCause();
        });
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(FAKE_TOKEN);
        // the single expected request was used up: a retry would have failed the mock before this verify
        serverFor(op).verify();
    }

    @Test
    void 모르는_코드와_상태_코드_불일치와_미디어_타입_오류는_모두_내부_오류다() {
        record Bad(int status, MediaType type, String body) {
        }
        String unknownCode = ((ObjectNode) tree(fixture("problem-engine-busy.json"))).put("code", "TEAPOT").toString();
        String mismatch = fixture("problem-path-too-long.json");
        java.util.List<Bad> rows = java.util.List.of(
                new Bad(503, PROBLEM, unknownCode),
                new Bad(503, PROBLEM, mismatch),
                new Bad(503, PROBLEM, ((ObjectNode) tree(mismatch)).put("status", 503).toString()),
                new Bad(503, PROBLEM, ((ObjectNode) tree(fixture("problem-engine-busy.json"))).put("status", 500).toString()),
                new Bad(422, MediaType.APPLICATION_JSON, fixture("problem-path-too-long.json")),
                new Bad(502, MediaType.TEXT_HTML, "<html>bad gateway</html>"),
                new Bad(302, MediaType.TEXT_PLAIN, ""),
                new Bad(500, PROBLEM, "{}"),
                new Bad(500, PROBLEM, "not json"));
        for (Bad row : rows) {
            EngineHarness each = new EngineHarness();
            each.paths.expect(once(), requestTo(BASE + "/v1/paths"))
                    .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(row.status())).contentType(row.type())
                            .body(row.body()));
            assertThatThrownBy(() -> each.client.createPath(EngineHarness.pathRequest()))
                    .as(row.toString()).isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

    private void respondOk(MediaType type, String body) {
        h.projections.expect(once(), requestTo(BASE + "/v1/projections")).andRespond(withSuccess(body, type));
    }

    private static String edit(String fixtureName, java.util.function.Consumer<ObjectNode> change) {
        ObjectNode node = (ObjectNode) tree(fixture(fixtureName));
        change.accept(node);
        return node.toString();
    }

    private static Stream<Arguments> strictBodies() {
        return Stream.of(
                Arguments.of("필수 필드 없음", edit("projection-response.json", n -> n.remove("historyDigest"))),
                Arguments.of("모르는 필드", edit("projection-response.json", n -> n.put("extra", 1))),
                Arguments.of("비율이 JSON 숫자", fixture("bad-projection-number.json")),
                Arguments.of("비율이 소수 9자리", fixture("bad-projection-nine-digits.json")),
                Arguments.of("비율이 소수 7자리", fixture("projection-response.json").replaceFirst("\"p50\":\"([0-9]+\\.[0-9]{7})[0-9]\"", "\"p50\":\"$1\"")),
                Arguments.of("비율이 소수 1자리", fixture("projection-response.json").replaceFirst("\"p50\":\"[0-9.]+\"", "\"p50\":\"1.5\"")),
                Arguments.of("확률이 1 초과", fixture("bad-projection-probability.json")),
                Arguments.of("정수 자리에 문자열", edit("projection-response.json", n -> n.put("n", "100"))),
                Arguments.of("정수 자리에 소수", edit("projection-response.json", n -> n.put("n", 100.0))),
                Arguments.of("null 필수 필드", edit("projection-response.json", n -> n.putNull("projectionVersion"))),
                Arguments.of("중복 키", fixture("projection-response.json").replaceFirst("\\{", "{\"n\":1,")),
                Arguments.of("꼬리 토큰", fixture("projection-response.json").trim() + " {}"),
                Arguments.of("JSON 이 아님", "<html></html>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strictBodies")
    void 엄격_파싱_200_본문이_계약을_어기면_내부_오류다(String name, String body) {
        respondOk(MediaType.APPLICATION_JSON, body);

        assertThatThrownBy(() -> h.client.createProjection(EngineHarness.projectionRequest()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 전송_계층의_예기치_못한_예외는_내용을_버리고_내부_오류로_바뀐다(CapturedOutput output) {
        h.paths.expect(once(), requestTo(BASE + "/v1/paths")).andRespond(request -> {
            throw new IllegalArgumentException("invalid header value: \"" + FAKE_TOKEN + "\"");
        });

        assertThatThrownBy(() -> h.client.createPath(EngineHarness.pathRequest())).isInstanceOfSatisfying(DomainException.class,
                e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(e).hasNoCause();
                    assertThat(String.valueOf(e)).doesNotContain(FAKE_TOKEN);
                });
        assertThat(output.getAll()).doesNotContain(FAKE_TOKEN);
    }

    @Test
    void 경로_응답의_달_형식_오류와_다이제스트_누락도_내부_오류다() {
        for (String bad : new String[] { "bad-path-month-13.json", "bad-path-no-digest.json" }) {
            EngineHarness each = new EngineHarness();
            each.paths.expect(once(), requestTo(BASE + "/v1/paths"))
                    .andRespond(withSuccess(fixture(bad), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> each.client.createPath(EngineHarness.pathRequest())).as(bad)
                    .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

    @Test
    void 경로_응답이_text_html_이면_200이어도_내부_오류다() {
        h.paths.expect(once(), requestTo(BASE + "/v1/paths"))
                .andRespond(withSuccess(fixture("paths-response-m2.json"), MediaType.TEXT_HTML));

        assertThatThrownBy(() -> h.client.createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 성공_응답에_problem_json_미디어_타입이면_내부_오류다() {
        h.paths.expect(once(), requestTo(BASE + "/v1/paths"))
                .andRespond(withSuccess(fixture("paths-response-m2.json"), PROBLEM));

        assertThatThrownBy(() -> h.client.createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }


    @Test
    void 국면_요청은_토큰_헤더와_JSON_본문으로_나가고_응답을_읽는다() {
        AtomicReference<String> body = new AtomicReference<>();
        h.regime.expect(once(), requestTo(BASE + "/v1/regime/evaluate")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(header(EngineClient.TOKEN_HEADER, FAKE_TOKEN))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(fixture("regime-response.json"), MediaType.APPLICATION_JSON));

        EngineRegimeResult result = h.client.evaluateRegime(EngineHarness.regimeRequest());

        h.regime.verify();
        assertThat(tree(body.get())).isEqualTo(tree(fixture("regime-request.json")));
        assertThat(result.labelVersion()).isEqualTo(EngineRegimeRequest.LABEL_VERSION);
        assertThat(result.months()).extracting(EngineRegimeResult.Month::month).containsExactly("1997-01", "1997-02");
        assertThat(result.months().getFirst().probabilities().p1()).isEqualTo("0.2731");
        assertThat(result.months().getFirst().axes().sentiment()).isNull();
        assertThat(result.months().getFirst().indicators()).containsEntry("2", null).hasSize(EngineRegimeResult.INDICATOR_KEYS.size());
        assertThat(result.months().get(1).label()).isNull();
    }

    @Test
    void 국면_422는_로그에_고유_키로_남고_상태가_다르면_계약_위반으로_본다(CapturedOutput output) {
        h.regime.expect(once(), requestTo(BASE + "/v1/regime/evaluate")).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(422))
                .contentType(PROBLEM).body(EngineHarness.problemWithMarker("problem-regime-data-invalid.json")));
        assertThatThrownBy(() -> h.client.evaluateRegime(EngineHarness.regimeRequest())).isInstanceOf(DomainException.class);
        assertThat(output.getAll()).contains("engine.regime_data_invalid").doesNotContain("engine.bad_response").doesNotContain(MARKER);

        EngineHarness wrongStatus = new EngineHarness();
        wrongStatus.regime.expect(once(), requestTo(BASE + "/v1/regime/evaluate")).andRespond(withStatus(
                org.springframework.http.HttpStatusCode.valueOf(500)).contentType(PROBLEM)
                .body(((ObjectNode) tree(fixture("problem-regime-data-invalid.json"))).put("status", 500).toString()));
        assertThatThrownBy(() -> wrongStatus.client.evaluateRegime(EngineHarness.regimeRequest())).isInstanceOf(DomainException.class);
        assertThat(output.getAll()).contains("engine.bad_response");
    }

    private static Stream<Arguments> strictRegimeBodies() {
        return Stream.of(
                Arguments.of("확률이 소수 5자리", fixture("bad-regime-probability-five-digits.json")),
                Arguments.of("방향이 모르는 값", fixture("bad-regime-unknown-direction.json")),
                Arguments.of("필수 필드 없음", edit("regime-response.json", n -> n.remove("inputDigest"))),
                Arguments.of("모르는 필드", edit("regime-response.json", n -> n.put("extra", 1))),
                Arguments.of("coverage 없음", edit("regime-response.json", n -> n.remove("coverage"))),
                Arguments.of("강도가 JSON 숫자", fixture("regime-response.json").replaceFirst("\"strength\":\"0.41250000\"", "\"strength\":0.4125")),
                Arguments.of("강도가 음수", fixture("regime-response.json").replaceFirst("\"strength\":\"0.41250000\"", "\"strength\":\"-0.41250000\"")),
                Arguments.of("지표 키가 모르는 값", fixture("regime-response.json").replaceFirst("\"14KR\":\"0.00000000\"", "\"15\":\"0.00000000\"")),
                Arguments.of("라벨이 모르는 값", fixture("regime-response.json").replaceFirst("\"label\":\"P4\"", "\"label\":\"P5\"")),
                Arguments.of("달 형식 오류", fixture("regime-response.json").replaceFirst("\"month\":\"1997-01\"", "\"month\":\"1997-13\"")),
                Arguments.of("다이제스트 형식 오류", edit("regime-response.json", n -> n.put("inputDigest", "ABC"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strictRegimeBodies")
    void 국면_응답이_계약을_어기면_내부_오류다(String name, String body) {
        h.regime.expect(once(), requestTo(BASE + "/v1/regime/evaluate")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.client.evaluateRegime(EngineHarness.regimeRequest()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 국면_요청_객체는_달을_문자열_표현에_싣지_않고_잘못된_달_목록을_거부한다() {
        assertThat(EngineHarness.regimeRequest().toString()).doesNotContain("1997").isEqualTo("EngineRegimeRequest[2 months]");
        var version = EngineRegimeRequest.LABEL_VERSION;
        for (java.util.List<String> bad : java.util.List.of(java.util.List.<String>of(), java.util.List.of("1997-02", "1997-01"),
                java.util.List.of("1997-01", "1997-01"), java.util.List.of("1997-13"), java.util.List.of("1899-12"),
                java.util.List.of("2100-01"), java.util.List.of("1997-1"))) {
            assertThatThrownBy(() -> new EngineRegimeRequest(version, bad)).as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new EngineRegimeRequest("m2-rl-v2", java.util.List.of("1997-01"))).isInstanceOf(IllegalArgumentException.class);
        java.util.List<String> tooMany = java.util.stream.IntStream.range(0, EngineRegimeRequest.MAX_MONTHS + 1)
                .mapToObj(i -> java.time.YearMonth.of(1960, 1).plusMonths(i).toString()).toList();
        assertThatThrownBy(() -> new EngineRegimeRequest(version, tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EngineRegimeRequest(version, tooMany.subList(0, EngineRegimeRequest.MAX_MONTHS)).months()).hasSize(EngineRegimeRequest.MAX_MONTHS);
    }

    @Test
    void 국면_결과의_문자열_표현은_달과_값을_싣지_않는다() {
        EngineRegimeResult result = EngineWire.read(EngineRegimeResult.class, fixture("regime-response.json").getBytes());
        assertThat(result.toString()).isEqualTo("EngineRegimeResult[2 months]");
        assertThat(result.months().getFirst().toString()).doesNotContain("1997").doesNotContain("0.2731");
    }

    @Test
    void 요청_객체와_결과는_시드와_달을_문자열_표현에_싣지_않는다() {
        EnginePathRequest request = EnginePathRequest.of("m2-sb6-v1", 987654321L, 12, EngineHarness.BASE_DATE);
        assertThat(request.toString()).doesNotContain("987654321").doesNotContain("2026");
        assertThat(EngineHarness.projectionRequest().toString()).doesNotContain("14,").doesNotContain("KR:A001");

        EnginePathResult result = EngineWire.read(EnginePathResult.class, fixture("paths-response-m2.json").getBytes());
        assertThat(result.toString()).doesNotContain("20").doesNotContain(result.historyDigest());
    }
}
