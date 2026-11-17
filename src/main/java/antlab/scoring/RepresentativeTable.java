package antlab.scoring;

import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.BaselineSpec.Slot;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The fixed table that says which assets stand in for each baseline slot, and its resolution against a season pool. The table is a rule,
 * not a position judgement. {@link #REP_V1} is the shipped version; tests build others with fake tickers.
 *
 * <p>A leg with no candidate in the pool makes that baseline unavailable (never an error) except the market index pair, whose absence is
 * a broken season and stays an error, as in the report's own baseline.
 */
public record RepresentativeTable(String version, AssetKey domesticEquity, AssetKey usEquity, List<AssetKey> bondCandidates,
        AssetKey gold, AssetKey growth) {

    public static final RepresentativeTable REP_V1 = new RepresentativeTable(BaselineRules.REPRESENTATIVE_VERSION,
            BaselineRules.MARKET_KR, BaselineRules.MARKET_US, BaselineRules.BOND_CANDIDATES, BaselineRules.GOLD,
            BaselineRules.GROWTH);

    public RepresentativeTable {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(domesticEquity, "domesticEquity");
        Objects.requireNonNull(usEquity, "usEquity");
        bondCandidates = List.copyOf(Objects.requireNonNull(bondCandidates, "bondCandidates"));
        Objects.requireNonNull(gold, "gold");
        Objects.requireNonNull(growth, "growth");
    }

    /** One baseline of the resolution. {@code spec} is null (and {@code reason} set) when the baseline is unavailable. */
    public record Entry(BaselineKey key, BaselineSpec spec, String reason, boolean bondLegSubstituted) {
        public boolean available() {
            return spec != null;
        }
    }

    public record Resolution(String version, List<Entry> entries) {
        public Entry entry(BaselineKey key) {
            return entries.stream().filter(e -> e.key() == key).findFirst().orElseThrow();
        }
    }

    public Resolution resolve(Set<AssetKey> pool) {
        Objects.requireNonNull(pool, "pool");
        if (!pool.contains(domesticEquity) || !pool.contains(usEquity)) {
            throw BaselineSimulator.invalid("the market index pair is not in the season pool");
        }
        List<Leg> market = List.of(new Leg(domesticEquity, 50), new Leg(usEquity, 50));
        AssetKey bond = null;
        int bondIndex = -1;
        for (int i = 0; i < bondCandidates.size(); i++) {
            if (pool.contains(bondCandidates.get(i))) {
                bond = bondCandidates.get(i);
                bondIndex = i;
                break;
            }
        }
        boolean substituted = bondIndex > 0;

        Entry marketEntry = new Entry(BaselineKey.MARKET,
                new BaselineSpec(BaselineKey.MARKET, List.of(new Slot(BaselineRules.SLOT_MARKET, market)), null), null, false);
        Entry sixtyForty = bond == null
                ? unavailable(BaselineKey.SIXTY_FORTY)
                : new Entry(BaselineKey.SIXTY_FORTY, new BaselineSpec(BaselineKey.SIXTY_FORTY,
                        List.of(new Slot(BaselineRules.SLOT_EQUITY, market),
                                new Slot(BaselineRules.SLOT_BOND, List.of(new Leg(bond, 100)))),
                        BaselineRules.BAND_PERCENT), null, substituted);
        boolean formationReady = bond != null && pool.contains(gold) && pool.contains(growth);
        Entry follow = formationReady ? formation(BaselineKey.FORMATION_FOLLOW, market, bond, substituted)
                : unavailable(BaselineKey.FORMATION_FOLLOW);
        Entry fixed = formationReady ? formation(BaselineKey.FORMATION_FIXED, market, bond, substituted)
                : unavailable(BaselineKey.FORMATION_FIXED);
        return new Resolution(version, List.of(marketEntry, sixtyForty, follow, fixed));
    }

    private Entry formation(BaselineKey key, List<Leg> market, AssetKey bond, boolean substituted) {
        return new Entry(key, new BaselineSpec(key, List.of(
                new Slot(BaselineRules.SLOT_GOALKEEPER, List.of()),
                new Slot(BaselineRules.SLOT_DEFENDER, List.of(new Leg(bond, 50), new Leg(gold, 50))),
                new Slot(BaselineRules.SLOT_MIDFIELDER, market),
                new Slot(BaselineRules.SLOT_FORWARD, List.of(new Leg(growth, 100)))),
                BaselineRules.BAND_PERCENT), null, substituted);
    }

    private static Entry unavailable(BaselineKey key) {
        return new Entry(key, null, BaselineRules.REASON_REPRESENTATIVE_MISSING, false);
    }
}
