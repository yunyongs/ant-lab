package antlab.support;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for tests that call the API port and the management port over real HTTP. The context shape is
 * the one of {@code ApiErrorSweepTest}, so the Spring context and its PostgreSQL container are shared through the cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestcontainersConfig.class, TestTokens.class, TestMembers.class})
public abstract class RealPortTest {

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    @Autowired
    protected TestTokens tokens;

    @Autowired
    protected TestMembers members;

    /** The API port with the default test member's user token on every call. */
    protected E2eHttp api() {
        return new E2eHttp(port, Map.of("Authorization", "Bearer " + tokens.user(members.defaultId())));
    }

    /** The API port without any token. */
    protected E2eHttp anonymousApi() {
        return new E2eHttp(port);
    }

    protected E2eHttp management() {
        return new E2eHttp(managementPort);
    }
}
