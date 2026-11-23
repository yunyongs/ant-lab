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
import antlab.engine.client.EngineManagerPartsRequest.Settings;
import antlab.engine.client.EngineManagerPartsRequest.StartFormation;
import antlab.engine.client.EngineManagerPartsRequest.Team;
import antlab.member.MemberKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Java client of {@code POST /v1/manager-parts}: the body it sends, the answer it accepts, and how each engine
 * refusal reaches the caller (a recorded refusal, an outage, or a bug). No Spring context, no network.
 */
@ExtendWith(OutputCaptureExtension.class)
class EngineManagerPartsClientTest {

    private static final String URI = BASE + "/v1/manager-parts";
    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");

    private final EngineHarness h = new EngineHarness();

    private static JsonNode tree(String json) {
        return EngineWire.MAPPER.readTree(json);
    }

    private void answer(int status, MediaType type, String body) {
        h.regime.expect(once(), requestTo(URI)).andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(type).body(body));
    }

    private EngineManagerPartsResult call() {
        return h.client.createManagerParts(EngineHarness.managerPartsRequest());
    }

    @Test
    void 요청은_픽스처와_같고_토큰을_싣고_응답을_문자열_그대로_읽는다() {
        AtomicReference<String> body = new AtomicReference<>();
        h.regime.expect(once(), requestTo(URI)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(header(EngineClient.TOKEN_HEADER, FAKE_TOKEN))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(fixture("manager-parts-response.json"), MediaType.APPLICATION_JSON));

        EngineManagerPartsResult result = call();

        h.regime.verify();
        assertThat(tree(body.get())).isEqualTo(tree(fixture("manager-parts-request.json")));
        assertThat(result.referenceSize()).isEqualTo(200);
        assertThat(result.botVersion()).isEqualTo("random/1");
        assertThat(result.components().benchCalmar()).isEqualTo("999999.999999990000");
        assertThat(result.percentiles().riskItem()).isEqualTo("50.00000000");
        assertThat(result.parts().ret()).isEqualTo("71.25000000");
    }

    @Test
    void 위험_항목이_없으면_null을_보내고_null_백분위를_받는다() {
        AtomicReference<String> body = new AtomicReference<>();
        h.regime.expect(once(), requestTo(URI)).andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(fixture("manager-parts-response-no-risk-item.json"), MediaType.APPLICATION_JSON));
        EngineManagerPartsRequest base = EngineHarness.managerPartsRequest();
        Team team = new Team(base.team().totals(), base.team().returns(), base.team().benchmarkReturns(), base.team().sharpe(),
                base.team().benchSharpe(), null);

        EngineManagerPartsResult result = h.client.createManagerParts(
                new EngineManagerPartsRequest(base.referenceVersion(), base.baseDate(), base.months(), base.settings(), team));

        JsonNode sent = tree(body.get());
        assertThat(sent.get("team").has("riskScore")).isTrue();
        assertThat(sent.get("team").get("riskScore").isNull()).isTrue();
        assertThat(result.percentiles().riskItem()).isNull();
    }

    private static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(409, "problem-reference-mismatch.json", EngineRefusedException.Reason.REFERENCE_MISMATCH,
                        "engine.reference_mismatch"),
                Arguments.of(422, "problem-feed-input.json", EngineRefusedException.Reason.FEED_INPUT, "engine.feed_input"),
                Arguments.of(422, "problem-no-reference.json", EngineRefusedException.Reason.NO_REFERENCE, "engine.no_reference"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("refusals")
    void 결정적_거부는_이유를_가진_거부_예외이고_엔진_설명은_어디에도_없다(int status, String file, EngineRefusedException.Reason reason,
            String logKey, CapturedOutput output) {
        answer(status, PROBLEM, EngineHarness.problemWithMarker(file));

        assertThatThrownBy(this::call).isInstanceOfSatisfying(EngineRefusedException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
            assertThat(e.getMessage()).doesNotContain(MARKER);
        });
        assertThat(output.getAll()).contains(logKey).doesNotContain(MARKER).doesNotContain("engine.bad_response");
    }

    @Test
    void 거부_코드가_다른_상태로_오면_계약_위반이다(CapturedOutput output) {
        answer(422, PROBLEM, ((ObjectNode) tree(fixture("problem-reference-mismatch.json"))).put("status", 422).toString());

        assertThatThrownBy(this::call).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e).isNotInstanceOf(EngineRefusedException.class);
            assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        });
        assertThat(output.getAll()).contains("engine.bad_response");
    }

    @Test
    void 잘못된_요청_400은_자바_버그로_내부_오류다(CapturedOutput output) {
        answer(400, PROBLEM, EngineHarness.problemWithMarker("problem-invalid-argument.json"));

        assertThatThrownBy(this::call).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e).isNotInstanceOf(EngineRefusedException.class).isNotInstanceOf(EngineUnavailableException.class);
            assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        });
        assertThat(output.getAll()).contains("engine.invalid_request").doesNotContain(MARKER);
    }

    @ParameterizedTest
    @ValueSource(strings = { "problem-engine-busy.json", "problem-history-unavailable.json" })
    void 엔진_503은_잠시_못_쓰는_상태다(String file) {
        answer(503, PROBLEM, fixture(file));

        assertThatThrownBy(this::call).isInstanceOf(EngineUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "bad-manager-parts-nine-digits.json", "bad-manager-parts-size.json", "bad-manager-parts-no-return.json" })
    void 계약에_어긋난_응답은_잘못된_응답이다(String file) {
        answer(200, MediaType.APPLICATION_JSON, fixture(file));

        assertThatThrownBy(this::call).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e).isNotInstanceOf(EngineRefusedException.class);
            assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        });
    }

    private static Stream<String> driftedAnswers() {
        String ok = fixture("manager-parts-response.json");
        List<String> out = new ArrayList<>();
        out.add(((ObjectNode) tree(ok)).put("referenceVersion", "ref69-v2").toString());
        out.add(((ObjectNode) tree(ok)).put("referenceKey", "3F".repeat(32)).toString());
        out.add(((ObjectNode) tree(ok)).put("referenceSize", 0).toString());
        out.add(((ObjectNode) tree(ok)).put("botVersion", "Random/1").toString());
        out.add(((ObjectNode) tree(ok)).put("extra", 1).toString());
        JsonNode component = tree(ok);
        ((ObjectNode) component.get("components")).put("mdd", "-0.05");
        out.add(component.toString());
        JsonNode percentile = tree(ok);
        ((ObjectNode) percentile.get("percentiles")).put("mdd", "100.00000001");
        out.add(percentile.toString());
        JsonNode number = tree(ok);
        ((ObjectNode) number.get("parts")).put("risk", 75);
        out.add(number.toString());
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("driftedAnswers")
    void 판_키_크기_자릿수가_어긋난_응답은_거부한다(String body) {
        answer(200, MediaType.APPLICATION_JSON, body);

        assertThatThrownBy(this::call).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 요청_객체는_시즌_내용을_문자열에_싣지_않고_모양이_틀리면_거부한다() {
        EngineManagerPartsRequest ok = EngineHarness.managerPartsRequest();
        assertThat(ok.toString()).isEqualTo("EngineManagerPartsRequest[12 turns]").doesNotContain("2009");
        Team team = ok.team();
        Settings settings = ok.settings();

        assertThatThrownBy(() -> new EngineManagerPartsRequest("ref69-v2", ok.baseDate(), ok.months(), settings, team))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineManagerPartsRequest(ok.referenceVersion(), ok.baseDate(), ok.months().subList(0, 11),
                settings, team)).isInstanceOf(IllegalArgumentException.class);
        List<LocalDate> repeated = new ArrayList<>(ok.months());
        repeated.set(11, repeated.get(0));
        assertThatThrownBy(() -> new EngineManagerPartsRequest(ok.referenceVersion(), ok.baseDate(), repeated, settings, team))
                .isInstanceOf(IllegalArgumentException.class);
        List<LocalDate> midMonth = new ArrayList<>(ok.months());
        midMonth.set(3, midMonth.get(3).plusDays(1));
        assertThatThrownBy(() -> new EngineManagerPartsRequest(ok.referenceVersion(), ok.baseDate(), midMonth, settings, team))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Team(team.totals(), team.returns(), team.benchmarkReturns(), team.sharpe(), team.benchSharpe(),
                "87.5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Team(team.totals(), team.returns(), team.benchmarkReturns(), "0.3125", team.benchSharpe(),
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Team(List.of("100000000.5"), team.returns(), team.benchmarkReturns(), team.sharpe(),
                team.benchSharpe(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(120, "100000000", "0.00015", "0.0025", "0.004", "-0.2",
                new StartFormation(10, 30, 30, 30), MemberKind.HUMAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(12, "100000000", "0.00015", "0.0025", "0.004", "0.2",
                new StartFormation(10, 30, 30, 30), MemberKind.HUMAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StartFormation(0, 40, 30, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StartFormation(10, 30, 30, 31)).isInstanceOf(IllegalArgumentException.class);
    }
}
