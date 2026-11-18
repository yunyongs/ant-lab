package antlab.scoring;

import antlab.engine.client.EnginePercentileRequest;
import antlab.engine.client.EnginePercentileResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The {@code inputs} document of a season score row: every value the score was computed from, for audits and for the
 * later re-fitting of the formula. Decimals are plain strings with their scale kept; the Sharpe values are the exact,
 * unrounded ones (the columns hold them rounded and capped). The formation item is scored on
 * {@code measuredFormationDeviation}; the declared-position {@code formationDeviation} is kept beside it as a reference. It is never part of a response. It holds the engine's
 * counts and versions but neither the seed nor any month.
 */
final class ScoreInputsJson {

    /** Why a season has no path distribution. */
    static final String PATH_TOO_LONG = "PATH_TOO_LONG";
    static final String HORIZON_UNSUPPORTED = "HORIZON_UNSUPPORTED";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(BigDecimal.class, new PlainSerializer()))
            .build();

    private ScoreInputsJson() {
    }

    /**
     * @param request  the engine request that was sent, null when no call was made
     * @param result   the engine's answer, null when there is none
     * @param noDistribution the reason there is no answer ({@link #PATH_TOO_LONG}, {@link #HORIZON_UNSUPPORTED}), else null
     */
    static String of(SeasonReportDto report, ScoreAssembler.Observation observation, ScoreBasis basis,
            EnginePercentileRequest request, EnginePercentileResult result, String noDistribution) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scoreVersion", SeasonScoreCalculator.VERSION);
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("reportTurns", report.reportTurns());
        items.put("riskBreachTurns", report.riskBreachTurns());
        items.put("riskBreachDepth", report.riskBreachDepth());
        items.put("riskLimit", report.riskLimit());
        items.put("maxSingleWeight", report.maxSingleWeight());
        items.put("turnoverAnnual", report.turnoverAnnual());
        items.put("measuredFormationDeviation", report.measuredFormationDeviation());
        items.put("formationDeviation", report.formationDeviation());
        items.put("roleFit", report.roleFit());
        items.put("nudgeShare", report.nudgeShare());
        root.put("report", items);
        Map<String, Object> discipline = null;
        if (basis.discipline() != null) {
            discipline = new LinkedHashMap<>();
            discipline.put("panicSellRatio", basis.discipline().panicSellRatio());
            discipline.put("chaseBuyRatio", basis.discipline().chaseBuyRatio());
        }
        root.put("discipline", discipline);
        root.put("noiseShare", basis.noiseShare());
        Map<String, Object> sharpe = new LinkedHashMap<>();
        sharpe.put("sharpe", observation.sharpe());
        sharpe.put("benchSharpe", observation.benchSharpe());
        sharpe.put("sharpeDiff", observation.sharpeDiff());
        root.put("sharpeRaw", sharpe);
        root.put("staticEndMultiple", observation.staticEndMultiple());
        root.put("notStarted", basis.notStarted());
        Map<String, Object> engine = null;
        if (request != null) {
            engine = new LinkedHashMap<>();
            engine.put("horizonTurns", request.horizonTurns());
            engine.put("observedEndMultiple", request.observed().endMultiple());
            engine.put("observedSharpeDiff", request.observed().sharpeDiff());
            if (result != null) {
                engine.put("projectionVersion", result.projectionVersion());
                engine.put("engineVersion", result.engineVersion());
                engine.put("historyDigest", result.historyDigest());
                engine.put("n", result.n());
                engine.put("endMultiple", counts(result.endMultiple()));
                engine.put("sharpeDiff", counts(result.sharpeDiff()));
            }
        }
        root.put("engine", engine);
        root.put("noDistribution", noDistribution);
        return MAPPER.writeValueAsString(root);
    }

    private static Map<String, Object> counts(EnginePercentileResult.Counts counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("below", counts.below());
        out.put("equal", counts.equal());
        return out;
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
