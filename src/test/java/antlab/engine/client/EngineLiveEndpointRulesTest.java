package antlab.engine.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The live parity run may only talk to an isolated loopback stack, never to the owner's own ports. Not skipped. */
class EngineLiveEndpointRulesTest {

    @Test
    void 격리_포트의_루프백_주소는_통과한다() {
        assertThatCode(() -> EngineLiveParityTest.requireIsolated("url", "http://127.0.0.1:56111")).doesNotThrowAnyException();
        assertThatCode(() -> EngineLiveParityTest.requireIsolated("url", "http://localhost:56100/")).doesNotThrowAnyException();
        assertThatCode(() -> EngineLiveParityTest.requireIsolated("db", "postgresql://127.0.0.1:56110/antlab_api?user=u"))
                .doesNotThrowAnyException();
    }

    @Test
    void 소유자_스택의_포트와_56100_미만은_거부한다() {
        for (String url : new String[] {"http://127.0.0.1:8000", "http://127.0.0.1:8080", "http://127.0.0.1:56099",
                "postgresql://127.0.0.1:5432/antlab_api"}) {
            assertThatThrownBy(() -> EngineLiveParityTest.requireIsolated("url", url)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("isolated port");
        }
    }

    @Test
    void 포트가_없거나_루프백이_아니면_거부한다() {
        assertThatThrownBy(() -> EngineLiveParityTest.requireIsolated("url", "http://127.0.0.1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("isolated port");
        assertThatThrownBy(() -> EngineLiveParityTest.requireIsolated("url", "http://example.test:56111"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
        assertThatThrownBy(() -> EngineLiveParityTest.requireIsolated("url", "http://10.0.0.5:56111"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
    }
}
