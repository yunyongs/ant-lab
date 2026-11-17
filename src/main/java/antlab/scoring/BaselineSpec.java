package antlab.scoring;

import antlab.scoring.SeasonReportSources.AssetKey;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The structure of one baseline: slots, each holding legs (an asset and its integer percent share of the slot), and the rebalancing band.
 * A slot without legs is the cash slot (at most one). Targets are not part of the spec: they come from a {@link TargetSchedule}
 * with one percentage per slot, in slot order. {@code bandPercent} is null when the baseline never rebalances.
 */
public record BaselineSpec(BaselineKey key, List<Slot> slots, Integer bandPercent) {

    public record Leg(AssetKey asset, int sharePercent) {
        public Leg {
            Objects.requireNonNull(asset, "asset");
            if (sharePercent < 1 || sharePercent > 100) {
                throw BaselineSimulator.invalid("a leg share is outside the allowed range");
            }
        }
    }

    public record Slot(String name, List<Leg> legs) {
        public Slot {
            Objects.requireNonNull(name, "name");
            legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
            if (!legs.isEmpty() && legs.stream().mapToInt(Leg::sharePercent).sum() != 100) {
                throw BaselineSimulator.invalid("the leg shares of a slot must add up to the whole");
            }
        }

        public boolean isCash() {
            return legs.isEmpty();
        }
    }

    public BaselineSpec {
        Objects.requireNonNull(key, "key");
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        if (slots.isEmpty()) {
            throw BaselineSimulator.invalid("a baseline needs at least one slot");
        }
        if (bandPercent != null && (bandPercent < 1 || bandPercent > 99)) {
            throw BaselineSimulator.invalid("the band is outside the allowed range");
        }
        Set<String> names = new HashSet<>();
        Set<AssetKey> assets = new HashSet<>();
        int cashSlots = 0;
        for (Slot slot : slots) {
            if (!names.add(slot.name())) {
                throw BaselineSimulator.invalid("slot names must be unique");
            }
            if (slot.isCash()) {
                cashSlots++;
            }
            for (Leg leg : slot.legs()) {
                if (!assets.add(leg.asset())) {
                    throw BaselineSimulator.invalid("an asset may appear in one leg only");
                }
            }
        }
        if (cashSlots > 1) {
            throw BaselineSimulator.invalid("a baseline has at most one cash slot");
        }
    }
}
