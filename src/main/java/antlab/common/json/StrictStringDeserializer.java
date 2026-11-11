package antlab.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 문자열 토큰만 받는 역직렬화기 — JSON 숫자·불리언·배열·객체는 400(INVALID_ARGUMENT)이다. JSON null 은 필드 기본 처리(null)를 따른다.
 * Jackson 의 기본 문자열 역직렬화는 숫자·불리언 토큰을 문자열로 바꿔 받는다(예: {@code 7} → "7"). 문자열이 계약인 요청 필드에만
 * {@code @JsonDeserialize(using = StrictStringDeserializer.class)} 로 붙인다 — 매퍼 전역의 강제 변환 설정은 바꾸지 않는다
 * (서버가 읽는 외부 JSON 에도 같은 매퍼가 쓰일 수 있다). 거부한 값은 메시지에 싣지 않는다.
 */
public class StrictStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return p.getString();
        }
        return (String) ctxt.handleUnexpectedToken(String.class, p);
    }
}
