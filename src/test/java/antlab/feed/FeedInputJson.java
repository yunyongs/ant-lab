package antlab.feed;

import antlab.history.MoveDirection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Test adapter: the JSON that {@code db/24 tf_input} assembles, read into the generator's {@link FeedInput}. */
final class FeedInputJson {

    /** Numbers keep their scale (0.01000000 stays scale 8), because template variables are compared as text. */
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();

    private FeedInputJson() {
    }

    static FeedInput read(String json) {
        JsonNode root = MAPPER.readTree(json);
        int turn = root.path("turn_no").intValue();
        long seed = Long.parseLong(root.path("seed").asString());
        List<AssetFacts> assets = new ArrayList<>();
        for (JsonNode a : root.path("assets")) {
            assets.add(new AssetFacts(turn, a.path("asset_id").longValue(), a.path("market").asString(), a.path("ticker").asString(),
                    a.path("asset_name").asString(), a.path("group_id").longValue(), a.path("group_code").asString(),
                    a.path("group_name").asString(), decimal(a, "ret"), decimal(a, "prev_ret"), a.path("streak").intValue(),
                    decimal(a, "attention_index"), integer(a, "attention_rank"), a.path("attention_n").intValue(),
                    decimal(a, "prev_attention"), integer(a, "prev_attention_rank"), direction(a, "prev_story_direction"),
                    a.path("prev_echo_repeat").intValue(), text(a, "focus_name")));
        }
        List<EventFacts> events = new ArrayList<>();
        for (JsonNode e : root.path("events")) {
            events.add(new EventFacts(turn, e.path("event_id").longValue(), e.path("event_key").asString(), text(e, "category"),
                    decimal(e, "reaction"), integer(e, "strength"), e.path("hidden").booleanValue()));
        }
        return new FeedInput(seed, turn, assets, events);
    }

    private static boolean absent(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return absent(node, field) ? null : node.path(field).decimalValue();
    }

    private static Integer integer(JsonNode node, String field) {
        return absent(node, field) ? null : node.path(field).intValue();
    }

    private static String text(JsonNode node, String field) {
        return absent(node, field) ? null : node.path(field).asString();
    }

    private static MoveDirection direction(JsonNode node, String field) {
        return absent(node, field) ? null : MoveDirection.valueOf(node.path(field).asString());
    }
}
