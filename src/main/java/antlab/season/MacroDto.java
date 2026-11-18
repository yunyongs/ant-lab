package antlab.season;

import antlab.common.money.MoneyMath;
import antlab.history.AxisDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;

/** Macro environment of the month applied at a turn, exactly as stored. Transformed values only: no level, label or date. */
@Schema(description = "Macro environment of the month applied at this turn, as stored for that month: direction and "
        + "strength of four axes and four regime probabilities. Transformed values only: no indicator level and no "
        + "date. A part the engine could not evaluate is null, which means no data, not neutral.\n"
        + "\n"
        + "이 턴에 적용된 달의 거시 환경(그 달에 저장된 값): 네 축의 방향·강도와 국면 확률 네 개. 변환값만 싣고 지표의 수준과 날짜는 "
        + "싣지 않는다. 엔진이 평가하지 못한 부분은 null이며 중립이 아니라 자료 없음을 뜻한다")
public record MacroDto(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The four axes / 네 축")
        MacroAxesDto axes,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Regime probabilities; null when the engine did not evaluate the month / 국면 확률. 엔진이 그 달을 평가하지 않았으면 null")
        RegimeProbabilitiesDto probabilities) {

    /**
     * Maps the stored values without changing them: a value that does not fit its scale exactly is an error, and a
     * missing axis or probability set stays null.
     */
    public static MacroDto from(TurnMacroRow row) {
        MacroAxesDto axes = new MacroAxesDto(axis(row.rateDir(), row.rateStrength()),
                axis(row.growthDir(), row.growthStrength()), axis(row.fxDir(), row.fxStrength()),
                axis(row.sentimentDir(), row.sentimentStrength()));
        return new MacroDto(axes, probabilities(row));
    }

    private static MacroAxisDto axis(AxisDirection direction, BigDecimal strength) {
        if (direction == null && strength == null) {
            return null;
        }
        if (direction == null || strength == null) {
            throw new IllegalStateException("축의 방향과 강도는 함께 있거나 함께 없어야 한다");
        }
        return new MacroAxisDto(direction, MoneyMath.exactRate(strength));
    }

    private static RegimeProbabilitiesDto probabilities(TurnMacroRow row) {
        BigDecimal[] p = {row.probP1(), row.probP2(), row.probP3(), row.probP4()};
        int present = 0;
        for (BigDecimal value : p) {
            present += value == null ? 0 : 1;
        }
        if (present == 0) {
            return null;
        }
        if (present != 4) {
            throw new IllegalStateException("국면 확률은 네 개가 함께 있거나 함께 없어야 한다");
        }
        return new RegimeProbabilitiesDto(MoneyMath.exactProbability(p[0]), MoneyMath.exactProbability(p[1]),
                MoneyMath.exactProbability(p[2]), MoneyMath.exactProbability(p[3]));
    }
}
