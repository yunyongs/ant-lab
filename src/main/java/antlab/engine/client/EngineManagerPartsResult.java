package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.regex.Pattern;

/**
 * Answer of {@code POST /v1/manager-parts} (docs/api/engine.yaml {@code ManagerPartsResult}): no month, asset or won. The
 * numbers stay decimal strings in their wire form (components at 12 places, percentiles and parts at 8), so Java stores
 * them as sent and rounds nothing; a value outside its pattern is contract drift and refused as a bad answer.
 */
public record EngineManagerPartsResult(
        @JsonProperty(required = true) String referenceVersion,
        @JsonProperty(required = true) String referenceKey,
        @JsonProperty(required = true) int referenceSize,
        @JsonProperty(required = true) String botVersion,
        @JsonProperty(required = true) String engineVersion,
        @JsonProperty(required = true) Components components,
        @JsonProperty(required = true) Percentiles percentiles,
        @JsonProperty(required = true) Parts parts) {

    static final Pattern BOT_VERSION = Pattern.compile("^[a-z]+/[0-9]{1,4}$");
    static final Pattern COMPONENT = Pattern.compile("^-?[0-9]{1,8}\\.[0-9]{12}$");
    static final Pattern PERCENTILE = Pattern.compile("^(100\\.0{8}|[0-9]{1,2}\\.[0-9]{8})$");

    /** The season's components (M3-03 definitions), each with its benchmark value and the difference. */
    public record Components(
            @JsonProperty(required = true) String cagr,
            @JsonProperty(required = true) String benchCagr,
            @JsonProperty(required = true) String excessCagr,
            @JsonProperty(required = true) String sharpeDiff,
            @JsonProperty(required = true) String sortino,
            @JsonProperty(required = true) String benchSortino,
            @JsonProperty(required = true) String sortinoDiff,
            @JsonProperty(required = true) String calmar,
            @JsonProperty(required = true) String benchCalmar,
            @JsonProperty(required = true) String calmarDiff,
            @JsonProperty(required = true) String mdd,
            @JsonProperty(required = true) String benchMdd) {

        public Components {
            for (String value : new String[] { cagr, benchCagr, excessCagr, sharpeDiff, sortino, benchSortino, sortinoDiff,
                    calmar, benchCalmar, calmarDiff, mdd, benchMdd }) {
                EngineWire.match("component", value, COMPONENT);
            }
        }
    }

    /** Mid-rank percentiles against the reference; {@code riskItem} is null when the season sent no risk item. */
    public record Percentiles(
            @JsonProperty(required = true) String excessCagr,
            @JsonProperty(required = true) String sharpeDiff,
            @JsonProperty(required = true) String sortinoDiff,
            @JsonProperty(required = true) String calmarDiff,
            @JsonProperty(required = true) String riskItem,
            @JsonProperty(required = true) String mdd) {

        public Percentiles {
            for (String value : new String[] { excessCagr, sharpeDiff, sortinoDiff, calmarDiff, mdd }) {
                EngineWire.match("percentile", value, PERCENTILE);
            }
            if (riskItem != null) {
                EngineWire.match("riskItem", riskItem, PERCENTILE);
            }
        }
    }

    /** The three percentile parts of S; {@code return} is a Java keyword, so the component is {@code ret}. */
    public record Parts(
            @JsonProperty(value = "return", required = true) String ret,
            @JsonProperty(required = true) String riskAdjusted,
            @JsonProperty(required = true) String risk) {

        public Parts {
            EngineWire.match("return", ret, PERCENTILE);
            EngineWire.match("riskAdjusted", riskAdjusted, PERCENTILE);
            EngineWire.match("risk", risk, PERCENTILE);
        }
    }

    public EngineManagerPartsResult {
        if (!EngineManagerPartsRequest.REFERENCE_VERSION.equals(referenceVersion)) {
            throw new IllegalArgumentException("referenceVersion must be " + EngineManagerPartsRequest.REFERENCE_VERSION);
        }
        EngineWire.match("referenceKey", referenceKey, EngineWire.DIGEST);
        if (referenceSize < 1 || referenceSize > 200) {
            throw new IllegalArgumentException("referenceSize must be 1..200");
        }
        EngineWire.match("botVersion", botVersion, BOT_VERSION);
        EngineWire.required(engineVersion, "engineVersion");
        EngineWire.required(components, "components");
        EngineWire.required(percentiles, "percentiles");
        EngineWire.required(parts, "parts");
    }
}
