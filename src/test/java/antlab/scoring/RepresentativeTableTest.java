package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.RepresentativeTable.Entry;
import antlab.scoring.RepresentativeTable.Resolution;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepresentativeTableTest {

    private static final AssetKey KR_INDEX = new AssetKey("KR", "069500");
    private static final AssetKey US_INDEX = new AssetKey("US", "SPY");
    private static final AssetKey QQQ = new AssetKey("US", "QQQ");
    private static final AssetKey TLT = new AssetKey("US", "TLT");
    private static final AssetKey GOLD = new AssetKey("KR", "411060");
    private static final AssetKey KR_BOND_1 = new AssetKey("KR", "148070");
    private static final AssetKey KR_BOND_2 = new AssetKey("KR", "114260");

    /** The sample pool has no Korean bond ETF: the bond leg falls to the US Treasury ETF. */
    private static Set<AssetKey> samplePool() {
        return new HashSet<>(List.of(KR_INDEX, US_INDEX, QQQ, TLT, GOLD, new AssetKey("US", "XLK")));
    }

    private static List<String> legs(BaselineSpec spec) {
        return spec.slots().stream().map(slot -> slot.name() + "="
                + slot.legs().stream().map(l -> l.asset().market() + ":" + l.asset().ticker() + "/" + l.sharePercent()).toList())
                .toList();
    }

    @Test
    void the_sample_pool_resolves_all_four_with_the_us_bond_flagged() {
        Resolution resolution = RepresentativeTable.REP_V1.resolve(samplePool());
        assertThat(resolution.version()).isEqualTo("rep-v1");
        assertThat(resolution.entries()).extracting(Entry::key).containsExactly(BaselineKey.MARKET, BaselineKey.SIXTY_FORTY,
                BaselineKey.FORMATION_FOLLOW, BaselineKey.FORMATION_FIXED);
        assertThat(resolution.entries()).allMatch(Entry::available);

        assertThat(resolution.entry(BaselineKey.MARKET).bondLegSubstituted()).isFalse();
        assertThat(resolution.entry(BaselineKey.SIXTY_FORTY).bondLegSubstituted()).isTrue();
        assertThat(resolution.entry(BaselineKey.FORMATION_FOLLOW).bondLegSubstituted()).isTrue();
        assertThat(resolution.entry(BaselineKey.FORMATION_FIXED).bondLegSubstituted()).isTrue();

        assertThat(legs(resolution.entry(BaselineKey.MARKET).spec())).containsExactly("MARKET=[KR:069500/50, US:SPY/50]");
        assertThat(legs(resolution.entry(BaselineKey.SIXTY_FORTY).spec()))
                .containsExactly("EQUITY=[KR:069500/50, US:SPY/50]", "BOND=[US:TLT/100]");
        assertThat(legs(resolution.entry(BaselineKey.FORMATION_FOLLOW).spec())).containsExactly("GK=[]",
                "DF=[US:TLT/50, KR:411060/50]", "MF=[KR:069500/50, US:SPY/50]", "FW=[US:QQQ/100]");
        assertThat(legs(resolution.entry(BaselineKey.FORMATION_FIXED).spec()))
                .isEqualTo(legs(resolution.entry(BaselineKey.FORMATION_FOLLOW).spec()));
    }

    @Test
    void the_band_is_five_for_all_but_the_market_baseline() {
        Resolution resolution = RepresentativeTable.REP_V1.resolve(samplePool());
        assertThat(resolution.entry(BaselineKey.MARKET).spec().bandPercent()).isNull();
        for (BaselineKey key : List.of(BaselineKey.SIXTY_FORTY, BaselineKey.FORMATION_FOLLOW, BaselineKey.FORMATION_FIXED)) {
            assertThat(resolution.entry(key).spec().bandPercent()).isEqualTo(5);
        }
    }

    @Test
    void a_domestic_bond_in_the_pool_wins_and_is_not_flagged() {
        Set<AssetKey> pool = samplePool();
        pool.add(KR_BOND_1);
        pool.add(KR_BOND_2);
        Entry entry = RepresentativeTable.REP_V1.resolve(pool).entry(BaselineKey.SIXTY_FORTY);
        assertThat(legs(entry.spec())).contains("BOND=[KR:148070/100]");
        assertThat(entry.bondLegSubstituted()).isFalse();
    }

    @Test
    void the_second_domestic_bond_is_already_a_substitution() {
        Set<AssetKey> pool = samplePool();
        pool.add(KR_BOND_2);
        Entry entry = RepresentativeTable.REP_V1.resolve(pool).entry(BaselineKey.SIXTY_FORTY);
        assertThat(legs(entry.spec())).contains("BOND=[KR:114260/100]");
        assertThat(entry.bondLegSubstituted()).isTrue();
    }

    @Test
    void without_the_growth_asset_only_the_formation_baselines_are_unavailable() {
        Set<AssetKey> pool = samplePool();
        pool.remove(QQQ);
        Resolution resolution = RepresentativeTable.REP_V1.resolve(pool);
        assertThat(resolution.entry(BaselineKey.MARKET).available()).isTrue();
        assertThat(resolution.entry(BaselineKey.SIXTY_FORTY).available()).isTrue();
        for (BaselineKey key : List.of(BaselineKey.FORMATION_FOLLOW, BaselineKey.FORMATION_FIXED)) {
            Entry entry = resolution.entry(key);
            assertThat(entry.available()).isFalse();
            assertThat(entry.spec()).isNull();
            assertThat(entry.reason()).isEqualTo("REPRESENTATIVE_MISSING");
        }
        assertThat(resolution.entry(BaselineKey.MARKET).reason()).isNull();
    }

    @Test
    void without_gold_the_formation_baselines_are_unavailable() {
        Set<AssetKey> pool = samplePool();
        pool.remove(GOLD);
        Resolution resolution = RepresentativeTable.REP_V1.resolve(pool);
        assertThat(resolution.entry(BaselineKey.FORMATION_FOLLOW).available()).isFalse();
        assertThat(resolution.entry(BaselineKey.SIXTY_FORTY).available()).isTrue();
    }

    @Test
    void without_any_bond_both_the_bond_baselines_are_unavailable() {
        Set<AssetKey> pool = samplePool();
        pool.remove(TLT);
        Resolution resolution = RepresentativeTable.REP_V1.resolve(pool);
        assertThat(resolution.entry(BaselineKey.MARKET).available()).isTrue();
        assertThat(resolution.entry(BaselineKey.SIXTY_FORTY).available()).isFalse();
        assertThat(resolution.entry(BaselineKey.SIXTY_FORTY).reason()).isEqualTo("REPRESENTATIVE_MISSING");
        assertThat(resolution.entry(BaselineKey.FORMATION_FIXED).available()).isFalse();
    }

    @Test
    void a_pool_without_a_market_index_asset_is_a_broken_season() {
        Set<AssetKey> noUs = samplePool();
        noUs.remove(US_INDEX);
        assertThatThrownBy(() -> RepresentativeTable.REP_V1.resolve(noUs)).isInstanceOf(IllegalStateException.class);
        Set<AssetKey> noKr = samplePool();
        noKr.remove(KR_INDEX);
        assertThatThrownBy(() -> RepresentativeTable.REP_V1.resolve(noKr)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_table_with_fake_tickers_resolves_the_same_way() {
        AssetKey kr = new AssetKey("KR", "FAKE01");
        AssetKey us = new AssetKey("US", "FAKE02");
        AssetKey bond = new AssetKey("KR", "FAKE03");
        AssetKey gold = new AssetKey("KR", "FAKE04");
        AssetKey growth = new AssetKey("US", "FAKE05");
        RepresentativeTable fake = new RepresentativeTable("test-v0", kr, us, List.of(bond), gold, growth);
        Resolution resolution = fake.resolve(Set.of(kr, us, bond, gold, growth));
        assertThat(resolution.version()).isEqualTo("test-v0");
        assertThat(resolution.entries()).allMatch(Entry::available);
        assertThat(resolution.entry(BaselineKey.FORMATION_FOLLOW).spec().slots().get(3).legs()).containsExactly(new Leg(growth, 100));
    }

    @Test
    void the_resolved_baselines_run_on_a_generated_season() {
        BaselineGenerated.Season season = BaselineGenerated.season(11);
        assertThat(season.play()).containsOnlyKeys(BaselineKey.values());
    }
}
