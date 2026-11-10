package antlab.api.turn;

import static antlab.api.turn.TurnViewSupport.getHoldings;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.CashAccountRepository;
import antlab.member.MemberRepository;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

/** 없는 시즌과 다른 회원의 시즌은 두 GET 모두 404 NOT_FOUND 다(없는 것과 같게). 시험 트랜잭션이 롤백한다. */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class TurnViewNotFoundTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    private void assertNotFound(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(TurnApiSupport.code(r)).isEqualTo("NOT_FOUND");
    }

    @Test
    void 없는_시즌은_404다() throws Exception {
        assertNotFound(getTurn(mvc, 999_999_999L, 1));
        assertNotFound(getHoldings(mvc, 999_999_999L));
    }

    @Test
    void 다른_회원의_시즌은_404다() throws Exception {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long others = fixture.newSeason(fixture.newMember()).getId();
        assertNotFound(getTurn(mvc, others, 1));
        assertNotFound(getHoldings(mvc, others));
    }

    @Test
    void 숫자가_아닌_시즌_번호는_400이다() {
        assertThat(mvc.get().uri("/api/v1/seasons/abc/holdings").exchange().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.get().uri("/api/v1/seasons/abc/turns/1").exchange().getResponse().getStatus()).isEqualTo(400);
    }
}
