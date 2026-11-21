package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.history.HistoryProperties;
import antlab.history.MoveDirection;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import antlab.support.SqlRecorder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link FeedInputReader} on a hand-built season of three fake assets at turn 3 (blocks b1..b4; b4 is the next block, which the
 * input must never see). Prices, attention, the previous turn's feed and the events are chosen so every fact has one value by hand:
 * <pre>
 * asset  prices t0..t3          ret     prevRet  streak  attention t2 / t3   previous story
 * ALPHA  1000 1100 1210 1331    +10%    +10%     3       0.5 / 0.7           RISE UP, ECHO UP repeat 2
 * BETA   1000 1000  900  990    +10%    -10%     1       0.9 / 0.6           FADE (no direction)
 * GAMMA  1000 1000 1000 1000      0%      0%     3       0.2 / none          none
 * </pre>
 * OUTSIDER is a fourth asset with the highest attention in both blocks but no price in this season's pool: it must not change any
 * rank, count or the focus name.
 * Always on (Testcontainers PostgreSQL 17), no sample files.
 */
@IntegrationTest
class FeedInputReaderTest {

    private static final int TURN = 3;

    @Autowired
    JdbcClient jdbcClient;
    private FeedInputReader reader;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    private SeasonAccountFixture fixture;
    private Season season;
    private long alpha;
    private long beta;
    private long gamma;
    private long outsider;
    private long[] blocks;
    private final List<Long> assets = new java.util.ArrayList<>();

    @BeforeEach
    void build() {
        reader = readerStartingAt(1498);
        fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        Member member = fixture.newMember();
        season = fixture.newSeason(member);
        alpha = namedAsset("알파");
        beta = namedAsset("베타");
        gamma = namedAsset("감마");
        outsider = namedAsset("외부");
        blocks = new long[5];
        for (int i = 1; i <= 4; i++) {
            blocks[i] = fixture.newMonthBlockId();
            // One block per year so the replay range bounds (start, January 1 of the base-date year) can cut between them.
            jdbc.update("UPDATE month_block SET events_reviewed = TRUE, month_start = ? WHERE id = ?",
                    LocalDate.of(1497 + i, 6, 1), blocks[i]);
            jdbc.update("INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES (?, ?, ?)", season.getId(), i,
                    blocks[i]);
        }
        prices(alpha, 1000, 1100, 1210);
        prices(beta, 1000, 1000, 900);
        prices(gamma, 1000, 1000, 1000);
        attention(2, "0.5", "0.9", "0.2");
        attention(3, "0.7", "0.6", null);
        attention(1, "0.3", "0.3", "0.3");
        attention(4, "0.99", "0.1", "0.8");
        for (int block : new int[] {2, 3}) {
            jdbc.update("INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak,"
                    + " attention_index) VALUES (?, ?, 6, 0.5, 1, 0.99000000)", outsider, blocks[block]);
        }
        event(blocks[1], "ZT10A_EVENT", "T10_COMMON", "TRADE", "0.01000000");
        event(blocks[2], "ZT10B_EVENT", "T10_COMMON", "TRADE", "0.01000000");
        event(blocks[3], "ZT10C_EVENT", "T10_COMMON", "TRADE", "0.04000000");
        event(blocks[3], "ZT10H_EVENT", "T10_RARE", "POLICY", "-0.02000000");
        event(blocks[4], "ZT10F_EVENT", "T10_RARE", "POLICY", "0.50000000");
        feed(2, 1, "RISE", alpha, "UP", 0);
        feed(2, 2, "ECHO", alpha, "UP", 2);
        feed(2, 3, "FADE", beta, null, 0);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM month_event WHERE event_key LIKE 'ZT10%'");
        for (long asset : assets) {
            jdbc.update("DELETE FROM asset_month_attention WHERE asset_id = ?", asset);
            jdbc.update("DELETE FROM asset_month_return WHERE asset_id = ?", asset);
        }
        fixture.cleanUp();
    }

    private FeedInputReader readerStartingAt(int year) {
        return new FeedInputReader(jdbcClient, new HistoryProperties(LocalDate.of(year, 1, 1)));
    }

    private long namedAsset(String name) {
        long id = fixture.newAssetId();
        jdbc.update("UPDATE asset SET name = ? WHERE id = ?", name, id);
        assets.add(id);
        return id;
    }

    private void prices(long asset, int... values) {
        prices(season.getId(), asset, values);
    }

    private void prices(long seasonId, long asset, int... values) {
        for (int turn = 0; turn < values.length; turn++) {
            jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) VALUES (?, ?, ?, ?)", seasonId, turn,
                    asset, new BigDecimal(values[turn]).setScale(4));
        }
    }

    private void attention(int block, String forAlpha, String forBeta, String forGamma) {
        long[] ids = {alpha, beta, gamma};
        String[] values = {forAlpha, forBeta, forGamma};
        for (int i = 0; i < ids.length; i++) {
            if (values[i] != null) {
                jdbc.update("INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak,"
                        + " attention_index) VALUES (?, ?, 6, 0.5, 1, ?)", ids[i], blocks[block], new BigDecimal(values[i]).setScale(8));
            }
        }
    }

    private void event(long block, String key, String type, String category, String reaction) {
        jdbc.update("INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction, strength,"
                + " reaction_change, title, source) VALUES (?, ?, ?, ?, 'START', 'FAKE_OUTCOME', 'DOWN', 2, ?, 'FAKE title',"
                + " 'FAKE source')", key, block, category, type, new BigDecimal(reaction));
    }

    private void feed(int turn, int seq, String kind, long asset, String direction, int repeat) {
        jdbc.update("INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, direction, repeat_count, template_id, body)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'fake.template.v1', 'FAKE body')", season.getId(), turn, seq, kind, asset,
                direction, repeat);
    }

    private static Map<Long, BigDecimal> currentPrices(long alpha, long beta, long gamma) {
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        prices.put(alpha, new BigDecimal("1331"));
        prices.put(beta, new BigDecimal("990"));
        prices.put(gamma, new BigDecimal("1000"));
        return prices;
    }

    private FeedInput read() {
        return reader.read(season.getId(), season.getSeed(), TURN, currentPrices(alpha, beta, gamma));
    }

    private static AssetFacts fact(FeedInput input, long assetId) {
        return input.assets().stream().filter(a -> a.assetId() == assetId).findFirst().orElseThrow();
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void the_input_of_turn_three_holds_the_hand_computed_facts() {
        FeedInput input = read();

        assertThat(input.seed()).isEqualTo(7L);
        assertThat(input.turnNo()).isEqualTo(TURN);
        assertThat(input.assets()).extracting(AssetFacts::name).as("the outsider is not in the pool").containsExactlyInAnyOrder("알파", "베타", "감마");
        assertThat(input.assets()).allSatisfy(a -> assertThat(a.asOfTurn()).isEqualTo(TURN));

        AssetFacts a = fact(input, alpha);
        assertThat(a.ret()).isEqualTo(d("0.10000000"));
        assertThat(a.prevRet()).isEqualTo(d("0.10000000"));
        assertThat(a.streak()).isEqualTo(3);
        assertThat(a.attention()).isEqualTo(d("0.70000000"));
        assertThat(a.attentionRank()).isEqualTo(1);
        assertThat(a.attentionN()).isEqualTo(2);
        assertThat(a.prevAttention()).isEqualTo(d("0.50000000"));
        assertThat(a.prevAttentionRank()).isEqualTo(2);
        assertThat(a.prevStoryDirection()).isEqualTo(MoveDirection.UP);
        assertThat(a.prevEchoRepeat()).isEqualTo(3);
        assertThat(a.focusName()).isEqualTo("알파");
        assertThat(a.groupId()).isEqualTo(jdbc.queryForObject("SELECT asset_group_id FROM asset WHERE id = ?", Long.class, alpha));

        AssetFacts b = fact(input, beta);
        assertThat(b.ret()).isEqualTo(d("0.10000000"));
        assertThat(b.prevRet()).isEqualTo(d("-0.10000000"));
        assertThat(b.streak()).isEqualTo(1);
        assertThat(b.attentionRank()).isEqualTo(2);
        assertThat(b.prevAttentionRank()).isEqualTo(1);
        assertThat(b.prevStoryDirection()).isNull();
        assertThat(b.prevEchoRepeat()).isEqualTo(1);

        AssetFacts g = fact(input, gamma);
        assertThat(g.ret()).isEqualTo(d("0.00000000"));
        assertThat(g.prevRet()).isEqualTo(d("0.00000000"));
        assertThat(g.streak()).as("a zero change is a sign of its own, so three flat turns are a streak of 3").isEqualTo(3);
        assertThat(g.attention()).isNull();
        assertThat(g.attentionRank()).isNull();
        assertThat(g.prevAttention()).isEqualTo(d("0.20000000"));
        assertThat(g.prevAttentionRank()).isEqualTo(3);
        assertThat(g.prevStoryDirection()).isNull();
    }

    @Test
    void the_last_story_of_the_previous_turn_wins_by_display_order() {
        feed(2, 4, "FALL", alpha, "DOWN", 0);

        AssetFacts a = fact(read(), alpha);
        assertThat(a.prevStoryDirection()).as("seq 4 comes after seq 1 and 2").isEqualTo(MoveDirection.DOWN);
        assertThat(a.prevEchoRepeat()).as("the largest ECHO repeat still counts").isEqualTo(3);
    }

    @Test
    void another_season_with_the_same_turn_numbers_changes_nothing() {
        FeedInput before = read();
        Season other = fixture.newSeason(fixture.newMember());
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES (?, ?, ?)", other.getId(), i, blocks[i]);
        }
        for (long asset : new long[] {alpha, beta, gamma}) {
            prices(other.getId(), asset, 1000, 900, 800);
        }
        jdbc.update("INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, direction, repeat_count, template_id, body)"
                + " VALUES (?, 2, 1, 'ECHO', ?, 'DOWN', 7, 'fake.template.v1', 'FAKE body')", other.getId(), beta);
        jdbc.update("INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, direction, repeat_count, template_id, body)"
                + " VALUES (?, 2, 2, 'RISE', ?, 'UP', 0, 'fake.template.v1', 'FAKE body')", other.getId(), gamma);

        assertThat(read()).as("prices, path and previous feed of the other season are not read").isEqualTo(before);
    }

    @Test
    void events_come_in_key_order_and_a_rare_type_hides_its_category() {
        List<EventFacts> events = read().events();

        assertThat(events).extracting(EventFacts::eventKey).containsExactly("ZT10C_EVENT", "ZT10H_EVENT");
        EventFacts common = events.get(0);
        assertThat(common.hidden()).as("three key families share the type").isFalse();
        assertThat(common.category()).isEqualTo("TRADE");
        assertThat(common.reaction()).isEqualTo(d("0.04000000"));
        EventFacts rare = events.get(1);
        assertThat(rare.hidden()).as("one key family is too rare").isTrue();
        assertThat(rare.category()).isNull();
        assertThat(rare.reaction()).isEqualTo(d("-0.02000000"));
        assertThat(events).allSatisfy(e -> assertThat(e.asOfTurn()).isEqualTo(TURN));
    }

    @Test
    void months_before_the_replay_start_do_not_count_toward_n() {
        FeedInput counted = read();
        assertThat(counted.events()).filteredOn(e -> e.eventKey().equals("ZT10C_EVENT")).allSatisfy(e -> assertThat(e.hidden()).isFalse());

        reader = readerStartingAt(1499);

        assertThat(read().events()).filteredOn(e -> e.eventKey().equals("ZT10C_EVENT"))
                .as("the 1498 episode is warm-up, so n is 2").allSatisfy(e -> {
                    assertThat(e.hidden()).isTrue();
                    assertThat(e.category()).isNull();
                });
    }

    @Test
    void months_from_january_of_the_base_date_year_do_not_count_toward_n() {
        jdbc.update("UPDATE season SET base_date = DATE '1500-01-02', data_snapshot_date = DATE '1500-01-02' WHERE id = ?",
                season.getId());

        assertThat(read().events()).filteredOn(e -> e.eventKey().equals("ZT10C_EVENT"))
                .as("the 1500 episode is at or after the base-date year, so n is 2").allSatisfy(e -> assertThat(e.hidden()).isTrue());
    }

    @Test
    void the_statement_count_is_fixed_and_does_not_grow_with_the_pool() {
        SqlRecorder.start();
        read();
        SqlRecorder.Recorded small = SqlRecorder.stopAll();

        for (int i = 0; i < 3; i++) {
            long extra = namedAsset("보탬" + i);
            prices(extra, 1000, 1010, 1020);
        }
        Map<Long, BigDecimal> larger = currentPrices(alpha, beta, gamma);
        assets.subList(4, assets.size()).forEach(id -> larger.put(id, new BigDecimal("1030")));
        SqlRecorder.start();
        reader.read(season.getId(), season.getSeed(), TURN, larger);
        SqlRecorder.Recorded large = SqlRecorder.stopAll();

        assertThat(small.hibernate()).as("the reader uses JdbcClient only").isEmpty();
        assertThat(small.jdbcClient()).as("reviewed flag, events, prices, assets, attention, previous feed").hasSize(6);
        assertThat(large.jdbcClient()).hasSameSizeAs(small.jdbcClient());
    }

    @Test
    void turn_one_reads_no_previous_feed() {
        Map<Long, BigDecimal> one = new LinkedHashMap<>();
        one.put(alpha, new BigDecimal("1100"));
        one.put(beta, new BigDecimal("1000"));
        one.put(gamma, new BigDecimal("1000"));
        SqlRecorder.start();
        FeedInput input = reader.read(season.getId(), season.getSeed(), 1, one);
        SqlRecorder.Recorded recorded = SqlRecorder.stopAll();

        assertThat(recorded.jdbcClient()).hasSize(5);
        assertThat(fact(input, alpha).prevRet()).as("no turn before turn 0").isNull();
        assertThat(fact(input, alpha).prevAttention()).isNull();
        assertThat(fact(input, alpha).streak()).isEqualTo(1);
    }

    @Test
    void changing_the_next_block_changes_nothing_but_changing_this_turn_does() {
        FeedInput before = read();

        // Everything of block b4 (turn 4) and the prices of a turn that does not exist yet.
        jdbc.update("UPDATE asset_month_attention SET attention_index = 0.12345678 WHERE month_block_id = ?", blocks[4]);
        jdbc.update("UPDATE month_event SET reaction_change = -0.45000000 WHERE month_block_id = ?", blocks[4]);
        event(blocks[4], "ZT10G_EVENT", "T10_COMMON", "TRADE", "0.30000000");
        jdbc.update("INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES (?, ?, 0.5)", alpha, blocks[4]);
        jdbc.update("UPDATE month_block SET events_reviewed = FALSE WHERE id = ?", blocks[4]);
        assertThat(read()).as("block of turn 4 is not read").isEqualTo(before);

        // Positive controls: the same kinds of change on the blocks the input may read do move it.
        jdbc.update("UPDATE asset_month_attention SET attention_index = 0.11000000 WHERE month_block_id = ? AND asset_id = ?",
                blocks[3], alpha);
        assertThat(read()).as("attention of this turn's block").isNotEqualTo(before);
        jdbc.update("UPDATE asset_month_attention SET attention_index = 0.70000000 WHERE month_block_id = ? AND asset_id = ?",
                blocks[3], alpha);
        assertThat(read()).isEqualTo(before);
        jdbc.update("UPDATE month_event SET reaction_change = 0.05000000 WHERE event_key = 'ZT10C_EVENT'");
        assertThat(read()).as("event of this turn's block").isNotEqualTo(before);
        jdbc.update("UPDATE month_event SET reaction_change = 0.04000000 WHERE event_key = 'ZT10C_EVENT'");
        jdbc.update("UPDATE asset_month_attention SET attention_index = 0.95000000 WHERE month_block_id = ? AND asset_id = ?",
                blocks[2], alpha);
        assertThat(read()).as("attention of the previous block").isNotEqualTo(before);
    }

    @Test
    void an_unreviewed_block_refuses_the_feed() {
        jdbc.update("UPDATE month_block SET events_reviewed = FALSE WHERE id = ?", blocks[3]);
        jdbc.update("UPDATE month_event SET reaction_change = NULL WHERE event_key = 'ZT10C_EVENT'");

        assertThatThrownBy(this::read).isInstanceOfSatisfying(FeedGenerationException.class,
                e -> assertThat(e.failure()).as("reviewed is checked before the reaction").isEqualTo(FeedFailure.FEED_EVENTS_UNREVIEWED));
    }

    @Test
    void an_event_without_a_reaction_refuses_the_feed() {
        jdbc.update("UPDATE month_event SET reaction_change = NULL WHERE event_key = 'ZT10H_EVENT'");

        assertThatThrownBy(this::read).isInstanceOfSatisfying(FeedGenerationException.class,
                e -> assertThat(e.failure()).isEqualTo(FeedFailure.FEED_REACTION_MISSING));
    }

    @Test
    void a_pool_asset_with_a_missing_price_turn_refuses_the_feed() {
        jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND asset_id = ? AND turn_no = 1", season.getId(), beta);

        assertThatThrownBy(this::read).isInstanceOfSatisfying(FeedGenerationException.class,
                e -> assertThat(e.failure()).isEqualTo(FeedFailure.FEED_INPUT));
    }

    @Test
    void a_turn_without_a_path_row_refuses_the_feed() {
        assertThatThrownBy(() -> reader.read(season.getId(), season.getSeed(), 9, currentPrices(alpha, beta, gamma)))
                .isInstanceOfSatisfying(FeedGenerationException.class,
                        e -> assertThat(e.failure()).isEqualTo(FeedFailure.FEED_INPUT));
    }
}
