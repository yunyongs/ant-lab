package antlab.api.turn;

import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.RankingSupport.playedSeason;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.ranking.RankingBoard;
import antlab.ranking.RankingBoardCalculator;
import antlab.ranking.RankingInputReader;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET …/turns/{n}/ranking}: the seven lists of every played turn, and the refusals around it. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class RankingApiTest {

    static final List<String> LISTS = List.of("gainers", "losers", "attention", "risingStreaks", "fallingStreaks", "groupGainers",
            "groupLosers");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RankingInputReader reader;
    @Autowired
    JsonMapper mapper;

    private Map<Long, BigDecimal> prices(long season, int turn) {
        return jdbc.queryForList("SELECT asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = ?", season, turn).stream()
                .collect(Collectors.toMap(r -> ((Number) r.get("asset_id")).longValue(), r -> (BigDecimal) r.get("price")));
    }

    @Test
    void every_played_turn_answers_the_seven_lists_and_equals_the_calculator_on_the_stored_rows() throws Exception {
        long season = playedSeason(mvc, jdbc, 5);
        int nonEmptyGainers = 0;
        int nonEmptyAttention = 0;
        for (int turn = 1; turn <= 5; turn++) {
            MockHttpServletResponse r = getRanking(mvc, season, turn);
            assertThat(r.getStatus()).as("turn " + turn).isEqualTo(200);
            JsonNode body = json(r);
            assertThat(body.get("turnNo").asInt()).isEqualTo(turn);
            for (String list : LISTS) {
                assertThat(body.get(list).isArray()).as(list).isTrue();
                assertThat(body.get(list).size()).as(list).isLessThanOrEqualTo(5);
            }
            RankingBoard expected = RankingBoardCalculator.board(reader.read(season, turn));
            assertThat(body).as("turn " + turn).isEqualTo(mapper.readTree(mapper.writeValueAsString(expected)));
            nonEmptyGainers += body.get("gainers").size() > 0 ? 1 : 0;
            nonEmptyAttention += body.get("attention").size() > 0 ? 1 : 0;
        }
        assertThat(nonEmptyGainers).as("some turn has a gainer").isPositive();
        assertThat(nonEmptyAttention).as("the seeded attention reaches the list").isEqualTo(5);
    }

    @Test
    void the_change_rates_follow_the_stored_prices_by_an_independent_calculation() throws Exception {
        long season = playedSeason(mvc, jdbc, 4);
        for (int turn = 1; turn <= 4; turn++) {
            Map<Long, BigDecimal> now = prices(season, turn);
            Map<Long, BigDecimal> before = prices(season, turn - 1);
            JsonNode body = json(getRanking(mvc, season, turn));
            int rows = 0;
            for (String list : List.of("gainers", "losers")) {
                for (JsonNode row : body.get(list)) {
                    long asset = row.get("assetId").asLong();
                    BigDecimal change = now.get(asset).divide(before.get(asset), 30, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                            .setScale(8, RoundingMode.HALF_UP);
                    assertThat(new BigDecimal(row.get("changeRate").asString())).as(list + " " + asset).isEqualTo(change);
                    assertThat(change.signum()).isEqualTo(list.equals("gainers") ? 1 : -1);
                    rows++;
                }
            }
            for (JsonNode row : body.get("groupGainers")) {
                assertThat(new BigDecimal(row.get("changeRate").asString()).signum()).isPositive();
            }
            for (JsonNode row : body.get("groupLosers")) {
                assertThat(new BigDecimal(row.get("changeRate").asString()).signum()).isNegative();
            }
            assertThat(rows).as("turn " + turn).isPositive();
        }
    }

    @Test
    void a_turn_below_one_or_not_a_number_is_a_400() throws Exception {
        long season = playedSeason(mvc, jdbc, 1);
        for (String turn : List.of("0", "-1", "abc", "1.5")) {
            MockHttpServletResponse r = getRanking(mvc, season, turn);
            assertThat(r.getStatus()).as(turn).isEqualTo(400);
            assertThat(code(r)).as(turn).isEqualTo("INVALID_ARGUMENT");
        }
        assertThat(mvc.get().uri("/api/v1/seasons/abc/turns/1/ranking").exchange().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void a_missing_season_is_a_404() throws Exception {
        MockHttpServletResponse r = getRanking(mvc, 999_999_999L, 1);
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(code(r)).isEqualTo("NOT_FOUND");
    }

    @Test
    void a_turn_not_reached_or_beyond_the_path_is_a_409() throws Exception {
        long season = playedSeason(mvc, jdbc, 2);
        for (int turn : new int[] {3, 4, 12, 13, 1000}) {
            MockHttpServletResponse r = getRanking(mvc, season, turn);
            assertThat(r.getStatus()).as("turn " + turn).isEqualTo(409);
            assertThat(code(r)).isEqualTo("TURN_NOT_REACHED");
        }
        assertThat(getRanking(mvc, season, 2).getStatus()).isEqualTo(200);
    }

    @Test
    void a_finished_season_still_answers_every_turn_and_beyond_the_path_is_a_409() throws Exception {
        long season = playedSeason(mvc, jdbc, 12);
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
        for (int turn = 1; turn <= 12; turn++) {
            assertThat(getRanking(mvc, season, turn).getStatus()).as("turn " + turn).isEqualTo(200);
        }
        MockHttpServletResponse beyond = getRanking(mvc, season, 13);
        assertThat(beyond.getStatus()).isEqualTo(409);
        assertThat(code(beyond)).isEqualTo("TURN_NOT_REACHED");
    }

    @Test
    void a_halted_season_still_answers_the_turns_it_played() throws Exception {
        long season = playedSeason(mvc, jdbc, 3);
        String before = getRanking(mvc, season, 2).getContentAsString();
        jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", season);
        assertThat(advance(mvc, season, 3).getStatus()).as("halted seasons do not advance").isEqualTo(409);
        for (int turn = 1; turn <= 3; turn++) {
            assertThat(getRanking(mvc, season, turn).getStatus()).as("turn " + turn).isEqualTo(200);
        }
        assertThat(getRanking(mvc, season, 2).getContentAsString()).isEqualTo(before);
        assertThat(getRanking(mvc, season, 4).getStatus()).isEqualTo(409);
    }

    @Test
    void a_season_without_attention_rows_still_answers_with_an_empty_attention_list() throws Exception {
        long season = TurnApiSupport.startSeason(mvc);
        TurnApiSupport.buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        JsonNode body = json(getRanking(mvc, season, 1));
        assertThat(body.get("attention")).isEmpty();
        assertThat(body.get("gainers").size() + body.get("losers").size()).isPositive();
    }
}
