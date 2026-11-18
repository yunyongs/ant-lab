package antlab.season;

import antlab.asset.Position;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Position-only change request. All three fields are required so a null never reaches the service. */
@Schema(description = "Changes the declared position of a held asset without any trade\n"
        + "\n"
        + "거래 없이 보유 종목의 선언 포지션만 바꾼다")
public record ChangeLineupRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The turn the change applies to. 409 TURN_MISMATCH if it differs from the season's "
                        + "current turn. At turn 0 (team being built) a position is set by the buy order, so 409 / "
                        + "변경이 보는 턴. 시즌의 현재 턴과 다르면 409 TURN_MISMATCH. 0턴(팀 구성 중)의 포지션은 매수 주문으로 정하므로 409")
        @NotNull @Min(0)
        Integer expectedTurnNo,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Asset identifier (the same as assetId in the "
                + "pool response). The asset must be held / 종목 식별자(풀 응답의 assetId 와 같다). 보유 중이어야 한다")
        @NotNull @Min(1)
        Long assetId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The new declared position. If it equals the "
                + "current one nothing is written / 새 선언 포지션. 현재와 같으면 아무것도 쓰지 않는다")
        @NotNull @JsonDeserialize(using = StrictPositionDeserializer.class)
        Position declaredPosition) {

    /**
     * Accepts only a JSON string equal to a constant name. Jackson would also read a number as an enum ordinal, which
     * would make the contract depend on the declaration order of {@link Position}; the mapper setting stays global.
     */
    static final class StrictPositionDeserializer extends ValueDeserializer<Position> {
        @Override
        public Position deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String text = p.getString();
                for (Position position : Position.values()) {
                    if (position.name().equals(text)) {
                        return position;
                    }
                }
            }
            return (Position) ctxt.handleUnexpectedToken(Position.class, p);
        }
    }
}
