package antlab.support;

import antlab.member.CurrentMember;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Base for tests that call the API port and the management port over real HTTP. The context shape (including the spy bean) is
 * the one of {@code ApiErrorSweepTest}, so the Spring context and its PostgreSQL container are shared through the cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
public abstract class RealPortTest {

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    // Same type and name as ApiErrorSweepTest#currentMember: both are part of the context cache key.
    @MockitoSpyBean
    CurrentMember currentMember;

    protected E2eHttp api() {
        return new E2eHttp(port);
    }

    protected E2eHttp management() {
        return new E2eHttp(managementPort);
    }
}
