package antlab.api.agentrunner;

import antlab.support.IntegrationTest;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "antlab.agent-runner.token=fake-token-exactly-thirty-one-c")
class UnusableShortTokenTest extends UnusableRunnerConfigTests {
}
