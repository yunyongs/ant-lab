package antlab.collect.price;

import antlab.collect.PriceBasis;

/**
 * One instrument the job collects. {@code exchangeCode} is the KIS overseas exchange (empty for KR); the other fields mirror the
 * asset natural key and the price basis stored with the close. Whether the row fits the asset is checked by the job, not here.
 */
public record KisTarget(String market, String ticker, String exchangeCode, PriceBasis basis) {

    public String key() {
        return market + ":" + ticker;
    }
}
