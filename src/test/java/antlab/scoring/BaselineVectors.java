package antlab.scoring;

import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.BaselineSpec.Slot;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hand-checked vectors in {@code scoring/baseline_vectors.json}. The file holds inputs and expected values of the four baselines
 * (computed by an independent Python model with exact fractions, not by this code); the tests build their specs from it, and
 * the Python simulator of a later feature reads the same file.
 */
final class BaselineVectors {

    static final Path FILE = Path.of("src/test/resources/scoring/baseline_vectors.json");

    final JsonNode root;
    final BigDecimal initialCash;
    final FeeRates fees;
    final Map<AssetKey, List<BigDecimal>> prices = new LinkedHashMap<>();

    private BaselineVectors(JsonNode root) {
        this.root = root;
        this.initialCash = new BigDecimal(root.get("initialCash").asString());
        JsonNode rates = root.get("feeRates");
        this.fees = new FeeRates(new BigDecimal(rates.get("kr").asString()), new BigDecimal(rates.get("us").asString()),
                new BigDecimal(rates.get("fxSpread").asString()));
        for (var entry : root.get("prices").properties()) {
            List<BigDecimal> list = new ArrayList<>();
            for (JsonNode price : entry.getValue()) {
                list.add(new BigDecimal(price.asString()));
            }
            prices.put(key(entry.getKey()), list);
        }
    }

    static BaselineVectors load() {
        try {
            return new BaselineVectors(JsonMapper.builder().build().readTree(Files.readString(FILE)));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static AssetKey key(String text) {
        String[] parts = text.split(":");
        return new AssetKey(parts[0], parts[1]);
    }

    JsonNode baseline(BaselineKey key) {
        return root.get("baselines").get(key.name());
    }

    BaselineSpec spec(BaselineKey key) {
        JsonNode node = baseline(key);
        List<Slot> slots = new ArrayList<>();
        for (JsonNode slot : node.get("slots")) {
            List<Leg> legs = new ArrayList<>();
            for (JsonNode leg : slot.get("legs")) {
                legs.add(new Leg(key(leg.get("asset").asString()), leg.get("sharePercent").asInt()));
            }
            slots.add(new Slot(slot.get("name").asString(), legs));
        }
        JsonNode band = node.get("bandPercent");
        return new BaselineSpec(key, slots, band.isNull() ? null : band.asInt());
    }

    TargetSchedule schedule(BaselineKey key) {
        JsonNode node = baseline(key);
        List<TargetSchedule.Switch> switches = new ArrayList<>();
        for (JsonNode sw : node.get("switches")) {
            switches.add(new TargetSchedule.Switch(sw.get("turnNo").asInt(), ints(sw.get("target"))));
        }
        return new TargetSchedule(ints(node.get("target")), switches);
    }

    BaselineResult simulate(BaselineKey key) {
        return BaselineSimulator.simulate(spec(key), schedule(key), prices, initialCash, fees);
    }

    static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode value : array) {
            out.add(value.asInt());
        }
        return out;
    }
}
