package antlab.projection;

import antlab.engine.client.EngineProjectionRequest;
import antlab.engine.client.EngineProjectionResult.PortfolioResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The two JSON documents of a stored projection. Ratios are written as plain decimal strings with their scale kept
 * (the default would write a number, and {@code 0E-8} for zero), so a stored row reads back to the exact values the
 * engine sent. The request document holds the portfolio and the risk limit only: no seed, no base date.
 */
final class ProjectionJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(BigDecimal.class, new PlainSerializer()))
            .build();

    private static final TypeReference<List<PortfolioResult>> PORTFOLIOS = new TypeReference<>() {
    };

    private ProjectionJson() {
    }

    static String request(EngineProjectionRequest request) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("riskLimit", request.riskLimit());
        document.put("summaryTurns", request.summaryTurns());
        document.put("portfolios", request.portfolios());
        return MAPPER.writeValueAsString(document);
    }

    static String result(List<PortfolioResult> portfolios) {
        return MAPPER.writeValueAsString(portfolios);
    }

    static List<PortfolioResult> readResult(String json) {
        return MAPPER.readValue(json, PORTFOLIOS);
    }

    private static final class PlainSerializer extends StdSerializer<BigDecimal> {

        PlainSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext context) {
            gen.writeString(value.toPlainString());
        }
    }
}
