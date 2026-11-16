package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.error.ErrorCode;
import io.micrometer.observation.Observation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpErrorCodeTagTest {

    private final HttpErrorCodeTag filter = new HttpErrorCodeTag();

    private String tagOf(Object attribute, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        if (attribute != null) {
            request.setAttribute(HttpErrorCodeTag.ATTRIBUTE, attribute);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, response);
        context.setName("http.server.requests");
        filter.map(context);
        var value = context.getLowCardinalityKeyValues().stream()
                .filter(kv -> kv.getKey().equals("error_code")).findFirst().orElseThrow();
        return value.getValue();
    }

    @Test
    void 오류_코드_이름은_그대로_태그가_된다() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(tagOf(code.name(), 409)).isEqualTo(code.name());
        }
    }

    @Test
    void 성공_응답은_none이다() {
        assertThat(tagOf(null, 200)).isEqualTo("none");
        assertThat(tagOf(null, 302)).isEqualTo("none");
    }

    @Test
    void 허용_목록_밖_값과_코드_없는_오류_응답은_other로_묶인다() {
        assertThat(tagOf("NOT_AN_ERROR_CODE", 400)).isEqualTo("other");
        assertThat(tagOf("not_found", 404)).isEqualTo("other");
        assertThat(tagOf("", 400)).isEqualTo("other");
        assertThat(tagOf(42, 400)).isEqualTo("other");
        assertThat(tagOf(null, 500)).isEqualTo("other");
    }

    @Test
    void 태그_값_집합은_오류_코드_열거형_더하기_none_other로_닫혀_있다() {
        Set<String> allowed = new HashSet<>(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        allowed.addAll(Set.of("none", "other"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(tagOf("RANDOM_" + i, 400 + i % 100));
        }
        assertThat(seen).containsExactly("other");
        assertThat(allowed).hasSize(ErrorCode.values().length + 2);
    }

    @Test
    void 다른_관측은_건드리지_않는다() {
        Observation.Context other = new Observation.Context();
        other.setName("something.else");
        filter.map(other);
        assertThat(other.getLowCardinalityKeyValues()).isEmpty();
    }
}
