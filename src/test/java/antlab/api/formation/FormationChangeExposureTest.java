package antlab.api.formation;

import static antlab.api.formation.FormationApiSupport.A;
import static antlab.api.formation.FormationApiSupport.body;
import static antlab.api.formation.FormationApiSupport.change;
import static antlab.api.formation.FormationApiSupport.json;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.ProblemBodyAssert;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** 전술 변경 응답·오류의 노출 — 변경 번호·시즌 번호·기록 시각·비용·경로·날짜가 없고, 거부한 입력 원문을 되돌려 주지 않는다. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationChangeExposureTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 응답_키는_계약과_같고_내부_값과_날짜가_없다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        MockHttpServletResponse r = change(mvc, season, body(1, A, "\"한 줄\""));

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);
        assertThat(keys(o)).containsExactlyInAnyOrder("turnNo", "before", "after", "reason");
        assertThat(keys(o.get("before"))).containsExactlyInAnyOrder("goalkeeper", "defender", "midfielder", "forward");
        assertThat(keys(o.get("after"))).containsExactlyInAnyOrder("goalkeeper", "defender", "midfielder", "forward");
        String text = r.getContentAsString();
        assertThat(text).doesNotContain("createdAt", "created_at", "seasonId", "formationChangeId", "fee", "seed", "path");
        assertThat(text).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void 거부된_입력_원문은_오류_응답에_없다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        String secret = "비밀사유문장" + "가".repeat(210);
        MockHttpServletResponse r = change(mvc, season, body(1, A, "\"" + secret + "\""));
        assertThat(r.getStatus()).isEqualTo(400);
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), "비밀사유문장");
        ProblemBodyAssert.assertInstanceShape(r.getContentAsString(), "formation-changes");
        MockHttpServletResponse sum = change(mvc, season, body(1, new int[] {10, 40, 30, 19}, null));
        assertThat(sum.getStatus()).isEqualTo(400);
        // 거부한 합(99)·비중 값이 본문에 되돌아오지 않는다 — instance 의 시즌 번호와 겹치지 않게 instance 를 뺀 사본 전체를 본다
        ProblemBodyAssert.assertAbsent(sum.getContentAsString(), "\"19\"", ":19", "99");
        ProblemBodyAssert.assertInstanceShape(sum.getContentAsString(), "formation-changes");
    }

    private static Set<String> keys(JsonNode n) {
        return new HashSet<>(n.propertyNames());
    }
}
