package antlab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.CashAccountRepository;
import antlab.feed.EventFacts;
import antlab.feed.FeedGenerationException;
import antlab.feed.FeedInputReader;
import antlab.feed.FeedRules;
import antlab.history.HistoryProperties;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Java is the oracle of the feed events a bot world carries. On the fake history ({@code history-v1.sql}) plus the fake events of
 * {@code events-v1.sql}, small seasons walk fixed months; for each the test reads every turn through {@link FeedInputReader} (the
 * production reader: reviewed flag, events in key byte order, the hidden rule over the replay range) and writes (with
 * {@code ANTLAB_PARITY_WRITE=1}, refused on CI) or compares the tracked {@code events-v1.json}. The Python test
 * {@code test_world_pg.py} loads the same two SQL files, builds worlds from the same months with {@code bots/world_pg.py} and must
 * produce the same events, hidden flags, categories and refusals.
 *
 * <p>Regenerate after an intended rule change: {@code ANTLAB_PARITY_WRITE=1 ./gradlew test --tests antlab.sim.FeedEventsParityFixtureTest}.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.parity.feed-events=true")
class FeedEventsParityFixtureTest {

    static final Path FILE = Path.of("src/test/resources/sim/parity/events-v1.json");
    static final String SCHEMA = "feed-events-parity/1";
    static final String BASE_DATE = "2026-01-02";
    static final LocalDate START_2007 = LocalDate.of(2007, 1, 1);
    static final LocalDate START_2012 = LocalDate.of(2012, 1, 1);

    /** name, replay starts to read with, path months (turn order). */
    record Walk(String name, List<LocalDate> starts, List<String> months) {
    }

    /** Walks have 12 months, the shortest path a world can have. */
    static final List<Walk> WALKS = List.of(
            new Walk("mixed", List.of(START_2007),
                    List.of("2008-09-01", "2009-04-01", "2011-08-01", "2016-06-01", "2018-05-01", "2020-03-01", "2021-01-01",
                            "2021-02-01", "2021-03-01", "2021-04-01", "2021-05-01", "2021-06-01")),
            new Walk("unreviewed", List.of(START_2007),
                    List.of("2009-05-01", "2009-06-01", "2010-04-01", "2010-05-01", "2010-06-01", "2010-07-01", "2010-08-01",
                            "2010-09-01", "2010-10-01", "2010-11-01", "2010-12-01", "2011-01-01")),
            new Walk("late", List.of(START_2007, START_2012),
                    List.of("2016-06-01", "2018-05-01", "2019-05-01", "2020-03-01", "2021-01-01", "2021-02-01", "2021-03-01",
                            "2021-04-01", "2021-05-01", "2021-06-01", "2021-07-01", "2021-08-01")));

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JdbcClient jdbcClient;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    private final JsonMapper mapper = JsonMapper.shared();

    @Test
    void feedEventsMatchTheFixture() throws IOException {
        new ResourceDatabasePopulator(new ClassPathResource("sim/parity/history-v1.sql"),
                new ClassPathResource("sim/parity/events-v1.sql")).execute(jdbc.getDataSource());
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        Map<Integer, ObjectNode> built = new TreeMap<>();
        int index = 1;
        for (Walk walk : WALKS) {
            built.put(index, walk(fixture, index, walk));
            index++;
        }
        ObjectNode header = mapper.createObjectNode();
        header.put("schema", SCHEMA);
        header.put("baseDate", BASE_DATE);
        header.put("rule", FeedRules.RULE_VERSION);
        SimFixtureWriter.writeOrCompare(FILE, SCHEMA, header, built);
    }

    private ObjectNode walk(SeasonAccountFixture fixture, int index, Walk walk) {
        Season season = fixture.newSeason(fixture.newMember());
        long seasonId = season.getId();
        int turns = walk.months().size();
        for (int turn = 1; turn <= turns; turn++) {
            jdbc.update("INSERT INTO season_path (season_id, turn_no, month_block_id) SELECT ?, ?, id FROM month_block WHERE month_start = ?",
                    seasonId, turn, LocalDate.parse(walk.months().get(turn - 1)));
        }
        // The events part of the reader never looks at prices, but the asset statement needs a turn-0 row per pool asset.
        jdbc.update("""
                INSERT INTO turn_price (season_id, turn_no, asset_id, price)
                SELECT ?, t, p.asset_id, 1000 FROM generate_series(0, ?) t
                CROSS JOIN asset_position p WHERE p.base_date = DATE '2026-01-02'""", seasonId, turns);
        Map<Long, BigDecimal> prices = new HashMap<>();
        jdbc.query("SELECT asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = 0", rs -> {
            prices.put(rs.getLong("asset_id"), rs.getBigDecimal("price"));
        }, seasonId);

        ObjectNode out = mapper.createObjectNode();
        out.put("index", index);
        out.put("name", walk.name());
        ArrayNode months = out.putArray("months");
        walk.months().forEach(months::add);
        ArrayNode runs = out.putArray("runs");
        for (LocalDate start : walk.starts()) {
            FeedInputReader reader = new FeedInputReader(jdbcClient, new HistoryProperties(start));
            ObjectNode run = runs.addObject();
            run.put("replayStart", start.toString());
            ArrayNode turnsOut = run.putArray("turns");
            for (int turn = 1; turn <= turns; turn++) {
                ObjectNode t = turnsOut.addObject();
                t.put("turn", turn);
                try {
                    List<EventFacts> events = reader.read(seasonId, 1L, turn, prices).events();
                    ArrayNode eventsOut = t.putArray("events");
                    for (EventFacts e : events) {
                        ObjectNode n = eventsOut.addObject();
                        n.put("key", e.eventKey());
                        n.put("category", e.category());
                        n.put("hidden", e.hidden());
                        n.put("reaction", SimFixtureWriter.plain(e.reaction()));
                        n.put("strength", e.strength());
                    }
                } catch (FeedGenerationException e) {
                    t.put("refusal", e.failure().name());
                }
            }
        }
        assertThat(runs).isNotEmpty();
        return out;
    }
}
