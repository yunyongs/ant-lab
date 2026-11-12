package antlab.api.config;

import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.deser.jdk.NumberDeserializers;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.type.LogicalType;

/**
 * JSON 형식 규칙. 금액·비율({@link BigDecimal})은 지수 없는 문자열로 보낸다.
 * JS number 는 부동소수라 금액을 숫자로 보내면 웹에서 정밀도가 깨진다(PLAN §5.2).
 * 수량·턴 번호 같은 long/int 는 숫자 그대로 둔다.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    /**
     * 지수 없이 펼친 자릿수 상한. 스키마의 가장 큰 NUMERIC(60,40)(60자리)보다 크게 잡은 일반 안전선이다.
     * 짧은 지수 입력(1E+100000000)이 수억 자리 문자열로 불어나는 것을 막는다. 업무별 자릿수는 DTO 의 검증이 정한다.
     */
    static final int MAX_PLAIN_DIGITS = 64;

    /**
     * toPlainString() 을 만들지 않고 펼친 자릿수를 계산한다.
     * scale 은 int 전 범위(±2^31)라 int 로 더하고 빼면 넘친다 — long 으로 계산한다.
     */
    static long plainDigits(BigDecimal v) {
        long precision = v.precision();
        long scale = v.scale();
        return scale >= 0 ? Math.max(precision, scale + 1) : precision - scale;
    }

    @Bean
    JsonMapperBuilderCustomizer bigDecimalAsPlainString() {
        SimpleModule module = new SimpleModule("antlab-money");
        module.addSerializer(BigDecimal.class, new PlainBigDecimalSerializer());
        module.addDeserializer(BigDecimal.class, new BoundedBigDecimalDeserializer());
        // 응답을 쓰다가 실패하면(예: 자릿수 상한 초과) 열린 배열·객체를 닫지 않는다.
        // 이미 보내기 시작한 큰 응답이 값이 빠진 정상 JSON 처럼 끝나지 않게 한다(상태 200 은 바꿀 수 없다).
        // Quoted numbers are rejected for integer logical types only; money (BigDecimal) stays string-in.
        return builder -> builder.addModule(module).disable(StreamWriteFeature.AUTO_CLOSE_CONTENT)
                .withCoercionConfig(LogicalType.Integer,
                        cfg -> cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }

    static class PlainBigDecimalSerializer extends StdSerializer<BigDecimal> {

        PlainBigDecimalSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            if (plainDigits(value) > MAX_PLAIN_DIGITS) {
                // 서버가 만든 값이 상한을 넘는 것은 버그다 — 500(INTERNAL_ERROR)으로 드러낸다.
                throw new IllegalStateException("BigDecimal 자릿수 상한 초과");
            }
            gen.writeString(value.toPlainString());
        }
    }

    /** 기본 BigDecimal 역직렬화(문자열·숫자 토큰, scale 유지) 뒤에 자릿수 상한만 더한다. 넘으면 400(INVALID_ARGUMENT). */
    static class BoundedBigDecimalDeserializer extends NumberDeserializers.BigDecimalDeserializer {

        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) {
            BigDecimal value = super.deserialize(p, ctxt);
            if (value != null && plainDigits(value) > MAX_PLAIN_DIGITS) {
                // 거부된 값은 메시지에 싣지 않는다.
                throw InvalidFormatException.from(p, "BigDecimal 자릿수 상한 초과", null, BigDecimal.class);
            }
            return value;
        }
    }
}
