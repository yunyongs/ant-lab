package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The contract hook really looks at problem responses (it is not a silent no-op). */
@IntegrationTest
class ContractErrorCheckTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void 문제_응답마다_계약_대조가_실행된다() {
        int before = ContractErrorCheck.checked();
        assertThat(mvc.get().uri("/api/v1/seasons/987654321").exchange().getResponse().getStatus()).isEqualTo(404);
        assertThat(ContractErrorCheck.checked()).isGreaterThan(before);
    }
}
