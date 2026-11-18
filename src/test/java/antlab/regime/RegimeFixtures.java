package antlab.regime;

import antlab.engine.client.EngineRegimeResult;
import antlab.support.FakeEngineServer;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The shared regime answer (two months: an EVALUATED one and a NO_DATA one) and ways to edit a copy of it. */
final class RegimeFixtures {

    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final String PATH = "/v1/regime/evaluate";

    private RegimeFixtures() {
    }

    static String text() {
        return FakeEngineServer.fixture("regime-response.json");
    }

    static ObjectNode tree() {
        return (ObjectNode) MAPPER.readTree(text());
    }

    static ObjectNode edited(Consumer<ObjectNode> change) {
        ObjectNode node = tree();
        change.accept(node);
        return node;
    }

    static ObjectNode month(ObjectNode answer, int index) {
        return (ObjectNode) ((ArrayNode) answer.get("months")).get(index);
    }

    static EngineRegimeResult parse(JsonNode answer) {
        return MAPPER.treeToValue(answer, EngineRegimeResult.class);
    }

    static EngineRegimeResult parsed() {
        return parse(tree());
    }
}
