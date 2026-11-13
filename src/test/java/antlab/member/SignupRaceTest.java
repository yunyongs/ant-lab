package antlab.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.support.IntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** The pre-check misses (another request committed in between); the unique index must still answer 409, not 500. */
@IntegrationTest
class SignupRaceTest {

    @MockitoSpyBean
    MemberRepository members;

    @Autowired
    SignupService signups;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from member where email like '%@race.example.test'");
    }

    @Test
    void 사전_검사를_지나친_경합도_409_EMAIL_TAKEN() {
        jdbc.update("insert into member(email, name, password) values (?, ?, ?)",
                "race@race.example.test", "first", "{noop}x");
        doReturn(Optional.empty()).when(members).findByEmail(anyString());

        assertThatThrownBy(() -> signups.signup(new SignupRequest("RACE@race.example.test", "second", "correct-horse-battery")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EMAIL_TAKEN));
        assertThat(jdbc.queryForObject("select count(*) from member where lower(email) = 'race@race.example.test'", Long.class))
                .isEqualTo(1L);
    }
}
