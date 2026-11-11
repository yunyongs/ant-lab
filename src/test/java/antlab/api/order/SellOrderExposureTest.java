package antlab.api.order;

import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.fixTurn;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderResultDto;
import antlab.common.exposure.ExposedFrom;
import antlab.support.ExposureCatalog;
import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** 매도 응답의 키는 record 구성 요소 14개와 같고, 시드·엔진 판·연월·기록 시각·출발점이 본문과 로그에 없다. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SellOrderExposureTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 응답_키는_record_구성_요소_14개와_같고_금지_값이_본문과_로그에_없다(CapturedOutput output) throws Exception {
        long season = startSeason(mvc);
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, season);
        String engine = jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, season);
        long asset = assetId(jdbc, "000660");
        place(mvc, season, body(0, "BUY", asset, 3, "FORWARD_BREAKOUT"));
        fixTurn(jdbc, season, 1, Map.of("000660", "160000"));
        MockHttpServletResponse r = place(mvc, season, body(1, "SELL", asset, 2, null));
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode o = json(r);

        List<String> expected = new ArrayList<>();
        Arrays.stream(OrderResultDto.class.getRecordComponents()).forEach(c -> expected.add(c.getName()));
        assertThat(expected).hasSize(14).contains("realizedPnl");
        List<String> actual = new ArrayList<>();
        o.propertyNames().forEach(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).doesNotContainAnyElementsOf(BuyOrderExposureTest.FORBIDDEN_KEYS);

        String body = r.getContentAsString();
        assertThat(body).doesNotContain(seed).doesNotContain(engine)
                .doesNotContain("DIRECT").doesNotContain("MARKET").doesNotContain("FILLED");
        assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}");

        List<String> months = jdbc.queryForList("SELECT mb.month_start::text FROM season_path sp"
                + " JOIN month_block mb ON mb.id = sp.month_block_id WHERE sp.season_id = ? ORDER BY sp.turn_no",
                String.class, season);
        assertThat(months).isNotEmpty();
        months.forEach(m -> assertThat(output.getAll()).as("로그의 달").doesNotContain(m));
        assertThat(output.getAll()).doesNotContain(engine);
    }

    @Test
    void 실현_손익은_노출_표지_열에서_오고_표지가_노출이다() {
        ExposedFrom exposed = Arrays.stream(OrderResultDto.class.getRecordComponents())
                .filter(c -> c.getName().equals("realizedPnl")).findFirst().orElseThrow().getAnnotation(ExposedFrom.class);
        assertThat(exposed.value()).containsExactly("execution.realized_pnl");
        assertThat(ExposureCatalog.load(jdbc).tags().get("execution.realized_pnl")).isEqualTo(ExposureCatalog.EXPOSED);
    }
}
