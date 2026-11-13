package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void x_error_codes와_x_framework_errors는_구조_비교를_바꾸지_않는다() {
        String annotated = swap(BASE, "paths:\n", "x-framework-errors:\n  '405': [METHOD_NOT_ALLOWED]\npaths:\n");
        annotated = swap(annotated, "        '200':\n          description: ok\n          content:",
                "        '409':\n          description: 충돌\n          x-error-codes: [TURN_MISMATCH]\n"
                        + "        '200':\n          description: ok\n          content:");
        String plain = swap(BASE, "        '200':\n          description: ok\n          content:",
                "        '409':\n          description: 충돌\n        '200':\n          description: ok\n          content:");
        assertThat(diff(annotated, plain)).isEmpty();
        assertThat(diff(plain, annotated)).isEmpty();
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

    // ---- #98 보강: 응답 헤더·미디어 형식·범위 밖 컴포넌트·파라미터 style/explode ----

    private static final String WITH_HEADER = swap(BASE, "description: ok\n          content:",
            "description: ok\n          headers:\n            Location:\n              schema: {type: string, format: uri}\n          content:");

    @Test
    void 응답_헤더가_빠지면_한_건이다() {
        assertThat(diff(WITH_HEADER, BASE)).singleElement().asString()
                .contains("/responses/200/headers/location");
    }

    @Test
    void 응답_헤더_스키마가_다르면_한_건이다() {
        String other = swap(WITH_HEADER, "format: uri", "format: uuid");
        assertThat(diff(WITH_HEADER, other)).singleElement().asString()
                .contains("/headers/location/schema/format");
    }

    @Test
    void 응답_헤더_이름의_대소문자와_Content_Type은_무시한다() {
        String lower = swap(WITH_HEADER, "Location:", "location:");
        String ctype = swap(WITH_HEADER, "          content:",
                "            Content-Type:\n              schema: {type: string}\n          content:");
        assertThat(diff(WITH_HEADER, lower)).isEmpty();
        assertThat(diff(WITH_HEADER, ctype)).isEmpty();
    }

    @Test
    void 미디어_형식의_세미콜론_뒤_매개변수는_무시한다() {
        String charset = swap(BASE, "application/json:", "application/json;charset=UTF-8:");
        assertThat(diff(BASE, charset)).isEmpty();
        assertThat(diff(BASE, swap(BASE, "application/json:", "application/problem+json:")))
                .isNotEmpty();
    }

    @Test
    void 범위_밖_경로만_쓰는_컴포넌트는_고아가_아니다() {
        String outside = swap(BASE, "'200': {description: ok}\ncomponents:",
                "'200':\n          description: ok\n          content:\n            application/json:\n"
                        + "              schema:\n                $ref: '#/components/schemas/Health'\ncomponents:");
        String withHealth = outside + "    Health:\n      type: object\n      properties:\n        s:\n"
                + "          $ref: '#/components/schemas/Status'\n    Status:\n      type: string\n";
        assertThat(diff(withHealth, withHealth)).isEmpty();
    }

    @Test
    void 어디서도_안_쓰는_컴포넌트는_범위_밖_경로가_있어도_고아다() {
        String withOrphan = BASE + "    Orphan:\n      type: string\n";
        assertThat(diff(withOrphan, withOrphan)).singleElement().asString().contains("/orphans");
    }

    private static final String WITH_PARAM = swap(BASE, "operationId: getThings\n",
            "operationId: getThings\n      parameters:\n"
                    + "        - {name: q, in: query, required: false, schema: {type: integer}}\n");

    @Test
    void 파라미터_style_explode는_양쪽이_지정하지_않으면_비교하지_않는다() {
        assertThat(diff(WITH_PARAM, WITH_PARAM)).isEmpty();
    }

    @Test
    void 파라미터_style을_한쪽만_지정하면_한_건이다() {
        String styled = swap(WITH_PARAM, "required: false,", "required: false, style: form, explode: true,");
        assertThat(diff(styled, WITH_PARAM)).hasSize(2).allSatisfy(l -> assertThat(l).contains("/parameters/q in query/"));
    }

    @Test
    void 정규화_뒤_미디어_형식이_겹치면_거절한다() {
        String clash = swap(BASE, "            application/json:\n",
                "            application/json;charset=UTF-8:\n"
                        + "              schema: {type: string}\n"
                        + "            application/json:\n");
        assertThatThrownBy(() -> diff(clash, BASE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application/json");
    }
}
