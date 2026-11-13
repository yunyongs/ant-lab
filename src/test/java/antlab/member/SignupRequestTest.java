package antlab.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignupRequestTest {

    @Test
    void toString에_비밀번호가_없다() {
        String text = new SignupRequest("a@example.test", "n", "correct-horse-battery").toString();
        assertThat(text).doesNotContain("correct-horse-battery").contains("password=***");
    }
}
