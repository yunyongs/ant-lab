package antlab.projection;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The team as fixed shares of its total value, ready for the engine. A share is the holding's value over the total,
 * rounded DOWN at 12 digits; cash is the exact remainder, so the shares add up to exactly 1 and cash is never below
 * its true share (never negative). Zero-quantity holdings are left out. Pure: no clock, no database, no rounding code of
 * its own (the roundings live in {@link MoneyMath}).
 */
public record ProjectionInput(BigDecimal totalValue, BigDecimal cashShare, List<Share> shares) {

    /** A held asset and its share of the total value. */
    public record Share(String asset, BigDecimal weight) {
    }

    private static final Pattern ASSET_KEY = Pattern.compile("^(KR|US):[A-Z0-9.\\-]{1,16}$");

    public ProjectionInput {
        shares = List.copyOf(shares);
    }

    public static ProjectionInput of(BigDecimal cash, List<PricedHolding> holdings) {
        List<PricedHolding> held = holdings.stream().filter(h -> h.quantity() > 0).toList();
        List<BigDecimal> valuations = held.stream().map(h -> MoneyMath.valuation(h.price(), h.quantity())).toList();
        BigDecimal total = MoneyMath.totalValue(cash, valuations);
        if (total.signum() <= 0) {
            throw new IllegalStateException("projection.empty_team");
        }
        Set<String> seen = new HashSet<>();
        List<Share> shares = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < held.size(); i++) {
            String key = held.get(i).assetKey();
            if (!ASSET_KEY.matcher(key).matches() || !seen.add(key)) {
                // the key is not logged: a ticker is pool data, and the rule name is enough to find the bug
                throw new IllegalStateException("projection.bad_asset_key");
            }
            BigDecimal weight = MoneyMath.shareDown(valuations.get(i), total);
            shares.add(new Share(key, weight));
            sum = sum.add(weight);
        }
        return new ProjectionInput(total, BigDecimal.ONE.subtract(sum), shares);
    }
}
