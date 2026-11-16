package antlab.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The fixed body of the Tomcat-stage 400: same fields as {@link ApiProblem}, and the path echoed only when it is plain. */
class ProblemErrorReportValveTest {

    private static JsonNode parse(String body) {
        return JsonMapper.shared().readTree(body);
    }

    @Test
    void 본문은_문제_상세의_필수_필드와_계약_코드를_가진다() {
        JsonNode json = parse(ProblemErrorReportValve.body("/api/v1/pool"));
        assertThat(json.get("title").asString()).isEqualTo("Bad Request");
        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("detail").asString()).isEqualTo(ProblemErrorReportValve.DETAIL);
        assertThat(json.get("instance").asString()).isEqualTo("/api/v1/pool");
        assertThat(json.get("code").asString()).isEqualTo(ErrorCode.MALFORMED_REQUEST.name());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
    }

    @Test
    void 평범하지_않은_경로는_instance에_싣지_않는다() {
        String tooLong = "/" + "a".repeat(201);
        for (String uri : new String[] {null, "", "api", "/api/v1/pool%ZZ", "/api/v1/a%2Fb", "/a\"b", "/a b", "/a\\b",
                "/한글", "/a\nb", tooLong}) {
            assertThat(parse(ProblemErrorReportValve.body(uri)).get("instance").asString()).as(String.valueOf(uri))
                    .isEqualTo("/");
        }
    }

    @Test
    void 경로_길이_상한_200자_경계() {
        String atLimit = "/" + "a".repeat(200);
        assertThat(parse(ProblemErrorReportValve.body(atLimit)).get("instance").asString()).isEqualTo(atLimit);
    }

    @Test
    void 오류_코드는_400에_매핑된다() {
        assertThat(ErrorHttpMapping.status(ErrorCode.MALFORMED_REQUEST).value()).isEqualTo(400);
    }
}
