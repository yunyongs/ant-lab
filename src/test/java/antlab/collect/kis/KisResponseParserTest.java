package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class KisResponseParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<DailyClose> parse(String closeJson) {
        String body = "{\"output2\":[{\"stck_bsop_date\":\"20260109\",\"stck_clpr\":" + closeJson + "}]}";
        return KisResponseParser.rows(JSON.readTree(body), "stck_bsop_date", "stck_clpr");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"abc\"", "\"-1\"", "\"0\"", "\"0.0000\"", "\"1.23456\"", "\"1e3\"", "\"1,000\"", "\" 5\"", "null", "12"})
    void 잘못된_종가는_필드_이름만_알리는_파싱_오류다(String closeJson) {
        assertThatThrownBy(() -> parse(closeJson)).isInstanceOfSatisfying(KisException.class, e -> {
            assertThat(e.code()).isEqualTo("KIS_PARSE");
            assertThat(e.getMessage()).contains("stck_clpr").doesNotContain("abc").doesNotContain("1.23456").doesNotContain("1,000");
        });
    }

    @Test
    void 종가_필드가_아예_없으면_필드_이름을_알리는_파싱_오류다() {
        var json = JSON.readTree("{\"output2\":[{\"stck_bsop_date\":\"20260109\"}]}");

        assertThatThrownBy(() -> KisResponseParser.rows(json, "stck_bsop_date", "stck_clpr"))
                .isInstanceOfSatisfying(KisException.class, e -> {
                    assertThat(e.code()).isEqualTo("KIS_PARSE");
                    assertThat(e.getMessage()).contains("stck_clpr");
                });
    }

    @Test
    void 날짜_모양이_틀리면_필드_이름만_알린다() {
        var json = JSON.readTree("{\"output2\":[{\"stck_bsop_date\":\"2026-01-09\",\"stck_clpr\":\"1\"}]}");

        assertThatThrownBy(() -> KisResponseParser.rows(json, "stck_bsop_date", "stck_clpr"))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.getMessage()).contains("stck_bsop_date").doesNotContain("2026-01-09"));
    }

    @Test
    void 뒤쪽_0은_버려_소수_넷_이하로_읽고_값은_그대로다() {
        assertThat(parse("\"101.2500\"").getFirst().close()).isEqualTo(new BigDecimal("101.25"));
        assertThat(parse("\"70000\"").getFirst().close()).isEqualTo(new BigDecimal("70000"));
        assertThat(parse("\"70000.0000\"").getFirst().close()).isEqualTo(new BigDecimal("70000"));
        assertThat(parse("\"0.0001\"").getFirst().close()).isEqualTo(new BigDecimal("0.0001"));
        assertThat(parse("\"1.230000\"").getFirst().close().scale()).isEqualTo(2);
    }

    @Test
    void 날짜가_빈_행은_건너뛰고_output2가_없으면_빈_목록이다() {
        assertThat(KisResponseParser.rows(JSON.readTree("{\"output2\":[{\"stck_bsop_date\":\"\"}]}"), "stck_bsop_date", "stck_clpr")).isEmpty();
        assertThat(KisResponseParser.rows(JSON.readTree("{\"rt_cd\":\"0\"}"), "stck_bsop_date", "stck_clpr")).isEmpty();
    }

    @Test
    void 종가_객체는_양수와_소수_넷_이하만_받는다() {
        assertThatThrownBy(() -> new DailyClose(java.time.LocalDate.of(2026, 1, 9), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailyClose(java.time.LocalDate.of(2026, 1, 9), new BigDecimal("1.00001"))).isInstanceOf(IllegalArgumentException.class);
    }
}
