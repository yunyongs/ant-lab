package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 비교기 자체 시험(스프링 없음): 계약이 어긋나는 경우를 각각 한 건으로 잡고, 의미 없는 차이는 무시한다. */
class OpenApiNormalizerTest {

    private static final String BASE = """
            openapi: 3.1.0
            info: {title: t, version: '1'}
            paths:
              /api/v1/things:
                get:
                  operationId: getThings
                  summary: 목록
                  tags: [things]
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/ThingDto'
              /actuator/health:
                get:
                  operationId: health
                  responses:
                    '200': {description: ok}
            components:
              schemas:
                ThingDto:
                  type: object
                  required: [id, name, kind]
                  properties:
                    id: {type: integer, format: int64}
                    name: {type: [string, 'null'], description: 이름}
                    kind:
                      $ref: '#/components/schemas/Kind'
                Kind:
                  type: string
                  enum: [A, B]
            """;

    /** 바꿀 문장이 실제로 있는지 확인하고 바꾼다(빈 치환으로 시험이 헛도는 것을 막는다). */
    private static String swap(String yaml, String from, String to) {
        assertThat(yaml).contains(from);
        return yaml.replace(from, to);
    }

    private static JsonNode doc(String yaml) {
        return OpenApiNormalizer.fromYaml(yaml);
    }

    private static List<String> diff(String yaml, String code) {
        return OpenApiDiff.diff(doc(yaml), doc(code));
    }

    @Test
    void 같은_문서는_차이가_없다() {
        assertThat(diff(BASE, BASE)).isEmpty();
    }

    @Test
    void 설명_예시_속성_순서만_다르면_차이가_없다() {
        String other = swap(BASE, "summary: 목록", "summary: 다른 요약\n      description: 길게 설명");
        other = swap(other, "description: 이름", "description: 다른 설명, example: x");
        other = swap(other, "required: [id, name, kind]", "required: [kind, name, id]");
        other = swap(other, "enum: [A, B]", "enum: [B, A]\n      example: A");
        assertThat(diff(BASE, other)).isEmpty();
    }

    @Test
    void 필수에서_하나가_빠지면_한_건이다() {
        assertThat(diff(BASE, swap(BASE, "required: [id, name, kind]", "required: [id, name]")))
                .singleElement().asString().contains("/components/ThingDto/required");
    }

    @Test
    void 속성_형만_integer에서_string으로_바뀌면_한_건이다() {
        String yaml = swap(BASE, "id: {type: integer, format: int64}", "id: {type: integer}");
        assertThat(diff(yaml, swap(yaml, "id: {type: integer}", "id: {type: string}")))
                .singleElement().asString().contains("/properties/id/type");
    }

    @Test
    void 응답_코드가_추가되면_한_건이다() {
        String other = swap(BASE, "        '200':\n          description: ok\n          content:",
                "        '409':\n          description: 충돌\n        '200':\n          description: ok\n          content:");
        assertThat(diff(BASE, other)).singleElement().asString()
                .contains("/operations/GET /api/v1/things/responses/409");
    }

    @Test
    void enum_값이_하나_늘면_한_건이다() {
        assertThat(diff(BASE, swap(BASE, "enum: [A, B]", "enum: [A, B, C]")))
                .singleElement().asString().contains("/components/Kind/enum");
    }

    @Test
    void 참조_이름이_바뀌면_차이로_잡힌다() {
        String renamed = swap(BASE, "$ref: '#/components/schemas/Kind'", "$ref: '#/components/schemas/ThingKind'");
        renamed = swap(renamed, "    Kind:\n", "    ThingKind:\n");
        assertThat(diff(BASE, renamed)).isNotEmpty()
                .anyMatch(l -> l.contains("/components/ThingDto/properties/kind"));
    }

    @Test
    void yaml에만_있는_고아_컴포넌트는_차이다() {
        String withOrphan = BASE + "    Orphan:\n      type: string\n";
        // 코드 쪽에도 같은 고아가 있어도 yaml 의 고아는 계약 위반이다.
        assertThat(diff(withOrphan, withOrphan)).singleElement().asString().contains("/orphans");
    }

    @Test
    void null_허용_type의_순서는_무시한다() {
        assertThat(diff(BASE, swap(BASE, "[string, 'null']", "['null', string]"))).isEmpty();
    }

    @Test
    void 따옴표_없는_정수_응답_키도_문자열_키와_같다() {
        assertThat(diff(BASE, swap(BASE, "'200':\n          description: ok\n          content:",
                "200:\n          description: ok\n          content:"))).isEmpty();
    }

    @Test
    void 범위_밖_경로의_차이는_무시한다() {
        assertThat(diff(BASE, swap(BASE, "operationId: health", "operationId: other"))).isEmpty();
        assertThat(diff(BASE, BASE + "  /probe/x:\n    get:\n      operationId: probe\n")).isEmpty();
    }

    @Test
    void 코드에만_있는_범위_안_경로도_실패다() {
        String more = swap(BASE, "  /actuator/health:",
                "  /api/v1/extra:\n    get:\n      operationId: extra\n      responses:\n        '200': {description: ok}\n  /actuator/health:");
        assertThat(diff(BASE, more)).singleElement().asString().contains("/operations/GET /api/v1/extra");
    }
}
