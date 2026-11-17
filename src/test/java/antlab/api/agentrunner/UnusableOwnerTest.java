package antlab.api.agentrunner;

import antlab.support.IntegrationTest;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "antlab.agent-runner.owner-member-id=")
class UnusableOwnerTest extends UnusableRunnerConfigTests {
}
