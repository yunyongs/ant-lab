package antlab.projection;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.client.EngineProjectionRequest;
import antlab.engine.client.EngineProjectionResult.Band;
import antlab.engine.client.EngineProjectionResult.EndQuantiles;
import antlab.engine.client.EngineProjectionResult.MddQuantiles;
import antlab.engine.client.EngineProjectionResult.PortfolioResult;
import antlab.engine.client.EngineProjectionResult.Summary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The stored documents: ratios as plain strings with their scale, and no seed in the request document. */
class ProjectionJsonTest {

    private static BigDecimal r(String text) {
        return new BigDecimal(text);
    }

    private static PortfolioResult portfolio() {
        Band band = new Band(1, r("0.00000000"), r("0.00000001"), r("1.00000000"), r("1.05000000"), r("12345.67890123"));
        EndQuantiles end = new EndQuantiles(r("0.00000000"), r("0.90000000"), r("1.00000000"), r("1.10000000"), r("1.20000000"));
        MddQuantiles mdd = new MddQuantiles(r("-1.00000000"), r("-0.20000000"), r("-0.10000000"), r("-0.05000000"),
                r("-0.02000000"), r("0.00000000"));
        return new PortfolioResult("TEAM", List.of(band), List.of(new Summary(12, end, mdd, 5, r("0.01000000"))));
    }

    @Test
    void 결과는_문자열_비율로_저장하고_자릿수까지_그대로_읽힌다() throws Exception {
        String json = ProjectionJson.result(List.of(portfolio()));
        JsonNode first = JsonMapper.shared().readTree(json).get(0).get("bands").get(0);

        assertThat(first.get("p05").isString()).isTrue();
        assertThat(first.get("p05").asString()).isEqualTo("0.00000000");
        assertThat(first.get("p25").asString()).isEqualTo("0.00000001");
        assertThat(first.get("p95").asString()).isEqualTo("12345.67890123");
        assertThat(ProjectionJson.readResult(json)).isEqualTo(List.of(portfolio()));
        assertThat(ProjectionJson.readResult(json).get(0).bands().get(0).p05().scale()).isEqualTo(8);
    }

    @Test
    void 요청_문서에는_시드와_기준일이_없다() throws Exception {
        var team = new EngineProjectionRequest.Portfolio("TEAM", "0.500000000000",
                List.of(new EngineProjectionRequest.Holding("KR:A001", "0.500000000000")));
        var request = new EngineProjectionRequest("-9223372036854775808", LocalDate.of(2026, 1, 2), 60, 2000, "-0.20000000",
                List.of(12, 60), List.of(team));

        String json = ProjectionJson.request(request);

        assertThat(json).doesNotContain("9223372036854775808").doesNotContain("2026-01-02").doesNotContain("seasonSeed")
                .doesNotContain("baseDate");
        JsonNode node = JsonMapper.shared().readTree(json);
        assertThat(node.get("riskLimit").asString()).isEqualTo("-0.20000000");
        assertThat(node.get("summaryTurns").size()).isEqualTo(2);
        assertThat(node.get("portfolios").get(0).get("weights").get(0).get("asset").asString()).isEqualTo("KR:A001");
    }
}
