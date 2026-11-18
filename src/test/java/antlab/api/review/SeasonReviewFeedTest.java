package antlab.api.review;

import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** The review lists each turn's stored feed rows as shown, with what each one was and how many orders came from it. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReviewFeedTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private long plant(long season, int turn, int seq, String kind, Long asset, Long group, Long event, String body) {
        return jdbc.queryForObject("INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, asset_group_id, month_event_id,"
                + " template_id, body) VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST_ROW', ?) RETURNING id", Long.class, season, turn, seq, kind,
                asset, group, event, body);
    }

    private void orderFrom(long season, long asset, int turn, String source, Long item) {
        orderFrom(season, asset, turn, source, item, "FILLED");
    }

    private void orderFrom(long season, long asset, int turn, String source, Long item, String status) {
        jdbc.update("INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, turn_feed_id) "
                + "VALUES (?, ?, ?, 'BUY', 1, ?, ?, ?)", season, asset, turn, status, source, item);
    }

    private long eventId() {
        return jdbc.queryForObject("SELECT id FROM month_event WHERE event_key = 'FAKE_EVENT_01'", Long.class);
    }

    private long groupId() {
        return jdbc.queryForObject("SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'", Long.class);
    }

    @Test
    void 항목은_저장된_행_그대로_표시_순서이고_사건_카드만_signal이다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        long asset = assetId(jdbc, "000660");
        // inserted out of order on purpose: the response must follow turn then seq
        plant(season, 1, 3, "NUDGE", null, null, null, "알림 문장");
        plant(season, 1, 1, "RISE", asset, null, null, "상승 문장");
        plant(season, 1, 2, "EVENT", null, null, eventId(), "사건 문장");
        plant(season, 1, 4, "SPREAD", null, groupId(), null, "그룹 문장");
        plant(season, 2, 1, "FALL", asset, null, null, "하락 문장");

        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode turns = json(r).get("turns");
        JsonNode feed = turns.get(0).get("feed");
        assertThat(feed.size()).isEqualTo(4);
        assertThat(feed.get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(feed.get(0).get("kind").asString()).isEqualTo("RISE");
        assertThat(feed.get(0).get("signal").asBoolean()).isFalse();
        assertThat(feed.get(0).get("assetId").asLong()).isEqualTo(asset);
        assertThat(feed.get(0).get("groupId").isNull()).isTrue();
        assertThat(feed.get(0).get("body").asString()).isEqualTo("상승 문장");
        assertThat(feed.get(0).get("event").isNull()).isTrue();

        JsonNode event = feed.get(1);
        assertThat(event.get("kind").asString()).isEqualTo("EVENT");
        assertThat(event.get("signal").asBoolean()).isTrue();
        assertThat(event.get("assetId").isNull()).isTrue();
        assertThat(event.get("event").get("title").asString()).isEqualTo("가짜 사건 제목");
        assertThat(event.get("event").get("hindsight").asString()).isEqualTo("가짜 사후 설명");
        assertThat(event.get("event").get("source").asString()).isEqualTo("가짜 출처");
        assertThat(event.get("body").asString()).isEqualTo("사건 문장");

        JsonNode nudge = feed.get(2);
        assertThat(nudge.get("kind").asString()).isEqualTo("NUDGE");
        assertThat(nudge.get("signal").asBoolean()).as("an alert without a target is not a signal").isFalse();
        assertThat(nudge.get("assetId").isNull()).isTrue();
        assertThat(nudge.get("groupId").isNull()).isTrue();
        assertThat(nudge.get("event").isNull()).isTrue();

        assertThat(feed.get(3).get("kind").asString()).isEqualTo("SPREAD");
        assertThat(feed.get(3).get("groupId").asLong()).isEqualTo(groupId());

        assertThat(turns.get(1).get("feed").size()).isEqualTo(1);
        assertThat(turns.get(1).get("feed").get(0).get("kind").asString()).isEqualTo("FALL");
    }

    @Test
    void 피드가_없는_턴은_빈_목록이고_키는_빠지지_않는다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        JsonNode body = json(ReviewApiSupport.getReview(mvc, season));
        for (JsonNode turn : body.get("turns")) {
            assertThat(turn.has("feed")).isTrue();
            assertThat(turn.get("feed").isArray()).isTrue();
            assertThat(turn.get("feed").size()).isZero();
        }
    }

    @Test
    void ordersFromItem은_FEED와_ALERT_주문만_세고_뒤_턴의_주문도_센다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        long asset = assetId(jdbc, "000660");
        long rise = plant(season, 1, 1, "RISE", asset, null, null, "상승 문장");
        long alert = plant(season, 1, 2, "NUDGE", null, null, null, "알림 문장");
        plant(season, 1, 3, "FALL", asset, null, null, "하락 문장");
        orderFrom(season, asset, 1, "FEED", rise);
        orderFrom(season, asset, 1, "FEED", rise);
        orderFrom(season, asset, 5, "FEED", rise); // placed turns later (#94)
        orderFrom(season, asset, 1, "FEED", rise, "REJECTED"); // counted whatever the outcome
        orderFrom(season, asset, 2, "ALERT", alert);
        orderFrom(season, asset, 1, "DIRECT", null);

        JsonNode feed = json(ReviewApiSupport.getReview(mvc, season)).get("turns").get(0).get("feed");
        assertThat(feed.get(0).get("ordersFromItem").asInt()).isEqualTo(4);
        assertThat(feed.get(1).get("ordersFromItem").asInt()).isEqualTo(1);
        assertThat(feed.get(2).get("ordersFromItem").asInt()).isZero();
    }

    @Test
    void 다른_시즌의_피드와_주문은_섞이지_않는다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        long other = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        long asset = assetId(jdbc, "000660");
        plant(season, 1, 1, "RISE", asset, null, null, "내 시즌 문장");
        long foreign = plant(other, 1, 1, "RISE", asset, null, null, "다른 시즌 문장");
        orderFrom(other, asset, 1, "FEED", foreign);

        JsonNode feed = json(ReviewApiSupport.getReview(mvc, season)).get("turns").get(0).get("feed");
        assertThat(feed.size()).isEqualTo(1);
        assertThat(feed.get(0).get("body").asString()).isEqualTo("내 시즌 문장");
        assertThat(feed.get(0).get("ordersFromItem").asInt()).isZero();
    }
}
