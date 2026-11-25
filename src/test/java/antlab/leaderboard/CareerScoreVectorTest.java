package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Java L equals the engine's L vectors ({@code career-l-vectors/1}) value for value; the fixture is read, never edited. */
class CareerScoreVectorTest {

    static final Path VECTORS = Path.of("engine/tests/fixtures/career_l_vectors.json");

    @Test
    void 엔진_벡터와_값이_같다() throws Exception {
        JsonNode root = JsonMapper.shared().readTree(Files.readString(VECTORS));
        assertThat(root.get("schema").asString()).isEqualTo("career-l-vectors/1");
        JsonNode vectors = root.get("vectors");
        assertThat(vectors.size()).isEqualTo(11);
        TreeSet<Integer> sizes = new TreeSet<>();
        for (JsonNode v : vectors) {
            String name = v.get("name").asString();
            List<BigDecimal> seasons = new ArrayList<>();
            v.get("seasons").forEach(s -> seasons.add(new BigDecimal(s.asString())));
            assertThat(seasons).as(name).hasSize(v.get("m").asInt());
            sizes.add(seasons.size());

            CareerScore.Result r = CareerScore.of(seasons);
            assertThat(r.trimEachSide()).as(name + " trimEachSide").isEqualTo(v.get("trimEachSide").asInt());
            assertThat(r.trimmedMean()).as(name + " trimmedMean").isEqualByComparingTo(v.get("trimmedMean").asString());
            assertThat(r.sampleSd()).as(name + " sampleSd").isEqualByComparingTo(v.get("sampleSd").asString());
            assertThat(r.lExact()).as(name + " lExact").isEqualByComparingTo(v.get("lExact").asString());
            assertThat(r.l().toPlainString()).as(name + " l").isEqualTo(v.get("l").asString());
        }
        assertThat(sizes).containsExactly(3, 4, 5, 6, 7, 8, 9, 10);
    }
}
