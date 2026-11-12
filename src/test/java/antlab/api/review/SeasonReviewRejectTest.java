package antlab.api.review;

import static antlab.api.turn.TurnApiSupport.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.api.error.ErrorHttpMapping;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.MemberRepository;
import antlab.member.UnavailableCurrentMember;
import antlab.season.SeasonRepository;
import antlab.season.SeasonReviewService;
import antlab.support.IntegrationTest;
import antlab.support.ProblemBodyAssert;
import antlab.support.SeasonAccountFixture;
import antlab.support.SqlRecorder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Review rejections: an unfinished season answers 409 without any month or event text and without the reveal queries
 * reaching the DB; unknown and foreign seasons answer the same 404; a non-numeric id answers 400.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReviewRejectTest {

    private static final List<String> REVEAL_COLUMNS = List.of("month_start", "event_summary", "title", "hindsight", "source");

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

    private void assertNotFinished(long season) throws Exception {
        SqlRecorder.start();
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        List<String> sqls = SqlRecorder.stop();
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(code(r)).isEqualTo("SEASON_NOT_FINISHED");
        assertThat(r.getContentAsString()).contains("끝난 시즌만 복기를 볼 수 있다");
        String body = ProblemBodyAssert.withoutInstance(r.getContentAsString());
        assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}");
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), ReviewApiSupport.FIXTURE_TEXTS.toArray(String[]::new));
        ProblemBodyAssert.assertInstanceShape(r.getContentAsString(), "review");
        Map<String, Object> repro = jdbc.queryForMap("SELECT seed::text AS seed, engine_version FROM season WHERE id = ?", season);
        assertThat(body).doesNotContain((String) repro.get("seed")).doesNotContain((String) repro.get("engine_version"));
        assertThat(sqls).as("recorded statements").isNotEmpty();
        for (String sql : sqls) {
            String s = sql.toLowerCase(Locale.ROOT);
            REVEAL_COLUMNS.forEach(c -> assertThat(s).as(sql).doesNotContain(c));
        }
    }

    @Test
    void 진행_중_시즌은_409이고_본문에_연월과_사건이_없다() throws Exception {
        assertNotFinished(ReviewApiSupport.seasonWithFixturePath(mvc, jdbc, 12));
    }

    @Test
    void 포기_중_시즌은_409이고_본문에_연월과_사건이_없다() throws Exception {
        long season = ReviewApiSupport.seasonWithFixturePath(mvc, jdbc, 12);
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = current_turn_no WHERE id = ?", season);
        assertNotFinished(season);
    }

    @Test
    void 없는_시즌과_남의_끝난_시즌은_같은_404다() throws Exception {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long others = fixture.newSeason(fixture.newMember()).getId();
        jdbc.update("UPDATE season SET current_turn_no = path_length, status = 'FINISHED' WHERE id = ?", others);
        try {
            MockHttpServletResponse missing = ReviewApiSupport.getReview(mvc, 999_999_999L);
            MockHttpServletResponse foreign = ReviewApiSupport.getReview(mvc, others);
            for (MockHttpServletResponse r : new MockHttpServletResponse[] {missing, foreign}) {
                assertThat(r.getStatus()).isEqualTo(404);
                assertThat(r.getContentType()).startsWith("application/problem+json");
                assertThat(code(r)).isEqualTo("NOT_FOUND");
            }
            assertThat(ProblemBodyAssert.withoutInstance(missing.getContentAsString()))
                    .isEqualTo(ProblemBodyAssert.withoutInstance(foreign.getContentAsString()));
        } finally {
            fixture.cleanUp();
        }
    }

    @Test
    void 숫자가_아닌_시즌_번호는_400이다() throws Exception {
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, "abc");
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void 회원을_알_수_없으면_503이다() {
        SeasonReviewService service = new SeasonReviewService(new UnavailableCurrentMember(), null, null, null);
        assertThatThrownBy(() -> service.get(1L)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(ErrorHttpMapping.status(e.getCode())).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(ErrorHttpMapping.status(ErrorCode.MEMBER_UNAVAILABLE)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void 저장된_경로가_이어지지_않으면_500_고정_문장이고_원인을_싣지_않는다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        jdbc.update("DELETE FROM season_path WHERE season_id = ? AND turn_no = 12", season);
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), "복기 원천", "모순", "경로", "season_path");
    }
}
