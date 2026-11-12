package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.SeasonReportDto;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** 끝난 시즌의 성적표 200 응답: 키 37개와 순서, 형식(금액은 소수점 없는 문자열, 비율은 소수 8자리), null 키 존재, 날짜 단서 없음. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReportApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 끝난_시즌은_200과_서른일곱_키를_레코드_순서대로_준다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentType()).startsWith("application/json");
        JsonNode o = json(r);
        List<String> expected = Arrays.stream(SeasonReportDto.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(expected).hasSize(37);
        List<String> actual = new ArrayList<>();
        o.propertyNames().forEach(actual::add);
        assertThat(actual).as("키와 순서").containsExactlyElementsOf(expected);

        assertThat(o.get("seasonStatus").asString()).isEqualTo("FINISHED");
        assertThat(o.get("isFinal").asBoolean()).isTrue();
        assertThat(o.get("reportTurns").asInt()).isEqualTo(12);
        assertThat(o.get("pathLength").asInt()).isEqualTo(12);
        for (String name : ReportApiSupport.MONEY) {
            assertThat(o.get(name).isString()).as(name).isTrue();
            assertThat(o.get(name).asString()).as(name).matches(ReportApiSupport.MONEY_FORMAT);
        }
        for (String name : ReportApiSupport.RATIO) {
            JsonNode v = o.get(name);
            if (!v.isNull()) {
                assertThat(v.asString()).as(name).matches(ReportApiSupport.RATIO_FORMAT);
            }
        }
        // 값이 없는 칸도 키는 있다(생략하지 않는다)
        for (String name : expected) {
            assertThat(o.has(name)).as(name).isTrue();
        }
        // 대사 건수는 정수: 12번 전환했으니 17행 묶음 12개, 수동 대사·불일치 없음
        assertThat(o.get("reconTurnBundles").asInt()).isEqualTo(12);
        assertThat(o.get("reconManualBundles").asInt()).isZero();
        assertThat(o.get("reconMismatchRows").asInt()).isZero();
        assertThat(o.get("reconOutOfRangeRows").asInt()).isZero();
        // 0턴에 팀을 샀으니 0턴 수수료가 있고, 1턴 이후 매매가 없으니 대금은 0
        assertThat(new java.math.BigDecimal(o.get("teamFee").asString()).signum()).isPositive();
        assertThat(o.get("totalFee").asString()).isEqualTo(o.get("teamFee").asString());
        assertThat(o.get("tradeNotional").asString()).isEqualTo("0");
        assertThat(o.get("nudgeShare").isNull()).isTrue();
        assertThat(o.get("formationChanges").asInt()).isZero();
    }

    @Test
    void 응답에_날짜_꼴_문자열과_날짜_단서_키가_없다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        String body = ReportApiSupport.getReport(mvc, season).getContentAsString();
        assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}");
        JsonNode o = json(ReportApiSupport.getReport(mvc, season));
        Set<String> forbidden = Set.of("seasonId", "id", "monthBlockId", "month", "date", "baseDate", "seed", "engineVersion",
                "dataSnapshotDate", "changeId", "players", "turns");
        o.propertyNames().forEach(k -> assertThat(forbidden).doesNotContain(k));
    }
}
