package antlab.scoring;

import antlab.scoring.SeasonReportSources.AssetKey;
import java.util.List;

/** Constants of the four baselines in one place (PLAN section 6.1, representative table rep-v1). */
public final class BaselineRules {

    /** A slot more than this many percentage points from its target triggers a rebalance (strict). */
    public static final int BAND_PERCENT = 5;
    public static final String REPRESENTATIVE_VERSION = "rep-v1";
    public static final String REASON_REPRESENTATIVE_MISSING = "REPRESENTATIVE_MISSING";

    public static final String SLOT_MARKET = "MARKET";
    public static final String SLOT_EQUITY = "EQUITY";
    public static final String SLOT_BOND = "BOND";
    public static final String SLOT_GOALKEEPER = "GK";
    public static final String SLOT_DEFENDER = "DF";
    public static final String SLOT_MIDFIELDER = "MF";
    public static final String SLOT_FORWARD = "FW";

    public static final List<Integer> MARKET_TARGET = List.of(100);
    public static final List<Integer> SIXTY_FORTY_TARGET = List.of(60, 40);

    /** Market index pair, the same two assets as the report's baseline (ReportRules). */
    public static final AssetKey MARKET_KR =
            new AssetKey(ReportRules.BENCH_KR_MARKET, ReportRules.BENCH_KR_TICKER);
    public static final AssetKey MARKET_US =
            new AssetKey(ReportRules.BENCH_US_MARKET, ReportRules.BENCH_US_TICKER);

    /** Bond leg candidates in order: the first one present in the season pool is used. */
    public static final List<AssetKey> BOND_CANDIDATES = List.of(
            new AssetKey("KR", "148070"), new AssetKey("KR", "114260"), new AssetKey("US", "TLT"));
    public static final AssetKey GOLD = new AssetKey("KR", "411060");
    public static final AssetKey GROWTH = new AssetKey("US", "QQQ");

    private BaselineRules() {
    }
}
