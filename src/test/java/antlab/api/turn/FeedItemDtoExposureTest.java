package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.FeedItemDto;
import antlab.season.TurnResultDto;
import antlab.support.ExposureCatalog;
import antlab.support.ExposureRules;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the player can see of a feed item: turn, order, the asset chip and the sentence, nothing else. The component set is pinned, the
 * exposure rules accept it, and the JSON of a turn that holds an event card carries no word of the stored row's inner fields — so an
 * event card cannot be told from noise by the public response (the screen text is covered by {@code FeedSameClothesTest}).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FeedItemDtoExposureTest {

    private static final List<String> INNER_WORDS = List.of("kind", "templateId", "templateVars", "EVENT", "RISE", "FALL", "ECHO",
            "SPREAD", "FADE", "NUDGE", "groupId", "assetGroupId", "monthEventId", "eventKey", "categoryHidden", "strength",
            "direction", "repeatCount");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void the_item_has_exactly_four_public_fields_and_the_exposure_rules_accept_it() {
        Set<String> components = new TreeSet<>(Arrays.stream(FeedItemDto.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertThat(components).containsExactly("assetId", "body", "seq", "turnNo");
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        assertThat(ExposureRules.check(FeedItemDto.class, catalog)).isEmpty();
        assertThat(ExposureRules.check(TurnResultDto.class, catalog)).isEmpty();
    }

    @Test
    void the_json_of_a_turn_with_an_event_card_names_no_inner_field_of_the_stored_row() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        Long block = jdbc.queryForObject("SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1", Long.class,
                season);
        jdbc.update("INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction, strength,"
                + " reaction_change, title, source) VALUES ('ZT15_EVENT_01', ?, 'TRADE', 'T15_TYPE', 'START', 'FAKE_OUTCOME', 'DOWN',"
                + " 2, 0.06000000, 'FAKE title', 'FAKE source')", block);
        try {
            MockHttpServletResponse advanced = advance(mvc, season, 0);
            assertThat(advanced.getStatus()).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_feed WHERE season_id = ? AND kind = 'EVENT'", Long.class, season))
                    .as("the turn holds an event card, so the check is not vacuous").isEqualTo(1L);
            for (MockHttpServletResponse response : List.of(advanced, getTurn(mvc, season, 1))) {
                String text = response.getContentAsString();
                JsonNode feed = JsonMapper.shared().readTree(text).get("feed");
                assertThat(feed).isNotEmpty();
                for (JsonNode item : feed) {
                    assertThat(item.propertyNames()).containsExactlyInAnyOrder("turnNo", "seq", "assetId", "body");
                }
                JsonNode alerts = JsonMapper.shared().readTree(text).get("alerts");
                assertThat(alerts).as("the turn holds a nudge alert, so the check is not vacuous").isNotEmpty();
                for (JsonNode item : alerts) {
                    assertThat(item.propertyNames()).containsExactlyInAnyOrder("turnNo", "seq", "assetId", "body");
                }
                for (String word : INNER_WORDS) {
                    assertThat(text).as("response must not carry \"" + word + "\"").doesNotContain("\"" + word + "\"");
                }
            }
        } finally {
            jdbc.update("DELETE FROM turn_feed WHERE season_id = ?", season);
            jdbc.update("DELETE FROM month_event WHERE event_key = 'ZT15_EVENT_01'");
        }
    }
}
