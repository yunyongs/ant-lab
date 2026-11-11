package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 오류 본문(problem+json) 노출 단언 도우미. 본문 전체 문자열에서 거부한 값의 부재를 보려면 {@code instance}(요청 경로, 시즌 번호를 품는다)를
 * 먼저 빼야 한다 — 시즌 번호가 우연히 같은 숫자를 품어 시험 순서에 따라 깨지는 것을 막는다. {@code instance} 는 경로 모양만 따로 단언한다.
 */
public final class ProblemBodyAssert {

    private ProblemBodyAssert() {
    }

    /** {@code instance} 만 뺀 본문 사본의 JSON 문자열. */
    public static String withoutInstance(String problemJson) {
        JsonNode node = JsonMapper.shared().readTree(problemJson);
        assertThat(node.isObject()).as("본문은 JSON 객체").isTrue();
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.remove("instance");
        return copy.toString();
    }

    /** {@code instance} 를 뺀 본문 전체에 금지 문자열이 하나도 없다. */
    public static void assertAbsent(String problemJson, String... forbidden) {
        assertThat(withoutInstance(problemJson)).doesNotContain(forbidden);
    }

    /** {@code instance} 는 시즌 하위 경로 모양({@code /api/v1/seasons/{번호}/…})이다. */
    public static void assertInstanceShape(String problemJson, String suffix) {
        JsonNode instance = JsonMapper.shared().readTree(problemJson).get("instance");
        assertThat(instance).as("instance 필드").isNotNull();
        assertThat(instance.asString()).matches("/api/v1/seasons/\\d+/" + suffix);
    }
}
