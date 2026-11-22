package antlab.engine.client;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;

/** The mapper on its own: what is logged for a 400, and which transport failures are "timeout" and which "unreachable". */
@ExtendWith(OutputCaptureExtension.class)
class EngineErrorMapperTest {

    private static final String REASON_MARKER = "MARKER-ENGINE-REASON-5521";
    private static final String DETAIL_MARKER = "MARKER-ENGINE-DETAIL-7731";

    private static EngineProblem badRequest() {
        return EngineWire.read(EngineProblem.class, ("{\"title\":\"Invalid Argument\",\"status\":400,\"detail\":\"" + DETAIL_MARKER
                + "\",\"instance\":\"/v1/paths\",\"code\":\"INVALID_ARGUMENT\",\"errors\":["
                + "{\"field\":\"body.length\",\"reason\":\"" + REASON_MARKER + "\"},"
                + "{\"field\":\"has space and \\\"quote\\\"\",\"reason\":\"" + REASON_MARKER + "\"}]}").getBytes());
    }

    @Test
    void 열의_오류는_필드_이름만_기록하고_이유와_엔진_문장은_기록하지_않는다(CapturedOutput output) {
        RuntimeException result = EngineErrorMapper.fromProblem("createPath", 400, badRequest());

        assertThat(result).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(output.getAll()).contains("engine.invalid_request", "fields=[body.length]")
                .doesNotContain(REASON_MARKER).doesNotContain(DETAIL_MARKER).doesNotContain("quote");
    }

    private static EngineProblem regimeDataInvalid(int status) {
        return EngineWire.read(EngineProblem.class, ("{\"title\":\"Regime Data Invalid\",\"status\":" + status + ",\"detail\":\""
                + DETAIL_MARKER + "\",\"instance\":\"/v1/regime/evaluate\",\"code\":\"REGIME_DATA_INVALID\"}").getBytes());
    }

    @Test
    void 국면_데이터_오류는_422일_때만_자기_규칙으로_매핑되고_엔진_문장은_기록하지_않는다(CapturedOutput output) {
        RuntimeException result = EngineErrorMapper.fromProblem("evaluateRegime", 422, regimeDataInvalid(422));

        assertThat(result).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(output.getAll()).contains("engine.regime_data_invalid").doesNotContain("engine.bad_response").doesNotContain(DETAIL_MARKER);
    }

    @Test
    void 국면_데이터_오류가_다른_상태로_오면_계약_어긋남으로_본다(CapturedOutput output) {
        for (int status : new int[] { 400, 500, 503 }) {
            RuntimeException result = EngineErrorMapper.fromProblem("evaluateRegime", status, regimeDataInvalid(status));

            assertThat(result).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
        assertThat(output.getAll()).contains("engine.bad_response").doesNotContain("engine.regime_data_invalid");
    }

    @Test
    void 국면_라벨_부족은_422일_때만_불가용이_되고_엔진_문장은_기록하지_않는다(CapturedOutput output) {
        EngineProblem problem = EngineWire.read(EngineProblem.class,
                antlab.support.FakeEngineServer.fixture("problem-regime-labels-missing.json").getBytes());

        RuntimeException mapped = EngineErrorMapper.fromProblem("createPath", 422, problem);
        assertThat(mapped).isInstanceOfSatisfying(EngineUnavailableException.class, e -> {
            assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.LABELS_MISSING);
            assertThat(e.getCode()).isEqualTo(ErrorCode.ENGINE_UNAVAILABLE);
            assertThat(e.getMessage()).doesNotContain("labelled");
        });
        assertThat(output.getAll()).contains("engine.labels_missing").doesNotContain("labelled").doesNotContain("pairs");

        // the same code with any other HTTP status is contract drift
        for (int status : new int[] { 400, 500, 503 }) {
            RuntimeException drift = EngineErrorMapper.fromProblem("createPath", status, problem);
            assertThat(drift).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
        assertThat(output.getAll().split("engine.bad_response", -1).length - 1).isEqualTo(3);
    }

    @Test
    void 쓸_수_없는_수집_관측값_코드는_422일_때만_이_이름으로_기록되고_내부_오류가_된다(CapturedOutput output) {
        EngineProblem problem = EngineWire.read(EngineProblem.class,
                antlab.support.FakeEngineServer.fixture("problem-regime-data-invalid.json").getBytes());

        RuntimeException mapped = EngineErrorMapper.fromProblem("evaluateRegime", 422, problem);
        assertThat(mapped).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(output.getAll()).contains("engine.regime_data_invalid").doesNotContain("engine.bad_response");

        // the same code with any other HTTP status is contract drift, not a data problem
        for (int status : new int[] { 400, 500, 503 }) {
            EngineErrorMapper.fromProblem("evaluateRegime", status, problem);
        }
        assertThat(output.getAll().split("engine.bad_response", -1).length - 1).isEqualTo(3);
    }

    @Test
    void 연결_시간_초과는_연결_불가이고_읽기_시간_초과는_시간_초과다(CapturedOutput output) {
        RuntimeException connect = EngineErrorMapper.transport("createPath", new ResourceAccessException("x", new HttpConnectTimeoutException("c")));
        RuntimeException read = EngineErrorMapper.transport("createPath", new ResourceAccessException("x", new HttpTimeoutException("r")));
        RuntimeException socket = EngineErrorMapper.transport("createPath",
                new ResourceAccessException("x", new SocketTimeoutException("s")));
        RuntimeException other = EngineErrorMapper.transport("createPath",
                new ResourceAccessException("x", new java.net.ConnectException("refused")));

        assertThat(reason(connect)).isEqualTo(EngineUnavailableException.Reason.UNREACHABLE);
        assertThat(reason(read)).isEqualTo(EngineUnavailableException.Reason.TIMEOUT);
        assertThat(reason(socket)).isEqualTo(EngineUnavailableException.Reason.TIMEOUT);
        assertThat(reason(other)).isEqualTo(EngineUnavailableException.Reason.UNREACHABLE);
        assertThat(output.getAll()).contains("engine.timeout", "engine.unreachable");
    }

    private static EngineUnavailableException.Reason reason(RuntimeException e) {
        assertThat(e).isInstanceOf(EngineUnavailableException.class);
        return ((EngineUnavailableException) e).reason();
    }
}
