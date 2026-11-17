package antlab.scoring;

import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.RepresentativeTable.Entry;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Seeded random seasons for the property, identity and look-ahead tests; every value is whole won and every draw comes from one seed. */
final class BaselineGenerated {

    static final List<AssetKey> POOL = List.of(BaselineRules.MARKET_KR, BaselineRules.MARKET_US,
            BaselineRules.BOND_CANDIDATES.get(0), BaselineRules.GOLD, BaselineRules.GROWTH);

    /** A season: prices turn 0..T for the five representative assets, cash, fees and the formation story. */
    record Season(int turns, BigDecimal initialCash, FeeRates fees, Map<AssetKey, List<BigDecimal>> prices,
            List<Integer> startFormation, List<TargetSchedule.Switch> switches) {

        Map<BaselineKey, BaselineResult> play() {
            Map<BaselineKey, BaselineResult> out = new EnumMap<>(BaselineKey.class);
            for (Entry entry : RepresentativeTable.REP_V1.resolve(Set.copyOf(POOL)).entries()) {
                TargetSchedule schedule = switch (entry.key()) {
                    case MARKET -> TargetSchedule.constant(100);
                    case SIXTY_FORTY -> TargetSchedule.constant(60, 40);
                    case FORMATION_FIXED -> new TargetSchedule(startFormation, List.of());
                    case FORMATION_FOLLOW -> new TargetSchedule(startFormation, switches);
                };
                out.put(entry.key(), BaselineSimulator.simulate(entry.spec(), schedule, prices, initialCash, fees));
            }
            return out;
        }

        Season withPrices(Map<AssetKey, List<BigDecimal>> other) {
            return new Season(turns, initialCash, fees, other, startFormation, switches);
        }

        Season withSwitches(List<TargetSchedule.Switch> other) {
            return new Season(turns, initialCash, fees, prices, startFormation, other);
        }
    }

    private BaselineGenerated() {
    }

    static Season season(long seed) {
        Random random = new Random(seed);
        int turns = 4 + random.nextInt(21);
        BigDecimal cash = BigDecimal.valueOf(3_000_000L + random.nextInt(97) * 1_000_000L);
        FeeRates fees = new FeeRates(new BigDecimal("0.00100000"), new BigDecimal("0.00200000"), new BigDecimal("0.00400000"));
        Map<AssetKey, List<BigDecimal>> prices = new LinkedHashMap<>();
        for (AssetKey asset : POOL) {
            prices.put(asset, walk(random, turns, 2_000 + random.nextInt(300_000)));
        }
        List<Integer> start = formation(random);
        List<TargetSchedule.Switch> switches = new ArrayList<>();
        List<Integer> previous = start;
        for (int turn = 1; turn < turns; turn++) {
            if (random.nextInt(5) == 0) {
                List<Integer> target = formation(random);
                if (!target.equals(previous)) {
                    switches.add(new TargetSchedule.Switch(turn, target));
                    previous = target;
                }
            }
        }
        return new Season(turns, cash, fees, prices, start, switches);
    }

    /** A fresh random walk with the same length, used to replace the future of a season. */
    static Map<AssetKey, List<BigDecimal>> otherFuture(Season season, int fromTurn, long seed) {
        Random random = new Random(seed);
        Map<AssetKey, List<BigDecimal>> out = new LinkedHashMap<>();
        for (Map.Entry<AssetKey, List<BigDecimal>> entry : season.prices().entrySet()) {
            List<BigDecimal> list = new ArrayList<>(entry.getValue());
            long price = list.get(fromTurn).longValueExact();
            for (int turn = fromTurn + 1; turn < list.size(); turn++) {
                price = step(random, price);
                list.set(turn, BigDecimal.valueOf(price));
            }
            out.put(entry.getKey(), list);
        }
        return out;
    }

    private static List<BigDecimal> walk(Random random, int turns, long first) {
        List<BigDecimal> list = new ArrayList<>();
        long price = first;
        list.add(BigDecimal.valueOf(price));
        for (int turn = 1; turn <= turns; turn++) {
            price = step(random, price);
            list.add(BigDecimal.valueOf(price));
        }
        return list;
    }

    private static long step(Random random, long price) {
        long change = price * (random.nextInt(31) - 15) / 100;
        return Math.max(1_000, price + change);
    }

    /** Four non-negative integer parts of 100 from three random cut points. */
    private static List<Integer> formation(Random random) {
        int[] cuts = {random.nextInt(101), random.nextInt(101), random.nextInt(101)};
        Arrays.sort(cuts);
        return List.of(cuts[0], cuts[1] - cuts[0], cuts[2] - cuts[1], 100 - cuts[2]);
    }
}
