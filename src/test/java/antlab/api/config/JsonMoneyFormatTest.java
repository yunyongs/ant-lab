package antlab.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.IntegrationTest;
import antlab.support.ProbeSecurityConfig;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import({JsonMoneyFormatTest.MoneyProbeController.class, ProbeSecurityConfig.class})
class JsonMoneyFormatTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JsonMapper jsonMapper;

    record Money(BigDecimal amount, long quantity) {
    }

    @RestController
    static class MoneyProbeController {
        @GetMapping("/probe/money")
        Money get() {
            return new Money(new BigDecimal("1E+3"), 7L);
        }

        @GetMapping("/probe/money/huge")
        BigDecimal huge() {
            return new BigDecimal("1E+100000");
        }

        @GetMapping("/probe/money/extreme")
        BigDecimal extreme() {
            return new BigDecimal("1E+2147483647");
        }

        @GetMapping("/probe/money/huge-list")
        List<BigDecimal> hugeList() {
            return List.of(BigDecimal.ONE, new BigDecimal("1E+100000"));
        }

        @PostMapping("/probe/money")
        Money echo(@RequestBody Money in) {
            return in;
        }
    }

    @Test
    void 지수_표기_없이_문자열로_나가고_수량은_숫자다() {
        var result = mvc.get().uri("/probe/money");
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.amount").isEqualTo("1000");
        assertThat(result).bodyJson().extractingPath("$.quantity").isEqualTo(7);
    }

    @Test
    void 매퍼는_scale을_유지한다() {
        assertThat(jsonMapper.writeValueAsString(new BigDecimal("0.01234567"))).isEqualTo("\"0.01234567\"");
        assertThat(jsonMapper.writeValueAsString(new BigDecimal("-0.5000"))).isEqualTo("\"-0.5000\"");
        assertThat(jsonMapper.writeValueAsString(new BigDecimal("1E+3"))).isEqualTo("\"1000\"");
    }

    @Test
    void 문자열로_받은_금액은_같은_BigDecimal로_읽힌다() {
        Money m = jsonMapper.readValue("{\"amount\":\"12.3400\",\"quantity\":3}", Money.class);
        assertThat(m.amount()).isEqualTo(new BigDecimal("12.3400"));
        assertThat(m.amount().scale()).isEqualTo(4);
    }

    @Test
    void 왕복해도_값과_scale이_같다() {
        var result = mvc.post().uri("/probe/money").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"-0.5000\",\"quantity\":2}");
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.amount").isEqualTo("-0.5000");
    }

    @Test
    void 명세에서도_금액은_string이다() {
        var result = mvc.get().uri("/v3/api-docs");
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.components.schemas.Money.properties.amount.type")
                .isEqualTo("string");
    }

    @Test
    void 정수_필드의_소수_입력은_400이다() {
        for (String q : new String[] {"1.5", "2.0", "1e1"}) {
            var result = mvc.post().uri("/probe/money").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":\"1\",\"quantity\":" + q + "}");
            assertThat(result).as(q).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
        }
    }

    @Test
    void 짧은_지수_입력은_불어나지_않고_400이다() {
        for (String amount : new String[] {"\"1E+100000000\"", "\"1E-100000000\"", "1E+100000000"}) {
            var result = mvc.post().uri("/probe/money").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":" + amount + ",\"quantity\":1}");
            assertThat(result).as(amount).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
        }
    }

    @Test
    void 극단_지수도_자릿수_계산이_넘치지_않는다() {
        assertThat(JsonConfig.plainDigits(new BigDecimal("1E-2147483647"))).isEqualTo(2_147_483_648L);
        assertThat(JsonConfig.plainDigits(new BigDecimal("1E+2147483648"))).isEqualTo(2_147_483_649L);
        for (String amount : new String[] {"\"1E-2147483647\"", "\"1E+2147483647\"", "\"1E+2147483648\"",
                "\"0E-2147483647\"", "1E-2147483647", "1E+2147483647"}) {
            var result = mvc.post().uri("/probe/money").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":" + amount + ",\"quantity\":1}");
            assertThat(result).as(amount).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_ARGUMENT");
        }
        assertThat(mvc.get().uri("/probe/money/extreme")).hasStatus(500)
                .bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void 스키마_최대_자릿수는_통과한다() {
        String n6040 = "12345678901234567890.1234567890123456789012345678901234567890";
        var result = mvc.post().uri("/probe/money").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"" + n6040 + "\",\"quantity\":1}");
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.amount").isEqualTo(n6040);
    }

    @Test
    void 상한을_넘는_출력은_500이다() {
        assertThat(mvc.get().uri("/probe/money/huge")).hasStatus(500)
                .bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void 목록_안의_상한_초과_출력도_500이고_로그에_남는다(CapturedOutput output) {
        assertThat(mvc.get().uri("/probe/money/huge-list")).hasStatus(500)
                .bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(output.getOut()).contains("응답 직렬화 실패");
    }

    @Test
    void 쓰다가_실패한_JSON은_닫히지_않는다() {
        // Spring 변환기처럼 생성기를 try-with-resources 로 닫는다. 이미 보낸 응답이 정상 JSON 처럼 끝나지 않아야 한다.
        var out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> {
            try (JsonGenerator gen = jsonMapper.createGenerator(out)) {
                jsonMapper.writeValue(gen, List.of(BigDecimal.ONE, new BigDecimal("1E+100000")));
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("[\"1\"");
    }
}
