package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.api.error.ErrorHttpMapping;
import antlab.auth.JwtCurrentMember;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.MemberRepository;
import antlab.scoring.SeasonReportService;
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
 * 성적표 음성 경로: 끝나지 않은 시즌(0턴·중간·포기 중)은 409 SEASON_NOT_FINISHED, 없는 시즌·남의 시즌은 404 같은 본문, 숫자가 아닌 id 는 400,
 * 회원 불명은 401, 저장 상태가 모순이면 500 고정 문장(원인·금액 비노출).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReportRejectTest {

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
    SeasonReportService reportService;

    /** HTTP 500 에 더해, 서비스가 던진 고정 문장이 기대한 원인 분기인지 직접 확인한다(본문에는 원인이 실리지 않으므로). */
    private void assertInconsistent(long season, String message) {
        assertThatThrownBy(() -> reportService.get(season)).isInstanceOf(IllegalStateException.class).hasMessage(message);
    }

    private void assertNotFinished(long season) throws Exception {
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(code(r)).isEqualTo("SEASON_NOT_FINISHED");
        assertThat(r.getContentAsString()).contains("끝난 시즌만 성적표를 볼 수 있다");
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), "initialCash", "finalValue");
        ProblemBodyAssert.assertInstanceShape(r.getContentAsString(), "report");
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
    void 없는_시즌과_남의_시즌은_같은_404다() throws Exception {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        long others = fixture.newSeason(fixture.newMember()).getId();
        try {
            MockHttpServletResponse missing = ReportApiSupport.getReport(mvc, 999_999_999L);
            MockHttpServletResponse foreign = ReportApiSupport.getReport(mvc, others);
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
        assertThat(ReportApiSupport.getReport(mvc, "abc").getStatus()).isEqualTo(400);
    }

    @Test
    void 보안_문맥에_토큰이_없으면_401_UNAUTHENTICATED이다() {
        SeasonReportService service = new SeasonReportService(new JwtCurrentMember(null), null, null, null, null, null,
                null, null, null);
        assertThatThrownBy(() -> service.get(1L)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(ErrorHttpMapping.status(e.getCode())).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void 저장_상태가_모순이면_500_고정_문장이고_원인을_싣지_않는다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        // 경로 한 행이 사라진 시즌 — 정합 검사가 멈춰야 한다
        jdbc.update("DELETE FROM season_path WHERE season_id = ? AND turn_no = 12", season);
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), "성적표 원천", "모순", "경로", "season_path");
        assertInconsistent(season, "성적표 원천이 모순이다: 경로가 처음부터 보고 턴까지 이어지지 않는다");
    }

    @Test
    void 대용_종목의_가격이_사라지면_500이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND turn_no = 7 AND asset_id = "
                + "(SELECT id FROM asset WHERE ticker = 'SPY')", season);
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertInconsistent(season, "성적표 원천이 모순이다: 풀 종목의 턴 가격이 비어 있다");
    }

    @Test
    void 풀_종목의_영_턴_가격이_사라지면_500이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        // 비대용 풀 종목 하나의 0턴 가격이 없다 — 26 은 REPORT_INCONSISTENT 로 멈추므로 Java 도 풀을 줄여 200 을 내면 안 된다
        int deleted = jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND turn_no = 0 AND asset_id = "
                + "(SELECT id FROM asset WHERE ticker = 'BRK.B')", season);
        assertThat(deleted).isEqualTo(1);
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertInconsistent(season, "성적표 원천이 모순이다: 시즌 풀과 0턴 가격 종목이 다르다");
    }

    @Test
    void 마지막_턴_뒤_주문이_끼어_있으면_500이다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        jdbc.update("UPDATE orders SET turn_no = 13 WHERE id = (SELECT min(id) FROM orders WHERE season_id = ?)", season);
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertInconsistent(season, "성적표 원천이 모순이다: 보고 턴 뒤의 주문이 있다");
    }

    @Test
    void 진행_중_시즌은_원천이_모순이어도_409다() throws Exception {
        long season = ReportApiSupport.season(mvc, jdbc, 3);
        jdbc.update("DELETE FROM season_path WHERE season_id = ? AND turn_no = 2", season);
        assertNotFinished(season);
    }
}
