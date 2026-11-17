package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.api.error.ErrorHttpMapping;
import antlab.auth.JwtCurrentMember;
import antlab.common.error.DomainException;
import antlab.member.MemberRepository;
import antlab.scoring.SeasonAnalysisService;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.ProblemBodyAssert;
import antlab.support.SeasonAccountFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Failure paths of the season analysis: a season that is not finished (turn 0, mid-season, abandoning, halted) answers 409
 * SEASON_NOT_FINISHED, a missing or foreign season is the same 404, a non-numeric id is 400, an unknown member is 401, and
 * contradicting stored rows end in a 500 with a fixed sentence that names no cause.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAnalysisRejectTest {

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
    @Autowired
    SeasonAnalysisService service;

    private void assertNotFinished(long season) throws Exception {
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(code(r)).isEqualTo("SEASON_NOT_FINISHED");
        assertThat(r.getContentAsString()).contains("끝난 시즌만 분석을 볼 수 있다");
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), "baselines", "discipline", "initialCash");
        ProblemBodyAssert.assertInstanceShape(r.getContentAsString(), "report/analysis");
    }

    @Test
    void 영_턴_시즌은_409다() throws Exception {
        assertNotFinished(ReportApiSupport.season(mvc, jdbc, 0));
    }

    @Test
    void 진행_중_시즌은_409다() throws Exception {
        assertNotFinished(ReportApiSupport.season(mvc, jdbc, 3));
    }

    @Test
    void 포기_중_시즌은_409다() throws Exception {
        long season = ReportApiSupport.season(mvc, jdbc, 3);
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = current_turn_no WHERE id = ?", season);
        assertNotFinished(season);
    }

    @Test
    void 정지된_시즌도_409다() throws Exception {
        long season = ReportApiSupport.season(mvc, jdbc, 3);
        jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", season);
        assertNotFinished(season);
    }

    @Test
    void 없는_시즌과_남의_시즌은_같은_404다() throws Exception {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long others = fixture.newSeason(fixture.newMember()).getId();
        try {
            MockHttpServletResponse missing = SeasonAnalysisApiSupport.getAnalysis(mvc, 999_999_999L);
            MockHttpServletResponse foreign = SeasonAnalysisApiSupport.getAnalysis(mvc, others);
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
    void 숫자가_아닌_시즌_번호는_400이다() {
        assertThat(SeasonAnalysisApiSupport.getAnalysis(mvc, "abc").getStatus()).isEqualTo(400);
    }

    @Test
    void 보안_문맥에_토큰이_없으면_401_UNAUTHENTICATED이다() {
        SeasonAnalysisService unauthenticated = new SeasonAnalysisService(new JwtCurrentMember(null), null, null, null, null,
                null, null, null);
        assertThatThrownBy(() -> unauthenticated.get(1L)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(ErrorHttpMapping.status(e.getCode())).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void 경로가_끊긴_시즌은_500_고정_문장이고_원인을_싣지_않는다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("DELETE FROM season_path WHERE season_id = ? AND turn_no = 12", season);
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), "원천", "모순", "경로", "season_path");
        assertThatThrownBy(() -> service.get(season)).isInstanceOf(IllegalStateException.class)
                .hasMessage("성적표 원천이 모순이다: 경로가 처음부터 보고 턴까지 이어지지 않는다");
    }

    @Test
    void 시장_대용_종목의_가격이_사라지면_500이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND turn_no = 7 AND asset_id = "
                + "(SELECT id FROM asset WHERE ticker = 'SPY')", season);
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertThatThrownBy(() -> service.get(season)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 턴_스냅샷이_빠진_시즌은_500이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("DELETE FROM turn WHERE season_id = ? AND turn_no = 6", season);
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThatThrownBy(() -> service.get(season)).isInstanceOf(IllegalStateException.class)
                .hasMessage("분석 원천이 모순이다: 턴 스냅샷이 첫 턴부터 보고 턴까지 이어지지 않는다");
    }

    @Test
    void 마지막_턴에_걸린_주문은_판정_범위_밖이라_금액에_들어가지_않는다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        ReportApiSupport.fill(jdbc, season, antlab.api.turn.TurnApiSupport.assetId(jdbc, "BRK.B"), 12, "SELL", 1);
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(antlab.api.turn.TurnApiSupport.json(r).get("discipline").get("sellNotional").asString()).isEqualTo("0");
    }
}
