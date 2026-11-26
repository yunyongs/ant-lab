package antlab.api.turn;

import static antlab.api.turn.RankingSupport.playedSeason;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.ratio;
import static antlab.api.turn.TurnViewSupport.won;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.TurnSummaryDto;
import antlab.season.TurnSummaryListDto;
import antlab.support.ExposureCatalog;
import antlab.support.ExposureRules;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** Value line: turns 0..current only, benchmark values match contribution, and no date or month field exists. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnSummaryApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private MockHttpServletResponse get(long season) {
        return mvc.get().uri("/api/v1/seasons/" + season + "/turns").exchange().getResponse();
    }

    @Test
    void a_new_season_has_only_turn_zero_with_the_initial_capital() throws Exception {
        long season = startSeason(mvc);
        MockHttpServletResponse r = get(season);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode items = json(r).get("items");
        BigDecimal cash = won(jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("turnNo").asInt()).isZero();
        assertThat(items.get(0).get("totalValue").asString()).isEqualTo(cash.toPlainString());
        assertThat(items.get(0).get("benchmarkValue").asString()).isEqualTo(cash.toPlainString());
        assertThat(items.get(0).get("drawdown").asString()).isEqualTo("0.00000000");
        assertThat(items.get(0).get("periodReturn").asString()).isEqualTo("0.00000000");
    }

    @Test
    void the_rows_are_turns_zero_to_current_and_equal_the_stored_values() throws Exception {
        long season = playedSeason(mvc, jdbc, 5);
        JsonNode items = json(get(season)).get("items");
        assertThat(items).hasSize(6);
        BigDecimal previous = won(jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season));
        for (int turn = 1; turn <= 5; turn++) {
            var row = jdbc.queryForMap("SELECT total_value, drawdown FROM turn WHERE season_id = ? AND turn_no = ?", season, turn);
            BigDecimal total = won(row.get("total_value"));
            JsonNode o = items.get(turn);
            assertThat(o.get("turnNo").asInt()).isEqualTo(turn);
            assertThat(o.get("totalValue").asString()).isEqualTo(total.toPlainString());
            assertThat(o.get("drawdown").asString()).isEqualTo(((BigDecimal) row.get("drawdown")).toPlainString());
            assertThat(o.get("periodReturn").asString()).isEqualTo(ratio(total.subtract(previous), previous).toPlainString());
            JsonNode contribution = json(mvc.get().uri("/api/v1/seasons/" + season + "/turns/" + turn + "/contribution")
                    .exchange().getResponse());
            assertThat(o.get("benchmarkValue").asString())
                    .isEqualTo(contribution.get("benchmark").get("value").asString());
            previous = total;
        }
    }

    @Test
    void the_finished_season_last_summary_value_equals_the_report_baseline() throws Exception {
        long season = playedSeason(mvc, jdbc, 12);
        JsonNode report = json(mvc.get().uri("/api/v1/seasons/" + season + "/report").exchange().getResponse());
        JsonNode items = json(get(season)).get("items");

        assertThat(items.get(items.size() - 1).get("benchmarkValue").asString())
                .isEqualTo(report.get("baselineFinalValue").asString());
    }

    @Test
    void a_future_turn_is_never_listed_even_when_its_snapshot_exists_nowhere_yet() throws Exception {
        long season = playedSeason(mvc, jdbc, 3);
        int current = jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season);
        assertThat(current).isEqualTo(3);
        jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) "
                + "SELECT ?, g.turn_no, a.id, tp.price FROM generate_series(4, 5) AS g(turn_no) "
                + "JOIN asset a ON (a.market = 'KR' AND a.ticker = '069500') OR (a.market = 'US' AND a.ticker = 'SPY') "
                + "JOIN turn_price tp ON tp.season_id = ? AND tp.turn_no = 3 AND tp.asset_id = a.id "
                + "ON CONFLICT (season_id, turn_no, asset_id) DO NOTHING", season, season);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price tp WHERE tp.season_id = ? AND tp.turn_no > ?",
                Integer.class, season, current)).isEqualTo(4);

        JsonNode items = json(get(season)).get("items");
        assertThat(items).hasSize(current + 1);
        items.forEach(o -> assertThat(o.get("turnNo").asInt()).isLessThanOrEqualTo(current));
        items.forEach(o -> assertThat(o.get("benchmarkValue").isNull()).isFalse());
        BigDecimal initial = won(jdbc.queryForObject("SELECT initial_cash FROM season WHERE id = ?", BigDecimal.class, season));
        assertThat(items.get(0).get("benchmarkValue").asString()).isEqualTo(initial.toPlainString());
        for (int turn = 1; turn <= current; turn++) {
            JsonNode contribution = json(mvc.get().uri("/api/v1/seasons/" + season + "/turns/" + turn + "/contribution")
                    .exchange().getResponse());
            assertThat(items.get(turn).get("benchmarkValue").asString())
                    .isEqualTo(contribution.get("benchmark").get("value").asString());
        }
    }

    @Test
    void a_summary_value_does_not_change_when_later_turns_are_played() throws Exception {
        long season = playedSeason(mvc, jdbc, 3);
        JsonNode early = json(get(season)).get("items");
        assertThat(advance(mvc, season, 3).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 4).getStatus()).isEqualTo(201);

        JsonNode later = json(get(season)).get("items");
        for (int turn = 0; turn <= 3; turn++) {
            assertThat(later.get(turn).toString()).isEqualTo(early.get(turn).toString());
        }
    }

    @Test
    void a_missing_benchmark_price_keeps_the_list_healthy_and_nulls_every_benchmark_value() throws Exception {
        long season = playedSeason(mvc, jdbc, 3);
        JsonNode before = json(get(season)).get("items");
        jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND turn_no = 2 AND asset_id = "
                + "(SELECT id FROM asset WHERE market = 'US' AND ticker = 'SPY')", season);

        MockHttpServletResponse response = get(season);
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode after = json(response).get("items");
        assertThat(after).hasSize(before.size());
        for (int i = 0; i < after.size(); i++) {
            assertThat(after.get(i).get("turnNo").asInt()).isEqualTo(before.get(i).get("turnNo").asInt());
            assertThat(after.get(i).get("totalValue").asString()).isEqualTo(before.get(i).get("totalValue").asString());
            assertThat(after.get(i).get("drawdown").asString()).isEqualTo(before.get(i).get("drawdown").asString());
            assertThat(after.get(i).get("periodReturn").asString()).isEqualTo(before.get(i).get("periodReturn").asString());
            assertThat(after.get(i).get("benchmarkValue").isNull()).isTrue();
        }
    }

    @Test
    void the_body_has_exactly_the_five_numbers_and_no_date_or_month_field() throws Exception {
        long season = playedSeason(mvc, jdbc, 2);
        JsonNode body = json(get(season));
        assertThat(body.propertyNames()).containsExactly("items");
        body.get("items").forEach(o -> assertThat(o.propertyNames())
                .containsExactly("turnNo", "totalValue", "drawdown", "periodReturn", "benchmarkValue"));
        assertThat(Arrays.stream(TurnSummaryDto.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("turnNo", "totalValue", "drawdown", "periodReturn", "benchmarkValue")
                .noneMatch(n -> n.toLowerCase().contains("date") || n.toLowerCase().contains("month")
                        || n.toLowerCase().contains("block") || n.toLowerCase().contains("seed"));
    }

    @Test
    void the_records_come_from_exposed_columns() {
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        for (Class<?> record : List.of(TurnSummaryListDto.class, TurnSummaryDto.class)) {
            assertThat(ExposureRules.check(record, catalog)).as(record.getSimpleName()).isEmpty();
        }
    }

    @Test
    void an_unknown_season_is_404_and_a_non_number_is_400() {
        assertThat(get(999_999_999L).getStatus()).isEqualTo(404);
        assertThat(mvc.get().uri("/api/v1/seasons/abc/turns").exchange().getResponse().getStatus()).isEqualTo(400);
    }
}
