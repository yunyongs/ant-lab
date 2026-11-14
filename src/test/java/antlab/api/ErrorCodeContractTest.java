package antlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.ContractErrorCodes;
import antlab.support.OpenApiNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The error layer of the contract ({@code x-error-codes}, {@code x-framework-errors}) is consistent with
 * {@code ErrorHttpMapping}. Pure file test: no Spring. {@code ApiErrorSweepTest} checks the codes it observes against
 * the same layer through {@link ContractErrorCodes#isListed}.
 */
class ErrorCodeContractTest {

    private static final String SNIPPET = """
            openapi: 3.1.0
            x-framework-errors:
              '405': [METHOD_NOT_ALLOWED]
            paths:
              /api/v1/things:
                post:
                  responses:
                    '200': {description: ok}
                    '409':
                      description: conflict - TURN_MISMATCH
                      x-error-codes: [TURN_MISMATCH]
                    '500':
                      $ref: '#/components/responses/InternalError'
            components:
              responses:
                InternalError:
                  description: boom - INTERNAL_ERROR
                  x-error-codes: [INTERNAL_ERROR]
            """;

    private static JsonNode doc(String yaml) {
        return OpenApiNormalizer.fromYaml(yaml);
    }

    private static String swap(String yaml, String from, String to) {
        assertThat(yaml).contains(from);
        return yaml.replace(from, to);
    }

    private static JsonNode contract() throws IOException {
        return doc(Files.readString(Path.of("docs/api/openapi.yaml")));
    }

    @Test
    void 은퇴한_MEMBER_UNAVAILABLE이_계약_어디에도_없다() throws IOException {
        assertThat(Files.readString(Path.of("docs/api/openapi.yaml"))).doesNotContain("MEMBER_UNAVAILABLE");
        assertThat(java.util.Arrays.stream(antlab.common.error.ErrorCode.values()).map(Enum::name))
                .doesNotContain("MEMBER_UNAVAILABLE");
    }

    @Test
    void 계약의_오류_코드는_상태_매핑과_맞고_FORBIDDEN_말고는_모두_적혀_있다() throws IOException {
        assertThat(ContractErrorCodes.violations(contract())).isEmpty();
    }

    @Test
    void 계약의_모든_비_2xx_응답에_코드가_적혀_있다() throws IOException {
        var perOperation = ContractErrorCodes.perOperation(contract());
        assertThat(perOperation).hasSize(16);
        perOperation.forEach((op, byStatus) -> {
            assertThat(byStatus).as(op).containsKey(500);
            byStatus.forEach((status, codes) -> assertThat(codes).as(op + " " + status).isNotEmpty());
        });
        assertThat(ContractErrorCodes.framework(contract())).containsOnlyKeys(405, 406, 415);
    }

    @Test
    void 샘플_조각은_위반이_없다_빠진_코드_말고는() {
        // the snippet lists only 3 codes, so only the "unlisted ErrorCode" lines remain
        assertThat(ContractErrorCodes.violations(doc(SNIPPET))).isNotEmpty().allMatch(v -> v.startsWith("ErrorCode "));
        assertThat(ContractErrorCodes.violations(doc(SNIPPET))).noneMatch(v -> v.contains("TURN_MISMATCH"));
    }

    @Test
    void TURN_MISMATCH를_422_아래_적으면_보고된다() {
        String planted = swap(SNIPPET, "'409':", "'422':");
        assertThat(ContractErrorCodes.violations(doc(planted)))
                .contains("POST /api/v1/things 422: TURN_MISMATCH maps to 409, not 422");
    }

    @Test
    void 코드_목록이_없거나_비었거나_이름이_틀리면_보고된다() {
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "\n          x-error-codes: [TURN_MISMATCH]", ""))))
                .contains("POST /api/v1/things 409: x-error-codes is missing or empty");
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "[TURN_MISMATCH]", "[]"))))
                .contains("POST /api/v1/things 409: x-error-codes is missing or empty");
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "[TURN_MISMATCH]", "[TURN_MISMATCHED]"))))
                .contains("POST /api/v1/things 409: TURN_MISMATCHED is not an ErrorCode");
    }

    @Test
    void 설명_글이_말하는_코드와_순서가_목록과_다르면_보고된다() {
        String text = "description: conflict - TURN_MISMATCH\n";
        String named = swap(SNIPPET, text, "description: conflict - SEASON_NOT_ACTIVE, TURN_MISMATCH\n");
        assertThat(ContractErrorCodes.violations(doc(named)))
                .contains("POST /api/v1/things 409: x-error-codes [TURN_MISMATCH] differ from the codes the description"
                        + " names [SEASON_NOT_ACTIVE, TURN_MISMATCH]");
        // distinct and ordered: a code named twice is one name, the order of first appearance counts
        String twice = swap(SNIPPET, "[TURN_MISMATCH]", "[TURN_MISMATCH, SEASON_NOT_ACTIVE]");
        twice = swap(twice, text, "description: SEASON_NOT_ACTIVE, TURN_MISMATCH, SEASON_NOT_ACTIVE\n");
        assertThat(ContractErrorCodes.violations(doc(twice))).anyMatch(v -> v.contains("differ from the codes"));
        String same = swap(twice, "[TURN_MISMATCH, SEASON_NOT_ACTIVE]", "[SEASON_NOT_ACTIVE, TURN_MISMATCH]");
        assertThat(ContractErrorCodes.violations(doc(same))).noneMatch(v -> v.contains("differ from the codes"));
    }

    @Test
    void 코드_목록이_문자열_배열이_아니거나_중복이면_보고된다() {
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "[TURN_MISMATCH]", "{a: TURN_MISMATCH}"))))
                .contains("POST /api/v1/things 409: x-error-codes must be an array of strings");
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "[TURN_MISMATCH]", "[1, 2]"))))
                .contains("POST /api/v1/things 409: x-error-codes must be an array of strings");
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "[TURN_MISMATCH]", "[TURN_MISMATCH, TURN_MISMATCH]"))))
                .contains("POST /api/v1/things 409: x-error-codes lists a code twice");
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "[METHOD_NOT_ALLOWED]", "{a: METHOD_NOT_ALLOWED}"))))
                .contains("x-framework-errors 405: x-error-codes must be an array of strings");
    }

    @Test
    void 프레임워크_오류도_상태와_맞아야_한다() {
        assertThat(ContractErrorCodes.violations(doc(swap(SNIPPET, "'405': [METHOD_NOT_ALLOWED]", "'406': [METHOD_NOT_ALLOWED]"))))
                .contains("x-framework-errors 406: METHOD_NOT_ALLOWED maps to 405, not 406");
    }

    @Test
    void 관찰한_코드는_적힌_코드의_부분집합이어야_한다() {
        JsonNode doc = doc(SNIPPET);
        String op = "POST /api/v1/things";
        assertThat(ContractErrorCodes.isListed(doc, op, 409, "TURN_MISMATCH")).isTrue();
        assertThat(ContractErrorCodes.isListed(doc, op, 500, "INTERNAL_ERROR")).isTrue();
        assertThat(ContractErrorCodes.isListed(doc, op, 405, "METHOD_NOT_ALLOWED")).isTrue();
        // not listed: another code at a listed status, a listed code at another status, an unknown operation
        assertThat(ContractErrorCodes.isListed(doc, op, 409, "SEASON_NOT_ACTIVE")).isFalse();
        assertThat(ContractErrorCodes.isListed(doc, op, 422, "TURN_MISMATCH")).isFalse();
        assertThat(ContractErrorCodes.isListed(doc, "GET /api/v1/other", 409, "TURN_MISMATCH")).isFalse();
    }
}
