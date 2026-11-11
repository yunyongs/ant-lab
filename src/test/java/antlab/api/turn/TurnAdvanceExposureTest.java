package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.TurnResultDto;
import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 턴 전환 응답의 키는 record 구성 요소와 같고, 시드·엔진 판·연월·월 블록·대사 시도 번호가 본문(성공·오류)과 로그에 없다. */
@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceExposureTest {

    static final Set<String> FORBIDDEN_KEYS = Set.of("seed", "engineVersion", "dataSnapshotDate", "baseDate",
            "monthBlockId", "monthBlock", "monthStart", "memberId", "version", "createdAt", "updatedAt", "attemptNo",
            "attempt", "checkCode", "mismatchCount", "reconciliation", "results", "path");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private long startSeason() throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        return JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
    }

    @Test
    void 응답_키는_record_구성_요소와_같고_금지_값이_본문과_로그에_없다(CapturedOutput output) throws Exception {
        long season = startSeason();
        buyTeam(mvc, jdbc, season);
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, season);
        String engine = jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, season);
        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);

        List<String> expected = new ArrayList<>();
        Arrays.stream(TurnResultDto.class.getRecordComponents()).forEach(c -> expected.add(c.getName()));
        List<String> actual = new ArrayList<>();
        o.propertyNames().forEach(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        String body = r.getContentAsString();
        assertThat(body).doesNotContain(seed).doesNotContain(engine).doesNotContainPattern("\\d{4}-\\d{2}");

        // 오류 응답에도 같다
        MockHttpServletResponse error = advance(mvc, season, 0);
        assertThat(error.getStatus()).isEqualTo(409);
        String errorBody = error.getContentAsString();
        assertThat(errorBody).doesNotContain(seed).doesNotContain(engine).doesNotContainPattern("\\d{4}-\\d{2}");
        json(error).propertyNames().forEach(k -> assertThat(FORBIDDEN_KEYS).doesNotContain(k));

        List<String> months = jdbc.queryForList("SELECT mb.month_start::text FROM season_path sp"
                + " JOIN month_block mb ON mb.id = sp.month_block_id WHERE sp.season_id = ? ORDER BY sp.turn_no",
                String.class, season);
        assertThat(months).isNotEmpty();
        months.forEach(m -> assertThat(output.getAll()).as("로그의 달").doesNotContain(m));
        assertThat(output.getAll()).doesNotContain(engine);
    }
}
