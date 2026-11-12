package antlab.api;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.exposure.FinishedSeasonOnly;
import antlab.member.CurrentMember;
import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Cases every {@code @FinishedSeasonOnly} handler must share (report and review): an unknown current member answers 503
 * as a problem body, and a FINISHED season that carries an abandoned turn still answers 200.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FinishedSeasonHandlerTest {

    /** The path ends every {@code @FinishedSeasonOnly} handler is expected to have today (a new one must be added on purpose). */
    private static final Set<String> EXPECTED_SUFFIXES = Set.of("/report", "/review");
    private static final String SEASON_PREFIX = "/api/v1/seasons/{seasonId}";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    CurrentMember currentMember;

    /** Path suffixes of the handlers annotated {@code @FinishedSeasonOnly}, read from the handler mapping (not typed here). */
    private List<String> finishedSeasonPaths() {
        List<String> suffixes = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (handler.hasMethodAnnotation(FinishedSeasonOnly.class)) {
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    assertThat(pattern).startsWith(SEASON_PREFIX);
                    suffixes.add(pattern.substring(SEASON_PREFIX.length()));
                }
            }
        });
        assertThat(suffixes).as("handlers annotated @FinishedSeasonOnly").isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SUFFIXES);
        return suffixes;
    }

    private MockHttpServletResponse get(long season, String suffix) {
        return mvc.get().uri("/api/v1/seasons/" + season + suffix).exchange().getResponse();
    }

    @Test
    void 회원을_알_수_없으면_두_핸들러_모두_503_문제_본문이다() throws Exception {
        doThrow(new DomainException(ErrorCode.MEMBER_UNAVAILABLE, "현재 회원을 알 수 없다(인증 전)"))
                .when(currentMember).existingId();
        for (String suffix : finishedSeasonPaths()) {
            MockHttpServletResponse r = get(1L, suffix);
            assertThat(r.getStatus()).as(suffix).isEqualTo(503);
            assertThat(r.getContentType()).as(suffix).startsWith("application/problem+json");
            assertThat(code(r)).as(suffix).isEqualTo("MEMBER_UNAVAILABLE");
        }
    }

    @Test
    void 포기_턴이_기록된_끝난_시즌은_두_핸들러_모두_200이다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 12; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        jdbc.update("UPDATE season SET abandoned_turn_no = 5 WHERE id = ? AND status = 'FINISHED'", season);
        assertThat(jdbc.queryForObject("SELECT abandoned_turn_no FROM season WHERE id = ?", Integer.class, season))
                .isEqualTo(5);
        for (String suffix : finishedSeasonPaths()) {
            assertThat(get(season, suffix).getStatus()).as(suffix).isEqualTo(200);
        }
    }
}
