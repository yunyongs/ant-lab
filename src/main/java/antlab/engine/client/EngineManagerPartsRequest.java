package antlab.engine.client;

import antlab.member.MemberKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request of {@code POST /v1/manager-parts}: one finished season as Java stored it (docs/api/engine.yaml
 * {@code ManagerPartsRequest}). Every number is a decimal string. The months, totals and returns are never printed.
 */
public record EngineManagerPartsRequest(
        @JsonProperty(required = true) String referenceVersion,
        @JsonProperty(required = true) LocalDate baseDate,
        @JsonProperty(required = true) List<LocalDate> months,
        @JsonProperty(required = true) Settings settings,
        @JsonProperty(required = true) Team team) {

    public static final String REFERENCE_VERSION = "ref69-v1";
    /** Path lengths the engine has a reference for ({@code PartsSettings.pathLength}). */
    public static final Set<Integer> PATH_LENGTHS = Set.of(12, 24, 60);
    static final Pattern WON = Pattern.compile("^[0-9]{1,15}(\\.0{1,4})?$");
    static final Pattern RATE = Pattern.compile("^0(\\.[0-9]{1,8})?$");
    static final Pattern RETURN = Pattern.compile("^-?[0-9]{1,6}(\\.[0-9]{1,30})?$");
    static final Pattern SHARPE = Pattern.compile("^-?[0-9]{1,6}\\.[0-9]{8}$");
    static final Pattern RISK_SCORE = Pattern.compile("^(100\\.00|[0-9]{1,2}\\.[0-9]{2})$");

    /** The season's own settings ({@code season} row). */
    public record Settings(
            @JsonProperty(required = true) int pathLength,
            @JsonProperty(required = true) String initialCash,
            @JsonProperty(required = true) String feeRateKr,
            @JsonProperty(required = true) String feeRateUs,
            @JsonProperty(required = true) String fxSpreadRate,
            @JsonProperty(required = true) String riskLimit,
            @JsonProperty(required = true) StartFormation startFormation,
            @JsonProperty(required = true) MemberKind memberKind) {

        public Settings {
            if (!PATH_LENGTHS.contains(pathLength)) {
                throw new IllegalArgumentException("pathLength must be one of 12, 24, 60");
            }
            EngineWire.match("initialCash", initialCash, WON);
            EngineWire.match("feeRateKr", feeRateKr, RATE);
            EngineWire.match("feeRateUs", feeRateUs, RATE);
            EngineWire.match("fxSpreadRate", fxSpreadRate, RATE);
            EngineWire.match("riskLimit", riskLimit, EngineWire.RISK_LIMIT);
            EngineWire.required(startFormation, "startFormation");
            EngineWire.required(memberKind, "memberKind");
        }
    }

    /** Start formation in whole percent; the goalkeeper holds at least 1 and the four add up to 100. */
    public record StartFormation(
            @JsonProperty(required = true) int goalkeeper,
            @JsonProperty(required = true) int defender,
            @JsonProperty(required = true) int midfielder,
            @JsonProperty(required = true) int forward) {

        public StartFormation {
            if (goalkeeper < 1 || goalkeeper > 100 || defender < 0 || defender > 100 || midfielder < 0 || midfielder > 100
                    || forward < 0 || forward > 100 || goalkeeper + defender + midfielder + forward != 100) {
                throw new IllegalArgumentException("a start formation is whole percent adding up to 100");
            }
        }
    }

    /**
     * The season's series: turn 1..T end values in won, monthly team and benchmark returns at the score scale, the stored
     * Sharpe values (8 places) and the stored risk item (2 places, null when the season has none).
     */
    public record Team(
            @JsonProperty(required = true) List<String> totals,
            @JsonProperty(required = true) List<String> returns,
            @JsonProperty(required = true) List<String> benchmarkReturns,
            @JsonProperty(required = true) String sharpe,
            @JsonProperty(required = true) String benchSharpe,
            @JsonProperty(required = true) String riskScore) {

        public Team {
            totals = EngineWire.list(totals, "totals");
            returns = EngineWire.list(returns, "returns");
            benchmarkReturns = EngineWire.list(benchmarkReturns, "benchmarkReturns");
            totals.forEach(v -> EngineWire.match("totals[]", v, WON));
            returns.forEach(v -> EngineWire.match("returns[]", v, RETURN));
            benchmarkReturns.forEach(v -> EngineWire.match("benchmarkReturns[]", v, RETURN));
            EngineWire.match("sharpe", sharpe, SHARPE);
            EngineWire.match("benchSharpe", benchSharpe, SHARPE);
            if (riskScore != null) {
                EngineWire.match("riskScore", riskScore, RISK_SCORE);
            }
        }
    }

    public EngineManagerPartsRequest {
        if (!REFERENCE_VERSION.equals(referenceVersion)) {
            throw new IllegalArgumentException("referenceVersion must be " + REFERENCE_VERSION);
        }
        EngineWire.required(baseDate, "baseDate");
        months = EngineWire.list(months, "months");
        EngineWire.required(settings, "settings");
        EngineWire.required(team, "team");
        int turns = settings.pathLength();
        if (months.size() != turns || team.totals().size() != turns || team.returns().size() != turns
                || team.benchmarkReturns().size() != turns) {
            throw new IllegalArgumentException("months, totals and returns must hold pathLength values each");
        }
        Set<LocalDate> seen = new HashSet<>();
        for (LocalDate month : months) {
            if (month.getDayOfMonth() != 1 || !seen.add(month)) {
                throw new IllegalArgumentException("months are distinct first days of a month");
            }
        }
    }

    @Override
    public String toString() {
        return "EngineManagerPartsRequest[" + settings.pathLength() + " turns]";
    }
}
