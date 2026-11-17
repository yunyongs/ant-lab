package antlab.api.turn;

import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.TurnApiSupport.code;
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

/** Another member's season answers the ranking path with 404 like a missing one. The test transaction rolls back. */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class RankingNotFoundTest {

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

    @Test
    void another_members_season_is_a_404_for_every_turn_number() throws Exception {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long others = fixture.newSeason(fixture.newMember()).getId();
        for (int turn : new int[] {1, 2, 99}) {
            MockHttpServletResponse r = getRanking(mvc, others, turn);
            assertThat(r.getStatus()).as("turn " + turn).isEqualTo(404);
            assertThat(r.getContentType()).startsWith("application/problem+json");
            assertThat(code(r)).isEqualTo("NOT_FOUND");
        }
    }
}
