package antlab.api.turn;

import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.RankingSupport.playedSeason;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.ranking.AttentionRankDto;
import antlab.ranking.RankedAssetDto;
import antlab.ranking.RankedGroupDto;
import antlab.ranking.RankingBoardDto;
import antlab.ranking.StreakRankDto;
import antlab.support.ExposureCatalog;
import antlab.support.ExposureRules;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** The board shows ranks, grades and 8-digit changes only: no attention value, no date, no block, no template. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class RankingExposureTest {

    static final Set<String> FORBIDDEN_KEYS = Set.of("attentionIndex", "attentionRank", "index", "kind", "templateId", "month", "block",
            "date", "monthBlockId", "seed", "price", "path", "streakIndex");
    static final Set<String> ALLOWED_KEYS = Set.of("turnNo", "gainers", "losers", "attention", "risingStreaks", "fallingStreaks",
            "groupGainers", "groupLosers", "rank", "assetId", "groupId", "changeRate", "grade", "streak");
    static final Pattern EIGHT_DIGITS = Pattern.compile("-?[0-9]+\\.[0-9]{8}");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            for (var e : node.properties()) {
                keys.add(e.getKey());
                collectKeys(e.getValue(), keys);
            }
        } else if (node.isArray()) {
            node.forEach(n -> collectKeys(n, keys));
        }
    }

    @Test
    void every_response_record_comes_from_exposed_columns() {
        ExposureCatalog catalog = ExposureCatalog.load(jdbc);
        for (Class<?> record : List.of(RankingBoardDto.class, RankedAssetDto.class, AttentionRankDto.class, StreakRankDto.class,
                RankedGroupDto.class)) {
            assertThat(ExposureRules.check(record, catalog)).as(record.getSimpleName()).isEmpty();
        }
    }

    @Test
    void the_body_has_only_the_listed_keys_and_changes_are_eight_digit_strings() throws Exception {
        long season = playedSeason(mvc, jdbc, 6);
        Set<String> seen = new HashSet<>();
        int changes = 0;
        for (int turn = 1; turn <= 6; turn++) {
            JsonNode body = json(getRanking(mvc, season, turn));
            Set<String> keys = new HashSet<>();
            collectKeys(body, keys);
            assertThat(keys).as("turn " + turn).doesNotContainAnyElementsOf(FORBIDDEN_KEYS).isSubsetOf(ALLOWED_KEYS);
            seen.addAll(keys);
            List<JsonNode> rows = new ArrayList<>();
            body.properties().forEach(e -> {
                if (e.getValue().isArray()) {
                    e.getValue().forEach(rows::add);
                }
            });
            for (JsonNode row : rows) {
                if (row.has("changeRate")) {
                    assertThat(row.get("changeRate").isString()).isTrue();
                    assertThat(row.get("changeRate").asString()).matches(EIGHT_DIGITS);
                    changes++;
                }
                if (row.has("grade")) {
                    assertThat(row.get("grade").asInt()).isBetween(1, 3);
                }
            }
        }
        assertThat(seen).as("every key shows up on some turn").containsAll(Set.of("rank", "assetId", "groupId", "changeRate", "grade"));
        assertThat(changes).isPositive();
    }

    @Test
    void the_attention_list_exposes_no_component_beyond_rank_asset_and_grade() {
        assertThat(Arrays.stream(AttentionRankDto.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("rank", "assetId", "grade");
    }

    @Test
    void the_attention_index_value_never_appears_in_the_text() throws Exception {
        long season = playedSeason(mvc, jdbc, 3);
        List<String> indexes = jdbc.queryForList("SELECT DISTINCT attention_index::text FROM asset_month_attention WHERE attention_index IS NOT NULL",
                String.class);
        assertThat(indexes).isNotEmpty();
        for (int turn = 1; turn <= 3; turn++) {
            String text = getRanking(mvc, season, turn).getContentAsString();
            for (String index : indexes) {
                assertThat(text).as("index " + index).doesNotContain("\"" + index + "\"");
            }
        }
    }
}
