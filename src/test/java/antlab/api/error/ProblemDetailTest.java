package antlab.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.support.IntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@IntegrationTest
@Import(ProblemDetailTest.ProbeController.class)
class ProblemDetailTest {

    private static final String PROBLEM = "application/problem+json";

    @Autowired
    MockMvcTester mvc;

    record Body(@Min(1) int quantity) {
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe/valid")
        String valid(@Valid @RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("비밀 내부 사정 XYZ");
        }

        @GetMapping("/probe/domain500")
        String domain500() {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "비밀 내부 사정 XYZ");
        }

        @GetMapping("/probe/param")
        String param(@RequestParam @Min(1) int n) {
            return "ok";
        }

        @PostMapping(value = "/probe/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
        String jsonOnly() {
            return "ok";
        }

        @GetMapping(value = "/probe/xml-only", produces = MediaType.APPLICATION_XML_VALUE)
        String xmlOnly() {
            return "<a/>";
        }

        @GetMapping("/probe/domain")
        String domain() {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "도메인 규칙 위반");
        }
    }

    @Test
    void 없는_경로는_404_NOT_FOUND() {
        assertThat(mvc.get().uri("/no/such/path")).hasStatus(404)
                .hasContentType(MediaType.parseMediaType(PROBLEM))
                .bodyJson()
                .extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void 깨진_JSON은_400_INVALID_ARGUMENT() {
        var result = mvc.post().uri("/probe/valid").contentType(MediaType.APPLICATION_JSON).content("{깨진");
        assertThat(result).hasStatus(400).hasContentType(MediaType.parseMediaType(PROBLEM)).bodyJson()
                .extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void Valid_위반은_400이고_errors에_필드와_제약이름이_있고_거부된_값은_없다() {
        var result = mvc.post().uri("/probe/valid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": -987654}");
        assertThat(result).hasStatus(400).hasContentType(MediaType.parseMediaType(PROBLEM));
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("quantity");
        assertThat(result).bodyJson().extractingPath("$.errors[0].reason").isEqualTo("Min");
        assertThat(result).body().asString().doesNotContain("987654");
    }

    @Test
    void 잘못된_메서드는_405() {
        assertThat(mvc.put().uri("/probe/valid")).hasStatus(405)
                .hasContentType(MediaType.parseMediaType(PROBLEM))
                .bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void 처리_안_된_예외는_500이고_예외_내용이_새지_않는다() {
        var result = mvc.get().uri("/probe/boom");
        assertThat(result).hasStatus(500).hasContentType(MediaType.parseMediaType(PROBLEM));
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Internal Server Error");
        assertThat(result).body().asString().doesNotContain("XYZ").doesNotContain("IllegalState")
                .doesNotContain("Exception");
    }

    @Test
    void 도메인_예외는_코드에_맞는_상태로_나간다() {
        assertThat(mvc.get().uri("/probe/domain")).hasStatus(400)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void 도메인_예외라도_5xx는_고정_문장이고_내용이_새지_않는다() {
        var result = mvc.get().uri("/probe/domain500");
        assertThat(result).hasStatus(500).hasContentType(MediaType.parseMediaType(PROBLEM));
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("처리 중 오류가 났다");
        assertThat(result).body().asString().doesNotContain("XYZ");
    }

    @Test
    void 지원하지_않는_본문_형식은_415() {
        var result = mvc.post().uri("/probe/json-only").contentType(MediaType.TEXT_PLAIN).content("x");
        assertThat(result).hasStatus(415).hasContentType(MediaType.parseMediaType(PROBLEM));
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void 제공하지_않는_응답_형식은_406() {
        var result = mvc.get().uri("/probe/xml-only").accept(MediaType.APPLICATION_JSON);
        assertThat(result).hasStatus(406);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    void 요청_파라미터_검증_위반은_400이고_errors에_필드와_제약이름이_있다() {
        var result = mvc.get().uri("/probe/param?n=0");
        assertThat(result).hasStatus(400).hasContentType(MediaType.parseMediaType(PROBLEM));
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("n");
        assertThat(result).bodyJson().extractingPath("$.errors[0].reason").isEqualTo("Min");
    }

    @Test
    void 오류_응답의_공통_필드와_405의_Allow_헤더() {
        var result = mvc.put().uri("/probe/valid");
        assertThat(result).hasStatus(405).hasHeader("Allow", "POST");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(405);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Method Not Allowed");
    }

    @Test
    void 모든_오류_코드에_HTTP_상태가_있다() {
        // 매핑 누락은 switch 식이 컴파일에서 막는다. 도메인 코드는 여러 개가 같은 상태(409·422)를 쓸 수 있다.
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(ErrorHttpMapping.status(code)).as(code.name()).isNotNull();
        }
    }

    @Test
    void MVC_예외_상태는_일반_코드로_접힌다() {
        assertThat(ErrorHttpMapping.code(HttpStatus.BAD_REQUEST)).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ErrorHttpMapping.code(HttpStatus.PAYLOAD_TOO_LARGE)).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ErrorHttpMapping.code(HttpStatus.NOT_FOUND)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorHttpMapping.code(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorHttpMapping.code(HttpStatus.NOT_ACCEPTABLE)).isEqualTo(ErrorCode.NOT_ACCEPTABLE);
        assertThat(ErrorHttpMapping.code(HttpStatus.UNSUPPORTED_MEDIA_TYPE)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorHttpMapping.code(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
